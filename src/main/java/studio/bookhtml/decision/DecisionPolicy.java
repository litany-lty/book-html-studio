package studio.bookhtml.decision;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * J06：确定性消费策略，纯函数。判定顺序执行第 9 章伪代码；
 * 原始 evidence 与策略结果分别版本化；confidence 不写 OCR 字段。
 */
public final class DecisionPolicy {
    private DecisionPolicy() {}

    public static final String POLICY_VERSION = "decision-policy-v1";
    public static final String THRESHOLD_PROFILE = "pilot-default-v1";

    public record Thresholds(double minChoiceProb, double minMargin, double maxGapNoul) {}

    public static final Thresholds PILOT_DEFAULT = new Thresholds(0.90, 0.20, 0.20);

    private static final Set<DecisionModels.SourceKind> TRANSCRIPTION_KINDS = Set.of(
            DecisionModels.SourceKind.NATIVE_TEXT,
            DecisionModels.SourceKind.PRIMARY_OCR,
            DecisionModels.SourceKind.CROP_OCR,
            DecisionModels.SourceKind.VISION_TRANSCRIPTION);

    /** 当前页面视图（与快照比对，任意变化即 STALE）。 */
    public record CurrentView(String bookId, String pdfSha256, int sourcePageNumber, int pageRevision,
                              String blockId, String issueId, String originalTextHash,
                              String issueBasisHash, String sourceSpanHash) {}

    public record Input(DecisionModels.DecisionSnapshot snapshot, DecisionModels.CandidateSet set,
                        Map<String, String> aliasToCandidateId, String currentTranscription,
                        JevDecisionClient.CallResult result, String jevUnavailableReason,
                        boolean cancelled, CurrentView current, boolean sourceConflict,
                        List<String> hardRiskFlags,
                        boolean expandedSpanValidated, String calibrationStatus,
                        String calibrationProfile,
                        Thresholds thresholds) {
        public Input {
            hardRiskFlags = hardRiskFlags == null ? List.of() : List.copyOf(hardRiskFlags);
        }

        public Input(DecisionModels.DecisionSnapshot snapshot, DecisionModels.CandidateSet set,
                     Map<String, String> aliasToCandidateId, String currentTranscription,
                     JevDecisionClient.CallResult result, String jevUnavailableReason,
                     boolean cancelled, CurrentView current, boolean sourceConflict,
                     List<String> hardRiskFlags,
                     boolean expandedSpanValidated, String calibrationStatus,
                     Thresholds thresholds) {
            this(snapshot, set, aliasToCandidateId, currentTranscription, result,
                    jevUnavailableReason, cancelled, current, sourceConflict, hardRiskFlags,
                    expandedSpanValidated, calibrationStatus, null, thresholds);
        }
    }

