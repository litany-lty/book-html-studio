package studio.bookhtml.decision;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * J01：决策值对象与枚举。
 * 身份、候选、决策与确认四层分开；候选全集不在 ContentIssue 内；JEV 分数绝不写入 OCR confidence。
 */
public final class DecisionModels {
    private DecisionModels() {}

    public enum Verdict {
        RECOMMEND, CANDIDATES_ONLY, KEEP_CURRENT,
        NEED_MORE_EVIDENCE, NONE_SUPPORTED, HUMAN_REQUIRED,
        UNAVAILABLE, STALE, CANCELLED
    }

    public enum Applicability { CURRENT, STALE, POLICY_CHANGED, CANCELLED }

    public enum ExecutionStatus { SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, UNKNOWN }

    public enum SourceKind {
        NATIVE_TEXT, PRIMARY_OCR, CROP_OCR, VISION_TRANSCRIPTION,
        SEMANTIC_INFERENCE, LEGACY_INFERENCE, HUMAN_INPUT
    }

    public enum AlignmentStatus { EXACT, EXPANDED_SPAN, AMBIGUOUS, UNALIGNED }

    public enum LocatorMode { GLYPH, REGION, PAGE }

    public enum Origin { JEV_ASSISTED, MANUAL, LEGACY_UNKNOWN }

    public enum CostStatus { REPORTED, ESTIMATED, UNKNOWN }

    /** J01/6.1：不可变问题引用。客户端传入值只作前置比较，不作事实来源。 */
    public record IssueRef(
            String bookId, String pdfSha256, int sourcePageNumber, int pageRevision,
            String blockId, String issueId, String originalTextHash, String issueBasisHash,
            int startUtf16, int endUtf16, String sourceSpanHash, String mappingVersion) {
        public IssueRef {
            requireText("bookId", bookId);
            requireText("pdfSha256", pdfSha256);
            requireText("blockId", blockId);
            requireText("issueId", issueId);
            requireText("originalTextHash", originalTextHash);
            requireText("issueBasisHash", issueBasisHash);
            requireText("sourceSpanHash", sourceSpanHash);
            requireText("mappingVersion", mappingVersion);
            if (sourcePageNumber < 1) throw new IllegalArgumentException("sourcePageNumber 非法");
            if (pageRevision < 0) throw new IllegalArgumentException("pageRevision 非法");
            if (startUtf16 < 0 || endUtf16 <= startUtf16)
                throw new IllegalArgumentException("问题区间非法");
        }

        /** 针对已冻结原文校验区间与代理对边界；仅通过边界检查仍不足以证明身份正确。 */
        public static void checkSpan(String frozenOriginal, int start, int end) {
            if (frozenOriginal == null) throw new IllegalArgumentException("原文快照为空");
            if (start < 0 || end <= start || end > frozenOriginal.length())
                throw new IllegalArgumentException("区间越界");
            if (!isBoundary(frozenOriginal, start) || !isBoundary(frozenOriginal, end))
                throw new IllegalArgumentException("区间切开 UTF-16 代理对");
        }

        public static boolean isBoundary(String s, int offset) {
            if (s == null || offset < 0 || offset > s.length()) return false;
            return offset == 0 || offset == s.length()
                    || !(Character.isHighSurrogate(s.charAt(offset - 1))
                    && Character.isLowSurrogate(s.charAt(offset)));
        }
    }

