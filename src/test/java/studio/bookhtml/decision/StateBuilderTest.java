package studio.bookhtml.decision;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.bookhtml.service.TraditionalConverter;

import static org.junit.jupiter.api.Assertions.*;

/** J05（T45–T47、T61）：问题独立、超限不截断关键内容、引用可验证、别名映射正确。 */
class StateBuilderTest {
    private DecisionStateBuilder builder() {
        return new DecisionStateBuilder();
    }

    private CandidateResolutionService resolution() {
        return new CandidateResolutionService(new TraditionalConverter());
    }

    private DecisionModels.IssueRef ref() {
        return new DecisionModels.IssueRef("book", "pdf", 3, 5, "b1", "i1",
                "otext", "basis", 0, 1, "span", "map-v1");
    }

    private DecisionModels.CandidateSet set(String original) {
        CandidateResolutionService service = resolution();
        var raws = List.of(
                new CandidateResolutionService.RawCandidate("甲",
                        DecisionModels.SourceKind.PRIMARY_OCR, "paddle", null, null, "r0",
                        "G0", List.of(), null, DecisionModels.LocatorMode.REGION,
                        new double[]{0, 0, 10, 10}, "t-v1", List.of("E0"), 0.9, 0, 1),
                new CandidateResolutionService.RawCandidate("乙",
                        DecisionModels.SourceKind.CROP_OCR, "qwen", null, null, "r1",
                        "G1", List.of(), "crop-1", DecisionModels.LocatorMode.REGION,
                        new double[]{0, 0, 10, 10}, "t-v1", List.of("E1"), 0.8, 0, 1));
        return service.buildSet(ref(), original, "甲", raws);
    }

    @Test void questionsIndependentAndComplete() throws Exception {
        // T45：每题 instructions 自含完整语义，不依赖同批另一题输出；ID 只关联结果
        DecisionStateBuilder.BuiltState built =
                builder().build(ref(), "甲乙", set("甲乙"), List.of(), false, 32768);
        String choice = built.questions().get(DecisionStateBuilder.CHOICE_ID).instructions();
        String gap = built.questions().get(DecisionStateBuilder.GAP_ID).instructions();
        assertTrue(choice.contains("NONE_SUPPORTED") && choice.contains("NEED_MORE_EVIDENCE"));
        assertFalse(choice.contains(DecisionStateBuilder.GAP_ID));
        assertFalse(gap.contains(DecisionStateBuilder.CHOICE_ID));
        assertFalse(gap.contains("best"));
        assertEquals("question-template-v3-book-context", DecisionStateBuilder.TEMPLATE_VERSION);
    }

    @Test void overBudgetDropsOptionalFirstThenRefuses() throws Exception {
        // T46：可选上下文先丢并记录范围；强制部分超限则整体拒绝，不静默截尾
        List<String> neighbors = List.of("邻一".repeat(50), "邻二".repeat(50));
        DecisionStateBuilder.BuiltState built =
                builder().build(ref(), "甲乙", set("甲乙"), neighbors, false, 32768);
        assertTrue(built.keptRanges().contains("target"));
        assertEquals(2, ((List<?>) built.state().get("candidates")).size());
        // 极小预算：可选丢完仍超限 → 拒绝，且 kept 含关键部分
        DecisionStateBuilder.InputTooLargeException e =
                assertThrows(DecisionStateBuilder.InputTooLargeException.class,
                        () -> builder().build(ref(), "不待擅改", set("不待擅改"),
                                List.of("邻".repeat(500)), false, 200));
        assertTrue(e.keptRanges().contains("target"));
        assertTrue(e.keptRanges().contains("hardRiskFlags"));
    }

