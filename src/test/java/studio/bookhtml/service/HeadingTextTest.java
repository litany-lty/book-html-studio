package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HeadingTextTest {
    @Test
    void originalPolicyNeverAcceptsInferenceEvenWhenSimplified() {
        ContentIssue inferred = issue("i1", "suspected", 1, 2, false, null, "候");
        ContentIssue unreadable = issue("i2", "unreadable", 2, 3, false, null, null);
        ContentIssue suspected = issue("i3", "suspected", 3, 4, false, null, null);
        ContentIssue removed = issue("i4", "suspected", 4, 5, true, "", null);
        Block block = block("甲乙丙丁戊", "甲乙丙丁戊", List.of(inferred, unreadable, suspected, removed));

        // U3/CONS-01：原文模式 + 简体也不接受未确认推测；缺字保留原转录。
        assertEquals("甲乙丙丁", HeadingText.display(block, true));
        assertEquals("甲乙丙丁", HeadingText.display(block, false));
        // 辅助模式才允许候选（调用方负责未确认标记）。
        assertEquals("甲候丙丁", HeadingText.displayAssisted(block, true));
        assertFalse(inferred.resolved());
        assertEquals("甲乙丙丁戊", block.original());
    }

    @Test
    void usesSimplifiedRangesAndSkipsInvalidOrOverlappingIssues() {
        ContentIssue first = new ContentIssue("first", "suspected", 0, 1, 1, 2, "", false, null, "候");
        ContentIssue overlap = new ContentIssue("overlap", "unreadable", 0, 2, 1, 3, "", false, null, null);
        ContentIssue invalid = new ContentIssue("invalid", "unreadable", 4, 8, 4, 8, "", false, null, null);
        Block block = block("甲乙", "A甲乙", List.of(overlap, invalid, first));

        assertEquals("A甲乙", HeadingText.display(block, true));
        assertEquals("A候乙", HeadingText.displayAssisted(block, true));
        assertEquals("甲乙", HeadingText.display(block, false));
    }

    @Test
    void pageTitleUsesOriginalByDefaultAndCandidateOnlyWhenAssisted() {
        ContentIssue preface = issue("preface", "suspected", 0, 4, false, null, "【前言】");
        Page inferred = page(6, block("【咖啡】", "【咖啡】", List.of(preface)));
        // U3：原文模式目录与标题不补推测字。
        assertEquals("【咖啡】", HeadingText.pageTitle(inferred));
        assertEquals("【前言】", HeadingText.pageTitle(inferred, HeadingText.EvidenceMode.ASSISTED));
        assertFalse(preface.resolved());

        ContentIssue resolved = issue("resolved", "suspected", 0, 4, true, "【前言】", null);
        assertEquals("【前言】", HeadingText.pageTitle(page(6, block("【咖啡】", "【咖啡】", List.of(resolved)))));
        assertEquals("第 9 页", HeadingText.pageTitle(new Page(9, 1, 1, "READY", "test", List.of(), List.of(), false, null)));
    }

    private static ContentIssue issue(String id, String kind, int start, int end, boolean resolved,
                                      String replacement, String inferredText) {
        return new ContentIssue(id, kind, start, end, start, end, "图像依据", resolved, replacement, inferredText);
    }

    private static Block block(String original, String simplified, List<ContentIssue> issues) {
        return new Block("heading", "heading", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                original, simplified, 0.8, true, false, 2, "test", List.of("source"), null, null, issues);
    }

    private static Page page(int number, Block heading) {
        return new Page(number, 1, 1, "READY", "test", List.of(heading), List.of(), false, null);
    }
}
