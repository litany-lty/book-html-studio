package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.PresentationOverrideRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * U3：书籍级结构投影判定。隔离合成夹具（TempDir），不碰日常数据目录，
 * 不调用模型；真实性能与准确性标为未验证。
 */
class BookPresentationServiceTest {
    @TempDir Path data;
    private BookStore store;
    private BookPresentationService presentation;
    private PresentationOverrideService overrides;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach void close() {
        if (store != null) store.close();
    }

    private String seedBook(String title, String filename, int totalPages) throws Exception {
        store = new BookStore(TestConfigs.config(data, "", ""), json);
        overrides = new PresentationOverrideService(store);
        presentation = new BookPresentationService(store);
        presentation.setOverrides(overrides);
        String id = UUID.randomUUID().toString();
        store.createBookDirectory(id);
        store.writeBook(new Book(id, title, filename, totalPages, Instant.now(), Instant.now(), 0, 0));
        for (int n = 1; n <= totalPages; n++) store.writePage(id, Page.pending(n, 600, 800), false);
        return id;
    }

    private static Block heading(String id, int order, String text, double y, Integer level, String source) {
        return new Block(id, "heading", order, new double[]{0.1, y, 0.8, 0.04}, "horizontal-tb",
                text, text, 0.9, false, false, level, source, List.of(id), null,
                new double[]{10, 20, 50, 10}, List.of());
    }

    private static Block edgeHeading(String id, int order, String text) {
        return heading(id, order, text, 0.02, 2, "paddle");
    }

    private static Block body(String id, int order, String text, double y) {
        return new Block(id, "text", order, new double[]{0.1, y, 0.8, 0.2}, "horizontal-tb",
                text, text, 0.9, false, false, null, "paddle", List.of(id), null,
                new double[]{10, 20, 50, 10}, List.of());
    }

    private static Page ready(int n, Block... blocks) {
        List<Block> all = List.of(blocks);
        return new Page(n, 600, 800, "READY", "paddle-aistudio", all, List.of(), false, null, all);
    }

    private void writeReady(String bookId, Page page) throws Exception {
        store.writePage(bookId, page, false);
    }

    private static List<String> titles(List<OutlineService.OutlineEntry> entries) {
        return entries.stream().map(OutlineService.OutlineEntry::title).toList();
    }

    /** 10 页相同页顶书名 + 2 个真正章标题（U0-F04 同构，书名任一页不挤满目录）。 */
    @Test void title01_repeatedRunningHeaderExcludedWhileTrueChaptersKept() throws Exception {
        String bookId = seedBook("紙頁工坊", "紙頁工坊.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            List<Block> blocks = new ArrayList<>();
            blocks.add(edgeHeading("header-" + n, 0, "紙頁工坊"));
            if (n == 3) {
                blocks.add(heading("chapter-3", 1, "第一章 星曜", 0.4, 1, "paddle"));
                blocks.add(body("body-3", 2, "星曜正文", 0.6));
            }
            if (n == 7) {
                blocks.add(heading("chapter-7", 1, "第二章 宮垣", 0.4, 1, "paddle"));
                blocks.add(body("body-7", 2, "宮垣正文", 0.6));
            }
            writeReady(bookId, ready(n, blocks.toArray(new Block[0])));
        }
        List<OutlineService.OutlineEntry> entries = presentation.outline(bookId);
        List<String> names = titles(entries);
        assertFalse(names.stream().anyMatch(t -> t.contains("紙頁工坊")), "重复书名不得进入目录");
        assertTrue(names.contains("第一章 星曜") && names.contains("第二章 宮垣"), "真正章标题保留");
        // 阅读投影：后续页书眉收起，原文与来源 hash 不变。
        PagePresentation p5 = presentation.project(bookId, store.readPage(bookId, 5));
        PagePresentation.BlockPresentation header = p5.byBlockId().get("header-5");
        assertEquals(PagePresentation.ROLE_RUNNING_HEADER, header.role());
        assertFalse(header.showInReading());
        assertFalse(header.includeInOutline());
    }

