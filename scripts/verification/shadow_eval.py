"""M3/J11 SHADOW 评测 harness 骨架（17.4 指标计算，与数据无关，可先合成自测）。

输入：冻结数据集 JSON：
  {"cases": [{"caseId", "truth": "<正确转录|null（不可读）>", "unreadable": bool,
              "annotation": {"status": "READABLE_WITH_TRUTH|UNREADABLE|AMBIGUOUS|UNLABELED", "originalScriptTruth": "..."},
              "category": "横排/竖排/繁体/数字否定/...", "riskTags": [...],
              "candidates": [{"candidateId", "text", "sourceKind"}],
              "rulePick": "<B 组规则所选 candidateId|null（弃权）>",
              "jevPick": "<C 组 JEV 所选 candidateId|NONE_SUPPORTED|NEED_MORE_EVIDENCE|null>",
              "verdict": "<CALL_FAILED|VALIDATED|...>",
              "executionStatus": "<SUCCEEDED|FAILED>",
              "admittedRecommendationId": "<candidateId|null>",
              "runs": {"A": {...}, "B": {...}, "C": {...}},
              "book": "<书名/代号>"}]}
输出：metrics.json（candidate recall、conditional accuracy、coverage、
raw choice coverage、unsupportedChoiceOnUnreadable、keyErrors、A/B/C 分组核算）。
用法：
  python3 scripts/verification/shadow_eval.py --dataset <json> --out <dir> [--normalize]
  python3 scripts/verification/shadow_eval.py --selftest
"""
import argparse
import json
import os
import sys

HARD_CATEGORIES = {"数字", "否定", "人名", "术语", "版次页码"}


def _candidate_text(case, pick):
    if pick in (None, "NONE_SUPPORTED", "NEED_MORE_EVIDENCE", "UNKNOWN"):
        return None
    for candidate in case.get("candidates", []):
        if candidate.get("candidateId") == pick:
            return candidate.get("text")
    return "UNKNOWN_CANDIDATE"


def _extract_case_info(case):
    ann = case.get("annotation") if isinstance(case.get("annotation"), dict) else {}
    status = ann.get("status")
    if not status:
        if case.get("unreadable") or (case.get("truth") is None and not ann.get("originalScriptTruth")):
            status = "UNREADABLE"
        else:
            status = "READABLE_WITH_TRUTH"
    truth = ann.get("originalScriptTruth") if ann.get("originalScriptTruth") is not None else case.get("truth")
    return status, truth


def _extract_run_info(case, group):
    runs = case.get("runs") if isinstance(case.get("runs"), dict) else {}
    group_run = runs.get(group) if isinstance(runs.get(group), dict) else {}

    if group == "A":
        text = group_run.get("displayedText") if group_run.get("displayedText") is not None else case.get("current")
        return {"text": text, "status": "SUCCEEDED"}

    pick_key = "rulePick" if group == "B" else "jevPick"
    pick = group_run.get("rawPick") if group_run.get("rawPick") is not None else case.get(pick_key)
    status = group_run.get("executionStatus") or case.get("executionStatus") or case.get(f"{group.lower()}ExecutionStatus") or "SUCCEEDED"
    verdict = group_run.get("policyVerdict") or case.get("verdict") or case.get("policyVerdict")
    admitted = group_run.get("admittedRecommendationId") if "admittedRecommendationId" in group_run else case.get("admittedRecommendationId")

    failed = (status == "FAILED" or verdict == "CALL_FAILED")
    text = None if failed else _candidate_text(case, pick)
    return {
        "pick": pick,
        "text": text,
        "status": status,
        "verdict": verdict,
        "admitted": admitted,
        "failed": failed,
    }


