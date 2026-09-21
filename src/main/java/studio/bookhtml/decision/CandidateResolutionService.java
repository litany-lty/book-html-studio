package studio.bookhtml.decision;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.TraditionalConverter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * J02/J03：疑点召回、候选对齐与规范化。
 * 不从 suggestion 人类说明字符串中用正则拆候选；不做 NFKC/异体归并/繁简统一消分歧；
 * 精确转录按原字符串保存。人工已确认目标默认不受自动召回影响。
 */
@Service
public class CandidateResolutionService {
    static final double LOW_CONFIDENCE_THRESHOLD = 0.5;
    static final String CANDIDATE_CONFIG_VERSION = "candidate-config-v3";
    static final String CONVERTER_VERSION = "opencc4j-ZhConverterUtil-v1";
    static final String NORMALIZER_VERSION = "exact-string-v1";

    private final TraditionalConverter converter;

    public CandidateResolutionService(TraditionalConverter converter) {
        this.converter = converter;
    }

    public enum RecallSignal {
        EXISTING_ISSUE, SUGGESTION, UNCERTAIN, LOW_CONFIDENCE,
        PLACEHOLDER, MISSING_SOURCE, USER_MARKED
    }

    public record SpanMark(String blockId, int startUtf16, int endUtf16) {}

    public record RecallTarget(Block block, ContentIssue issue, Set<RecallSignal> signals,
                               SpanMark userSpan) {}

    /** J02/7.1：统一召回入口。native/Paddle/Qwen/local 问题均可进入，不限通道。 */
    public List<RecallTarget> recall(Page page, List<SpanMark> userMarks, boolean includeResolved) {
        List<RecallTarget> targets = new ArrayList<>();
        if (page == null || page.blocks() == null) return targets;
        List<Block> ordered = page.blocks().stream()
                .filter(b -> b != null)
                .sorted(Comparator.comparingInt(Block::order)).toList();
        for (Block block : ordered) {
            Set<RecallSignal> blockSignals = blockSignals(block);
            List<ContentIssue> issues = block.issues() == null ? List.of() : block.issues();
            boolean hasUnresolved = false;
            boolean hasResolved = false;
            for (ContentIssue issue : issues) {
                if (issue == null) continue;
                if (issue.resolved()) hasResolved = true;
                else {
                    hasUnresolved = true;
                    Set<RecallSignal> signals = EnumSet.copyOf(blockSignals);
                    signals.add(RecallSignal.EXISTING_ISSUE);
                    targets.add(new RecallTarget(block, issue, Set.copyOf(signals), null));
                }
            }
            if (includeResolved) for (ContentIssue issue : issues) {
                if (issue == null || !issue.resolved()) continue;
                Set<RecallSignal> signals = EnumSet.copyOf(blockSignals);
                signals.add(RecallSignal.EXISTING_ISSUE);
                targets.add(new RecallTarget(block, issue, Set.copyOf(signals), null));
            }
            // 已确认目标默认不受自动复核影响：全块仅剩已确认问题时，连建议级目标也不生成
            if (!hasUnresolved && (hasResolved && !includeResolved)) continue;
            if (!hasUnresolved && !blockSignals.isEmpty() && !"figure".equals(block.type())
                    && !"table".equals(block.type()) && !"formula".equals(block.type())) {
                targets.add(new RecallTarget(block, null, Set.copyOf(blockSignals), null));
            }
        }
        if (userMarks != null) for (SpanMark mark : userMarks) {
            if (mark == null) continue;
            Block block = ordered.stream().filter(b -> b.id().equals(mark.blockId())).findFirst().orElse(null);
            if (block == null || block.original() == null) continue;
            try {
                DecisionModels.IssueRef.checkSpan(block.original(), mark.startUtf16(), mark.endUtf16());
            } catch (IllegalArgumentException e) {
                continue;
            }
            Set<RecallSignal> signals = EnumSet.copyOf(blockSignals(block));
            signals.add(RecallSignal.USER_MARKED);
            targets.add(new RecallTarget(block, null, Set.copyOf(signals), mark));
        }
        return List.copyOf(targets);
    }

