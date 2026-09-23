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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IndexSearchIsolationTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookIndexService indexService;
    private String bookA;
    private String bookB;

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
        indexService = store.indexService();

        bookA = UUID.randomUUID().toString();
        bookB = UUID.randomUUID().toString();
        store.createBookDirectory(bookA);
        store.createBookDirectory(bookB);

        store.writeBook(new Book(bookA, "书A", "a.pdf", 5, Instant.now(), Instant.now(), 0, 0));
        store.writeBook(new Book(bookB, "书B", "b.pdf", 5, Instant.now(), Instant.now(), 0, 0));
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private Block block(String id, String text) {
        return new Block(id, "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                text, text, 0.99, false, false, null, "test", List.of(id), null, null);
    }

    private Page pageWithText(int pageNumber, String text) {
        return new Page(pageNumber, 600.0, 800.0, "READY", "test",
                List.of(block("b" + pageNumber, text)), List.of(), false, null, List.of(block("b" + pageNumber, text)));
    }

    @Test
    void searchIsIsolatedAcrossDifferentBooksWithSameKeywords() throws Exception {
        // Build index for Book A containing keyword
        indexService.buildOrRebuild(store.bookDir(bookA), bookA, "hashA", 5, store.sourceJournal(),
                p -> pageWithText(p, "这是第一本书中的天干地支五行学说")).get();

        // Build index for Book B containing different text
        indexService.buildOrRebuild(store.bookDir(bookB), bookB, "hashB", 5, store.sourceJournal(),
                p -> pageWithText(p, "这是第二本书中的现代量子力学原理")).get();

        // Search Book A for "天干地支"
        List<Map<String, Object>> hitsA = indexService.search(store.bookDir(bookA), bookA, "天干地支", null, 500);
        assertFalse(hitsA.isEmpty());
        for (var hit : hitsA) {
            assertTrue(((String) hit.get("text")).contains("天干地支"));
        }

        // Search Book B for "天干地支" -> must be empty
        List<Map<String, Object>> hitsB = indexService.search(store.bookDir(bookB), bookB, "天干地支", null, 500);
        assertTrue(hitsB.isEmpty(), "Book B should not have hits for Book A keywords");

        // Search Book B for "量子力学"
        List<Map<String, Object>> hitsB2 = indexService.search(store.bookDir(bookB), bookB, "量子力学", null, 500);
        assertFalse(hitsB2.isEmpty());
    }

    @Test
    void chineseNGramAndPunctuationStrippingWorksAccurately() throws Exception {
        indexService.buildOrRebuild(store.bookDir(bookA), bookA, "hashA", 3, store.sourceJournal(),
                p -> pageWithText(p, "【重要提示】：古籍版本（校对版）已完成。")).get();

        // Search with punctuation stripped
        List<Map<String, Object>> hits1 = indexService.search(store.bookDir(bookA), bookA, "重要提示", null, 500);
        assertFalse(hits1.isEmpty());

        // Single character n-gram search
        List<Map<String, Object>> hits2 = indexService.search(store.bookDir(bookA), bookA, "校", null, 500);
        assertFalse(hits2.isEmpty());

        // Multi-gram search
        List<Map<String, Object>> hits3 = indexService.search(store.bookDir(bookA), bookA, "校对版", null, 500);
        assertFalse(hits3.isEmpty());
    }

    @Test
    void searchSnapshotExpirationReturnsHttp410() throws Exception {
        // Non-existent snapshot token throws 410 GONE
        ApiException ex = assertThrows(ApiException.class, () ->
                indexService.search(store.bookDir(bookA), bookA, "任意查询", "expired-or-invalid-token", 500)
        );
        assertEquals(410, ex.status().value());
    }
}
