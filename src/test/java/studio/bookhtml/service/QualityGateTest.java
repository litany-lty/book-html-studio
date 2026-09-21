package studio.bookhtml.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import static org.junit.jupiter.api.Assertions.*;

class QualityGateTest {
    private static Block block(String id, double[] bbox, String text) {
        return new Block(id, "text", 0, bbox, "horizontal-tb", text, text, 0.9, false, false, null, "native", List.of(id), null, null);
    }

    private static BufferedImage white(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static BufferedImage inky(int w, int h) {
        BufferedImage image = white(w, h);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h / 2);
        g.dispose();
        return image;
    }

    @Test void smallNativeWithHeavyInkPrefersOcr() {
        Block tiny = block("n0", new double[]{.1, .1, .02, .02}, "甲");
        BufferedImage image = inky(400, 400);
        try {
            assertTrue(QualityGate.shouldPreferOcr(List.of(tiny), image));
        } finally {
            image.flush();
        }
    }

    @Test void denseNativeKeepsTextLayer() {
        String text = "甲乙丙丁戊己庚辛壬癸".repeat(15);
        Block dense = block("n0", new double[]{.05, .05, .8, .8}, text);
        BufferedImage image = inky(400, 400);
        try {
            assertFalse(QualityGate.shouldPreferOcr(List.of(dense), image));
        } finally {
            image.flush();
        }
    }

    @Test void trueBlankDetectedOnWhitePage() {
        BufferedImage image = white(400, 400);
        try {
            assertTrue(QualityGate.isTrueBlank(image));
            assertFalse(QualityGate.isTrueBlank(inky(400, 400)));
        } finally {
            image.flush();
        }
    }

