package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import studio.bookhtml.service.TraditionalConverter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3/J11 SHADOW 执行器（真实付费调用）。三重门控，缺一即跳过：
 * SHADOW_LIVE=1、-Dshadow.dataset、TYPESAFE_API_KEY。
 * 逐案走真实生产链路（召回构建→状态构建→JEV 单次调用→确定性策略），无重试；
 * 单案失败记录后继续；费用按 pricingVersion 逐案核算。
 * 注意：calibrationStatus 用 VALIDATED 覆盖，只为测量条件选择能力；
 * 生产保持 UNVALIDATED，本覆盖不改变任何生产默认。
 */
class ShadowEvalRunner {
    private static final String MODEL = "jev-1.13.0";
    private static final double USD_PER_MTOK = 0.042;

    @Test void runShadowLive() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("SHADOW_LIVE")),
                "门控：默认不跑真实评测");
        String datasetPath = System.getProperty("shadow.dataset");
        String outPath = System.getProperty("shadow.out", "/tmp/shadow-live.jsonl");
        String apiKey = System.getenv("TYPESAFE_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(datasetPath != null && !datasetPath.isBlank()
                && apiKey != null && !apiKey.isBlank());
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        @SuppressWarnings("unchecked")
        Map<String, Object> dataset = json.readValue(Path.of(datasetPath).toFile(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");

        TraditionalConverter converter = new TraditionalConverter();
        CandidateResolutionService resolution = new CandidateResolutionService(converter);
        DecisionStateBuilder builder = new DecisionStateBuilder();
        List<String> lines = new ArrayList<>();
        long totalInputTokens = 0;
        int calls = 0, failures = 0;
        try (SharedTransport transport = new SharedTransport()) {
            JevDecisionClient jev = new JevDecisionClient(json, transport);
            for (Map<String, Object> kase : cases) {
                Map<String, Object> record = runOne(kase, resolution, builder, jev, json, apiKey);
                lines.add(json.writeValueAsString(record));
                if (record.get("usageInputTokens") instanceof Number n)
                    totalInputTokens += n.longValue();
                calls++;
                if (record.get("jevPick") == null && record.get("verdict") == null) failures++;
                else if ("CALL_FAILED".equals(record.get("verdict"))) failures++;
            }
        }
        Files.write(Path.of(outPath), String.join("\n", lines).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        double usd = totalInputTokens * USD_PER_MTOK / 1_000_000.0;
        System.out.println("SHADOW_LIVE calls=" + calls + " failures=" + failures
                + " inputTokens=" + totalInputTokens + " usd=" + String.format("%.6f", usd));
        assertTrue(calls > 0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runOne(Map<String, Object> kase,
                                       CandidateResolutionService resolution,
                                       DecisionStateBuilder builder,
                                       JevDecisionClient jev, ObjectMapper json,
                                       String apiKey) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("caseId", kase.get("caseId"));
        Instant start = Instant.now();
        try {
            String book = String.valueOf(kase.get("book"));
            int page = ((Number) kase.get("sourcePage")).intValue();
            List<Number> span = (List<Number>) kase.get("span");
            int startUtf16 = span.get(0).intValue(), endUtf16 = span.get(1).intValue();
            String current = String.valueOf(kase.getOrDefault("current", ""));
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) kase.get("candidates");
            DecisionModels.IssueRef ref = new DecisionModels.IssueRef(
                    book, String.valueOf(kase.getOrDefault("pdfSha256", "eval")),
                    page, 0, String.valueOf(kase.getOrDefault("blockId", "b")),
                    String.valueOf(kase.get("caseId")),
                    DecisionHash.sha256Hex(current), "eval-basis", startUtf16, endUtf16,
                    DecisionHash.sha256Hex(book + page + startUtf16 + endUtf16), "issue-basis-v1");
            List<CandidateResolutionService.RawCandidate> raws = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                String kind = String.valueOf(candidate.getOrDefault("sourceKind", "PRIMARY_OCR"));
                raws.add(new CandidateResolutionService.RawCandidate(
                        String.valueOf(candidate.getOrDefault("text", "")),
                        DecisionModels.SourceKind.valueOf(kind), "eval", null, null,
                        "run-eval", "G-eval", List.of(), null,
                        DecisionModels.LocatorMode.REGION, null, "eval-v1",
                        List.of("E-eval"), null, startUtf16, endUtf16));
            }
            // 冻结原文用 current 占位（span 恒为 [区间起点, 起点+current 长度] 的合法子串校验）
            String frozen = "X".repeat(startUtf16) + current;
            DecisionModels.CandidateSet set = resolution.buildSet(ref, frozen, current, raws);
            DecisionStateBuilder.BuiltState built = builder.build(ref, frozen, set,
                    List.of(), false, 32768);
            String physicalId = UUID.randomUUID().toString();
            JevDecisionClient.CallResult call = jev.callOnce(
                    DecisionCoordinator.ENDPOINT_URL, apiKey, MODEL,
                    new LinkedHashMap<>(built.state()), built.questions(),
                    Duration.ofSeconds(20).toNanos(), 32768, 65536, () -> false);
            DecisionPolicy.CurrentView view = new DecisionPolicy.CurrentView(
                    ref.bookId(), ref.pdfSha256(), ref.sourcePageNumber(), ref.pageRevision(),
                    ref.blockId(), ref.issueId(), ref.originalTextHash(), ref.issueBasisHash(),
                    ref.sourceSpanHash());
            DecisionPolicy.Output policy = DecisionPolicy.resolve(new DecisionPolicy.Input(
                    DecisionStateBuilder.snapshot(ref, set.candidateSetHash(), built), set,
                    built.aliasToCandidateId(), current, call, null, false, view, false,
                    built.hardRiskFlags(), false, "VALIDATED", DecisionPolicy.PILOT_DEFAULT));
            String recommended = policy.recommendedCandidateId();
            String pick = null;
            // 按原文回查 kN（顺序可能被优先级重排，不依赖别名索引）
            if (recommended != null) for (DecisionModels.Candidate c : set.candidates()) {
                if (!c.candidateId().equals(recommended)) continue;
                for (int i = 0; i < candidates.size(); i++) {
                    if (String.valueOf(candidates.get(i).getOrDefault("text", ""))
                            .equals(c.originalScriptText())) pick = "k" + i;
                }
            }
            record.put("jevPick", pick);
            record.put("verdict", policy.verdict().name());
            record.put("reasons", policy.reasonCodes());
            record.put("usageInputTokens", call.usage() == null ? null : call.usage().get("input_tokens"));
            record.put("reportedModel", call.reportedModel());
            record.put("physicalAttemptId", physicalId);
        } catch (Exception e) {
            record.put("verdict", "CALL_FAILED");
            record.put("error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()).substring(0, Math.min(120, String.valueOf(e.getMessage()).length())));
            record.put("jevPick", null);
        }
        record.put("seconds", Duration.between(start, Instant.now()).toMillis() / 1000.0);
        return record;
    }
}