    /**
     * JR-08/4.3：模型比较偏好、程序正式推荐、人工确认资格分开。
     * modelPreferredCandidateId 只是比较信号；admittedRecommendationId 仅在
     * RECOMMEND/KEEP_CURRENT 时为正式建议，其余一律 null，不得混用。
     */
    public record Output(DecisionModels.Verdict verdict, String modelPreferredCandidateId,
                         String admittedRecommendationId, List<String> reasonCodes,
                         DecisionModels.Applicability applicability) {
        public Output {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        /** 兼容旧调用：正式推荐位空。 */
        public Output(DecisionModels.Verdict verdict, String modelPreferredCandidateId,
                      List<String> reasonCodes, DecisionModels.Applicability applicability) {
            this(verdict, modelPreferredCandidateId, null, reasonCodes, applicability);
        }

        /** 兼容旧调用：返回正式推荐 ID（未放行则为 null）。 */
        public String recommendedCandidateId() {
            return admittedRecommendationId;
        }
    }

    public static Output resolve(Input input) {
        if (input.cancelled())
            return out(DecisionModels.Verdict.CANCELLED, null, List.of("CANCELLED_JOB"));
        if (!isCurrent(input))
            return out(DecisionModels.Verdict.STALE, null, List.of("STALE_SNAPSHOT"));
        if (input.jevUnavailableReason() != null)
            return out(DecisionModels.Verdict.UNAVAILABLE, null,
                    List.of("UNAVAILABLE_" + input.jevUnavailableReason()));
        if (input.result() == null)
            return out(DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNAVAILABLE_PROTOCOL_ERROR"));
        if (input.set() == null || input.set().candidates().isEmpty())
            return out(DecisionModels.Verdict.HUMAN_REQUIRED, null, List.of("NO_CANDIDATE"));
        DecisionModels.NormalizedChoice choice = input.result().choice();
        DecisionModels.NormalizedNoul gap = input.result().gap();
        if (choice == null)
            return out(DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNAVAILABLE_PROTOCOL_ERROR"));
        if ("NONE_SUPPORTED".equals(choice.selectedAlias()))
            return out(DecisionModels.Verdict.NONE_SUPPORTED, null, List.of("NONE_SUPPORTED"));
        if ("NEED_MORE_EVIDENCE".equals(choice.selectedAlias()))
            return out(DecisionModels.Verdict.NEED_MORE_EVIDENCE, null, List.of("NEED_MORE_EVIDENCE"));
        String candidateId = input.aliasToCandidateId().get(choice.selectedAlias());
        if (candidateId == null)
            return out(DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNKNOWN_CANDIDATE"));
        DecisionModels.Candidate selected = input.set().candidates().stream()
                .filter(c -> c.candidateId().equals(candidateId)).findFirst().orElse(null);
        if (selected == null)
            return out(DecisionModels.Verdict.UNAVAILABLE, null, List.of("UNKNOWN_CANDIDATE"));
        if (selected.alignmentStatus() != DecisionModels.AlignmentStatus.EXACT
                && !(selected.alignmentStatus() == DecisionModels.AlignmentStatus.EXPANDED_SPAN
                && input.expandedSpanValidated()))
            return out(DecisionModels.Verdict.NEED_MORE_EVIDENCE, candidateId,
                    List.of("LOCATION_AMBIGUOUS"));
        if (!TRANSCRIPTION_KINDS.contains(selected.sourceKind())
                || selected.originalScriptText() == null)
            return out(DecisionModels.Verdict.CANDIDATES_ONLY, candidateId, List.of("SEMANTIC_ONLY"));
        if (input.sourceConflict())
            return out(DecisionModels.Verdict.HUMAN_REQUIRED, candidateId, List.of("SOURCE_CONFLICT"));
        if (!input.hardRiskFlags().isEmpty())
            return out(DecisionModels.Verdict.HUMAN_REQUIRED, candidateId,
                    hardRiskCodes(input.hardRiskFlags()));
        // JR-08：风险比较原文与每个候选的变化；候选新引入否定/数值/单位即人工。
        // 人名/术语不可靠检测的不声称能识别，只处理可解释的字符级变化。
        List<String> deltaRisks = candidateDeltaRisks(
                input.currentTranscription(), selected.originalScriptText());
        if (!deltaRisks.isEmpty())
            return out(DecisionModels.Verdict.HUMAN_REQUIRED, candidateId,
                    hardRiskCodes(deltaRisks));
        boolean isCalibrated = "VALIDATED".equals(input.calibrationStatus())
                && input.calibrationProfile() != null
                && !input.calibrationProfile().isBlank();
        if (!isCalibrated)
            return out(DecisionModels.Verdict.CANDIDATES_ONLY, candidateId, List.of("UNCALIBRATED"));
        Thresholds thresholds = input.thresholds() == null ? PILOT_DEFAULT : input.thresholds();
        double top = choice.probabilities().getOrDefault(choice.selectedAlias(), 0.0);
        double second = choice.probabilities().entrySet().stream()
                .filter(e -> !e.getKey().equals(choice.selectedAlias()))
                .mapToDouble(Map.Entry::getValue).max().orElse(0.0);
        if (gap != null && gap.pYes() > thresholds.maxGapNoul())
            return out(DecisionModels.Verdict.NEED_MORE_EVIDENCE, candidateId, List.of("EVIDENCE_GAP"));
        if (top < thresholds.minChoiceProb() || top - second < thresholds.minMargin())
            return out(DecisionModels.Verdict.CANDIDATES_ONLY, candidateId, List.of("LOW_SEPARATION"));
        if (selected.originalScriptText() != null
                && selected.originalScriptText().equals(input.currentTranscription()))
            return out(DecisionModels.Verdict.KEEP_CURRENT, candidateId, List.of("KEEP_CURRENT"));
        return out(DecisionModels.Verdict.RECOMMEND, candidateId, List.of("RECOMMEND"));
    }

    private static List<String> hardRiskCodes(List<String> risks) {
        List<String> codes = new ArrayList<>();
        for (String risk : risks) codes.add("HARD_RISK_" + risk);
        return codes;
    }

    private static final Set<String> CJK_NUMERALS = Set.of(
            "〇", "零", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十",
            "百", "千", "萬", "万", "億", "亿", "兩", "两", "壹", "贰", "貳", "叁",
            "肆", "伍", "陆", "陸", "柒", "捌", "玖", "拾", "佰", "仟");
    private static final Set<String> UNITS = Set.of(
            "年", "月", "日", "時", "时", "分", "秒", "刻", "尺", "寸", "丈", "斤",
            "两", "兩", "钱", "錢", "升", "斗", "石", "里", "步", "亩", "畝", "页",
            "頁", "卷", "篇", "章", "节", "節");
    private static final Set<String> SYMBOLS = Set.of(
            "，", "。", "、", "；", "：", "？", "！", "—", "…", "（", "）", "《", "》",
            "“", "”", "‘", "’", ",", ".", ";", ":", "?", "!", "-", "(", ")", "[", "]");

    static List<String> candidateDeltaRisks(String current, String selected) {
        Set<String> flags = new java.util.TreeSet<>();
        String before = current == null ? "" : current;
        String after = selected == null ? "" : selected;
        if (!before.equals(after)) {
            if (containsNegation(before) != containsNegation(after)
                    || (containsNegation(before) && containsNegation(after))) {
                flags.add("NEGATION");
            }
            if (containsDigit(before) || containsDigit(after)
                    || containsAny(before, CJK_NUMERALS) || containsAny(after, CJK_NUMERALS)) {
                flags.add("NUMERIC");
            }
            if (containsAny(before, UNITS) || containsAny(after, UNITS)) {
                flags.add("UNIT");
            }
            if (containsAny(before, SYMBOLS) || containsAny(after, SYMBOLS)) {
                flags.add("SYMBOL");
            }
        }
        return List.copyOf(flags);
    }

    private static boolean containsNegation(String text) {
        for (String negation : DecisionStateBuilder.negations()) {
            if (text.contains(negation)) return true;
        }
        return text.contains("無") || text.contains("无") || text.contains("沒") || text.contains("没");
    }

    private static boolean containsDigit(String text) {
        return text.codePoints().anyMatch(Character::isDigit);
    }

    private static boolean containsAny(String text, Set<String> chars) {
        for (String c : chars) {
            if (text.contains(c)) return true;
        }
        return false;
    }

    private static Output out(DecisionModels.Verdict verdict, String candidateId, List<String> reasons) {
        // JR-08：STALE 与 CANCELLED 的 applicability 分别表达
        DecisionModels.Applicability applicability =
                verdict == DecisionModels.Verdict.STALE ? DecisionModels.Applicability.STALE
                        : verdict == DecisionModels.Verdict.CANCELLED
                        ? DecisionModels.Applicability.CANCELLED
                        : DecisionModels.Applicability.CURRENT;
        // JR-08：只有 RECOMMEND/KEEP_CURRENT 才是正式建议；其余 verdict 的候选 ID 只是模型偏好
        String admitted = (verdict == DecisionModels.Verdict.RECOMMEND
                || verdict == DecisionModels.Verdict.KEEP_CURRENT) ? candidateId : null;
        return new Output(verdict, candidateId, admitted, reasons, applicability);
    }

    private static boolean isCurrent(Input input) {
        if (input.current() == null || input.snapshot() == null) return false;
        DecisionModels.IssueRef ref = input.snapshot().issueRef();
        CurrentView current = input.current();
        return ref.bookId().equals(current.bookId())
                && ref.pdfSha256().equals(current.pdfSha256())
                && ref.sourcePageNumber() == current.sourcePageNumber()
                && ref.pageRevision() == current.pageRevision()
                && ref.blockId().equals(current.blockId())
                && ref.issueId().equals(current.issueId())
                && ref.originalTextHash().equals(current.originalTextHash())
                && ref.issueBasisHash().equals(current.issueBasisHash())
                && ref.sourceSpanHash().equals(current.sourceSpanHash());
    }
}
