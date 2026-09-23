package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.SourceChangeJournal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PersistentBookIndexTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookIndexService indexService;
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
        indexService = store.indexService();
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private Block block(String id, String text) {
        return new Block(id, "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                text, text, 0.99, false, false, null, "test", List.of(id), null, null);
    }

    private Page readyPage(int pageNumber, String text) {
        return new Page(pageNumber, 600.0, 800.0, "READY", "test",
                List.of(block("b" + pageNumber, text)), List.of(), false, null, List.of(block("b" + pageNumber, text)));
    }

    @Test
    void shardedSummariesAreBoundedTo128ItemsPerShard() throws Exception {
        int totalPages = 300;
        Book book = new Book(bookId, "分片索引测试书", "source.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        var manifestFuture = indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash123", totalPages,
                store.sourceJournal(), p -> readyPage(p, "第" + p + "页文本内容"));

        BookIndexManifest manifest = manifestFuture.get();
        assertNotNull(manifest);
        assertEquals("READY", manifest.status());
        assertEquals(totalPages, manifest.totalPages());
        assertEquals(totalPages, manifest.processedPages());

        Path genDir = BookIndexService.generationDir(store.bookDir(bookId), manifest.generationId());
        Path shard0 = BookIndexService.shardPath(genDir, 0);
        Path shard1 = BookIndexService.shardPath(genDir, 1);
        Path shard2 = BookIndexService.shardPath(genDir, 2);

        assertTrue(Files.exists(shard0), "Shard 0 should exist");
        assertTrue(Files.exists(shard1), "Shard 1 should exist");
        assertTrue(Files.exists(shard2), "Shard 2 should exist");

        // Query single summary
        PageSummary p10 = indexService.pageSummary(store.bookDir(bookId), 10);
        assertNotNull(p10);
        assertEquals(10, p10.pageNumber());
        assertEquals("READY", p10.status());

        PageSummary p150 = indexService.pageSummary(store.bookDir(bookId), 150);
        assertNotNull(p150);
        assertEquals(150, p150.pageNumber());

        // Range query spanning shards 0 and 1 (from 120 to 140)
        List<PageSummary> slice = indexService.pageSummaries(store.bookDir(bookId), 120, 21);
        assertEquals(21, slice.size());
        assertEquals(120, slice.get(0).pageNumber());
        assertEquals(140, slice.get(slice.size() - 1).pageNumber());
    }

    @Test
    void coldManifestLoadReportsAggregateCountersWithoutScanningPages() throws Exception {
        int totalPages = 50;
        Book book = new Book(bookId, "冷计数测试书", "source.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        indexService.buildOrRebuild(store.bookDir(bookId), bookId, "pdfhash", totalPages,
                store.sourceJournal(), p -> readyPage(p, "正文 " + p)).get();

        // Close and reopen store to simulate cold start
        store.close();
        store = new BookStore(config(realDir), json);
        indexService = store.indexService();

        BookIndexManifest coldManifest = indexService.manifest(store.bookDir(bookId));
        assertNotNull(coldManifest);
        assertEquals(totalPages, coldManifest.totalPages());
        assertEquals(totalPages, coldManifest.processedPages());
        assertEquals(0, coldManifest.reviewedPages());
    }
}
