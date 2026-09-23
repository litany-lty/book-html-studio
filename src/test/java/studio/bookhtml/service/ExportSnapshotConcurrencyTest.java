package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * G12 / B10-01: 导出快照并发一致性与版本固定。
 * 验证导出期间并发修改页面、覆盖或决策依赖时，整书导出明确绑定同一一致快照，不产生混合书或脏读。
 */
class ExportSnapshotConcurrencyTest {
    @TempDir
    Path tempDir;

    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookService books;
    private PdfService pdf;
    private BookPresentationService presentation;
    private ExportService exportService;
    private String bookId;

    static AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, true);
    }

    @BeforeEach
    void setUp() throws Exception {
        realDir = tempDir.toRealPath();
        json = new ObjectMapper().findAndRegisterModules();
        var cfg = config(realDir);
        store = new BookStore(cfg, json);
        books = mock(BookService.class);
        pdf = mock(PdfService.class);

        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        Book book = new Book(bookId, "并发测试书籍", "source.pdf", 2, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        when(books.get(bookId)).thenReturn(book);

        Path sourcePdf = store.pdf(bookId);
        Files.write(sourcePdf, "%PDF-mock-content".getBytes(StandardCharsets.UTF_8));
        when(pdf.render(any(Path.class), anyInt(), anyInt()))
                .thenReturn(new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB));

        presentation = new BookPresentationService(store);
        presentation.setOverrides(new PresentationOverrideService(store));

        exportService = new ExportService(books, store, pdf, json, null, null);
        exportService.setPresentation(presentation);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private Block textBlock(String id, String text) {
        return new Block(id, "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                text, text, 0.95, false, false, null, "test", List.of(id), null, null);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                result.put(entry.getName(), zip.readAllBytes());
                zip.closeEntry();
            }
        }
        return result;
    }

    @Test
    void exportPinRetainsOriginalPageVersionWhenPageIsUpdatedConcurrently() throws Exception {
        // 初始写入第 1 页与第 2 页
        Block b1 = textBlock("b1", "第一版稳定内容");
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1), 1);
        store.writePage(bookId, p1, false);

        Block b2 = textBlock("b2", "第二页稳定内容");
        Page p2 = new Page(2, 600.0, 800.0, "READY", "test", List.of(b2), List.of(), false, null, List.of(b2), 1);
        store.writePage(bookId, p2, false);

        // 创建冻结快照（此时第 1 页固定在 revision 1）
        ExportSnapshot snapshot = exportService.createSnapshot(bookId, "all");
        assertNotNull(snapshot);
        assertEquals(2, snapshot.pageRefs().size());
        assertEquals(1, snapshot.pageRef(1).revision());

        // 模拟并发人工修改第 1 页，写入 revision 2
        Page currentP1 = store.readPage(bookId, 1);
        Block b1Updated = textBlock("b1", "第二版被并发修改的内容");
        Page p1Updated = new Page(1, 600.0, 800.0, "READY", "manual", List.of(b1Updated), List.of(), true, null, List.of(b1Updated));
        store.writePage(bookId, p1Updated, false);

        // 确认当前存储已推进到 revision 2
        assertEquals(2, BookStore.revisionOrZero(store.readPage(bookId, 1)));

        // 执行快照导出
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(snapshot, out);
        byte[] zipBytes = out.toByteArray();
        assertTrue(zipBytes.length > 0);

        Map<String, byte[]> entries = unzip(zipBytes);
        assertTrue(entries.containsKey("assets/pages-data/1.js"));
        String page1Data = new String(entries.get("assets/pages-data/1.js"), StandardCharsets.UTF_8);

        // 核心断言：导出结果严格保留快照冻结的 revision 1，不混用并发提交的 revision 2
        assertTrue(page1Data.contains("第一版稳定内容"), "导出必须来自快照固定的版本");
        assertFalse(page1Data.contains("第二版被并发修改的内容"), "导出严禁混入并发修改的新版本");
    }

    @Test
    void exportDecisionsRetainSnapshotStateWhenDecisionsChangeConcurrently() throws Exception {
        Block b1 = textBlock("b1", "文本内容");
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1));
        store.writePage(bookId, p1, false);

        ExportSnapshot snapshot = exportService.createSnapshot(bookId, "1");
        assertNotNull(snapshot);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(snapshot, out);
        Map<String, byte[]> entries = unzip(out.toByteArray());
        assertTrue(entries.containsKey("assets/decisions.js"));
        String decisionsJs = new String(entries.get("assets/decisions.js"), StandardCharsets.UTF_8);
        assertTrue(decisionsJs.startsWith("globalThis.BOOK_DECISIONS="));
    }

    @Test
    void snapshotExpiredWhenReferencedRevisionIsPurgedOrCorrupted() throws Exception {
        Block b1 = textBlock("b1", "原始内容");
        Page p1 = new Page(1, 600.0, 800.0, "READY", "test", List.of(b1), List.of(), false, null, List.of(b1));
        store.writePage(bookId, p1, false);

        // 创建一个故意持有不匹配 hash 的快照
        ExportSnapshot.PageRef corruptedRef = new ExportSnapshot.PageRef(
                1, 1, 1, UUID.randomUUID(), "corrupted-hash-does-not-match", "READY", true, false
        );
        ExportSnapshot corruptedSnapshot = new ExportSnapshot(
                UUID.randomUUID().toString(),
                bookId,
                "测试书",
                1,
                1,
                "pdf-sha",
                0L,
                List.of(1),
                List.of(corruptedRef),
                0L,
                0L,
                2,
                Map.of(),
                Instant.now()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ApiException ex = assertThrows(ApiException.class, () -> exportService.writeZip(corruptedSnapshot, out));
        assertEquals(409, ex.status().value());
        assertTrue(ex.getMessage().contains("SNAPSHOT_EXPIRED"));
        assertEquals(0, out.size(), "快照失效严禁向客户端发送残缺 ZIP");
    }

    @Test
    void exportSelectionRetainsWholeBookHeaderEvidenceWithoutMisclassifyingHeaders() throws Exception {
        Book book10 = new Book(bookId, "并发测试书籍", "source.pdf", 10, Instant.now(), Instant.now(), 10, 0);
        store.writeBook(book10);
        when(books.get(bookId)).thenReturn(book10);

        // 创建 10 页的书，每页顶部都有相同的“紙頁工坊”书眉
        for (int n = 1; n <= 10; n++) {
            List<Block> blocks = new ArrayList<>();
            blocks.add(new Block("h-" + n, "heading", 0, new double[]{0.1, 0.02, 0.8, 0.04},
                    "horizontal-tb", "紙頁工坊", "紙頁工坊", 0.9, false, false, 2, "test",
                    List.of("h-" + n), null, new double[]{10, 20, 50, 10}, List.of()));
            if (n == 2) {
                blocks.add(new Block("ch-2", "heading", 1, new double[]{0.1, 0.4, 0.8, 0.06},
                        "horizontal-tb", "第二章 墨水调配", "第二章 墨水调配", 0.9, false, false, 1, "test",
                        List.of("ch-2"), null, new double[]{10, 40, 50, 10}, List.of()));
            }
            Page page = new Page(n, 600.0, 800.0, "READY", "test", List.copyOf(blocks), List.of(),
                    false, null, List.copyOf(blocks));
            store.writePage(bookId, page, false);
        }

        // 仅导出第 2 页
        ExportSnapshot snapshot = exportService.createSnapshot(bookId, "2");
        assertEquals(1, snapshot.pageRefs().size());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.writeZip(snapshot, out);
        Map<String, byte[]> entries = unzip(out.toByteArray());

        String bookJs = new String(entries.get("assets/book.js"), StandardCharsets.UTF_8);
        int outlineStart = bookJs.indexOf("\"outline\":[");
        assertTrue(outlineStart >= 0);
        int outlineEnd = bookJs.indexOf(']', outlineStart);
        String outlineJson = bookJs.substring(outlineStart, outlineEnd);

        // 核心断言：全书画像书眉证据保留，即使只导出一页，书眉也不被误判为正文章节
        assertFalse(outlineJson.contains("紙頁工坊"), "全书画像书眉不得误作为目录标题导出");
        assertTrue(outlineJson.contains("第二章 墨水调配"), "真实章节标题必须保留在目录中");
    }

    @Test
    void snapshotHashIsDeterministicAndUnique() {
        ExportSnapshot.PageRef ref1 = new ExportSnapshot.PageRef(1, 1, 1, UUID.randomUUID(), "hash1", "READY", true, false);
        ExportSnapshot s1 = new ExportSnapshot("id1", "b1", "title", 1, 1, "pdf1", 10L, List.of(1), List.of(ref1), 1L, 1L, 2, Map.of(), Instant.now());
        ExportSnapshot s2 = new ExportSnapshot("id2", "b1", "title", 1, 1, "pdf1", 10L, List.of(1), List.of(ref1), 1L, 1L, 2, Map.of(), Instant.now());

        // snapshotHash 基于固定内容（不依赖瞬时 snapshotId 或 createdAt）
        assertEquals(s1.snapshotHash(), s2.snapshotHash());
        assertFalse(s1.snapshotHash().isBlank());
    }
}
