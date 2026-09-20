package studio.bookhtml.service;

import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds navigation-only text without mutating OCR text or review state. */
public final class HeadingText {
    public static final String UNREADABLE_PLACEHOLDER = "□";

    private HeadingText() {}

    public static String pageTitle(Page page) {
        Block heading = page.blocks().stream()
                .filter(block -> "heading".equals(block.type()))
                .min(Comparator.comparingInt(Block::order))
                .orElse(null);
        if (heading != null) {
            String title = display(heading, true).strip();
            if (!title.isEmpty()) return title;
        }
        return "第 " + page.pageNumber() + " 页";
    }

    public static String display(Block block, boolean simplified) {
        boolean useSimplified = simplified && block.simplified() != null && !block.simplified().isEmpty();
        String text = useSimplified ? block.simplified() : block.original();
        if (text == null || text.isEmpty() || block.issues().isEmpty()) return text == null ? "" : text;

        List<PositionedIssue> issues = new ArrayList<>();
        for (ContentIssue issue : block.issues()) {
            if (issue == null) continue;
            int start = useSimplified ? issue.simplifiedStart() : issue.start();
            int end = useSimplified ? issue.simplifiedEnd() : issue.end();
            if (start >= 0 && end > start && end <= text.length()) {
                issues.add(new PositionedIssue(issue, start, end));
            }
        }
        issues.sort(Comparator.comparingInt(PositionedIssue::start).thenComparingInt(PositionedIssue::end));

        StringBuilder output = new StringBuilder(text.length());
        int cursor = 0;
        for (PositionedIssue positioned : issues) {
            if (positioned.start() < cursor) continue;
            output.append(text, cursor, positioned.start());
            ContentIssue issue = positioned.issue();
            String source = text.substring(positioned.start(), positioned.end());
            if (issue.resolved()) {
                output.append(issue.replacement() == null ? source : issue.replacement());
            } else if (!simplified) {
                output.append(source);
            } else if (issue.inferredText() != null && !issue.inferredText().isBlank()) {
                output.append(issue.inferredText());
            } else if ("unreadable".equals(issue.kind())) {
                output.append(UNREADABLE_PLACEHOLDER);
            } else {
                output.append(source);
            }
            cursor = positioned.end();
        }
        output.append(text, cursor, text.length());
        return output.toString();
    }

    private record PositionedIssue(ContentIssue issue, int start, int end) {}
}
