package studio.bookhtml.decision;

import java.util.ArrayList;
import java.util.List;

/**
 * J02/FIX-07：重复 quote 安全定位。
 * 顺序：稳定行/词 ID（调用方已解析时）→ occurrenceIndex 程序枚举校验 → 原文前后文/位置约束过滤
 * → 唯一且边界合法才绑定；仍歧义返回 LOCATION_AMBIGUOUS，只生成区域级复核提示，
 * 绝不绑定第一处，不悄悄删除。
 */
public final class QuoteLocator {
    private QuoteLocator() {}

    public sealed interface LocateResult permits Located, Ambiguous {}

    public record Located(int start, int end) implements LocateResult {}

    public record Ambiguous(String code) implements LocateResult {}

    public static final String EMPTY_QUOTE = "EMPTY_QUOTE";
    public static final String UNALIGNED = "UNALIGNED";
    public static final String BAD_OCCURRENCE = "BAD_OCCURRENCE";
    public static final String CONTEXT_MISMATCH = "CONTEXT_MISMATCH";
    public static final String LOCATION_AMBIGUOUS = "LOCATION_AMBIGUOUS";
    public static final String SPLIT_SURROGATE = "SPLIT_SURROGATE";

    public static LocateResult locate(String original, String quote, Integer occurrenceIndex,
                                      String contextBefore, String contextAfter) {
        if (original == null || quote == null || quote.isEmpty())
            return new Ambiguous(EMPTY_QUOTE);
        List<Integer> matches = new ArrayList<>();
        int from = 0;
        while (true) {
            int found = original.indexOf(quote, from);
            if (found < 0) break;
            matches.add(found);
            from = found + Math.max(1, quote.length());
        }
        if (matches.isEmpty()) return new Ambiguous(UNALIGNED);
        List<Integer> narrowed = matches;
        if (occurrenceIndex != null) {
            if (occurrenceIndex < 0 || occurrenceIndex >= matches.size())
                return new Ambiguous(BAD_OCCURRENCE);
            narrowed = List.of(matches.get(occurrenceIndex));
        }
        narrowed = filterByContext(original, quote, narrowed, contextBefore, contextAfter);
        if (narrowed.isEmpty()) return new Ambiguous(CONTEXT_MISMATCH);
        if (narrowed.size() > 1) return new Ambiguous(LOCATION_AMBIGUOUS);
        int start = narrowed.get(0);
        int end = start + quote.length();
        if (!DecisionModels.IssueRef.isBoundary(original, start)
                || !DecisionModels.IssueRef.isBoundary(original, end))
            return new Ambiguous(SPLIT_SURROGATE);
        return new Located(start, end);
    }

    private static List<Integer> filterByContext(String original, String quote, List<Integer> candidates,
                                                 String before, String after) {
        boolean hasBefore = before != null && !before.isEmpty();
        boolean hasAfter = after != null && !after.isEmpty();
        if (!hasBefore && !hasAfter) return candidates;
        List<Integer> kept = new ArrayList<>();
        for (int start : candidates) {
            int end = start + quote.length();
            if (hasBefore) {
                if (start < before.length()) continue;
                if (!original.substring(start - before.length(), start).equals(before)) continue;
            }
            if (hasAfter) {
                if (end + after.length() > original.length()) continue;
                if (!original.substring(end, end + after.length()).equals(after)) continue;
            }
            kept.add(start);
        }
        return kept;
    }
}