def _eval_single(cases, normalize_fn=None):
    norm = normalize_fn or (lambda s: s or "")

    readable = []
    unreadable = []
    unlabeled = []

    for c in cases:
        status, truth = _extract_case_info(c)
        if status == "UNLABELED":
            unlabeled.append(c)
        elif status in ("UNREADABLE", "AMBIGUOUS"):
            unreadable.append(c)
        else:
            c_copy = dict(c)
            c_copy["truth"] = norm(truth)
            c_copy["candidates"] = [
                dict(k, text=norm(k.get("text"))) for k in c.get("candidates", [])
            ]
            c_copy["current"] = norm(c.get("current"))
            readable.append(c_copy)

    metrics = {
        "n": len(cases),
        "readableN": len(readable),
        "unreadableN": len(unreadable),
        "unlabeledN": len(unlabeled),
        "keyErrors": [],
        "casesDetail": [],
    }

    recall_hit = 0
    abstain_good = 0
    abstain_bad = 0

    a_correct = a_wrong = 0
    b_correct = b_wrong = b_abstain = b_failed = 0
    b_cond_correct = b_cond_wrong = 0
    c_correct = c_wrong = c_abstain = c_failed = 0
    c_cond_correct = c_cond_wrong = 0
    c_flipped = 0
    coverage_b = 0
    coverage_c = 0

    # Readable evaluation
    for case in readable:
        truth = case["truth"]
        texts = [c.get("text") for c in case.get("candidates", [])]
        truth_in_candidates = (truth in texts)
        if truth_in_candidates:
            recall_hit += 1

        # Evaluate A (baseline)
        run_a = _extract_run_info(case, "A")
        if run_a["text"] == truth:
            a_correct += 1
        else:
            a_wrong += 1

        # Evaluate B
        run_b = _extract_run_info(case, "B")
        if run_b["failed"]:
            b_failed += 1
        elif run_b["text"] is None:
            b_abstain += 1
        else:
            coverage_b += 1
            if run_b["text"] == truth:
                b_correct += 1
            else:
                b_wrong += 1
            if truth_in_candidates:
                if run_b["text"] == truth:
                    b_cond_correct += 1
                else:
                    b_cond_wrong += 1

        # Evaluate C
        run_c = _extract_run_info(case, "C")
        if run_c["failed"]:
            c_failed += 1
        else:
            if run_c["text"] is None:
                c_abstain += 1
                if not truth_in_candidates:
                    abstain_good += 1
            else:
                coverage_c += 1
                if not truth_in_candidates:
                    abstain_bad += 1
                if run_c["text"] == truth:
                    c_correct += 1
                else:
                    c_wrong += 1
                    if case.get("current") == truth:
                        c_flipped += 1
                if truth_in_candidates:
                    if run_c["text"] == truth:
                        c_cond_correct += 1
                    else:
                        c_cond_wrong += 1

        # Key errors detection
        is_key_error = False
        cat = case.get("category")
        tags = set(case.get("riskTags", []))
        if cat in HARD_CATEGORIES or bool(tags & HARD_CATEGORIES):
            if not run_c["failed"] and run_c["text"] not in (None, truth):
                is_key_error = True
                metrics["keyErrors"].append({
                    "caseId": case.get("caseId"),
                    "category": cat,
                    "truth": truth,
                    "picked": run_c["text"],
                })

        metrics["casesDetail"].append({
            "caseId": case.get("caseId"),
            "status": "READABLE_WITH_TRUTH",
            "truth": truth,
            "bText": run_b["text"],
            "cText": run_c["text"],
            "cFailed": run_c["failed"],
            "isKeyError": is_key_error,
        })

    # Unreadable / Ambiguous evaluation
    unsupported_on_unreadable_b = 0
    unsupported_on_unreadable_c = 0
    for case in unreadable:
        run_b = _extract_run_info(case, "B")
        if not run_b["failed"] and run_b["text"] is not None:
            unsupported_on_unreadable_b += 1

        run_c = _extract_run_info(case, "C")
        if run_c["failed"]:
            c_failed += 1
        elif run_c["text"] is not None:
            unsupported_on_unreadable_c += 1

        metrics["casesDetail"].append({
            "caseId": case.get("caseId"),
            "status": "UNREADABLE",
            "truth": None,
            "bText": run_b["text"],
            "cText": run_c["text"],
            "cFailed": run_c["failed"],
            "isKeyError": False,
        })

    # All eligible cases = readable + unreadable (excludes unlabeled)
    eligible_n = len(readable) + len(unreadable)

    raw_choices_c = sum(
        1 for d in metrics["casesDetail"] if d["cText"] is not None and not d["cFailed"]
    )
    raw_choices_b = sum(
        1 for d in metrics["casesDetail"] if d["bText"] is not None
    )

    admitted_count_c = sum(
        1 for c in cases
        if _extract_run_info(c, "C").get("admitted") is not None
        and not _extract_run_info(c, "C")["failed"]
    )

    b_cond_decided = b_cond_correct + b_cond_wrong
    c_cond_decided = c_cond_correct + c_cond_wrong
    c_decided = c_correct + c_wrong

    metrics.update({
        "candidateRecall": round(recall_hit / len(readable), 4) if readable else None,
        "missingCandidate": len(readable) - recall_hit,
        "abstainWhenMissing": {"good": abstain_good, "bad": abstain_bad},
        "failures": {"B": b_failed, "C": c_failed},
        "unsupportedChoiceOnUnreadable": unsupported_on_unreadable_c,
        "unsupportedChoiceOnUnreadableByGroup": {
            "B": unsupported_on_unreadable_b,
            "C": unsupported_on_unreadable_c,
        },
        "readableWrongChoiceRate": round(c_wrong / c_decided, 4) if c_decided > 0 else None,
        "rawChoiceCoverage": round(raw_choices_c / eligible_n, 4) if eligible_n > 0 else None,
        "admittedRecommendationCoverage": round(admitted_count_c / eligible_n, 4) if eligible_n > 0 else None,
        "A": {
            "correct": a_correct,
            "wrong": a_wrong,
            "accuracy": round(a_correct / len(readable), 4) if readable else None,
            "n": len(readable),
        },
        "B": {
            "correct": b_correct,
            "wrong": b_wrong,
            "abstain": b_abstain,
            "failed": b_failed,
            "conditionalAccuracy": round(b_cond_correct / b_cond_decided, 4) if b_cond_decided > 0 else None,
            "conditionalN": b_cond_decided,
            "coverage": round(coverage_b / len(readable), 4) if readable else None,
            "rawChoiceCoverage": round(raw_choices_b / eligible_n, 4) if eligible_n > 0 else None,
        },
        "C": {
            "correct": c_correct,
            "wrong": c_wrong,
            "abstain": c_abstain,
            "failed": c_failed,
            "conditionalAccuracy": round(c_cond_correct / c_cond_decided, 4) if c_cond_decided > 0 else None,
            "conditionalN": c_cond_decided,
            "coverage": round(coverage_c / len(readable), 4) if readable else None,
            "rawChoiceCoverage": round(raw_choices_c / eligible_n, 4) if eligible_n > 0 else None,
            "admittedRecommendationCoverage": round(admitted_count_c / eligible_n, 4) if eligible_n > 0 else None,
            "flippedFromCorrect": c_flipped,
        },
        "byBook": {},
        "byCategory": {},
    })

    for book in sorted({c.get("book", "?") for c in cases}):
        sub = [c for c in cases if c.get("book") == book]
        sub_readable = [c for c in readable if c.get("book") == book]
        metrics["byBook"][book] = {
            "n": len(sub),
            "readableN": len(sub_readable),
        }

    for cat in sorted({c.get("category", "未分类") for c in cases}):
        sub = [c for c in cases if c.get("category", "未分类") == cat]
        metrics["byCategory"][cat] = {"n": len(sub)}

    return metrics


