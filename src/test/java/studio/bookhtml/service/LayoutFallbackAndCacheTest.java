package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * U6：复杂版式保真降级、画像/统计增量失效、按需加载。隔离合成夹具。
 */
class LayoutFallbackAndCacheTest {
    @TempDir Path data;
    private BookStore store;
    private BookPresentationService presentation;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @AfterEach void close() {
        if (store != null) store.close();
    }

    private String seedBook(String title, int totalPages) throws Exception {
        store = new BookStore(TestConfigs.config(data, "", ""), json);
        presentation = new BookPresentationService(store);
        presentation.setOverrides(new PresentationOverrideService(store));
        String id = UUID.randomUUID().toString();
        store.createBookDirectory(id);
        store.writeBook(new Book(id, title, title + ".pdf", totalPages, Instant.now(), Instant.now(), 0, 0));
        for (int n = 1; n <= totalPages; n++) store.writePage(id, Page.pending(n, 600, 800), false);
        return id;
    }

    private static Block text(String id, int order, String content, double[] bbox) {
        return new Block(id, "text", order, bbox, "horizontal-tb",
                content, content, 0.9, false, false, null, "paddle",
                List.of(id), null, new double[]{10, 20, 50, 10}, List.of());
    }

    private static Page ready(int n, Block... blocks) {
        List<Block> all = List.of(blocks);
        return new Page(n, 600, 800, "READY", "paddle-aistudio", all, List.of(), false, null, all);
    }

    @Test void lay_regionImageForRelationshipDiagram() throws Exception {
        String bookId = seedBook("图谱书", 6);
        String diagram = "甲→乙\n丙→丁\n戊→己\n庚→辛";
        for (int n = 1; n <= 6; n++) {
            store.writePage(bookId, ready(n,
                    text("t-" + n, 0, diagram, new double[]{0.1, 0.2, 0.8, 0.5}),
                    text("b-" + n, 1, "正文" + n, new double[]{0.1, 0.75, 0.5, 0.1})), false);
        }
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 1));
        assertEquals(PagePresentation.ROLE_VISUAL, view.byBlockId().get("t-1").role(),
                "关系图不拆开拼顺序，转原子视觉区");
        assertEquals("REGION_IMAGE", view.fallbackMode(), "复杂图谱区域原图降级");
        assertTrue(view.byBlockId().get("b-1").showInReading(), "普通正文仍可读");
    }

    @Test void lay_pageImageWhenTextGeometryMissing() throws Exception {
        String bookId = seedBook("残页书", 6);
        for (int n = 1; n <= 6; n++) {
            Block noGeometry = new Block("t-" + n, "text", 0, null, "horizontal-tb",
                    "正文" + n, "正文" + n, 0.4, true, false, null, "paddle",
                    List.of("t-" + n), null, null, List.of());
            store.writePage(bookId, ready(n, noGeometry), false);
        }
        PagePresentation view = presentation.project(bookId, store.readPage(bookId, 1));
        assertEquals("PAGE_IMAGE", view.fallbackMode(), "几何缺失不强行输出看似通顺的横排");
        // 内容本身保留（降级只改变呈现，不删字）。
        assertEquals("正文1", store.readPage(bookId, 1).blocks().get(0).original());
    }

    @Test void lay_normalPageHasNoFallback() throws Exception {
        String bookId = seedBook("普通书", 6);
        for (int n = 1; n <= 6; n++) {
            store.writePage(bookId, ready(n,
                    text("t-" + n, 0, "正文内容" + n, new double[]{0.1, 0.2, 0.8, 0.5})), false);
        }
        assertEquals("NONE", presentation.project(bookId, store.readPage(bookId, 1)).fallbackMode());
    }

    @Test void profileCacheHitAndInvalidatedOnMutation() throws Exception {
        String bookId = seedBook("缓存书", 6);
        for (int n = 1; n <= 6; n++) {
            store.writePage(bookId, ready(n,
                    text("t-" + n, 0, "正文" + n, new double[]{0.1, 0.2, 0.8, 0.5})), false);
        }
        BookLayoutProfile first = presentation.buildProfile(bookId);
        BookLayoutProfile second = presentation.buildProfile(bookId);
        assertEquals(1, presentation.cacheHits(), "无变更直接返回缓存");
        assertEquals(first.profileRevision(), second.profileRevision());
        assertSame(first, second, "同一缓存对象");
        // 页变更后失效并重建（版本号连续性经 sidecar 保持）。
        store.writePage(bookId, ready(1,
                text("t-1", 0, "修改后正文", new double[]{0.1, 0.2, 0.8, 0.5})), false);
        BookLayoutProfile third = presentation.buildProfile(bookId);
        assertEquals(1, presentation.cacheHits(), "变更后重建，不记命中");
        assertTrue(third.profileRevision() >= first.profileRevision());
    }

    @Test void bookStatsCacheInvalidatedOnMutation() throws Exception {
        String bookId = seedBook("统计书", 3);
        PdfService pdf = mock(PdfService.class);
        BookService books = new BookService(store, pdf, TestConfigs.config(data, "", ""));
        assertEquals(0, books.get(bookId).processedPages());
        store.writePage(bookId, ready(1, text("t", 0, "正文", new double[]{0.1, 0.2, 0.8, 0.5})), false);
        assertEquals(1, books.get(bookId).processedPages(), "页变更后统计更新，不读旧缓存");
        assertEquals(1, books.get(bookId).processedPages(), "缓存命中值一致");
    }

    @Test void u6_readerHonorsFallbackAndLazyImages() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("pom.xml"))) root = root.getParent();
        assertNotNull(root);
        String reader = Files.readString(root.resolve("src/main/resources/static/reader.js"));
        assertTrue(reader.contains("fallbackMode"), "U6：阅读器消费降级决策");
        assertTrue(reader.contains("PAGE_IMAGE"), "U6：整页降级看原稿");
        assertTrue(reader.contains("loading") && reader.contains("lazy"), "U6：图片按需加载");
    }
}
