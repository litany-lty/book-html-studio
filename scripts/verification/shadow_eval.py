"""M3/J11 SHADOW 评测 harness 骨架（17.4 指标计算，与数据无关，可先合成自测）。

输入：冻结数据集 JSON：
  {"cases": [{"caseId", "truth": "<正确转录|null（不可读）>", "unreadable": bool,
              "category": "横排/竖排/繁体/数字否定/...",
              "candidates": [{"candidateId", "text", "sourceKind"}],
              "rulePick": "<B 组规则所选 candidateId|null（弃权）>",
              "jevPick": "<C 组 JEV 所选 candidateId|NONE_SUPPORTED|NEED_MORE_EVIDENCE|null>",
              "book": "<书名/代号>"}]}
  truth=null 表示原图不可辨认（UNREADABLE/AMBIGUOUS），选择任何文字都算错，弃权才算对。
输出：metrics.json（疑点召回外：candidate recall、conditional accuracy、coverage、
wrong-rate、原正确改错、弃权质量、关键错误逐案、费用占位）。
用法：
  python3 scripts/verification/shadow_eval.py --dataset <json> --out <dir>
  python3 scripts/verification/shadow_eval.py --selftest
费用：合成自测 0；真实 JEV 调用由外部按冻结候选执行，本脚本只消费结果，不发起调用。
"""
import argparse
import json
import os
import sys

HARD_CATEGORIES = {"数字", "否定", "人名", "术语", "版次页码"}


def evaluate(dataset):
    cases = dataset.get("cases", [])
    metrics = {"n": len(cases), "byBook": {}, "keyErrors": []}
    graded = [c for c in cases if not c.get("unreadable") and c.get("truth") is not None]
    metrics["readableN"] = len(graded)

    def candidate_text(case, pick):
        if pick in (None, "NONE_SUPPORTED", "NEED_MORE_EVIDENCE"):
            return None
        for candidate in case.get("candidates", []):
            if candidate.get("candidateId") == pick:
                return candidate.get("text")
        return "UNKNOWN_CANDIDATE"

    recall_hit = abstain_good = abstain_bad = 0
    b_correct = b_wrong = b_abstain = 0
    c_correct = c_wrong = c_abstain = 0
    c_flipped = 0  # 原 OCR 正确却被推荐改错（需 truth==current 且改错，调用方在 case 置 current）
    coverage_c = 0
    for case in graded:
        truth = case["truth"]
        texts = [c.get("text") for c in case.get("candidates", [])]
        if truth in texts:
            recall_hit += 1
        else:
            # 正确候选缺失：弃权才算对
            if case.get("jevPick") in (None, "NONE_SUPPORTED", "NEED_MORE_EVIDENCE"):
                abstain_good += 1
            else:
                abstain_bad += 1
            continue
        for group, pick_key in (("B", "rulePick"), ("C", "jevPick")):
            pick = case.get(pick_key)
            text = candidate_text(case, pick)
            if text is None:
                if group == "B":
                    b_abstain += 1
                else:
                    c_abstain += 1
            elif text == truth:
                if group == "B":
                    b_correct += 1
                else:
                    c_correct += 1
            else:
                if group == "B":
                    b_wrong += 1
                else:
                    c_wrong += 1
                if group == "C" and case.get("current") == truth:
                    c_flipped += 1
        picked_c = candidate_text(case, case.get("jevPick"))
        if picked_c is not None:
            coverage_c += 1
        if case.get("category") in HARD_CATEGORIES and candidate_text(case, case.get("jevPick")) not in (None, truth):
            metrics["keyErrors"].append({"caseId": case.get("caseId"), "category": case.get("category"),
                                         "truth": truth, "picked": candidate_text(case, case.get("jevPick"))})
    decided_b = b_correct + b_wrong
    decided_c = c_correct + c_wrong
    metrics.update({
        "candidateRecall": round(recall_hit / max(1, len(graded)), 4),
        "missingCandidate": len(graded) - recall_hit,
        "abstainWhenMissing": {"good": abstain_good, "bad": abstain_bad},
        "B": {"correct": b_correct, "wrong": b_wrong, "abstain": b_abstain,
              "conditionalAccuracy": round(b_correct / max(1, decided_b), 4)},
        "C": {"correct": c_correct, "wrong": c_wrong, "abstain": c_abstain,
              "conditionalAccuracy": round(c_correct / max(1, decided_c), 4),
              "coverage": round(coverage_c / max(1, len(graded)), 4),
              "flippedFromCorrect": c_flipped},
    })
    for book in sorted({c.get("book", "?") for c in graded}):
        sub = [c for c in graded if c.get("book") == book]
        metrics["byBook"][book] = {"n": len(sub)}
    return metrics


def selftest():
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
    assert len(metrics["keyErrors"]) == 0, metrics  # s2 类别横排非关键；s1 选对
    print("SHADOW_EVAL_SELFTEST_PASS")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    dataset = json.load(open(args.dataset))
    metrics = evaluate(dataset)
    os.makedirs(args.out, exist_ok=True)
    json.dump(metrics, open(os.path.join(args.out, "metrics.json"), "w"),
              ensure_ascii=False, indent=2)
    print(json.dumps(metrics, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
