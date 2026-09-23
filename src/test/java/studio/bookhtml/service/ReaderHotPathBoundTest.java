package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ReaderController;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReaderHotPathBoundTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private BookService bookService;
    private ProcessingProgressService progress;
    private ReaderController readerController;
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
        store = spy(new BookStore(cfg, json));
        bookService = new BookService(store, mock(PdfService.class), cfg);
        progress = new ProcessingProgressService();
        progress.setStore(store);
        readerController = new ReaderController(store, bookService, progress);

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
    void readerProgressOnLargeBookExecutesConstantZeroFullPageReadsWhenHeadPresent() throws Exception {
        int totalPages = 5000;
        Book book = new Book(bookId, "大书5000页", "large.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        // Commit page 2500, which writes page 2500 and its small head
        Page page2500 = readyPage(2500, "第2500页内容");
        store.writePage(bookId, page2500, true);
        store.commitPage(bookId, page2500, 0, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);

        // Clear mock invocations to count only reads during reader controller progress call
        clearInvocations(store);

        // Progress check for page 2500
        ReaderController.PageProgress result = readerController.progress(bookId, 2500);
        assertNotNull(result);
        assertEquals(2500, result.pageNumber());
        assertEquals("READY", result.status());
        assertEquals(1, result.revision());

        // Assert zero calls to readPage(bookId, 2500) or any other page!
        verify(store, never()).readPage(anyString(), anyInt());
    }

    @Test
    void pageSummaryLookupDoesNotScanEntireBook() throws Exception {
        int totalPages = 1000;
        Book book = new Book(bookId, "千页书", "thousand.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        // Pre-build index
        store.indexService().buildOrRebuild(store.bookDir(bookId), bookId, "pdf1k", totalPages,
                store.sourceJournal(), p -> readyPage(p, "正文 " + p)).get();

        clearInvocations(store);

        // Fetch page summaries for pages 500 to 520 (21 pages)
        List<PageSummary> summaries = bookService.pages(bookId).subList(499, 520);
        assertEquals(21, summaries.size());
        assertEquals(500, summaries.get(0).pageNumber());
        assertEquals(520, summaries.get(20).pageNumber());

        // Zero full page deserialization calls
        verify(store, never()).readPage(anyString(), anyInt());
    }

    @Test
    void coldIndexDirectlyServesCurrentPage() throws Exception {
        int totalPages = 10;
        Book book = new Book(bookId, "冷书", "cold.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        Page page3 = readyPage(3, "冷启动正文内容");
        store.writePage(bookId, page3, true);

        // Read page 3 directly without index being built
        Page fetched = bookService.page(bookId, 3);
        assertNotNull(fetched);
        assertEquals(3, fetched.pageNumber());
        assertEquals("READY", fetched.status());
    }
}
