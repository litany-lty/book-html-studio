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
                        Thresholds thresholds) {
        public Input {
            hardRiskFlags = hardRiskFlags == null ? List.of() : List.copyOf(hardRiskFlags);
        }
    }

    public record Output(DecisionModels.Verdict verdict, String recommendedCandidateId,
                         List<String> reasonCodes,
                         DecisionModels.Applicability applicability) {
        public Output {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
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
        if (!"VALIDATED".equals(input.calibrationStatus()))
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

    private static Output out(DecisionModels.Verdict verdict, String candidateId, List<String> reasons) {
        DecisionModels.Applicability applicability =
                verdict == DecisionModels.Verdict.STALE || verdict == DecisionModels.Verdict.CANCELLED
                        ? DecisionModels.Applicability.CANCELLED
                        : DecisionModels.Applicability.CURRENT;
        return new Output(verdict, candidateId, reasons, applicability);
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