    private static Set<RecallSignal> blockSignals(Block block) {
        Set<RecallSignal> signals = EnumSet.noneOf(RecallSignal.class);
        if (block.suggestion() != null && !block.suggestion().isBlank())
            signals.add(RecallSignal.SUGGESTION);
        if (block.uncertain()) signals.add(RecallSignal.UNCERTAIN);
        if (block.confidence() != null && block.confidence() < LOW_CONFIDENCE_THRESHOLD)
            signals.add(RecallSignal.LOW_CONFIDENCE);
        String original = block.original();
        if (("text".equals(block.type()) || "heading".equals(block.type()))
                && (original == null || original.strip().isEmpty() || original.contains("□")))
            signals.add(RecallSignal.PLACEHOLDER);
        if ((block.sourceIds() == null || block.sourceIds().isEmpty())
                && ("text".equals(block.type()) || "heading".equals(block.type())))
            signals.add(RecallSignal.MISSING_SOURCE);
        return signals;
    }

    /** 上游原始候选（调用方声明覆盖区间，服务侧程序复核，不轻信）。 */
    public record RawCandidate(
            String text, DecisionModels.SourceKind sourceKind, String producer,
            String requestedModel, String reportedModel, String runId,
            String acquisitionGroup, List<String> upstreamEvidenceIds,
            String cropHash, DecisionModels.LocatorMode locatorMode, double[] bbox,
            String transformVersion, List<String> evidenceRefs, Double rawConfidence,
            int claimedStartUtf16, int claimedEndUtf16) {
        public RawCandidate(String text, DecisionModels.SourceKind sourceKind, String producer,
                            String requestedModel, String reportedModel, String runId,
                            String acquisitionGroup, List<String> upstreamEvidenceIds,
                            String cropHash, DecisionModels.LocatorMode locatorMode, double[] bbox,
                            String transformVersion, List<String> evidenceRefs, Double rawConfidence) {
            this(text, sourceKind, producer, requestedModel, reportedModel, runId, acquisitionGroup,
                    upstreamEvidenceIds, cropHash, locatorMode, bbox, transformVersion, evidenceRefs,
                    rawConfidence, -1, -1);
        }
    }

    public record ExpandedSpan(int startUtf16, int endUtf16) {}

    public record AlignedCandidate(RawCandidate raw, DecisionModels.AlignmentStatus alignment,
                                   ExpandedSpan expandedSpan) {}

    /**
     * J02/7.5 + T34：区间对齐复核。对齐依据是候选声明覆盖区间与目标区间的关系，
     * 不是文本包含——同一区间的竞争读法文本必然不同。
     * 声明区间与目标一致 → EXACT；声明区间严格包含目标 → EXPANDED_SPAN 并给出新范围
     * （须建新任务/新基线，旧确认请求不得套用）；其余（交错、脱离、未知）一律排除并记缺口，
     * 整行结果不得参加单字选择。
     */
    public AlignedCandidate align(String frozenOriginal, int start, int end, RawCandidate raw) {
        DecisionModels.IssueRef.checkSpan(frozenOriginal, start, end);
        String text = raw == null ? null : raw.text();
        if (text == null || text.isEmpty()) return new AlignedCandidate(raw,
                DecisionModels.AlignmentStatus.UNALIGNED, null);
        int claimedStart = raw.claimedStartUtf16();
        int claimedEnd = raw.claimedEndUtf16();
        if (claimedStart < 0 || claimedEnd <= claimedStart) return new AlignedCandidate(raw,
                DecisionModels.AlignmentStatus.UNALIGNED, null);
        try {
            DecisionModels.IssueRef.checkSpan(frozenOriginal, claimedStart, claimedEnd);
        } catch (IllegalArgumentException e) {
            return new AlignedCandidate(raw, DecisionModels.AlignmentStatus.UNALIGNED, null);
        }
        if (claimedStart == start && claimedEnd == end) return new AlignedCandidate(raw,
                DecisionModels.AlignmentStatus.EXACT, null);
        if (claimedStart <= start && claimedEnd >= end)
            return new AlignedCandidate(raw, DecisionModels.AlignmentStatus.EXPANDED_SPAN,
                    new ExpandedSpan(claimedStart, claimedEnd));
        return new AlignedCandidate(raw, DecisionModels.AlignmentStatus.UNALIGNED, null);
    }