    @Test void title02_oddEvenHeadersClusteredSeparately() throws Exception {
        String bookId = seedBook("合集", "合集.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            if (n == 3) continue;
            String header = n % 2 == 1 ? "書名甲" : "第二章";
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, header), body("b-" + n, 1, "正文" + n, 0.5)));
        }
        // 偶页书眉“第二章”形成独立簇；章节真正起始（内部位置）保留。
        writeReady(bookId, ready(3, edgeHeading("h-3", 0, "書名甲"),
                heading("real", 1, "第二章", 0.4, 1, "paddle"), body("b-3", 2, "正文", 0.6)));
        List<String> names = titles(presentation.outline(bookId));
        assertTrue(names.contains("第二章"), "章节真正起始位置保留");
        assertFalse(names.contains("書名甲"), "奇页持续书眉不进入目录");
    }

    @Test void title03_coverTitleExcludedWhileSameTextChapterKept() throws Exception {
        String bookId = seedBook("星雲集", "星雲集.pdf", 10);
        writeReady(bookId, ready(1, edgeHeading("cover", 0, "星雲集")));
        for (int n = 2; n <= 10; n++) {
            if (n == 4) continue;
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "星雲集"), body("b-" + n, 1, "正文" + n, 0.5)));
        }
        writeReady(bookId, ready(4, edgeHeading("h-4", 0, "星雲集"),
                heading("ch", 1, "星雲集", 0.4, 1, "paddle"), body("b-4", 2, "卷首语", 0.6)));
        List<String> names = titles(presentation.outline(bookId));
        assertTrue(names.contains("星雲集"), "后文同名真实章节不被字符串黑名单删除");
        // 封面不成为章节：条目来自第 4 页章节位置，而非第 1 页封面。
        assertTrue(presentation.outline(bookId).stream()
                .filter(e -> e.title().contains("星雲集")).allMatch(e -> e.pageNumber() == 4));
    }

    @Test void title04_filenameNeitherDeletesNorManufacturesTitles() throws Exception {
        String bookId = seedBook("真書名", "random-scan-001.pdf", 6);
        for (int n = 1; n <= 6; n++) {
            writeReady(bookId, ready(n, heading("ch-" + n, 0, "第一章 开篇", 0.3, 1, "paddle"),
                    body("b-" + n, 1, "正文", 0.5)));
        }
        List<String> names = titles(presentation.outline(bookId));
        assertEquals(6, names.size(), "文件名不制造也不删除章标题");
    }

    @Test void title05_singleObservedPageNeverAutoHides() throws Exception {
        String bookId = seedBook("孤本", "孤本.pdf", 6);
        writeReady(bookId, ready(1, edgeHeading("h-1", 0, "疑似书眉"), body("b-1", 1, "正文", 0.5)));
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 1));
        PagePresentation.BlockPresentation header = view.byBlockId().get("h-1");
        assertTrue(header.showInReading(), "跨页证据不足不隐藏正文");
        assertEquals(PagePresentation.EVIDENCE_INSUFFICIENT, header.evidenceLevel());
    }

    @Test void title06_repeatedBodyTitlesKept() throws Exception {
        String bookId = seedBook("诗集", "诗集.pdf", 8);
        for (int n = 1; n <= 8; n++) {
            writeReady(bookId, ready(n, heading("t-" + n, 0, "静夜思", 0.4, 2, "paddle"),
                    body("b-" + n, 1, "床前明月光", 0.6)));
        }
        assertEquals(8, presentation.outline(bookId).size(), "正文区域重复不认定为书眉");
    }

    @Test void title07_marginalNumbersAndFootnotesKept() throws Exception {
        String bookId = seedBook("算书", "算书.pdf", 6);
        writeReady(bookId, ready(1,
                heading("ch", 0, "第一章", 0.3, 1, "paddle"),
                body("formula-no", 1, "（1）", 0.85),
                body("note", 2, "① 注释", 0.7)));
        String before = hashBlocks(store, bookId, 1);
        // 非标题块本就不进入目录；投影保留且原文 hash 不变。
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 1));
        assertTrue(view.byBlockId().get("formula-no").showInReading());
        assertTrue(view.byBlockId().get("note").showInReading());
        presentation.outline(bookId);
        assertEquals(before, hashBlocks(store, bookId, 1), "结构识别不改原始内容");
        assertEquals(List.of("第一章"), titles(presentation.outline(bookId)));
    }

    @Test void title08_manualHeadingNeverHiddenByCluster() throws Exception {
        String bookId = seedBook("紙頁工坊", "紙頁工坊.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            Block header = edgeHeading("header-" + n, 0, "紙頁工坊");
            if (n == 5) {
                header = new Block("header-5", "heading", 0, new double[]{0.1, 0.02, 0.8, 0.04},
                        "horizontal-tb", "紙頁工坊", "紙頁工坊", 0.9, false, true, 2, "paddle",
                        List.of("header-5"), null, new double[]{10, 20, 50, 10}, List.of());
            }
            writeReady(bookId, ready(n, header, body("b-" + n, 1, "正文", 0.5)));
        }
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 5));
        PagePresentation.BlockPresentation kept = view.byBlockId().get("header-5");
        assertTrue(kept.showInReading(), "人工保留块前后端与离线均保留");
        assertTrue(kept.includeInOutline());
    }

    @Test void title09_supplierSourceNeverExcludesRealChapter() throws Exception {
        String bookId = seedBook("实录", "实录.pdf", 6);
        for (int n = 1; n <= 6; n++) {
            writeReady(bookId, ready(n, heading("ch-" + n, 0, "第" + n + "章", 0.35, 1, "paddle-span-7"),
                    body("b-" + n, 1, "正文", 0.55)));
        }
        assertEquals(6, presentation.outline(bookId).size(), "来源前缀不单独导致排除");
    }

    @Test void title10_printedTocKeptAsContentButNotChapters() throws Exception {
        String bookId = seedBook("目录书", "目录书.pdf", 6);
        Block tocTitle = heading("toc", 0, "目錄", 0.2, 1, "paddle");
        Block entry = heading("e1", 1, "第一章 星曜……一", 0.3, 2, "paddle");
        writeReady(bookId, ready(2, tocTitle, entry));
        for (int n : new int[]{1, 3, 4, 5, 6}) {
            writeReady(bookId, ready(n, body("b-" + n, 0, "正文" + n, 0.4)));
        }
        assertTrue(presentation.outline(bookId).isEmpty(), "目录页条目不直接变成正文章节");
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 2));
        assertTrue(view.byBlockId().get("toc").showInReading(), "目录页内容正常展示");
        assertTrue(view.byBlockId().get("e1").showInReading());
    }

    @Test void title11_twoHeadingsSamePageLocateDistinctBlocks() throws Exception {
        String bookId = seedBook("双标题", "双标题.pdf", 6);
        for (int n = 1; n <= 6; n++) {
            writeReady(bookId, ready(n, heading("a-" + n, 0, "上篇", 0.3, 1, "paddle"),
                    heading("b-" + n, 1, "第一节", 0.45, 2, "paddle"), body("c-" + n, 2, "正文", 0.6)));
        }
        List<OutlineService.OutlineEntry> entries = presentation.outline(bookId);
        long page1 = entries.stream().filter(e -> e.pageNumber() == 1).count();
        assertEquals(2, page1, "同一页两条目录定位到不同 blockId");
        assertEquals(2, entries.stream().filter(e -> e.pageNumber() == 1)
                .map(OutlineService.OutlineEntry::blockId).distinct().count());
    }

    @Test void title12_profileRevisionBumpRemovesStaleHeaderEntries() throws Exception {
        String bookId = seedBook("增补书", "增补书.pdf", 10);
        for (int n = 1; n <= 4; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "增补書眉"), body("b-" + n, 1, "正文", 0.5)));
        }
        BookLayoutProfile early = presentation.buildProfile(bookId);
        int earlyHeaders = (int) presentation.outline(bookId).stream()
                .filter(e -> e.title().contains("增補") || e.title().contains("增补")).count();
        for (int n = 5; n <= 10; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "增补書眉"), body("b-" + n, 1, "正文", 0.5)));
        }
        BookLayoutProfile late = presentation.buildProfile(bookId);
        assertTrue(late.profileRevision() > early.profileRevision(), "实质结构改变才增长版本号");
        int lateHeaders = (int) presentation.outline(bookId).stream()
                .filter(e -> e.title().contains("增補") || e.title().contains("增补")).count();
        assertTrue(lateHeaders < earlyHeaders, "旧书眉条目随画像更新移除");
        // 首现页内容仍保留可见（候选），位置不跳。
        PagePresentation first = presentation.project(bookId, store.readPage(bookId, 1));
        assertTrue(first.byBlockId().get("h-1").showInReading());
    }

    @Test void title13_frozenProfileReusableForSubsetExport() throws Exception {
        String bookId = seedBook("紙頁工坊", "紙頁工坊.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "紙頁工坊"),
                    body("b-" + n, 1, "正文" + n, 0.5)));
        }
        BookLayoutProfile frozen = presentation.buildProfile(bookId);
        List<Page> subset = List.of(store.readPage(bookId, 3), store.readPage(bookId, 4));
        List<OutlineService.OutlineEntry> subsetOutline =
                presentation.outlineForPages(bookId, subset, frozen);
        assertTrue(subsetOutline.isEmpty(), "只导出两页仍用全书画像，书眉不当标题");
        // 画像冻结后新增页面不影响本次导出结果。
        writeReady(bookId, ready(5, edgeHeading("h-5b", 0, "新书眉"), body("b-5b", 1, "正文", 0.5)));
        assertEquals(subsetOutline, presentation.outlineForPages(bookId, subset, frozen));
    }

    @Test void title14_overridesAreBlockScopedReversibleAndStaleOnReOcr() throws Exception {
        // 簇环境：10 页重复书眉 + 每页真章节，自动判断下书眉被排除。
        String bookId = seedBook("覆寫書", "覆寫書.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "覆寫書眉"),
                    heading("ch-" + n, 1, "第" + n + "章", 0.35, 1, "paddle"),
                    body("b-" + n, 2, "正文", 0.55)));
        }
        assertFalse(titles(presentation.outline(bookId)).contains("覆寫書眉"), "前置：书眉自动排除");
        // 加入目录：精确作用本块。
        int rev5 = BookStore.revisionOrZero(store.readPage(bookId, 5));
        overrides.apply(bookId, 5, new PresentationOverrideRequest(rev5, "h-5", null,
                PresentationOverrideRequest.OverrideAction.INCLUDE_IN_OUTLINE,
                PresentationOverrideRequest.OverrideScope.BLOCK), "tester");
        assertTrue(titles(presentation.outline(bookId)).contains("覆寫書眉"), "加入精确作用本块");
        assertEquals(1, presentation.outline(bookId).stream()
                .filter(e -> e.title().contains("覆寫書眉")).count(), "他块不受影响");
        // 恢复自动判断：删除对应覆盖，回到自动排除。
        overrides.apply(bookId, 5, new PresentationOverrideRequest(
                BookStore.revisionOrZero(store.readPage(bookId, 5)), "h-5", null,
                PresentationOverrideRequest.OverrideAction.CLEAR_OVERRIDE,
                PresentationOverrideRequest.OverrideScope.BLOCK), "tester");
        assertFalse(titles(presentation.outline(bookId)).contains("覆寫書眉"), "恢复自动判断后回到排除");
        // 排除某章：精确作用本块，他章不受影响。
        int rev2 = BookStore.revisionOrZero(store.readPage(bookId, 2));
        overrides.apply(bookId, 2, new PresentationOverrideRequest(rev2, "ch-2", null,
                PresentationOverrideRequest.OverrideAction.EXCLUDE_FROM_OUTLINE,
                PresentationOverrideRequest.OverrideScope.BLOCK), "tester");
        assertFalse(titles(presentation.outline(bookId)).contains("第2章"));
        assertTrue(titles(presentation.outline(bookId)).contains("第3章"));
        // 仅改展示角色：页 revision 与 reviewed 不变，不算全文人工校对。
        assertEquals(rev2, BookStore.revisionOrZero(store.readPage(bookId, 2)));
        assertFalse(store.readPage(bookId, 2).reviewed());
        // 重识别导致 sourceHash 变化：覆盖进入 STALE，不套到新块。
        Page changed = new Page(2, 600, 800, "READY", "paddle-aistudio",
                List.of(edgeHeading("h-2", 0, "覆寫書眉"),
                        heading("ch-2", 0, "第二章（重识别）", 0.35, 1, "paddle"),
                        body("b-2", 1, "正文", 0.55)),
                List.of(), false, null,
                List.of(edgeHeading("h-2", 0, "覆寫書眉")));
        store.writePage(bookId, changed, false);
        assertFalse(overrides.stale(bookId).isEmpty(), "失配覆盖进入待重新定位");
        assertTrue(titles(presentation.outline(bookId)).stream().anyMatch(t -> t.contains("第二章")),
                "STALE 覆盖不错误套用到新块");
    }

    @Test void title16_projectionNeverMutatesSourceRecords() throws Exception {
        String bookId = seedBook("紙頁工坊", "紙頁工坊.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "紙頁工坊"),
                    body("b-" + n, 1, "正文" + n, 0.5)));
        }
        Page before = store.readPage(bookId, 5);
        String sourceHash = hashOf(before.sourceRecords());
        String blocksHash = hashOf(before.blocks());
        presentation.project(bookId, before);
        presentation.outline(bookId);
        Page after = store.readPage(bookId, 5);
        assertEquals(blocksHash, hashOf(after.blocks()), "结构识别不改原始内容");
        assertEquals(sourceHash, hashOf(after.sourceRecords()));
    }

    @Test void pageTitleFallsBackToPageNumberInsteadOfRunningHeader() throws Exception {
        String bookId = seedBook("紙頁工坊", "紙頁工坊.pdf", 10);
        for (int n = 1; n <= 10; n++) {
            writeReady(bookId, ready(n, edgeHeading("h-" + n, 0, "紙頁工坊"),
                    body("b-" + n, 1, "正文" + n, 0.5)));
        }
        // 非首现书眉页：无可用标题，回到“第 N 页”，不冒用书眉。
        assertEquals("第 5 页", presentation.pageTitle(bookId, store.readPage(bookId, 5)));
    }

    private static String hashOf(List<Block> blocks) {
        if (blocks == null) return "null";
        return blocks.stream()
                .map(b -> b == null ? "null" : b.id() + "=" + b.original() + "=" + b.simplified())
                .toList().toString();
    }

    private static String hashBlocks(BookStore store, String bookId, int page) {
        Page p = store.readPage(bookId, page);
        if (p == null) return null;
        return hashOf(p.blocks()) + "|" + hashOf(p.sourceRecords());
    }
}
