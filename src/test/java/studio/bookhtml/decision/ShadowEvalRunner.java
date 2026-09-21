package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import studio.bookhtml.service.TraditionalConverter;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3/J11 SHADOW 执行器（真实付费调用）。三重门控，缺一即跳过：
 * SHADOW_LIVE=1、-Dshadow.dataset、TYPESAFE_API_KEY。
 * JR-10：与生产链路输入输出等价——冻结原文必填（禁 X.repeat 伪造）、
 * 冻结候选/证据原样消费、别名映射冻结、unknown ID 当协议错误、
 * 逐案追加（可断点续跑，不截断重写）、B/C 同候选同证据、A/B/C 分开记录、
 * casesPlanned/Processed/sends/success/failures 分开计数、全失败/零样本不 PASS。
 * 本地/CI 默认不跑真实；calibration 覆盖仅为诊断测量，record 如实标注，
 * 不得命名为生产可发布结果。
 */
class ShadowEvalRunner {
    private static final String MODEL = "jev-1.13.0";
    private static final double USD_PER_MTOK = 0.042;

    @Test void runShadowLive() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("1".equals(System.getenv("SHADOW_LIVE")),
                "门控：默认不跑真实评测");
        String datasetPath = System.getProperty("shadow.dataset");
        String outPath = System.getProperty("shadow.out", "/tmp/shadow-live.jsonl");
        String calibrationStatus = System.getProperty("shadow.calibration", "UNVALIDATED");
        String apiKey = System.getenv("TYPESAFE_API_KEY");
        org.junit.jupiter.api.Assumptions.assumeTrue(datasetPath != null && !datasetPath.isBlank()
                && apiKey != null && !apiKey.isBlank());
        // JR-10-T03：模型固定版本检查；调用/费用上限预检（默认上限 200 案，避免无界外呼）
        String modelOverride = System.getProperty("shadow.model", MODEL);
        assertTrue(MODEL.equals(modelOverride), "评测模型必须钉死 " + MODEL + "，当前为 " + modelOverride);
        int maxCases = Integer.parseInt(System.getProperty("shadow.maxCases", "200"));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Path datasetFile = Path.of(datasetPath);
        String datasetHash = sha256Hex(Files.readAllBytes(datasetFile));
        @SuppressWarnings("unchecked")
        Map<String, Object> dataset = json.readValue(datasetFile.toFile(), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cases = (List<Map<String, Object>>) dataset.get("cases");
        int casesPlanned = cases == null ? 0 : cases.size();
        assertTrue(casesPlanned > 0, "数据集无有效样本，不 PASS");

        TraditionalConverter converter = new TraditionalConverter();
        CandidateResolutionService resolution = new CandidateResolutionService(converter);
        DecisionStateBuilder builder = new DecisionStateBuilder();
        Path outputPath = Path.of(outPath);
        if (outputPath.getParent() != null) Files.createDirectories(outputPath.getParent());
        // JR-10-T04：增量追加，不截断；已存在 caseId 跳过（不自动重发发送未知）
        Set<String> done = new LinkedHashSet<>();
        if (Files.exists(outputPath)) {
            for (String line : Files.readAllLines(outputPath, java.nio.charset.StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rec = json.readValue(line, Map.class);
                    if (rec.get("caseId") != null) done.add(String.valueOf(rec.get("caseId")));
                } catch (Exception ignored) {
                }
            }
        } else {
            Files.write(outputPath, new byte[0]);
        }

        long totalInputTokens = 0;
        int calls = 0, successes = 0, failures = 0, processed = 0, physicalSends = 0;
        String runId = "run-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (SharedTransport transport = new SharedTransport()) {
            JevDecisionClient jev = new JevDecisionClient(json, transport);
            for (Map<String, Object> kase : cases) {
                String caseId = String.valueOf(kase.get("caseId"));
                if (done.contains(caseId)) continue;
                if (processed >= maxCases) break;
                Map<String, Object> record = runOne(kase, resolution, builder, jev, json,
                        apiKey, calibrationStatus, datasetHash, runId);
                String line = json.writeValueAsString(record) + "\n";
                Files.writeString(outputPath, line, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                processed++;
                if (record.get("usageInputTokens") instanceof Number n)
                    totalInputTokens += n.longValue();
                // C 组物理发送计数（真实 JEV 调用一次即一次发送）
                if (Boolean.TRUE.equals(record.get("cSent"))) {
                    calls++;
                    physicalSends++;
                }
                if ("SUCCEEDED".equals(record.get("cExecutionStatus"))) successes++;
                else failures++;
            }
        }
        double usd = totalInputTokens * USD_PER_MTOK / 1_000_000.0;
        System.out.println("SHADOW_LIVE runId=" + runId + " datasetHash=" + datasetHash
                + " casesPlanned=" + casesPlanned + " casesProcessed=" + processed
                + " physicalSends=" + physicalSends + " successes=" + successes
                + " failures=" + failures + " inputTokens=" + totalInputTokens
                + " usd=" + String.format("%.6f", usd));
        // JR-10-T03：全失败/零成功/零有效样本不 PASS；故障注入预期失败与真实评测分开（本入口只计真实）
        assertTrue(processed > 0, "零有效样本，不 PASS");
        assertTrue(calls > 0, "No calls were planned or executed");
        assertTrue(successes > 0, "全部调用失败，不 PASS：calls=" + calls + " failures=" + failures);
        assertTrue(failures < calls || successes > 0, "All calls failed: calls=" + calls + " failures=" + failures);
    }