    @Test void sourceLossOrDuplicationDetected() {
        Block a = block("a", new double[]{0, 0, .4, .2}, "甲");
        Block b = block("b", new double[]{0, .3, .4, .2}, "乙");
        assertFalse(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(b, a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a, a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a, b, block("c", new double[]{0, 0, .1, .1}, "丙"))));
    }

    @Test void figureOnlyDetected() {
        Block fig = new Block("f", "figure", 0, new double[]{0, 0, 1, 1}, "horizontal-tb", "", "", null, false, false, null, "paddle", List.of("f"), null, null);
        assertTrue(QualityGate.isFigureOnly(List.of(fig)));
        assertFalse(QualityGate.isFigureOnly(List.of(block("t", new double[]{0, 0, .5, .2}, "文字"))));
    }

    private static Block sourceBlock(String id, String text) {
        return new Block(id, "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                text, text, 0.9, false, false, null, "paddle", List.of(id), null, new double[]{0, 0, 40, 20});
    }

    private static Block outBlock(String id, String text, List<String> sourceIds, double[] sourceRect) {
        return new Block(id, "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                text, text, 0.9, false, false, null, "assist", sourceIds, null, sourceRect);
    }

    @Test void reorderRejectsSameIdTextChange() {
        // T08：同 ID 改字必须拒绝
        Block s1 = sourceBlock("s1", "甲");
        Block changed = outBlock("s1", "乙", List.of("s1"), new double[]{0, 0, 40, 20});
        QualityGate.GateVerdict verdict = QualityGate.check(List.of(s1), List.of(changed),
                QualityGate.GateOp.REORDER_OR_RECLASSIFY);
        assertFalse(verdict.accepted());
        assertTrue(verdict.reason().contains("改写"));
        // 仅重排则通过
        Block a = sourceBlock("a", "甲"), b = sourceBlock("b", "乙");
        Block ra = outBlock("a", "甲", List.of("a"), new double[]{0, 0, 40, 20});
        Block rb = outBlock("b", "乙", List.of("b"), new double[]{0, .3, 40, 20});
        assertTrue(QualityGate.check(List.of(a, b), List.of(rb, ra),
                QualityGate.GateOp.REORDER_OR_RECLASSIFY).accepted());
    }

    @Test void reorderRejectsPunctuationSneak() {
        Block s1 = sourceBlock("s1", "甲乙。");
        Block changed = outBlock("s1", "甲乙，", List.of("s1"), new double[]{0, 0, 40, 20});
        assertFalse(QualityGate.check(List.of(s1), List.of(changed),
                QualityGate.GateOp.REORDER_OR_RECLASSIFY).accepted());
    }

    @Test void mergeAcceptsLegalConcatButRejectsSplit() {
        // T09：合法合并 s1+s2→m1 通过
        Block s1 = sourceBlock("s1", "甲乙"), s2 = sourceBlock("s2", "丙丁");
        Block m1 = outBlock("m1", "甲乙丙丁", List.of("s1", "s2"), null);
        assertTrue(QualityGate.check(List.of(s1, s2), List.of(m1),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
        // 拆分（同一来源被两个输出块引用）拒绝：合同未实现
        Block d1 = outBlock("m1", "甲乙", List.of("s1"), null);
        Block d2 = outBlock("m2", "丙丁", List.of("s1"), null);
        assertFalse(QualityGate.check(List.of(s1, s2), List.of(d1, d2),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
    }

    @Test void mergeRejectsBadCoverage() {
        // T10：漏区间、未知来源、重复引用分别拒绝
        Block s1 = sourceBlock("s1", "甲乙"), s2 = sourceBlock("s2", "丙丁");
        Block onlyOne = outBlock("m1", "甲乙", List.of("s1"), null);
        assertFalse(QualityGate.check(List.of(s1, s2), List.of(onlyOne),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
        Block unknown = outBlock("m1", "甲乙丙丁", List.of("s1", "ghost"), null);
        assertFalse(QualityGate.check(List.of(s1, s2), List.of(unknown),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
        Block dup = outBlock("m1", "甲乙甲乙", List.of("s1", "s1"), null);
        assertFalse(QualityGate.check(List.of(s1, s2), List.of(dup),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
        Block tampered = outBlock("m1", "甲乙戊己", List.of("s1", "s2"), null);
        assertFalse(QualityGate.check(List.of(s1, s2), List.of(tampered),
                QualityGate.GateOp.MERGE_TEXT_STRUCTURE).accepted());
    }

    @Test void recoveryAcceptsEvidencedBlocksButRejectsUnevidenced() {
        // T11：有原图区域依据的新转录进入 NEW_VISUAL 契约；无证据的新文字拒绝
        Block s1 = sourceBlock("s1", "图甲");
        Block kept = outBlock("s1", "图甲", List.of("s1"), new double[]{0, 0, 40, 20});
        Block fresh = outBlock("qwen-toc-r1", "目录一", List.of("s1"), new double[]{0, 0, 100, 50});
        assertTrue(QualityGate.check(List.of(s1), List.of(kept, fresh),
                QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION).accepted());
        Block noEvidence = outBlock("qwen-toc-r1", "目录一", List.of("s1"), null);
        assertFalse(QualityGate.check(List.of(s1), List.of(kept, noEvidence),
                QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION).accepted());
        // 同一视觉来源被两个区域恢复引用：允许
        Block r2 = outBlock("qwen-toc-r2", "目录二", List.of("s1"), new double[]{0, 50, 100, 50});
        assertTrue(QualityGate.check(List.of(s1), List.of(fresh, r2),
                QualityGate.GateOp.NEW_VISUAL_TRANSCRIPTION).accepted());
    }

    @Test void reorderRejectsTamperedResolvedIssuePayload() {
        // JR-12-T01: 同 id/resolved=true，但 replacement/range/ReviewResolution 改动：重排门拒绝
        studio.bookhtml.decision.DecisionModels.ReviewResolution res =
                new studio.bookhtml.decision.DecisionModels.ReviewResolution(
                        "res-1", "op-1", studio.bookhtml.decision.DecisionModels.Origin.JEV_ASSISTED,
                        "dec-1", "cand-1", "cs-1", "pdf-1", 0, "basis-1", "甲", "甲",
                        "conv-v1", true, java.time.Instant.now(), 1);
        studio.bookhtml.domain.ContentIssue origIssue =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 1, 0, 1, "r", true, "甲", "推", res);
        Block s1 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(origIssue));

        // 1. 改动 replacement
        studio.bookhtml.domain.ContentIssue tamperedReplacement =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 1, 0, 1, "r", true, "乙", "推", res);
        Block out1 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(tamperedReplacement));
        assertFalse(QualityGate.checkReorderOrReclassify(List.of(s1), List.of(out1)).accepted());

        // 2. 改动 range
        studio.bookhtml.domain.ContentIssue tamperedRange =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 2, 0, 2, "r", true, "甲", "推", res);
        Block out2 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(tamperedRange));
        assertFalse(QualityGate.checkReorderOrReclassify(List.of(s1), List.of(out2)).accepted());

        // 3. 改动 resolution
        studio.bookhtml.decision.DecisionModels.ReviewResolution tamperedRes =
                new studio.bookhtml.decision.DecisionModels.ReviewResolution(
                        "res-2", "op-2", studio.bookhtml.decision.DecisionModels.Origin.MANUAL,
                        "dec-x", "cand-x", "cs-x", "pdf-x", 0, "basis-x", "乙", "乙",
                        "conv-v1", true, java.time.Instant.now(), 1);
        studio.bookhtml.domain.ContentIssue tamperedResolution =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 1, 0, 1, "r", true, "甲", "推", tamperedRes);
        Block out3 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(tamperedResolution));
        assertFalse(QualityGate.checkReorderOrReclassify(List.of(s1), List.of(out3)).accepted());
    }

    @Test void reorderAllowsOrderTypeHeadingChangeWithSamePayload() {
        // JR-12-T02: 合法只改 order/type/heading：原字和确认载荷不变时通过
        studio.bookhtml.decision.DecisionModels.ReviewResolution res =
                new studio.bookhtml.decision.DecisionModels.ReviewResolution(
                        "res-1", "op-1", studio.bookhtml.decision.DecisionModels.Origin.JEV_ASSISTED,
                        "dec-1", "cand-1", "cs-1", "pdf-1", 0, "basis-1", "甲", "甲",
                        "conv-v1", true, java.time.Instant.now(), 1);
        studio.bookhtml.domain.ContentIssue origIssue =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 1, 0, 1, "r", true, "甲", "推", res);
        Block s1 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(origIssue));
        Block modified = new Block("s1", "heading", 1, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, 2, "paddle", List.of("s1"), null, null, List.of(origIssue));
        assertTrue(QualityGate.checkReorderOrReclassify(List.of(s1), List.of(modified)).accepted());
    }

    @Test void mergePreservesResolvedIssuesOrRejects() {
        // JR-12-T03: 合并含已确认疑点的块：必须保留已确认疑点及其确认载荷
        studio.bookhtml.decision.DecisionModels.ReviewResolution res =
                new studio.bookhtml.decision.DecisionModels.ReviewResolution(
                        "res-1", "op-1", studio.bookhtml.decision.DecisionModels.Origin.JEV_ASSISTED,
                        "dec-1", "cand-1", "cs-1", "pdf-1", 0, "basis-1", "甲", "甲",
                        "conv-v1", true, java.time.Instant.now(), 1);
        studio.bookhtml.domain.ContentIssue origIssue =
                new studio.bookhtml.domain.ContentIssue("i1", "suspected", 0, 1, 0, 1, "r", true, "甲", "推", res);
        Block s1 = new Block("s1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙", "甲乙", 0.9, false, false, null, "paddle", List.of("s1"), null, null, List.of(origIssue));
        Block s2 = sourceBlock("s2", "丙丁");

        // 合并后丢弃 i1 -> 拒绝
        Block mDropped = outBlock("m1", "甲乙丙丁", List.of("s1", "s2"), null);
        assertFalse(QualityGate.checkMergeTextStructure(List.of(s1, s2), List.of(mDropped)).accepted());

        // 合并后保留 i1 且载荷一致 -> 通过
        Block mKept = new Block("m1", "text", 0, new double[]{0, 0, .4, .2}, "horizontal-tb",
                "甲乙丙丁", "甲乙丙丁", 0.9, false, false, null, "assist", List.of("s1", "s2"), null, null, List.of(origIssue));
        assertTrue(QualityGate.checkMergeTextStructure(List.of(s1, s2), List.of(mKept)).accepted());
    }

    @Test void newVisualTranscriptionRejectsInvalidBbox() {
        // JR-12-T04: 新视觉条目 sourceRect 含 NaN/无穷/越界或非正：拒绝
        Block s1 = sourceBlock("s1", "图甲");
        Block kept = outBlock("s1", "图甲", List.of("s1"), new double[]{0, 0, 40, 20});
        Block nanRect = outBlock("qwen-1", "目录", List.of("s1"), new double[]{Double.NaN, 0, 100, 50});
        assertFalse(QualityGate.checkNewVisualTranscription(List.of(s1), List.of(kept, nanRect)).accepted());

        Block infRect = outBlock("qwen-2", "目录", List.of("s1"), new double[]{0, Double.POSITIVE_INFINITY, 100, 50});
        assertFalse(QualityGate.checkNewVisualTranscription(List.of(s1), List.of(kept, infRect)).accepted());

        Block zeroWidth = outBlock("qwen-3", "目录", List.of("s1"), new double[]{0, 0, 0, 50});
        assertFalse(QualityGate.checkNewVisualTranscription(List.of(s1), List.of(kept, zeroWidth)).accepted());
    }
}
