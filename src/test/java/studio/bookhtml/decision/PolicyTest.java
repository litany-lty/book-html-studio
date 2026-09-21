package studio.bookhtml.decision;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.bookhtml.service.TraditionalConverter;

import static org.junit.jupiter.api.Assertions.*;

/** J06（T48–T51）： verdict 判定表、高风险门禁、未校准降级、平分与冲突处理。 */
class PolicyTest {
    private CandidateResolutionService resolution() {
        return new CandidateResolutionService(new TraditionalConverter());
    }

    private DecisionModels.IssueRef ref() {
        return new DecisionModels.IssueRef("book", "pdfhash", 3, 5, "b1", "i1",
                "otext", "basis", 0, 1, "span", "map-v1");
    }

    private DecisionModels.CandidateSet set(String original, String current,
                                            CandidateResolutionService.RawCandidate... raws) {
        return resolution().buildSet(ref(), original, current, List.of(raws));
    }

    private CandidateResolutionService.RawCandidate raw(String text,
                                                        DecisionModels.SourceKind kind, String group) {
        return new CandidateResolutionService.RawCandidate(text, kind, "producer", null, null,
                "run-" + group, group, List.of("E-" + group), null,
                DecisionModels.LocatorMode.REGION, new double[]{0, 0, 10, 10}, "t-v1",
                List.of("E-" + group), 0.8, 0, 1);
    }

    private DecisionStateBuilder.BuiltState built(DecisionModels.CandidateSet set, String original) throws Exception {
        return new DecisionStateBuilder().build(ref(), original, set, List.of(), false, 32768);
    }

    private DecisionPolicy.CurrentView view() {
        return new DecisionPolicy.CurrentView("book", "pdfhash", 3, 5, "b1", "i1",
                "otext", "basis", "span");
    }

    private JevDecisionClient.CallResult result(String selected, double top, double second, double gap) {
        Map<String, Double> distribution = new java.util.LinkedHashMap<>();
        distribution.put(selected, top);
        distribution.put(second == top ? "OTHER" : pickOther(selected), second);
        distribution.put("NONE_SUPPORTED", 0.02);
        distribution.put("NEED_MORE_EVIDENCE", 0.03);
        double sum = top + second + 0.05;
        distribution.put(selected, top + (1.0 - sum));
        DecisionModels.NormalizedChoice choice = new DecisionModels.NormalizedChoice(
                DecisionStateBuilder.CHOICE_ID, selected, distribution, 0.5);
        DecisionModels.NormalizedNoul noul =
                new DecisionModels.NormalizedNoul(DecisionStateBuilder.GAP_ID, gap);
        return new JevDecisionClient.CallResult(choice, noul, Map.of(), Map.of(), "jev-synth-1",
                null, "response-hash");
    }

    private static String pickOther(String selected) {
        return "C0".equals(selected) ? "C1" : "C0";
    }

    private DecisionPolicy.Input input(DecisionModels.CandidateSet set, String original, String current,
                                       JevDecisionClient.CallResult result, String calibration,
                                       List<String> risks, boolean conflict) throws Exception {
        DecisionStateBuilder.BuiltState built = built(set, original);
        DecisionModels.DecisionSnapshot snapshot =
                DecisionStateBuilder.snapshot(ref(), set.candidateSetHash(), built);
        return new DecisionPolicy.Input(snapshot, set, built.aliasToCandidateId(), current, result,
                null, false, view(), conflict, risks, false, calibration, DecisionPolicy.PILOT_DEFAULT);
    }

    @Test void semanticOnlyHighScoreNeverConfirms() throws Exception {
        // T48：只有语义猜测，即使高分也仅候选提示
        DecisionModels.CandidateSet set = set("甲乙", "甲",
                raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0"),
                raw("丙", DecisionModels.SourceKind.SEMANTIC_INFERENCE, "G1"));
        DecisionStateBuilder.BuiltState built = built(set, "甲乙");
        String semanticAlias = built.aliasToCandidateId().entrySet().stream()
                .filter(e -> set.candidates().stream()
                        .anyMatch(c -> c.candidateId().equals(e.getValue())
                                && c.sourceKind() == DecisionModels.SourceKind.SEMANTIC_INFERENCE))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        DecisionPolicy.Output output = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result(semanticAlias, 0.99, 0.0, 0.0), "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.CANDIDATES_ONLY, output.verdict());
        assertTrue(output.reasonCodes().contains("SEMANTIC_ONLY"));
    }