    /** J01/6.3：单个候选。originalScriptText 为原书文字体系转录，可空但须说明原因。 */
    public record Candidate(
            String candidateId, String originalScriptText, String originalUnknownReason,
            String simplifiedDisplayText, String converterVersion, SourceKind sourceKind,
            String producer, String requestedModel, String reportedModel, String runId,
            String acquisitionGroup, List<String> upstreamEvidenceIds,
            String pdfSha256, int sourcePageNumber, String sourceSpanHash, String cropHash,
            LocatorMode locatorMode, double[] bbox, String transformVersion,
            AlignmentStatus alignmentStatus, List<String> evidenceRefs,
            Double rawConfidence, String normalizerVersion, Instant createdAt) {
        public Candidate {
            requireText("candidateId", candidateId);
            if (sourceKind == null) throw new IllegalArgumentException("sourceKind 为空");
            if (originalScriptText == null && (originalUnknownReason == null || originalUnknownReason.isBlank()))
                throw new IllegalArgumentException("原字未知须说明原因");
            if (rawConfidence != null && !(rawConfidence >= 0 && rawConfidence <= 1
                    && Double.isFinite(rawConfidence)))
                throw new IllegalArgumentException("rawConfidence 非法");
            if (bbox != null && bbox.length != 4) throw new IllegalArgumentException("bbox 非法");
            if (evidenceRefs == null) throw new IllegalArgumentException("evidenceRefs 为空");
            if (locatorMode == null) throw new IllegalArgumentException("locatorMode 为空");
            if (alignmentStatus == null) throw new IllegalArgumentException("alignmentStatus 为空");
            upstreamEvidenceIds = upstreamEvidenceIds == null ? List.of() : List.copyOf(upstreamEvidenceIds);
            evidenceRefs = List.copyOf(evidenceRefs);
            bbox = bbox == null ? null : bbox.clone();
        }

        /** 语义稳定投影：排除 runId/模型回执/时间等易变字段，语义去重与缓存键用它。 */
        public Map<String, Object> stableMap() {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("candidateId", candidateId);
            map.put("originalScriptText", originalScriptText);
            map.put("originalUnknownReason", originalUnknownReason);
            map.put("simplifiedDisplayText", simplifiedDisplayText);
            map.put("converterVersion", converterVersion);
            map.put("sourceKind", sourceKind);
            map.put("producer", producer);
            map.put("acquisitionGroup", acquisitionGroup);
            map.put("upstreamEvidenceIds", new ArrayList<>(upstreamEvidenceIds));
            map.put("pdfSha256", pdfSha256);
            map.put("sourcePageNumber", sourcePageNumber);
            map.put("sourceSpanHash", sourceSpanHash);
            map.put("cropHash", cropHash);
            map.put("locatorMode", locatorMode);
            map.put("bbox", bbox == null ? null : bbox.clone());
            map.put("transformVersion", transformVersion);
            map.put("alignmentStatus", alignmentStatus);
            map.put("evidenceRefs", new ArrayList<>(evidenceRefs));
            map.put("rawConfidence", rawConfidence);
            map.put("normalizerVersion", normalizerVersion);
            return map;
        }
    }

    /** J01/6.3：候选集合，最多六个实质候选；原始候选保留，去重只合并展示不丢来源。 */
    public record CandidateSet(
            String candidateSetHash, IssueRef issueRef, List<Candidate> candidates,
            String candidateConfigVersion, int rawCount, int dedupedCount,
            boolean truncated, List<String> truncationReasons,
            List<String> evidenceGaps, boolean hasPlaceholder, boolean allSemanticOnly,
            Instant createdAt) {
        public CandidateSet {
            if (issueRef == null) throw new IllegalArgumentException("issueRef 为空");
            if (candidates == null || candidates.isEmpty() || candidates.size() > 6)
                throw new IllegalArgumentException("候选数量须为 1..6");
            long distinct = candidates.stream().map(Candidate::candidateId).distinct().count();
            if (distinct != candidates.size()) throw new IllegalArgumentException("candidateId 重复");
            requireText("candidateSetHash", candidateSetHash);
            requireText("candidateConfigVersion", candidateConfigVersion);
            candidates = List.copyOf(candidates);
            truncationReasons = truncationReasons == null ? List.of() : List.copyOf(truncationReasons);
            evidenceGaps = evidenceGaps == null ? List.of() : List.copyOf(evidenceGaps);
        }

        public static String computeHash(IssueRef issueRef, List<Candidate> candidates,
                                         String candidateConfigVersion, int rawCount,
                                         boolean truncated, List<String> evidenceGaps) {
            List<Map<String, Object>> stable = new ArrayList<>();
            for (Candidate candidate : candidates) stable.add(candidate.stableMap());
            return DecisionHash.of(Map.of(
                    "issueRef", issueRef,
                    "candidates", stable,
                    "candidateConfigVersion", candidateConfigVersion,
                    "rawCount", rawCount,
                    "truncated", truncated,
                    "evidenceGaps", evidenceGaps == null ? List.of() : evidenceGaps));
        }
    }

    /** 类型化答案：Choice 比较信号；confidence 与 OCR confidence、人工状态各自保存。 */
    public record NormalizedChoice(String questionId, String selectedAlias,
                                   Map<String, Double> probabilities, Double confidence) {
        public NormalizedChoice {
            requireText("questionId", questionId);
            requireText("selectedAlias", selectedAlias);
            if (probabilities == null || probabilities.isEmpty())
                throw new IllegalArgumentException("probabilities 为空");
            for (Map.Entry<String, Double> e : probabilities.entrySet()) {
                Double p = e.getValue();
                if (p == null || Double.isNaN(p) || Double.isInfinite(p) || p < 0 || p > 1)
                    throw new IllegalArgumentException("非法概率值：" + e.getKey());
            }
            if (confidence != null && (Double.isNaN(confidence) || Double.isInfinite(confidence)
                    || confidence < 0 || confidence > 1))
                throw new IllegalArgumentException("confidence 非法");
            probabilities = Map.copyOf(probabilities);
        }
    }

