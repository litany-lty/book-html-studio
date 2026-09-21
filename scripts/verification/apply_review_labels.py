"""Bind source-image review labels to a frozen dataset without changing its evidence.

AI review is exploratory, never human calibration. No cloud calls or application
data writes. Private output must stay outside the repository; existing output is
never overwritten. Optional live JSONL must belong to the exact frozen dataset.
"""
import argparse
from collections import Counter
import copy
import hashlib
import html
import json
import os
from pathlib import Path

from shadow_eval import evaluate

STATUSES = {"READABLE_WITH_TRUTH", "UNREADABLE", "AMBIGUOUS", "NOT_TEXT", "UNLABELED"}


def apply_labels(dataset, labels, dataset_sha):
    if labels.get("datasetSha256") != dataset_sha:
        raise ValueError("标注与冻结数据集 SHA256 不一致")
    reviewer = labels.get("reviewerType")
    if reviewer not in ("AI", "HUMAN"):
        raise ValueError("必须显式声明 reviewerType 为 AI 或 HUMAN")
    if labels.get("reviewedByHuman") is not (reviewer == "HUMAN"):
        raise ValueError("标注来源与 reviewedByHuman 声明不一致")
    result = copy.deepcopy(dataset)
    cases = {case["caseId"]: case for case in result["cases"]}
    if len(cases) != len(result["cases"]):
        raise ValueError("冻结案例 ID 重复")
    seen = set()
    for label in labels["labels"]:
        case_id, status = label.get("caseId"), label.get("status")
        if case_id not in cases or case_id in seen:
            raise ValueError("标注案例未知或重复")
        seen.add(case_id)
        if status not in STATUSES:
            raise ValueError("未知标注状态")
        truth = label.get("originalScriptTruth")
        if status == "READABLE_WITH_TRUTH" and (not isinstance(truth, str) or not truth.strip()):
            raise ValueError("可读案例必须填写非空原字真值")
        if status != "READABLE_WITH_TRUTH" and truth not in (None, ""):
            raise ValueError("不可判定/非文本案例不能携带正式真值")
        case = cases[case_id]
        if case.get("annotation", {}).get("status", "UNLABELED") != "UNLABELED":
            raise ValueError("输入已经有标注；请基于原始冻结数据另存版本，禁止覆盖")
        case["annotation"] = {key: value for key, value in label.items() if key != "caseId"}
        case["annotation"].update(reviewerType=reviewer, reviewedByHuman=reviewer == "HUMAN",
                                   sourceOnlyReview=labels.get("sourceOnlyReview") is True)
    result["reviewProvenance"] = {
        "datasetSha256": dataset_sha, "reviewerType": reviewer,
        "reviewer": labels.get("reviewer", ""),
        "sourceOnlyReview": labels.get("sourceOnlyReview") is True,
        "reviewedByHuman": reviewer == "HUMAN",
        "method": labels.get("method", ""),
    }
    return result


def attach_live(dataset, rows, dataset_sha):
    cases = {case["caseId"]: case for case in dataset["cases"]}
    seen = set()
    for row in rows:
        case_id = row.get("caseId")
        if case_id not in cases or case_id in seen or row.get("datasetHash") != dataset_sha:
            raise ValueError("真实运行记录的案例/数据集身份不匹配或重复")
        seen.add(case_id)
        b_hash = row.get("runB", {}).get("candidateSetHash")
        c_hash = row.get("runC", {}).get("candidateSetHash")
        if not b_hash or b_hash != c_hash:
            raise ValueError("B/C 候选集合身份缺失或不一致")
        case = cases[case_id]
        pick = row.get("jevPick")
        if pick not in {None, "NONE_SUPPORTED", "NEED_MORE_EVIDENCE", "UNKNOWN"} and pick not in {
                candidate["candidateId"] for candidate in case.get("candidates", [])}:
            raise ValueError("JEV 选择了冻结集合之外的候选")
        # The runner's jevPick is its explicit canonical-ID -> frozen-ID mapping.
        # Preserve B's frozen rules, not a misleading comparison of two ID namespaces.
        case.setdefault("runs", {})["C"] = {
            "rawPick": pick, "executionStatus": row.get("cExecutionStatus"),
            "policyVerdict": row.get("policyVerdict"),
            "admittedRecommendationId": row.get("admittedRecommendationId"),
        }
        case["liveEvidence"] = {key: row.get(key) for key in (
            "runId", "candidateSetHash", "decisionSnapshotHash", "canonicalRequestHash", "reportedModel")}
    if seen != set(cases):
        raise ValueError("真实运行记录不完整；禁止把未运行案例混作成功")


