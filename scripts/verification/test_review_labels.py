import copy
import unittest

from apply_review_labels import apply_labels, attach_live
from freeze_candidates import match_blocks
from shadow_eval import evaluate


class ReviewLabelTests(unittest.TestCase):
    def setUp(self):
        self.dataset = {"cases": [{"caseId": "c1", "current": "甲", "candidates": [
            {"candidateId": "k0", "text": "甲"}], "annotation": {"status": "UNLABELED"}}]}
        self.labels = {"datasetSha256": "sha", "reviewerType": "AI", "reviewedByHuman": False,
                       "sourceOnlyReview": True, "labels": [{"caseId": "c1",
                       "status": "READABLE_WITH_TRUTH", "originalScriptTruth": "甲"}]}

    def test_ai_labels_are_not_human_calibration(self):
        result = apply_labels(self.dataset, self.labels, "sha")
        metrics = evaluate(result)
        self.assertEqual(metrics["readableN"], 1)
        self.assertFalse(metrics["humanCalibrationEligible"])
        self.assertFalse(metrics["automaticAssistApproval"])
        self.assertEqual(self.dataset["cases"][0]["annotation"]["status"], "UNLABELED")

    def test_hash_duplicates_unknown_and_provenance_rejected(self):
        for change in (
            {"datasetSha256": "other"}, {"reviewedByHuman": True}, {"reviewerType": None},
            {"labels": self.labels["labels"] * 2},
            {"labels": [{"caseId": "other", "status": "NOT_TEXT"}]},
            {"labels": [{"caseId": "c1", "status": "MAYBE"}]},
            {"labels": [{"caseId": "c1", "status": "READABLE_WITH_TRUTH", "originalScriptTruth": " "}]},
            {"labels": [{"caseId": "c1", "status": "AMBIGUOUS", "originalScriptTruth": "甲"}]},
        ):
            with self.subTest(change=change), self.assertRaises(ValueError):
                apply_labels(self.dataset, dict(self.labels, **change), "sha")

    def test_existing_labels_not_overwritten(self):
        labeled = apply_labels(self.dataset, self.labels, "sha")
        with self.assertRaises(ValueError):
            apply_labels(labeled, self.labels, "sha")

    def test_not_text_missing_labels_and_invalid_status(self):
        dataset = {"cases": [
            {"caseId": "layout", "annotation": {"status": "NOT_TEXT"}, "jevPick": "k0",
             "admittedRecommendationId": "k0", "candidates": [{"candidateId": "k0", "text": "一"}]},
            {"caseId": "missing", "candidates": []},
        ]}
        metrics = evaluate(dataset)
        self.assertEqual((metrics["nonTextN"], metrics["unlabeledN"], metrics["unreadableN"]), (1, 1, 0))
        self.assertIsNone(metrics["candidateRecall"])
        self.assertIsNone(metrics["admittedRecommendationCoverage"])
        self.assertEqual(metrics["admittedRecommendationCountAllCases"], 1)
        with self.assertRaises(ValueError):
            evaluate({"cases": [{"annotation": {"status": "TYPO"}}]})

    def test_live_identity_and_completeness_checked(self):
        row = {"caseId": "c1", "datasetHash": "sha", "jevPick": "k0",
               "cExecutionStatus": "SUCCEEDED", "runB": {"candidateSetHash": "hash"},
               "runC": {"candidateSetHash": "hash"}, "policyVerdict": "CANDIDATES_ONLY"}
        result = apply_labels(self.dataset, self.labels, "sha")
        attach_live(result, [row], "sha")
        self.assertEqual(evaluate(result)["C"]["correct"], 1)
        for rows in ([], [row, row], [dict(row, datasetHash="other")],
                     [dict(row, runC={"candidateSetHash": "other"})], [dict(row, jevPick="bogus")]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                attach_live(copy.deepcopy(result), rows, "sha")

    def test_freeze_risk_uses_disputed_text_not_context(self):
        a = {"id": "a", "order": 1, "bbox": [0, 0, 1, 1], "original": "不可，123"}
        b = dict(a, id="b", original="不可123")
        divergent, gaps = [], {"unmatchedBlocks": 0, "agreementBlocks": 0}
        match_blocks([a], [b], "book", {"pdfSha256": "sha"}, 1, divergent, [], gaps)
        self.assertEqual(divergent[0]["category"], ["普通"])
        a["original"], b["original"] = "123\n456", "123甲456"
        divergent = []
        match_blocks([a], [b], "book", {"pdfSha256": "sha"}, 1, divergent, [], gaps)
        self.assertEqual(divergent, [])
        self.assertEqual(gaps["nonTextTargets"], 1)


if __name__ == "__main__":
    unittest.main()