    /** 类型化答案：Noul 无独立 confidence，缺失绝不能当 0。 */
    public record NormalizedNoul(String questionId, double pYes) {
        public NormalizedNoul {
            requireText("questionId", questionId);
            if (Double.isNaN(pYes) || Double.isInfinite(pYes) || pYes < 0 || pYes > 1)
                throw new IllegalArgumentException("noul 非法");
        }
    }

    /** J01/6.5：不可变判断结果。原始 evidence 不因新策略修改而覆写。 */
    public record DecisionEvidence(
            String schemaVersion, String decisionId, String logicalRequestId, String physicalAttemptId,
            String snapshotHash, String candidateSetHash, String questionTemplateVersion,
            String provider, String endpointIdentity, String requestedModel, String reportedModel,
            String providerContractVersion, String responseHash, String providerRequestId,
            ExecutionStatus executionStatus, NormalizedChoice choice, NormalizedNoul evidenceGap,
            String policyVersion, String thresholdProfileVersion,
            Verdict verdict, Applicability applicabilityAtWrite,
            List<String> reasonCodes, List<String> evidenceRefs,
            Map<String, Long> usageReported, Long estimatedCostMinor, long reservedCostMinor,
            CostStatus costStatus, String scoresJson,
            Instant createdAt, Instant sentAt, Instant completedAt,
            Instant deadlineAt) {
        public DecisionEvidence {
            requireText("schemaVersion", schemaVersion);
            requireText("decisionId", decisionId);
            requireText("logicalRequestId", logicalRequestId);
            requireText("physicalAttemptId", physicalAttemptId);
            requireText("snapshotHash", snapshotHash);
            requireText("candidateSetHash", candidateSetHash);
            requireText("questionTemplateVersion", questionTemplateVersion);
            requireText("provider", provider);
            requireText("endpointIdentity", endpointIdentity);
            requireText("providerContractVersion", providerContractVersion);
            requireText("responseHash", responseHash);
            requireText("policyVersion", policyVersion);
            requireText("thresholdProfileVersion", thresholdProfileVersion);
            if (executionStatus == null || verdict == null || applicabilityAtWrite == null || costStatus == null)
                throw new IllegalArgumentException("决策枚举为空");
            if (createdAt == null || deadlineAt == null) throw new IllegalArgumentException("时间为空");
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            evidenceRefs = evidenceRefs == null ? List.of() : List.copyOf(evidenceRefs);
            usageReported = usageReported == null ? null : Map.copyOf(usageReported);
        }
    }

    /** J01/6.6：人工确认元数据，随 Page 同次提交；sidecar 索引只是可重建派生。 */
    public record ReviewResolution(
            String resolutionId, String clientOperationId, Origin origin,
            String decisionId, String candidateId, String candidateSetHash,
            String basisPdfSha256, int basisPageRevision, String basisIssueHash,
            String originalReplacement, String simplifiedReplacement, String converterVersion,
            boolean userAttestedSourceCheck, Instant confirmedAt, int appliedRevision) {
        public ReviewResolution {
            requireText("resolutionId", resolutionId);
            requireText("clientOperationId", clientOperationId);
            if (origin == null) throw new IllegalArgumentException("origin 为空");
            requireText("basisPdfSha256", basisPdfSha256);
            requireText("basisIssueHash", basisIssueHash);
            if (confirmedAt == null) throw new IllegalArgumentException("confirmedAt 为空");
        }
    }

    /** 冻结快照：候选冻结后才生成 requestHash；页/PDF/范围变更即失效。 */
    public record DecisionSnapshot(
            String snapshotHash, IssueRef issueRef, String candidateSetHash,
            Map<String, Object> context, List<String> contextKeptRanges,
            List<String> contextDroppedRanges, String questionTemplateVersion, Instant createdAt) {
        public DecisionSnapshot {
            requireText("snapshotHash", snapshotHash);
            if (issueRef == null) throw new IllegalArgumentException("issueRef 为空");
            requireText("candidateSetHash", candidateSetHash);
            requireText("questionTemplateVersion", questionTemplateVersion);
            if (createdAt == null) throw new IllegalArgumentException("createdAt 为空");
            context = context == null ? Map.of() : DeepFreeze.copy(context);
            contextKeptRanges = contextKeptRanges == null ? List.of() : List.copyOf(contextKeptRanges);
            contextDroppedRanges = contextDroppedRanges == null ? List.of() : List.copyOf(contextDroppedRanges);
        }

        public static String computeHash(IssueRef issueRef, String candidateSetHash,
                                         Map<String, Object> context, String questionTemplateVersion) {
            return DecisionHash.of(Map.of(
                    "issueRef", issueRef,
                    "candidateSetHash", candidateSetHash,
                    "context", context == null ? Map.of() : context,
                    "questionTemplateVersion", questionTemplateVersion));
        }
    }

    static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 为空");
    }
}