    @Test void evidenceRefsVerifiableAndAliasMappingCorrect() throws Exception {
        // T47/T61：引用取回由代码完成；别名映射永远正确，顺序置换只改变 hash 不弄错映射
        DecisionStateBuilder.BuiltState built =
                builder().build(ref(), "甲乙", set("甲乙"), List.of(), false, 32768);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) built.state().get("candidates");
        assertEquals(2, candidates.size());
        for (Map<String, Object> entry : candidates) {
            String alias = (String) entry.get("alias");
            assertNotNull(built.aliasToCandidateId().get(alias));
            @SuppressWarnings("unchecked")
            List<String> refs = (List<String>) entry.get("evidenceRefs");
            assertFalse(refs.isEmpty());
        }
        DecisionModels.DecisionSnapshot snapshot =
                DecisionStateBuilder.snapshot(ref(), "cs-hash", built);
        assertNotNull(snapshot.snapshotHash());
        // 同一候选集合做受控顺序置换：优先级排序使最终顺序确定，映射永远正确且幂等
        CandidateResolutionService service = resolution();
        var swapped = List.of(
                new CandidateResolutionService.RawCandidate("乙",
                        DecisionModels.SourceKind.CROP_OCR, "qwen", null, null, "r1",
                        "G1", List.of(), "crop-1", DecisionModels.LocatorMode.REGION,
                        new double[]{0, 0, 10, 10}, "t-v1", List.of("E1"), 0.8, 0, 1),
                new CandidateResolutionService.RawCandidate("甲",
                        DecisionModels.SourceKind.PRIMARY_OCR, "paddle", null, null, "r0",
                        "G0", List.of(), null, DecisionModels.LocatorMode.REGION,
                        new double[]{0, 0, 10, 10}, "t-v1", List.of("E0"), 0.9, 0, 1));
        DecisionModels.CandidateSet set2 = service.buildSet(ref(), "甲乙", "甲", swapped);
        DecisionStateBuilder.BuiltState built2 =
                builder().build(ref(), "甲乙", set2, List.of(), false, 32768);
        DecisionModels.CandidateSet first = set("甲乙");
        assertEquals(first.candidateSetHash(), set2.candidateSetHash());
        assertEquals(first.candidates().get(0).candidateId(),
                built2.aliasToCandidateId().get("C0"));
        assertEquals(snapshot.snapshotHash(),
                DecisionStateBuilder.snapshot(ref(), "cs-hash", built2).snapshotHash());
    }

    @Test void jr07T01_legacyInferenceHasComparisonTextAndHypothesisLayerInState() throws Exception {
        // JR-07-T01: 仅当前 OCR + 旧 inferredText：实际序列化 state 能读到两个候选文字，旧者明确是假设
        CandidateResolutionService res = resolution();
        var raws = List.of(new CandidateResolutionService.RawCandidate("甲",
                DecisionModels.SourceKind.PRIMARY_OCR, "paddle", null, null, "r0",
                "G0", List.of(), null, DecisionModels.LocatorMode.REGION,
                new double[]{0, 0, 10, 10}, "t-v1", List.of("E0"), 0.9, 0, 1));
        DecisionModels.CandidateSet set = res.buildSet(ref(), "甲乙", "甲", raws);
        DecisionModels.Candidate legacy = res.legacyCandidate(ref(), "不得", "cand-legacy-1");
        DecisionModels.CandidateSet merged = res.mergeLegacy(set, legacy);

        DecisionStateBuilder.BuiltState built = builder().build(ref(), "甲乙", merged, List.of(), false, 32768);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) built.state().get("candidates");
        assertEquals(2, candidates.size());

        Map<String, Object> ocrEntry = candidates.stream()
                .filter(c -> "甲".equals(c.get("text"))).findFirst().orElseThrow();
        assertEquals(DecisionModels.Candidate.LAYER_ORIGINAL, ocrEntry.get("textLayer"));
        assertEquals(true, ocrEntry.get("originalScriptKnown"));

        Map<String, Object> legacyEntry = candidates.stream()
                .filter(c -> "不得".equals(c.get("text"))).findFirst().orElseThrow();
        assertEquals(DecisionModels.Candidate.LAYER_LEGACY_HYPOTHESIS, legacyEntry.get("textLayer"));
        assertEquals(false, legacyEntry.get("originalScriptKnown"));
        assertFalse(String.valueOf(legacyEntry.get("text")).isBlank());
    }
}