    /**
     * 整行/区域级输出的范围界定：只在原文恰好出现一次时给出声明区间，否则返回 null
     * （调用方不得回退为“取第一次出现”）。
     */
    public static ExpandedSpan locateScope(String frozenOriginal, String rawText) {
        if (frozenOriginal == null || rawText == null || rawText.isEmpty()) return null;
        int first = frozenOriginal.indexOf(rawText);
        if (first < 0 || frozenOriginal.indexOf(rawText, first + 1) >= 0) return null;
        int end = first + rawText.length();
        if (!DecisionModels.IssueRef.isBoundary(frozenOriginal, first)
                || !DecisionModels.IssueRef.isBoundary(frozenOriginal, end)) return null;
        return new ExpandedSpan(first, end);
    }

    /**
     * J02/6.3：候选规范化。当前原始转录（非占位符）必留；精确字符串去重并保留全部来源；
     * 按配置上限筛选并记录删选理由；未对齐排除并记缺口。
     */
    public DecisionModels.CandidateSet buildSet(DecisionModels.IssueRef ref, String frozenOriginal,
                                                String currentTranscription, List<RawCandidate> raws) {
        return buildSet(ref, frozenOriginal, currentTranscription, raws, List.of(), 6);
    }

    /** JR-07-T04/T05：调用方传入收集缺口（VISION_* 等）与配置上限；占位/空/截断行为与配置一致。 */
    public DecisionModels.CandidateSet buildSet(DecisionModels.IssueRef ref, String frozenOriginal,
                                                String currentTranscription, List<RawCandidate> raws,
                                                List<String> extraGaps, int maxCandidates) {
        DecisionModels.IssueRef.checkSpan(frozenOriginal, ref.startUtf16(), ref.endUtf16());
        List<String> gaps = new ArrayList<>();
        if (extraGaps != null) for (String g : extraGaps)
            if (g != null && !g.isBlank()
                    && (g.startsWith("VISION_") || g.startsWith("EVIDENCE_")
                        || g.startsWith("FRESH_VISION_") || g.startsWith("CANCELLED_")
                        || g.startsWith("PIXEL_") || g.startsWith("CURRENT_"))
                    && !gaps.contains(g)) gaps.add(g);
        List<AlignedCandidate> aligned = new ArrayList<>();
        int unaligned = 0;
        if (raws != null) for (RawCandidate raw : raws) {
            if (raw == null) continue;
            AlignedCandidate checked = align(frozenOriginal, ref.startUtf16(), ref.endUtf16(), raw);
            if (checked.alignment() == DecisionModels.AlignmentStatus.UNALIGNED) {
                unaligned++;
                continue;
            }
            aligned.add(checked);
        }
        if (unaligned > 0) gaps.add("UNALIGNED_EXCLUDED:" + unaligned);
        boolean currentBlank = currentTranscription == null || currentTranscription.isBlank();
        if (!currentBlank && aligned.stream().noneMatch(a -> isCurrent(a, currentTranscription)))
            gaps.add("CURRENT_NOT_IN_EVIDENCE");
        // 精确去重：同字符串合并展示，全部来源保留，不计重复支持
        Map<String, List<AlignedCandidate>> groups = new LinkedHashMap<>();
        for (AlignedCandidate a : aligned) {
            String key = a.raw().text() == null ? "\u0000null" : a.raw().text();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(a);
        }
        List<DecisionModels.Candidate> merged = new ArrayList<>();
        for (List<AlignedCandidate> group : groups.values()) merged.add(mergeGroup(ref, group));
        // 规则筛选：当前转录 > 人工 > 精确转录 > 扩展区间 > 语义推测/旧推测
        merged.sort(Comparator.comparingInt(a -> priority(a, currentTranscription)));
        List<String> truncationReasons = new ArrayList<>();
        List<DecisionModels.Candidate> kept = new ArrayList<>();
        DecisionModels.Candidate currentKept = null;
        for (DecisionModels.Candidate c : merged) {
            if (!currentBlank && c.originalScriptText() != null
                    && c.originalScriptText().equals(currentTranscription)) currentKept = c;
            else kept.add(c);
        }
        List<DecisionModels.Candidate> substantive = new ArrayList<>();
        if (currentKept != null) substantive.add(currentKept);
        substantive.addAll(kept);
        int limit = maxCandidates <= 0 ? 6 : maxCandidates;
        List<DecisionModels.Candidate> finalList;
        if (substantive.size() > limit) {
            finalList = new ArrayList<>(substantive.subList(0, limit));
            for (int i = limit; i < substantive.size(); i++) {
                DecisionModels.Candidate dropped = substantive.get(i);
                truncationReasons.add("OVER_LIMIT_DROPPED:" + dropped.candidateId() + ":"
                        + dropped.sourceKind());
            }
        } else {
            finalList = substantive;
        }
        boolean allSemantic = !finalList.isEmpty() && finalList.stream().allMatch(c ->
                c.sourceKind() == DecisionModels.SourceKind.SEMANTIC_INFERENCE
                        || c.sourceKind() == DecisionModels.SourceKind.LEGACY_INFERENCE
                        || c.originalScriptText() == null);
        int rawCount = raws == null ? 0 : raws.size();
        if (finalList.isEmpty())
            throw new IllegalArgumentException("NO_USABLE_CANDIDATE：无可用实质候选");
        // JR-07-T05：占位符/空转录明确标记，不假装正常候选
        boolean hasPlaceholder = currentBlank
                || (currentTranscription != null && (currentTranscription.contains("□")
                        || currentTranscription.contains("�") || currentTranscription.isBlank()));
        String hash = DecisionModels.CandidateSet.computeHash(ref, finalList,
                CANDIDATE_CONFIG_VERSION, rawCount, !truncationReasons.isEmpty(), gaps,
                truncationReasons);
        return new DecisionModels.CandidateSet(hash, ref, finalList, CANDIDATE_CONFIG_VERSION,
                rawCount, finalList.size(), !truncationReasons.isEmpty(), truncationReasons,
                gaps, hasPlaceholder, allSemantic, Instant.now());
    }

