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

    /** 阅读依据：原文阅读 vs 辅助阅读。独立维度，不由繁简选项推导。 */
    public enum EvidenceMode { ORIGINAL, ASSISTED }

    private HeadingText() {}

    public static String pageTitle(Page page) {
        return pageTitle(page, EvidenceMode.ORIGINAL);
    }

    /** 原文模式目录与标题不偷偷补推测字；辅助模式见 displayAssisted。 */
    public static String pageTitle(Page page, EvidenceMode mode) {
        Block heading = page.blocks().stream()
                .filter(block -> "heading".equals(block.type()))
                .min(Comparator.comparingInt(Block::order))
                .orElse(null);
        if (heading != null) {
            String title = mode == EvidenceMode.ASSISTED
                    ? displayAssisted(heading, true).strip()
                    : display(heading, true).strip();
            if (!title.isEmpty()) return title;
        }
        return "第 " + page.pageNumber() + " 页";
    }

    /**
     * U3：原文阅读政策。未确认模型推测不替换原始转录（即使简体模式）；
     * 已确认替换照常显示；缺字保留原转录，不凭空补字。
     */
    public static String display(Block block, boolean simplified) {        return displayWith(block, simplified, EvidenceMode.ORIGINAL);
    }

    /**
     * U3：辅助阅读政策。允许显示受策略允许的候选（调用方负责未确认标记，
     * 不得冒充人工已校对）。旧偏好无明确证据时默认原文阅读。
     */
    public static String displayAssisted(Block block, boolean simplified) {
        return displayWith(block, simplified, EvidenceMode.ASSISTED);
    }

    private static String displayWith(Block block, boolean simplified, EvidenceMode mode) {
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
            } else if (mode == EvidenceMode.ASSISTED
                    && issue.inferredText() != null && !issue.inferredText().isBlank()) {
                output.append(issue.inferredText());
            } else {
                output.append(source);
            }
            cursor = positioned.end();
        }
        output.append(text, cursor, text.length());
        return output.toString();
    }

    /**
     * U3：已确认展示政策 —— 简体导航不得擅自纳入未确认推测（CONS-01）。
     * 与 {@link #display(Block, boolean)} 的唯一区别：未解决 issue 一律用原文
     * （不可读 kind 用占位符），永不使用 inferredText。旧调用保持原语义。
     */
    public static String displayConfirmed(Block block) {
        String text = block.original();
        if (text == null || text.isEmpty() || block.issues().isEmpty()) {
            return text == null ? "" : text;
        }
        List<PositionedIssue> issues = new ArrayList<>();
        for (ContentIssue issue : block.issues()) {
            if (issue == null) continue;
            int start = issue.start();
            int end = issue.end();
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
            String source = text.substring(positioned.start(), positioned.end());
            if (positioned.issue().resolved()) {
                output.append(positioned.issue().replacement() == null
                        ? source : positioned.issue().replacement());
            } else if ("unreadable".equals(positioned.issue().kind())) {
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