    @Test void sentinelsAndKeepCurrent() throws Exception {
        // T49：NONE/NEED_MORE 无文字替换；选当前原文是保留建议，不是已确认
        DecisionModels.CandidateSet set = set("甲乙", "甲", raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0"));
        DecisionStateBuilder.BuiltState built = built(set, "甲乙");
        String currentAlias = built.aliasToCandidateId().entrySet().stream()
                .filter(e -> set.candidates().stream().anyMatch(c -> c.candidateId().equals(e.getValue())
                        && "甲".equals(c.originalScriptText())))
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        Map<String, Double> noneDistribution = Map.of(currentAlias, 0.1, "NONE_SUPPORTED", 0.8,
                "NEED_MORE_EVIDENCE", 0.1);
        JevDecisionClient.CallResult none = new JevDecisionClient.CallResult(
                new DecisionModels.NormalizedChoice(DecisionStateBuilder.CHOICE_ID, "NONE_SUPPORTED",
                        noneDistribution, 0.6),
                new DecisionModels.NormalizedNoul(DecisionStateBuilder.GAP_ID, 0.9),
                Map.of(), Map.of(), "jev-synth-1", null, "h");
        DecisionPolicy.Output noneOut = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", none, "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.NONE_SUPPORTED, noneOut.verdict());
        assertNull(noneOut.recommendedCandidateId());

        DecisionPolicy.Output keep = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result(currentAlias, 0.95, 0.0, 0.0), "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.KEEP_CURRENT, keep.verdict());
        assertNotNull(keep.recommendedCandidateId());
    }

