package studio.bookhtml.decision;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.TraditionalConverter;

import static org.junit.jupiter.api.Assertions.*;

/**
 * J02（T32、T34–T36）：疑点召回、候选对齐、旧推测兼容、同源不计票。
 */
class CandidateResolutionTest {
    private CandidateResolutionService service() {
        return new CandidateResolutionService(new TraditionalConverter());
    }

    private static Block block(String id, String original, String suggestion, Double confidence,
                               boolean uncertain, List<ContentIssue> issues, List<String> sourceIds) {
        return new Block(id, "text", 0, new double[]{0, 0, 0.4, 0.2}, "horizontal-tb",
                original, original, confidence, uncertain, false, null, "paddle",
                sourceIds, suggestion, new double[]{0, 0, 40, 20}, issues);
    }

    private static ContentIssue issue(String id, boolean resolved) {
        return new ContentIssue(id, "suspected", 0, 1, 0, 1, "理由", resolved, null, null);
    }

    private static Page page(List<Block> blocks) {
        return new Page(1, 600, 800, "READY", "paddle", blocks, List.of(), false, null, blocks);
    }

    private static DecisionModels.IssueRef ref(String span) {
        return new DecisionModels.IssueRef("book", "pdf", 3, 5, "b1", "i1",
                "otext", "basis", 0, 1, span, "map-v1");
    }

    private CandidateResolutionService.RawCandidate raw(String text,
                                                        DecisionModels.SourceKind kind, String group, String run) {
        return raw(text, kind, group, run, 0, 1);
    }

    private CandidateResolutionService.RawCandidate raw(String text,
                                                        DecisionModels.SourceKind kind, String group, String run,
                                                        int claimedStart, int claimedEnd) {
        return new CandidateResolutionService.RawCandidate(text, kind, "producer", null, null, run,
                group, List.of("E-" + run), "crop-1", DecisionModels.LocatorMode.REGION,
                new double[]{0, 0, 10, 10}, "t-v1", List.of("E-" + run), 0.8, claimedStart, claimedEnd);
    }

    @Test void recallCoversNonSuggestionSignalsAndProtectsResolved() {
        // T32：无 suggestion 但有 issue/低置信/缺来源可进入；已确认目标受保护
        Block withIssue = block("b1", "甲乙", null, 0.9, false, List.of(issue("i1", false)), List.of("b1"));
        Block lowConf = block("b2", "丙丁", null, 0.2, false, List.of(), List.of("b2"));
        Block noSource = block("b3", "戊己", null, 0.9, false, List.of(), List.of());
        Block resolved = block("b4", "庚辛", "疑点", 0.9, false, List.of(issue("i4", true)), List.of("b4"));
        // suggestion 文案含引号也不得被解析成候选（结构上 recall 根本不读文案内容）
        Block tricky = block("b5", "壬癸", "候选「甲」或「乙」", 0.9, false, List.of(), List.of("b5"));
        List<CandidateResolutionService.RecallTarget> targets =
                service().recall(page(List.of(withIssue, lowConf, noSource, resolved, tricky)), List.of(), false);
        assertTrue(targets.stream().anyMatch(t -> t.block().id().equals("b1")
                && t.signals().contains(CandidateResolutionService.RecallSignal.EXISTING_ISSUE)));
        assertTrue(targets.stream().anyMatch(t -> t.block().id().equals("b2")
                && t.signals().contains(CandidateResolutionService.RecallSignal.LOW_CONFIDENCE)));
        assertTrue(targets.stream().anyMatch(t -> t.block().id().equals("b3")
                && t.signals().contains(CandidateResolutionService.RecallSignal.MISSING_SOURCE)));
        assertTrue(targets.stream().noneMatch(t -> t.block().id().equals("b4")),
                "已确认目标默认不进入自动复核");
        assertTrue(targets.stream().anyMatch(t -> t.block().id().equals("b5") && t.issue() == null),
                "有 suggestion 的块进入建议级目标，但不产生解析出的候选");
        // 显式允许时已确认目标可进入
        assertTrue(service().recall(page(List.of(resolved)), List.of(), true).stream()
                .anyMatch(t -> t.block().id().equals("b4")));
    }

