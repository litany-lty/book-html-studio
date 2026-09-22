package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OutlineServiceTest {
    @Test
    void keepsMixedPageBodyHeadingsAndOrdersMultipleHeadingsWithoutInventingPageEntries() {
        Block recoveredDirectory = heading("toc", 0, 2, "目录条目……一", "目录条目……一", "qwen-toc-recovery", List.of());
        Block bodyTitle = heading("body", 2, 1, "紫微斗數", "紫微斗数", "paddle", List.of());
        Block laterTitle = heading("later", 5, 3, "命例", "命例", "paddle", List.of());
        List<OutlineService.OutlineEntry> entries = OutlineService.fromPages(List.of(
                page(4, "READY", recoveredDirectory),
                page(5, "READY", laterTitle, bodyTitle),
                page(6, "READY"),
                page(7, "PENDING", heading("pending", 0, 2, "未处理", "未处理", "paddle", List.of()))));

        assertEquals(List.of("body", "later"), entries.stream().map(OutlineService.OutlineEntry::blockId).toList());
        assertEquals(List.of("紫微斗数", "命例"), entries.stream().map(OutlineService.OutlineEntry::title).toList());
        assertEquals(List.of(1, 3), entries.stream().map(OutlineService.OutlineEntry::level).toList());
        assertFalse(entries.stream().anyMatch(entry -> entry.pageNumber() == 6), "无标题页不得生成占位目录");
    }

    @Test
    void excludesOnlyExplicitContentsPatternsAndKeepsChapterTitlesIncludingRepeatedTitles() {
        List<OutlineService.OutlineEntry> entries = OutlineService.fromPages(List.of(page(2, "READY",
                heading("contents", 0, 2, "目錄", "目录", "paddle", List.of()),
                heading("contents-entry", 1, 2, "第一篇……一○四", "第一篇……一○四", "paddle", List.of()),
                heading("qwen-toc-legacy", 2, 2, "旧目录校对文字", "旧目录校对文字", "manual", List.of()),
                heading("chapter-a", 3, 2, "第二章 星曜", "第二章 星曜", "paddle", List.of()),
                heading("chapter-b", 4, 2, "第二章 星曜", "第二章 星曜", "manual", List.of()))));

        assertEquals(List.of("chapter-a", "chapter-b"), entries.stream().map(OutlineService.OutlineEntry::blockId).toList());
    }

    @Test
    void usesOriginalTextWhileKeepingSavedReplacementAndDroppingConfirmedEmptyHeading() {
        ContentIssue candidate = new ContentIssue("candidate", "suspected", 0, 2, 0, 2,
                "原图", false, null, "前言");
        ContentIssue resolved = new ContentIssue("resolved", "suspected", 0, 2, 0, 2,
                "原图", true, "序言", null);
        ContentIssue removed = new ContentIssue("removed", "suspected", 0, 2, 0, 2,
                "原图", true, "", null);
        List<OutlineService.OutlineEntry> entries = OutlineService.fromPages(List.of(page(6, "READY",
                heading("candidate", 0, 2, "咖啡", "咖啡", "paddle", List.of(candidate)),
                heading("resolved", 1, 2, "咖啡", "咖啡", "paddle", List.of(resolved)),
                heading("removed", 2, 2, "咖啡", "咖啡", "paddle", List.of(removed)))));

        // U3/CONS-01：原文模式目录不补未确认推测（“前言”为推测，不收录），已确认替换保留。
        assertEquals(List.of("咖啡", "序言"), entries.stream().map(OutlineService.OutlineEntry::title).toList());
    }

    private static Page page(int number, String status, Block... blocks) {
        return new Page(number, 600, 800, status, "test", List.of(blocks), List.of(), false, null);
    }

    private static Block heading(String id, int order, Integer level, String original, String simplified,
                                 String source, List<ContentIssue> issues) {
        return new Block(id, "heading", order, new double[]{.1,.1,.2,.2}, "horizontal-tb",
                original, simplified, .9, false, false, level, source, List.of(id), null, null, issues);
    }
}