    @Test void hardRiskAndTrueConflictRequireHuman() throws Exception {
        // T50：高风险 0.999 也必须人工；真正来源冲突门禁；普通 OCR 分歧不触发硬冲突
        DecisionModels.CandidateSet set = set("不得擅改", "不",
                raw("不", DecisionModels.SourceKind.PRIMARY_OCR, "G0"),
                raw("未", DecisionModels.SourceKind.CROP_OCR, "G1"));
        DecisionModels.IssueRef negRef = new DecisionModels.IssueRef("book", "pdfhash", 3, 5, "b1",
                "i1", "otext", "basis", 0, 1, "span", "map-v1");
        DecisionStateBuilder.BuiltState built = new DecisionStateBuilder()
                .build(negRef, "不得擅改", set, List.of(), false, 32768);
        assertTrue(built.hardRiskFlags().contains("NEGATION"));
        DecisionModels.DecisionSnapshot snapshot =
                DecisionStateBuilder.snapshot(negRef, set.candidateSetHash(), built);
        JevDecisionClient.CallResult high = result(
                built.aliasToCandidateId().entrySet().stream()
                        .filter(e -> set.candidates().stream().anyMatch(c -> c.candidateId().equals(e.getValue())
                                && "未".equals(c.originalScriptText())))
                        .map(Map.Entry::getKey).findFirst().orElseThrow(),
                0.999, 0.0, 0.0);
        DecisionPolicy.Input in = new DecisionPolicy.Input(snapshot, set, built.aliasToCandidateId(),
                "不", high, null, false, view(), false, built.hardRiskFlags(), false, "VALIDATED",
                DecisionPolicy.PILOT_DEFAULT);
        DecisionPolicy.Output risk = DecisionPolicy.resolve(in);
        assertEquals(DecisionModels.Verdict.HUMAN_REQUIRED, risk.verdict());
        assertTrue(risk.reasonCodes().contains("HARD_RISK_NEGATION"));

        // 真正来源冲突
        DecisionPolicy.Input conflict = new DecisionPolicy.Input(snapshot, set, built.aliasToCandidateId(),
                "不", high, null, false, view(), true, List.of(), false, "VALIDATED",
                DecisionPolicy.PILOT_DEFAULT);
        DecisionPolicy.Output conflictOut = DecisionPolicy.resolve(conflict);
        assertEquals(DecisionModels.Verdict.HUMAN_REQUIRED, conflictOut.verdict());
        assertTrue(conflictOut.reasonCodes().contains("SOURCE_CONFLICT"));

        // 普通 OCR 分歧（无风险、无冲突、未校准）→ 仅候选，不덜硬拦
        DecisionPolicy.Output plain = DecisionPolicy.resolve(
                input(set("甲乙", "甲", raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0"),
                        raw("乙", DecisionModels.SourceKind.CROP_OCR, "G1")),
                        "甲乙", "甲", result("C1", 0.95, 0.0, 0.0), "UNVALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.CANDIDATES_ONLY, plain.verdict());
    }

    @Test void calibrationSeparationAndStaleCancelledUnknown() throws Exception {
        // T51：无校准/过期/不匹配、低分差、平分、Choice/Noul 冲突分别降级
        DecisionModels.CandidateSet set = set("甲乙", "甲",
                raw("甲", DecisionModels.SourceKind.PRIMARY_OCR, "G0"),
                raw("乙", DecisionModels.SourceKind.CROP_OCR, "G1"));
        // 有阈值配置但 UNVALIDATED → 仅比较首选（未验证），verdict 不是正式推荐
        DecisionPolicy.Output unvalidated = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result("C1", 0.95, 0.0, 0.0), "UNVALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.CANDIDATES_ONLY, unvalidated.verdict());
        assertTrue(unvalidated.reasonCodes().contains("UNCALIBRATED"));
        // 校准后同样分数 → 正式推荐
        DecisionPolicy.Output validated = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result("C1", 0.95, 0.0, 0.0), "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.RECOMMEND, validated.verdict());
        // 低分差/平分 → LOW_SEPARATION，不拿最小索引当默认胜者
        DecisionPolicy.Output tie = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result("C1", 0.5, 0.45, 0.0), "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.CANDIDATES_ONLY, tie.verdict());
        assertTrue(tie.reasonCodes().contains("LOW_SEPARATION"));
        // 缺口信号强 → NEED_MORE_EVIDENCE
        DecisionPolicy.Output gap = DecisionPolicy.resolve(
                input(set, "甲乙", "甲", result("C1", 0.95, 0.0, 0.8), "VALIDATED", List.of(), false));
        assertEquals(DecisionModels.Verdict.NEED_MORE_EVIDENCE, gap.verdict());
        // 页版本变化 → STALE；取消 → CANCELLED；未知别名 → UNAVAILABLE
        DecisionStateBuilder.BuiltState built = built(set, "甲乙");
        DecisionModels.DecisionSnapshot snapshot =
                DecisionStateBuilder.snapshot(ref(), set.candidateSetHash(), built);
        DecisionPolicy.CurrentView moved = new DecisionPolicy.CurrentView("book", "pdfhash", 3, 6,
                "b1", "i1", "otext", "basis", "span");
        DecisionPolicy.Output stale = DecisionPolicy.resolve(new DecisionPolicy.Input(snapshot, set,
                built.aliasToCandidateId(), "甲", result("C1", 0.95, 0.0, 0.0), null, false, moved,
                false, List.of(), false, "VALIDATED", DecisionPolicy.PILOT_DEFAULT));
        assertEquals(DecisionModels.Verdict.STALE, stale.verdict());
        DecisionPolicy.Output cancelled = DecisionPolicy.resolve(new DecisionPolicy.Input(snapshot, set,
                built.aliasToCandidateId(), "甲", result("C1", 0.95, 0.0, 0.0), null, true, view(),
                false, List.of(), false, "VALIDATED", DecisionPolicy.PILOT_DEFAULT));
        assertEquals(DecisionModels.Verdict.CANCELLED, cancelled.verdict());
        Map<String, Double> evil = Map.of("C9", 0.9, "C0", 0.05, "NONE_SUPPORTED", 0.03,
                "NEED_MORE_EVIDENCE", 0.02);
        JevDecisionClient.CallResult forged = new JevDecisionClient.CallResult(
                new DecisionModels.NormalizedChoice(DecisionStateBuilder.CHOICE_ID, "C9", evil, 0.9),
                new DecisionModels.NormalizedNoul(DecisionStateBuilder.GAP_ID, 0.1),
                Map.of(), Map.of(), "jev-synth-1", null, "h");
        DecisionPolicy.Output unknown = DecisionPolicy.resolve(new DecisionPolicy.Input(snapshot, set,
                built.aliasToCandidateId(), "甲", forged, null, false, view(), false, List.of(),
                false, "VALIDATED", DecisionPolicy.PILOT_DEFAULT));
        assertEquals(DecisionModels.Verdict.UNAVAILABLE, unknown.verdict());
        assertTrue(unknown.reasonCodes().contains("UNKNOWN_CANDIDATE"));
    }

    @Test void evidenceAndPolicyVersionedSeparately() {
        // 策略版本与证据版本分离：同一证据换阈值只重评策略，不改证据 hash
        assertEquals("decision-policy-v1", DecisionPolicy.POLICY_VERSION);
        assertEquals("pilot-default-v1", DecisionPolicy.THRESHOLD_PROFILE);
    }
}