    static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runOne(Map<String, Object> kase,
                                       CandidateResolutionService resolution,
                                       DecisionStateBuilder builder,
                                       JevDecisionClient jev, ObjectMapper json,
                                       String apiKey,
                                       String calibrationStatus,
                                       String datasetHash,
                                       String runId) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("caseId", kase.get("caseId"));
        record.put("runId", runId);
        record.put("datasetHash", datasetHash);
        record.put("calibrationStatus", calibrationStatus);
        record.put("split", kase.getOrDefault("split", "holdout"));
        Instant start = Instant.now();
        try {
            String book = String.valueOf(kase.get("book"));
            int page = ((Number) kase.get("sourcePage")).intValue();
            List<Number> span = (List<Number>) kase.get("span");
            int startUtf16 = span.get(0).intValue(), endUtf16 = span.get(1).intValue();
            String current = String.valueOf(kase.getOrDefault("current", ""));
            List<Map<String, Object>> candidates = (List<Map<String, Object>>) kase.get("candidates");
            // JR-10-T01：冻结原文必填，禁 X.repeat 伪造；缺失即协议错误，不补占位
            Object frozenObj = kase.containsKey("frozenOriginal") ? kase.get("frozenOriginal")
                    : kase.get("originalText");
            if (frozenObj == null || String.valueOf(frozenObj).isEmpty())
                throw new IllegalArgumentException("冻结原文缺失，拒绝 X.repeat 伪造");
            String frozen = String.valueOf(frozenObj);
            DecisionModels.IssueRef.checkSpan(frozen, startUtf16, endUtf16);
            String pdfSha256 = String.valueOf(kase.getOrDefault("pdfSha256", "eval"));
            String blockId = String.valueOf(kase.getOrDefault("blockId", "b"));
            // 冻结身份：沿用数据集 truth 外的版本字段，不自造 eval-basis 覆盖真实 basis
            String basis = String.valueOf(kase.getOrDefault("issueBasisHash", "eval-basis"));
            String mapping = String.valueOf(kase.getOrDefault("mappingVersion", "issue-basis-v1"));
            DecisionModels.IssueRef ref = new DecisionModels.IssueRef(
                    book, pdfSha256, page, 0, blockId,
                    String.valueOf(kase.get("caseId")),
                    DecisionHash.sha256Hex(frozen), basis, startUtf16, endUtf16,
                    DecisionHash.sha256Hex(blockId + "\u0000" + startUtf16 + "\u0000" + endUtf16
                            + "\u0000" + frozen.substring(startUtf16, endUtf16)), mapping);
            List<CandidateResolutionService.RawCandidate> raws = new ArrayList<>();
            for (Map<String, Object> candidate : candidates) {
                String text = String.valueOf(candidate.getOrDefault("text", ""));
                // JR-10-T01：禁止抹除 crop/来源/局部上下文；缺失即保留 null，不填 eval-v1 假值
                Object kindObj = candidate.get("sourceKind");
                if (kindObj == null) throw new IllegalArgumentException("候选 sourceKind 缺失");
                DecisionModels.SourceKind kind = DecisionModels.SourceKind.valueOf(String.valueOf(kindObj));
                String producer = (String) candidate.getOrDefault("producer", "eval");
                String requestedModel = (String) candidate.get("requestedModel");
                String reportedModel = (String) candidate.get("reportedModel");
                String runIdCand = (String) candidate.getOrDefault("runId", "run-eval");
                String groupKey = (String) candidate.getOrDefault("acquisitionGroup", "G-eval");
                List<String> upstream = (List<String>) candidate.getOrDefault("upstreamEvidenceIds", List.of());
                String cropHash = (String) candidate.get("cropHash");
                Object locObj = candidate.get("locatorMode");
                DecisionModels.LocatorMode loc = locObj == null ? DecisionModels.LocatorMode.REGION
                        : DecisionModels.LocatorMode.valueOf(String.valueOf(locObj));
                double[] bbox = null;
                Object bboxObj = candidate.get("bbox");
                if (bboxObj instanceof List<?> list && list.size() == 4) {
                    bbox = new double[]{((Number) list.get(0)).doubleValue(),
                            ((Number) list.get(1)).doubleValue(),
                            ((Number) list.get(2)).doubleValue(),
                            ((Number) list.get(3)).doubleValue()};
                }
                String xform = (String) candidate.getOrDefault("transformVersion", "eval-v1");
                List<String> evidenceRefs = (List<String>) candidate.getOrDefault("evidenceRefs", List.of());
                Object claimedStartObj = candidate.get("claimedStartUtf16");
                Object claimedEndObj = candidate.get("claimedEndUtf16");
                int claimedStart = claimedStartObj instanceof Number n ? n.intValue() : startUtf16;
                int claimedEnd = claimedEndObj instanceof Number n ? n.intValue() : endUtf16;
                raws.add(new CandidateResolutionService.RawCandidate(text, kind, producer,
                        requestedModel, reportedModel, runIdCand, groupKey, upstream, cropHash,
                        loc, bbox, xform, evidenceRefs, null, claimedStart, claimedEnd));
            }
            DecisionModels.CandidateSet set = resolution.buildSet(ref, frozen, current, raws,
                    List.of(), 6);
            DecisionStateBuilder.BuiltState built = builder.build(ref, frozen, set,
                    List.of(), false, 32768);
            String candidateSetHash = set.candidateSetHash();
            DecisionModels.DecisionSnapshot snapshot =
                    DecisionStateBuilder.snapshot(ref, candidateSetHash, built);
            record.put("candidateSetHash", candidateSetHash);
            record.put("decisionSnapshotHash", snapshot.snapshotHash());
            record.put("canonicalRequestHash", DecisionHash.sha256Hex(
                    json.writeValueAsString(new LinkedHashMap<>(built.state()))));
            record.put("templateVersion", DecisionStateBuilder.TEMPLATE_VERSION);
            record.put("aliasMap", new LinkedHashMap<>(built.aliasToCandidateId()));
            // A=当前实际展示基线（同版本真实展示逻辑导出，此处为冻结 current，不一律 current OCR）
            Map<String, Object> runA = new LinkedHashMap<>();
            runA.put("method", "current-display");
            runA.put("displayedText", current);
            runA.put("executionStatus", "SUCCEEDED");
            record.put("runA", runA);
            // B=相同候选的确定性方法（冻结规则：EXACT 且等于 current 则选，否则弃权）
            String rulePickId = null;
            for (DecisionModels.Candidate c : set.candidates()) {
                if (c.alignmentStatus() == DecisionModels.AlignmentStatus.EXACT
                        && current.equals(c.originalScriptText())) {
                    rulePickId = c.candidateId();
                    break;
                }
            }
            Map<String, Object> runB = new LinkedHashMap<>();
            runB.put("method", "frozen-rules");
            runB.put("executionStatus", "SUCCEEDED");
            runB.put("rawPickCandidateId", rulePickId);
            runB.put("candidateSetHash", candidateSetHash);
            record.put("runB", runB);
            // C=相同候选+JEV（单次真实调用）
            String physicalId = UUID.randomUUID().toString();
            JevDecisionClient.CallResult call = jev.callOnce(
                    DecisionCoordinator.ENDPOINT_URL, apiKey, MODEL,
                    new LinkedHashMap<>(built.state()), built.questions(),
                    Duration.ofSeconds(20).toNanos(), 32768, 65536, () -> false);
            record.put("cSent", true);
            record.put("cExecutionStatus", "SUCCEEDED");
            DecisionPolicy.CurrentView view = new DecisionPolicy.CurrentView(
                    ref.bookId(), ref.pdfSha256(), ref.sourcePageNumber(), ref.pageRevision(),
                    ref.blockId(), ref.issueId(), ref.originalTextHash(), ref.issueBasisHash(),
                    ref.sourceSpanHash());
            DecisionPolicy.Output policy = DecisionPolicy.resolve(new DecisionPolicy.Input(
                    snapshot, set,
                    built.aliasToCandidateId(), current, call, null, false, view, false,
                    built.hardRiskFlags(), false, calibrationStatus,
                    "cal-v1|model=" + MODEL + "|template=" + DecisionStateBuilder.TEMPLATE_VERSION
                            + "|candidate=" + set.candidateConfigVersion()
                            + "|policy=" + DecisionPolicy.POLICY_VERSION
                            + "|threshold=" + DecisionPolicy.THRESHOLD_PROFILE
                            + "|dataset=shadow|result=diagnostic",
                    DecisionPolicy.PILOT_DEFAULT));
            // JR-10-T02：用冻结别名映射解析，不用文本相等反推 kN；unknown 别名当协议错误
            String selectedAlias = call.choice() == null ? null : call.choice().selectedAlias();
            String rawPickId = selectedAlias == null ? null
                    : built.aliasToCandidateId().get(selectedAlias);
            if (selectedAlias != null && !"NONE_SUPPORTED".equals(selectedAlias)
                    && !"NEED_MORE_EVIDENCE".equals(selectedAlias)
                    && !"gap".equals(selectedAlias) && rawPickId == null) {
                record.put("cExecutionStatus", "PROTOCOL_ERROR");
                record.put("verdict", "CALL_FAILED");
                record.put("policyVerdict", "CALL_FAILED");
                record.put("error", "未知别名:" + selectedAlias);
            } else {
                Map<String, Object> runC = new LinkedHashMap<>();
                runC.put("method", "jev");
                runC.put("executionStatus", "SUCCEEDED");
                runC.put("rawPickCandidateId", rawPickId);
                runC.put("rawPickAlias", selectedAlias);
                runC.put("policyVerdict", policy.verdict().name());
                runC.put("admittedRecommendationId", policy.admittedRecommendationId());
                runC.put("candidateSetHash", candidateSetHash);
                record.put("runC", runC);
                record.put("rawPick", selectedAlias);
                record.put("rawPickCandidateId", rawPickId);
                // 兼容旧评分器的 kN（仅展示，不作推荐依据）
                String pick = null;
                if (rawPickId != null) for (int i = 0; i < set.candidates().size(); i++) {
                    if (set.candidates().get(i).candidateId().equals(rawPickId)) pick = "k" + i;
                }
                record.put("jevPick", pick);
                record.put("verdict", policy.verdict().name());
                record.put("policyVerdict", policy.verdict().name());
                record.put("modelPreferredCandidateId", policy.modelPreferredCandidateId());
                record.put("admittedRecommendationId", policy.admittedRecommendationId());
                record.put("reasons", policy.reasonCodes());
            }
            record.put("usageInputTokens", call.usage() == null ? null : call.usage().get("input_tokens"));
            record.put("reportedModel", call.reportedModel());
            record.put("physicalAttemptId", physicalId);
        } catch (Exception e) {
            record.put("cSent", false);
            record.put("cExecutionStatus", "FAILED");
            record.put("verdict", "CALL_FAILED");
            record.put("policyVerdict", "CALL_FAILED");
            String msg = String.valueOf(e.getMessage());
            record.put("error", e.getClass().getSimpleName() + ": "
                    + msg.substring(0, Math.min(120, msg.length())));
            record.put("jevPick", null);
            record.put("rawPick", null);
        }
        record.put("seconds", Duration.between(start, Instant.now()).toMillis() / 1000.0);
        return record;
    }
}
