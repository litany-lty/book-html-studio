#!/usr/bin/env python3
"""JR-09 标准库 unittest 评分测试。
覆盖 JR-09-T01 ~ JR-09-T06 验收标准。
"""
import unittest
import sys
from pathlib import Path

# Ensure scripts/verification is on path
sys.path.insert(0, str(Path(__file__).parent))
import shadow_eval


class TestShadowEval(unittest.TestCase):

    def test_jr_09_t01_probe_cases_and_metrics(self):
        """JR-09-T01: 第 6 章三例：条件准确率 1.0，另报 1 个可读选错、1 个不可读强选、全量 choice coverage=1.0，R2 在 keyErrors"""
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
        metrics = shadow_eval.evaluate({"cases": probe_cases})
        self.assertEqual(metrics["n"], 3)
        self.assertEqual(metrics["readableN"], 2)
        self.assertEqual(metrics["unreadableN"], 1)
        self.assertEqual(metrics["C"]["conditionalAccuracy"], 1.0)
        self.assertEqual(metrics["C"]["wrong"], 1)
        self.assertEqual(metrics["C"]["correct"], 1)
        self.assertEqual(metrics["unsupportedChoiceOnUnreadable"], 1)
        self.assertEqual(metrics["rawChoiceCoverage"], 1.0)
        self.assertTrue(any(e["caseId"] == "R2" for e in metrics["keyErrors"]))

    def test_jr_09_t02_call_failed_differentiation(self):
        """JR-09-T02: CALL_FAILED/null pick：计 failed=1、model good abstention=0；不得改善弃权质量"""
        failed_case = [
            dict(caseId="R4", book="synthetic", category="数字", truth="甲", current="乙",
                 candidates=[dict(candidateId="k0", text="乙")],
                 jevPick=None, verdict="CALL_FAILED", executionStatus="FAILED")
        ]
        metrics = shadow_eval.evaluate({"cases": failed_case})
        self.assertEqual(metrics["abstainWhenMissing"]["good"], 0)
        self.assertEqual(metrics["failures"]["C"], 1)
        self.assertEqual(metrics["C"]["failed"], 1)

    def test_jr_09_t03_missing_candidate_key_error(self):
        """JR-09-T03: 正确候选缺失且选错数字：进入全量错误和关键错误列表，不只放到旁路 bad 计数"""
        case = [
            dict(caseId="R2", book="synthetic", category="数字", truth="一", current="二",
                 candidates=[dict(candidateId="k0", text="二")],
                 rulePick="k0", jevPick="k0")
        ]
        metrics = shadow_eval.evaluate({"cases": case})
        self.assertEqual(metrics["abstainWhenMissing"]["bad"], 1)
        self.assertEqual(metrics["C"]["wrong"], 1)
        self.assertEqual(len(metrics["keyErrors"]), 1)
        self.assertEqual(metrics["keyErrors"][0]["caseId"], "R2")
        self.assertEqual(metrics["keyErrors"][0]["category"], "数字")

    def test_jr_09_t04_zero_and_undefined_denominators(self):
        """JR-09-T04: 零样本/零推荐/全不可读/未标注混合：分母透明、未定义指标为 null，不能输出伪 100%/0%"""
        empty_metrics = shadow_eval.evaluate({"cases": []})
        self.assertIsNone(empty_metrics["candidateRecall"])
        self.assertIsNone(empty_metrics["C"]["conditionalAccuracy"])
        self.assertIsNone(empty_metrics["rawChoiceCoverage"])
        self.assertIsNone(empty_metrics["readableWrongChoiceRate"])

        all_unreadable = shadow_eval.evaluate({"cases": [
            dict(caseId="u1", book="b", truth=None, unreadable=True, candidates=[], rulePick=None, jevPick=None)
        ]})
        self.assertEqual(all_unreadable["readableN"], 0)
        self.assertEqual(all_unreadable["unreadableN"], 1)
        self.assertIsNone(all_unreadable["candidateRecall"])
        self.assertIsNone(all_unreadable["C"]["conditionalAccuracy"])
        self.assertEqual(all_unreadable["rawChoiceCoverage"], 0.0)

    def test_jr_09_t05_projection_and_opencc_requirement(self):
        """JR-09-T05: 原字不同但繁简投影相同：主指标仍能区分，投影指标另报；转换依赖缺失立即失败"""
        proj_cases = [
            dict(caseId="P1", book="synthetic", category="繁体", truth="國", current="國",
                 candidates=[dict(candidateId="k0", text="国")],
                 jevPick="k0")
        ]
        # 验证缺失依赖时立即报错
        from unittest.mock import patch
        with patch.dict(sys.modules, {"opencc": None}):
            with self.assertRaises(RuntimeError) as ctx:
                shadow_eval.evaluate({"cases": proj_cases}, normalize=True)
            self.assertIn("requires opencc", str(ctx.exception))

        # 若环境具备 opencc，验证原字区分与投影归一化
        try:
            import opencc
            metrics = shadow_eval.evaluate({"cases": proj_cases}, normalize=True)
            # Exact match main metric distinguishes
            self.assertEqual(metrics["C"]["correct"], 0)
            self.assertEqual(metrics["C"]["wrong"], 1)
            # Projection metric normalizes
            self.assertEqual(metrics["projection"]["C"]["correct"], 1)
            self.assertEqual(metrics["projection"]["C"]["wrong"], 0)
        except ImportError:
            pass

    def test_jr_09_t06_abc_groups_and_runs_format(self):
        """JR-09-T06: A/B/C 同样本配对、raw/preferred/admitted 分开；按书与风险类别能逐案复算"""
        case = dict(
            caseId="ABC-1", book="test-book", category="否定", truth="不得", current="不待",
            candidates=[
                dict(candidateId="k0", text="不待"),
                dict(candidateId="k1", text="不得"),
            ],
            runs={
                "A": {"displayedText": "不待"},
                "B": {"rawPick": "k0", "executionStatus": "SUCCEEDED"},
                "C": {"rawPick": "k1", "executionStatus": "SUCCEEDED", "policyVerdict": "CURRENT_RECOMMENDED",
                      "admittedRecommendationId": "k1"},
            }
        )
        metrics = shadow_eval.evaluate({"cases": [case]})
        self.assertEqual(metrics["A"]["wrong"], 1)
        self.assertEqual(metrics["B"]["wrong"], 1)
        self.assertEqual(metrics["C"]["correct"], 1)
        self.assertEqual(metrics["admittedRecommendationCoverage"], 1.0)
        self.assertIn("test-book", metrics["byBook"])
        self.assertIn("否定", metrics["byCategory"])


if __name__ == "__main__":
    unittest.main()