    private static boolean isCurrent(AlignedCandidate a, String current) {
        return a.alignment() == DecisionModels.AlignmentStatus.EXACT
                && current.equals(a.raw().text());
    }

    private static int priority(DecisionModels.Candidate c, String current) {
        if (current != null && current.equals(c.originalScriptText())) return 0;
        return switch (c.sourceKind()) {
            case HUMAN_INPUT -> 1;
            case NATIVE_TEXT, PRIMARY_OCR, CROP_OCR, VISION_TRANSCRIPTION -> 2;
            case LEGACY_INFERENCE -> 4;
            case SEMANTIC_INFERENCE -> 5;
        };
    }

    private DecisionModels.Candidate mergeGroup(DecisionModels.IssueRef ref, List<AlignedCandidate> group) {
        AlignedCandidate first = group.get(0);
        RawCandidate raw = first.raw();
        List<String> evidence = new ArrayList<>();
        List<String> upstream = new ArrayList<>();
        // JR-07-T03：同字不同来源去重时保留全部 acquisition 可追溯信息；
        // 结构化字段取首条，同源重试不增加独立支持数（精确文本去重已合并），
        // 其余来源的 producer/模型/run/组/crop/变换/bbox 编码进 evidenceRefs 保留。
        for (AlignedCandidate a : group) {
            if (a.raw().evidenceRefs() != null) for (String e : a.raw().evidenceRefs())
                if (e != null && !evidence.contains(e)) evidence.add(e);
            if (a.raw().upstreamEvidenceIds() != null) for (String u : a.raw().upstreamEvidenceIds())
                if (u != null && !upstream.contains(u)) upstream.add(u);
            String descriptor = "acq:producer=" + a.raw().producer()
                    + "|reqModel=" + a.raw().requestedModel()
                    + "|repModel=" + a.raw().reportedModel()
                    + "|run=" + a.raw().runId()
                    + "|group=" + a.raw().acquisitionGroup()
                    + "|crop=" + a.raw().cropHash()
                    + "|loc=" + a.raw().locatorMode()
                    + "|xform=" + a.raw().transformVersion();
            if (!evidence.contains(descriptor)) evidence.add(descriptor);
        }
        String simplified = raw.text() == null ? null : converter.toSimplified(raw.text());
        return new DecisionModels.Candidate(
                "cand-" + DecisionHash.of(Map.of("span", ref.sourceSpanHash(), "text",
                        raw.text() == null ? "" : raw.text(), "kind", raw.sourceKind().name()))
                        .substring(0, 12),
                raw.text(), null, simplified, CONVERTER_VERSION, raw.sourceKind(),
                raw.producer(), raw.requestedModel(), raw.reportedModel(), raw.runId(),
                raw.acquisitionGroup(), upstream, ref.pdfSha256(), ref.sourcePageNumber(),
                ref.sourceSpanHash(), raw.cropHash(), raw.locatorMode(),
                raw.bbox() == null ? null : raw.bbox().clone(), raw.transformVersion(),
                first.alignment(), evidence, raw.rawConfidence(), NORMALIZER_VERSION, Instant.now(),
                raw.text(), DecisionModels.Candidate.LAYER_ORIGINAL, true);
    }