    @Test void wholeRowNeverReplacesSingleChar() {
        // T34：单字目标 + 整行输出 → 不直接替换；声明区间严格包含目标则给新范围，旧确认不得套用
        CandidateResolutionService service = service();
        String original = "甲乙丙丁";
        CandidateResolutionService.AlignedCandidate aligned =
                service.align(original, 1, 2, raw("甲乙丙丁", DecisionModels.SourceKind.CROP_OCR, "G1", "r1", 0, 4));
        assertEquals(DecisionModels.AlignmentStatus.EXPANDED_SPAN, aligned.alignment());
        assertNotNull(aligned.expandedSpan());
        assertEquals(0, aligned.expandedSpan().startUtf16());
        assertEquals(4, aligned.expandedSpan().endUtf16());
        // 重复出现无法界定唯一区间 → 返回 null，调用方不得取第一次，排除
        assertNull(CandidateResolutionService.locateScope("甲乙甲乙", "甲乙"));
        assertEquals(DecisionModels.AlignmentStatus.UNALIGNED,
                service.align("甲乙甲乙", 0, 2,
                        raw("甲乙甲乙", DecisionModels.SourceKind.CROP_OCR, "G1", "r1")).alignment());
        // 唯一出现可界定
        CandidateResolutionService.ExpandedSpan scope =
                CandidateResolutionService.locateScope(original, "甲乙丙丁");
        assertNotNull(scope);
        assertEquals(0, scope.startUtf16());
        assertEquals(4, scope.endUtf16());
        // 同一区间的竞争读法 → EXACT（文本不同正是要比较的内容）
        assertEquals(DecisionModels.AlignmentStatus.EXACT,
                service.align(original, 1, 2, raw("乙", DecisionModels.SourceKind.CROP_OCR, "G1", "r1", 1, 2)).alignment());
        // 交错区间 → 排除
        assertEquals(DecisionModels.AlignmentStatus.UNALIGNED,
                service.align(original, 1, 2, raw("乙丙", DecisionModels.SourceKind.CROP_OCR, "G1", "r1", 2, 4)).alignment());
    }

    @Test void legacyInferenceKeepsDisplayWithoutForgingOriginal() {
        // T35：已被转简体的旧 inferredText 标 LEGACY，不伪造原字与图像支持
        DecisionModels.Candidate legacy = service().legacyCandidate(ref("span"), "未得", "k-legacy");
        assertEquals(DecisionModels.SourceKind.LEGACY_INFERENCE, legacy.sourceKind());
        assertNull(legacy.originalScriptText());
        assertEquals("未得", legacy.simplifiedDisplayText());
        assertEquals("LEGACY_UNKNOWN_ORIGINAL", legacy.originalUnknownReason());
        assertTrue(legacy.evidenceRefs().isEmpty());
    }

    @Test void sameSourceRepeatsDoNotCountTriple() {
        // T36：同模型三次 + 同源派生不计三票；正确候选缺失不强选（此处只验集合层）
        CandidateResolutionService service = service();
        DecisionModels.IssueRef issueRef = ref("span");
        List<CandidateResolutionService.RawCandidate> raws = List.of(
                raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0", "r0"),
                raw("乙", DecisionModels.SourceKind.CROP_OCR, "G1", "r1"),
                raw("乙", DecisionModels.SourceKind.CROP_OCR, "G1", "r2"),
                raw("乙", DecisionModels.SourceKind.SEMANTIC_INFERENCE, "G1", "r3"));
        DecisionModels.CandidateSet set = service.buildSet(issueRef, "甲乙", "甲", raws);
        assertEquals(4, set.rawCount());
        assertEquals(2, set.dedupedCount());
        DecisionModels.Candidate merged = set.candidates().stream()
                .filter(c -> "乙".equals(c.originalScriptText())).findFirst().orElseThrow();
        assertEquals("G1", merged.acquisitionGroup());
        assertEquals(3, merged.evidenceRefs().size());
        assertEquals(3, merged.upstreamEvidenceIds().size());
        // 当前转录必留
        assertTrue(set.candidates().stream().anyMatch(c -> "甲".equals(c.originalScriptText())));
    }

    @Test void overLimitDropsWithReasonsAndNoUsableThrows() {
        CandidateResolutionService service = service();
        DecisionModels.IssueRef issueRef = ref("span");
        // 同一区间的 7 个不同读法 → 6 实质位不够，截断须留理由
        var raws = new java.util.ArrayList<CandidateResolutionService.RawCandidate>();
        raws.add(raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0", "r0"));
        String[] others = {"乙", "丙", "丁", "戊", "己", "庚"};
        for (int i = 0; i < others.length; i++)
            raws.add(raw(others[i], DecisionModels.SourceKind.CROP_OCR, "G" + i, "r" + i));
        DecisionModels.CandidateSet set = service.buildSet(issueRef, "甲乙", "甲", raws);
        assertEquals(6, set.candidates().size());
        assertTrue(set.truncated());
        assertEquals(1, set.truncationReasons().size());
        assertTrue(set.truncationReasons().get(0).startsWith("OVER_LIMIT_DROPPED:"));
        // 当前转录即使超限也必留
        assertTrue(set.candidates().stream().anyMatch(c -> "甲".equals(c.originalScriptText())));
        // 脱离区间的候选被排除 → 无可用时抛明确错误（上游转 HUMAN_REQUIRED）
        assertThrows(IllegalArgumentException.class,
                () -> service.buildSet(issueRef, "甲乙丙", "甲",
                        List.of(raw("丙", DecisionModels.SourceKind.SEMANTIC_INFERENCE, "G", "r", 2, 3))));
    }
}