def review_html(dataset, metrics, board, board_dir):
    if board.get("datasetSha256") != dataset["reviewProvenance"]["datasetSha256"]:
        raise ValueError("原图看板的数据集身份不匹配")
    sources = {case["caseId"]: case for case in board["cases"]}
    escape = lambda value: html.escape(str(value or ""), quote=True)
    names = {"READABLE_WITH_TRUTH": "可辨认", "AMBIGUOUS": "无法唯一判定",
             "NOT_TEXT": "版面分隔，非文字", "UNREADABLE": "原图不可辨认", "UNLABELED": "未审阅"}
    items = []
    for case in dataset["cases"]:
        source = sources[case["caseId"]]
        ann = case.get("annotation", {})
        crop = (board_dir / source["cropImage"]).resolve()
        if not crop.is_relative_to(board_dir.resolve()) or not crop.is_file():
            raise ValueError("原图路径无效")
        value = ann.get("originalScriptTruth") or ann.get("likelyText") or "—"
        suffix = "（仍存疑，不作真值）" if ann.get("likelyText") else ""
        items.append(f'<section id="{escape(case["caseId"])}"><h2>{escape(case["caseId"])} · '
                     f'{escape(names.get(ann.get("status"), "未审阅"))}</h2><p>{escape(source["book"])} · '
                     f'PDF 第 {source["page"]} 页</p><p>原字/观察：<strong>{escape(value)}</strong>{suffix}</p>'
                     f'<p>{escape(ann.get("note"))}</p><p class="context">{escape(source["maskedContext"])}</p>'
                     f'<a href="{crop.as_uri()}" target="_blank" rel="noopener"><img loading="lazy" '
                     f'src="{crop.as_uri()}" alt="原 PDF 裁图"></a></section>')
    counts = Counter(c.get("annotation", {}).get("status", "UNLABELED") for c in dataset["cases"])
    summary = "；".join(f"{names.get(key,key)} {value} 案" for key, value in counts.items())
    nav = " ".join(f'<a href="#{escape(c["caseId"])}">{escape(c["caseId"])}</a>' for c in dataset["cases"])
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" '
            'content="width=device-width,initial-scale=1"><title>原图审阅记录</title><style>'
            'body{margin:0;background:#f4f2ed;color:#26313a;font:16px/1.7 system-ui,sans-serif}'
            'main{max-width:960px;margin:auto;padding:24px}section{background:white;padding:24px;'
            'margin:24px 0;border:1px solid #d4d9de;border-radius:12px}img{max-width:100%;max-height:70vh;'
            'object-fit:contain}nav{display:flex;flex-wrap:wrap;gap:12px}h2{font-size:19px}'
            '.context{color:#56616b;white-space:pre-wrap}.notice{padding:16px;background:#fff4d8}</style>'
            f'<main><h1>{len(dataset["cases"])} 案原图审阅记录</h1><p class="notice">标注来源：{escape(dataset["reviewProvenance"]["reviewerType"])}。AI 标注不是独立人工真值。'
            '存疑文字不自动写回书籍；本记录不批准 ASSIST 或证明出版级准确率。</p>'
            f'<p>{escape(summary)}</p><nav>{nav}</nav>' + ''.join(items) + '</main></html>')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--live", type=Path)
    parser.add_argument("--board", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    raw = args.dataset.read_bytes()
    dataset_sha = hashlib.sha256(raw).hexdigest()
    dataset = apply_labels(json.loads(raw), json.loads(args.labels.read_text()), dataset_sha)
    if args.live:
        attach_live(dataset, [json.loads(line) for line in args.live.read_text().splitlines() if line.strip()], dataset_sha)
    metrics = evaluate(dataset)
    rendered = None
    if args.board:
        rendered = review_html(dataset, metrics, json.loads(args.board.read_text()), args.board.parent)
    os.umask(0o077)
    args.out.mkdir(mode=0o700, parents=True, exist_ok=False)
    for name, value in (("reviewed-dataset.json", dataset), ("metrics.json", metrics)):
        (args.out / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    if rendered:
        (args.out / "index.html").write_text(rendered, encoding="utf-8")
    print(json.dumps({"cases": len(dataset["cases"]), "reviewerType": dataset["reviewProvenance"]["reviewerType"],
                      "output": str(args.out), "admission": "NO_AUTOMATIC_PROMOTION"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