    /**
     * J02/6.4 + JR-07：旧 inferredText 兼容导入。保留现有显示值并作为比较文字，
     * 文字层级明确标假设；不反向转繁体，originalScriptText 保持 unknown。
     */
    public DecisionModels.Candidate legacyCandidate(DecisionModels.IssueRef ref, String legacyDisplayText,
                                                    String candidateId) {
        if (legacyDisplayText == null || legacyDisplayText.isBlank())
            throw new IllegalArgumentException("旧推测显示值为空");
        return new DecisionModels.Candidate(candidateId, null, "LEGACY_UNKNOWN_ORIGINAL",
                legacyDisplayText, "legacy-unknown", DecisionModels.SourceKind.LEGACY_INFERENCE,
                "legacy", null, null, null, "G-legacy", List.of(),
                ref.pdfSha256(), ref.sourcePageNumber(), ref.sourceSpanHash(), null,
                DecisionModels.LocatorMode.REGION, null, "legacy-unknown",
                DecisionModels.AlignmentStatus.AMBIGUOUS, List.of(), null,
                NORMALIZER_VERSION, Instant.now(),
                legacyDisplayText, DecisionModels.Candidate.LAYER_LEGACY_HYPOTHESIS, false);
    }

    public DecisionModels.CandidateSet mergeLegacy(DecisionModels.CandidateSet set,
                                                   DecisionModels.Candidate legacy) {
        return mergeLegacy(set, legacy == null ? List.of() : List.of(legacy));
    }

    public DecisionModels.CandidateSet mergeLegacy(DecisionModels.CandidateSet set,
                                                   List<DecisionModels.Candidate> legacy) {
        if (legacy == null || legacy.isEmpty()) return set;
        List<DecisionModels.Candidate> merged = new ArrayList<>(set.candidates());
        List<String> truncation = new ArrayList<>(set.truncationReasons());
        List<String> gaps = new ArrayList<>(set.evidenceGaps());
        for (DecisionModels.Candidate candidate : legacy) {
            if (merged.size() >= 6) {
                gaps.add("LEGACY_DEFERRED:" + candidate.candidateId());
                continue;
            }
            if (merged.stream().anyMatch(c -> c.candidateId().equals(candidate.candidateId()))) continue;
            merged.add(candidate);
        }
        if (merged.size() == set.candidates().size() && gaps.size() == set.evidenceGaps().size())
            return set;
        String hash = DecisionModels.CandidateSet.computeHash(set.issueRef(), merged,
                CANDIDATE_CONFIG_VERSION, set.rawCount(),
                set.truncated() || merged.size() != set.candidates().size(), gaps);
        return new DecisionModels.CandidateSet(hash, set.issueRef(), merged,
                CANDIDATE_CONFIG_VERSION, set.rawCount(), merged.size(),
                set.truncated(), truncation, gaps, set.hasPlaceholder(), set.allSemanticOnly(),
                Instant.now());
    }
}
