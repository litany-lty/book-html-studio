package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.service.ProcessingProgressService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PageHeadStoreTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private PageHeadStore headStore;
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
        headStore = store.headStore();
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "页头测试书", "source.pdf", 100, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
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
    void pagePublicationWritesSmallHeadFile() throws Exception {
        Page page5 = readyPage(5, "第5页正文内容");
        store.writePage(bookId, page5, true);

        Page committed = store.commitPage(bookId, page5, 0, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);
        assertEquals(1, committed.revision());

        Path headPath = PageHeadStore.headPath(store.bookDir(bookId), 5);
        assertTrue(Files.exists(headPath));
        assertTrue(Files.size(headPath) < 1024, "Head file should be small (< 1KB)");

        PageHead head = headStore.readHead(store.bookDir(bookId), 5);
        assertNotNull(head);
        assertEquals(5, head.pageNumber());
        assertEquals(1, head.revision());
        assertEquals("READY", head.status());
        assertTrue(head.processed());
        assertFalse(head.reviewed());
        assertEquals(committed.lastCommitId(), head.commitId());
    }

    @Test
    void attemptUpdatesAreReflectedInHead() throws Exception {
        UUID attemptId = UUID.randomUUID();
        PageHead updated = headStore.updateAttempt(store.bookDir(bookId), 12, attemptId, 3L, "RUNNING", "OCR");

        assertNotNull(updated);
        assertEquals(12, updated.pageNumber());
        assertEquals(attemptId, updated.attemptId());
        assertEquals(3L, updated.attemptSeq());
        assertEquals("RUNNING", updated.attemptLifecycle());
        assertEquals("OCR", updated.attemptStage());

        PageHead loaded = headStore.readHead(store.bookDir(bookId), 12);
        assertNotNull(loaded);
        assertEquals(attemptId, loaded.attemptId());
        assertEquals("RUNNING", loaded.attemptLifecycle());
    }

    @Test
    void progressLatestPrefersSmallHeadWithoutReadingFullPage() throws Exception {
        ProcessingProgressService progress = new ProcessingProgressService();
        progress.setStore(store);

        UUID attemptId = UUID.randomUUID();
        headStore.updateAttempt(store.bookDir(bookId), 20, attemptId, 5L, "RUNNING", "OCR_EXTRACT");

        // Verify progress.latest returns snapshot derived directly from head
        ProcessingSnapshot snapshot = progress.latest(bookId, 20);
        assertNotNull(snapshot);
        assertEquals(attemptId, snapshot.attemptId());
        assertEquals("RUNNING", snapshot.lifecycle());
        assertEquals("OCR_EXTRACT", snapshot.stage());
        assertEquals("PERSISTED_ATTEMPT_HEAD", snapshot.messageCode());
        assertEquals(5L, snapshot.attemptSeq());
    }

    @Test
    void repairHeadFromSinglePageInConstantTime() throws Exception {
        Page page7 = readyPage(7, "可快速修复的正文");
        store.writePage(bookId, page7, true);
        store.commitPage(bookId, page7, 0, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);

        Path headPath = PageHeadStore.headPath(store.bookDir(bookId), 7);
        Files.deleteIfExists(headPath);
        assertNull(headStore.readHead(store.bookDir(bookId), 7));

        // Repair head for page 7
        Page page = store.readPage(bookId, 7);
        String hash = store.pageContentHash(page);
        PageHead repaired = headStore.createOrUpdatePublication(store.bookDir(bookId), page, hash, 1L,
                "第 7 页标题", null, null, "SUCCEEDED", null);

        assertNotNull(repaired);
        assertTrue(Files.exists(headPath));
        assertEquals(7, repaired.pageNumber());
        assertEquals(1, repaired.revision());
        assertEquals("READY", repaired.status());
    }
}
