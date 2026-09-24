package studio.bookhtml.service;

import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import java.util.*;

/** Read-only evidence projection. Confirmed edits are distinct from model suggestions. */
final class EvidenceTextResolver {
    record Text(String value, boolean hasCorrections, boolean hasUnresolved) {}
    private EvidenceTextResolver() {}
    static Optional<Text> resolve(Block block) {
        if (block == null || block.original() == null || block.original().length() > 256 * 1024)
            return Optional.empty();
        String original = block.original();
        List<ContentIssue> spans = block.issues().stream().filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ContentIssue::start).thenComparingInt(ContentIssue::end)).toList();
        int previous = 0;
        boolean edited = false, unresolved = false;
        StringBuilder text = new StringBuilder();
        for (ContentIssue issue : spans) {
            if (issue.start() < previous || issue.end() <= issue.start() || issue.end() > original.length()
                    || !ParagraphComprehensibilityService.boundary(original, issue.start())
                    || !ParagraphComprehensibilityService.boundary(original, issue.end())) return Optional.empty();
            text.append(original, previous, issue.start());
            if (!issue.resolved()) {
                text.append('□');
                unresolved = true;
            } else {
                String replacement = issue.resolution() != null
                        ? issue.resolution().originalReplacement() : issue.replacement();
                if (issue.resolution() != null && replacement == null && issue.replacement() != null)
                    return Optional.empty();
                if (replacement == null) text.append(original, issue.start(), issue.end());
                else {
                    if (replacement.length() > 8192 || !validUtf16(replacement)) return Optional.empty();
                    text.append(replacement);
                    edited = true;
                }
            }
            if (text.length() > 256 * 1024) return Optional.empty();
            previous = issue.end();
        }
        text.append(original, previous, original.length());
        return Optional.of(new Text(text.toString(), edited, unresolved));
    }
    private static boolean validUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