def evaluate(dataset, normalize=False):
    cases = dataset.get("cases", [])
    # 1. 主指标：原字精确转录（不先归一化，严格区分原字）
    metrics = _eval_single(cases, normalize_fn=None)

    # 2. 投影指标（若启用 normalize，依赖 opencc s2t；缺失必须报错）
    if normalize:
        try:
            from opencc import OpenCC
            conv = OpenCC("s2t")
        except Exception as e:
            raise RuntimeError(
                f"--normalize requires opencc but it could not be loaded: {e}"
            )
        norm = (lambda s: conv.convert(s or "") if s else "")
        metrics["projection"] = _eval_single(cases, normalize_fn=norm)

    return metrics


def selftest():
    # 基础回归案例
    dataset = {"cases": [
        {"caseId": "s1", "truth": "不得", "category": "否定", "book": "b1", "current": "不待",
         "candidates": [{"candidateId": "k0", "text": "不待", "sourceKind": "PRIMARY_OCR"},
                        {"candidateId": "k1", "text": "不得", "sourceKind": "CROP_OCR"}],
         "rulePick": "k0", "jevPick": "k1"},
        {"caseId": "s2", "truth": "未得", "category": "横排", "book": "b1", "current": "未得",
         "candidates": [{"candidateId": "k0", "text": "未得", "sourceKind": "PRIMARY_OCR"},
                        {"candidateId": "k1", "text": "不得", "sourceKind": "CROP_OCR"}],
         "rulePick": "k0", "jevPick": "k1"},
        {"caseId": "s3", "truth": "甲", "category": "数字", "book": "b2", "current": "甲",
         "candidates": [{"candidateId": "k0", "text": "乙", "sourceKind": "SEMANTIC_INFERENCE"}],
         "rulePick": None, "jevPick": "NEED_MORE_EVIDENCE"},
        {"caseId": "s4", "truth": None, "unreadable": True, "category": "缺损", "book": "b2",
         "candidates": [], "rulePick": None, "jevPick": None},
    ]}
    metrics = evaluate(dataset)
    assert metrics["readableN"] == 3, metrics
    assert metrics["candidateRecall"] == round(2 / 3, 4), metrics
    assert metrics["B"]["correct"] == 1 and metrics["B"]["wrong"] == 1, metrics
    assert metrics["C"]["correct"] == 1 and metrics["C"]["wrong"] == 1, metrics
    assert metrics["C"]["flippedFromCorrect"] == 1, metrics  # s2 原正确被改错
    assert metrics["abstainWhenMissing"] == {"good": 1, "bad": 0}, metrics
    assert len(metrics["keyErrors"]) == 0, metrics

    # 第 6 章三例探针 + CALL_FAILED 探针 (JR-09-T01, JR-09-T02, JR-09-T03)
    probe_cases = [
        dict(caseId="R1", book="synthetic", category="横排", truth="甲", current="乙",
             candidates=[dict(candidateId="k0", text="甲"), dict(candidateId="k1", text="乙")],
             rulePick="k0", jevPick="k0"),
        dict(caseId="R2", book="synthetic", category="数字", truth="一", current="二",
             candidates=[dict(candidateId="k0", text="二")],
             rulePick="k0", jevPick="k0"),
        dict(caseId="R3", book="synthetic", category="缺损", truth=None, unreadable=True,
             candidates=[dict(candidateId="k0", text="丙")],
             rulePick="k0", jevPick="k0"),
    ]
    p_metrics = evaluate({"cases": probe_cases})
    assert p_metrics["C"]["conditionalAccuracy"] == 1.0, p_metrics
    assert p_metrics["C"]["wrong"] == 1, p_metrics  # R2 选错
    assert p_metrics["unsupportedChoiceOnUnreadable"] == 1, p_metrics  # R3 不可读强选
    assert p_metrics["rawChoiceCoverage"] == 1.0, p_metrics  # 3/3 强选
    assert any(e["caseId"] == "R2" for e in p_metrics["keyErrors"]), p_metrics  # R2 在关键错误中

    # CALL_FAILED 探针 (JR-09-T02)
    failed_case = [
        dict(caseId="R4", book="synthetic", category="数字", truth="甲", current="乙",
             candidates=[dict(candidateId="k0", text="乙")],
             jevPick=None, verdict="CALL_FAILED", executionStatus="FAILED")
    ]
    f_metrics = evaluate({"cases": failed_case})
    assert f_metrics["abstainWhenMissing"]["good"] == 0, f_metrics  # 不能算良好弃权
    assert f_metrics["failures"]["C"] == 1, f_metrics

    # 零样本与零分母 (JR-09-T04)
    empty_metrics = evaluate({"cases": []})
    assert empty_metrics["candidateRecall"] is None, empty_metrics
    assert empty_metrics["C"]["conditionalAccuracy"] is None, empty_metrics
    assert empty_metrics["rawChoiceCoverage"] is None, empty_metrics

    # 繁简原字与投影 (JR-09-T05)
    proj_cases = [
        dict(caseId="P1", book="synthetic", category="繁体", truth="國", current="國",
             candidates=[dict(candidateId="k0", text="国")],
             jevPick="k0")
    ]
    proj_metrics = evaluate({"cases": proj_cases}, normalize=True)
    assert proj_metrics["C"]["correct"] == 0 and proj_metrics["C"]["wrong"] == 1, proj_metrics
    assert proj_metrics["projection"]["C"]["correct"] == 1, proj_metrics

    print("SHADOW_EVAL_SELFTEST_PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--normalize", action="store_true",
                        help="17.4 繁简投影固定规则：比较前一律转繁（opencc s2t）")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    dataset = json.load(open(args.dataset))
    metrics = evaluate(dataset, normalize=args.normalize)
    if args.out:
        os.makedirs(args.out, exist_ok=True)
        json.dump(metrics, open(os.path.join(args.out, "metrics.json"), "w"),
                  ensure_ascii=False, indent=2)
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
