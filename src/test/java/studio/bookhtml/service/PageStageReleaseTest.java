package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.PageAttempt;
import studio.bookhtml.domain.PageExecutionRecord;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PageStageReleaseTest {

    @TempDir
    Path data;

    private BookStore store;
    private PageProcessingService pageEngine;
    private PageWorkScheduler scheduler;
    private String bookId;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(data, "", "");
        store = new BookStore(app, new ObjectMapper().findAndRegisterModules());
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "stage-release", "stage-release.pdf", 2, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        store.writePage(bookId, Page.pending(1, 600, 800), false);
        store.writePage(bookId, Page.pending(2, 600, 800), false);

        pageEngine = mock(PageProcessingService.class);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) scheduler.close();
        if (store != null) store.close();
    }

    private PageProcessingService.Request createRequest(int page) {
        PageAttempt attempt = PageAttempt.register(bookId, page, 0, "hash", List.of("PUBLISH"));
        return new PageProcessingService.Request(attempt, "paddle-aistudio", "auto", false, false, true, () -> false, () -> true);
    }

    @Test
    void testBaselineReleasedWhileEnhancementBlockedAndNextPageExecutes() throws Exception {
        CountDownLatch enhancementBlockedLatch = new CountDownLatch(1);
        CountDownLatch enhancementResumeLatch = new CountDownLatch(1);
        CountDownLatch page1BaselineDoneLatch = new CountDownLatch(1);

        // Page 1: Baseline commits, then enhancement blocks on enhancementResumeLatch
        // Page 2: Baseline commits and settles without enhancement
        when(pageEngine.executeBaseline(any(), any())).thenAnswer(inv -> {
            PageProcessingService.Request req = inv.getArgument(0);
            PageExecutionRecord record = inv.getArgument(1);
            int page = req.page();

            Block b = new Block("b1", "text", 0, new double[]{.1,.1,.8,.2}, "horizontal-tb",
                    "Page " + page + " baseline", "Page " + page + " baseline", .95, false, false, null, "paddle", List.of("b1"), null, null, List.of());
            Page published = new Page(page, 600, 800, "READY", "paddle-aistudio", List.of(b), List.of(), false, null, List.of(b));
            store.writePage(bookId, published, true);

            if (page == 1) {
                page1BaselineDoneLatch.countDown();
                // Stage transition to BASELINE_COMMITTED
                return record.withBaselineCommitted(1);
            } else {
                return record.withSettled("SUCCEEDED", "OK", 1);
            }
        });

        when(pageEngine.executeEnhancement(any(), any())).thenAnswer(inv -> {
            enhancementBlockedLatch.countDown();
            // Block on artificial latch (e.g. simulating slow Qwen assist)
            boolean resumed = enhancementResumeLatch.await(10, TimeUnit.SECONDS);
            assertTrue(resumed, "Enhancement resume latch timed out");

            PageExecutionRecord record = inv.getArgument(1);
            Block b = new Block("b1", "text", 0, new double[]{.1,.1,.8,.2}, "horizontal-tb",
                    "Page 1 enhanced", "Page 1 enhanced", .99, false, false, null, "paddle", List.of("b1"), null, null, List.of());
            Page enhanced = new Page(1, 600, 800, "READY", "paddle-aistudio", List.of(b), List.of(), false, null, List.of(b));
            store.writePage(bookId, enhanced, true);

            return record.withSettled("SUCCEEDED", "OK", 2);
        });

        when(pageEngine.settle(any(), any())).thenAnswer(inv -> {
            PageExecutionRecord record = inv.getArgument(1);
            return new PageProcessingService.Result(record.stage().name(), "OK", record.publishedRevision());
        });

        // 1 worker per pool: 1 baseline worker, 1 enhancement worker
        scheduler = new PageWorkScheduler(pageEngine, 1, 1);

        // Schedule Page 1 (needs baseline + enhancement)
        CompletableFuture<PageProcessingService.Result> f1 = scheduler.schedule(createRequest(1), PageWorkScheduler.Priority.P0);

        // Wait for page 1 baseline to be committed
        assertTrue(page1BaselineDoneLatch.await(5, TimeUnit.SECONDS), "Page 1 baseline did not finish in time");

        // Verify Page 1 baseline is readable in store immediately!
        Page page1InStore = store.readPage(bookId, 1);
        assertNotNull(page1InStore);
        assertEquals("READY", page1InStore.status());
        assertEquals("Page 1 baseline", page1InStore.blocks().get(0).original());

        // Wait for Page 1 to enter enhancement worker and block
        assertTrue(enhancementBlockedLatch.await(5, TimeUnit.SECONDS), "Page 1 did not block in enhancement worker");

        // While Page 1 is still blocked in enhancement, schedule Page 2!
        CompletableFuture<PageProcessingService.Result> f2 = scheduler.schedule(createRequest(2), PageWorkScheduler.Priority.P0);

        // Page 2 should complete successfully because baseline worker pool was released!
        PageProcessingService.Result r2 = f2.get(5, TimeUnit.SECONDS);
        assertNotNull(r2);
        assertEquals("SETTLED", r2.lifecycle());

        // Verify Page 2 is written and readable in store
        Page page2InStore = store.readPage(bookId, 2);
        assertNotNull(page2InStore);
        assertEquals("READY", page2InStore.status());
        assertEquals("Page 2 baseline", page2InStore.blocks().get(0).original());

        // Now resume Page 1 enhancement
        enhancementResumeLatch.countDown();

        // Page 1 finishes enhancement and settles
        PageProcessingService.Result r1 = f1.get(5, TimeUnit.SECONDS);
        assertNotNull(r1);
        assertEquals("SETTLED", r1.lifecycle());

        Page page1Enhanced = store.readPage(bookId, 1);
        assertEquals("Page 1 enhanced", page1Enhanced.blocks().get(0).original());
    }

    @Test
    void testPageExecutionRecordLifecycleTransitions() {
        PageAttempt attempt = PageAttempt.register(bookId, 1, 0, "hash", List.of("PUBLISH"));
        PageExecutionRecord rec = PageExecutionRecord.initial(attempt);

        assertEquals(PageExecutionRecord.Stage.QUEUED_BASELINE, rec.stage());
        assertFalse(rec.isBaselineCommitted());
        assertFalse(rec.isSettled());

        PageExecutionRecord r1 = rec.withRunningBaseline();
        assertEquals(PageExecutionRecord.Stage.BASELINE_RUNNING, r1.stage());

        PageExecutionRecord r2 = r1.withBaselineCommitted(1);
        assertEquals(PageExecutionRecord.Stage.BASELINE_COMMITTED, r2.stage());
        assertTrue(r2.isBaselineCommitted());
        assertTrue(r2.isEligibleForEnhancement());
        assertEquals(1, r2.publishedRevision());

        PageExecutionRecord r3 = r2.withRunningEnhancement();
        assertEquals(PageExecutionRecord.Stage.ENHANCEMENT_RUNNING, r3.stage());
        assertTrue(r3.isBaselineCommitted());

        PageExecutionRecord r4 = r3.withSettled("SUCCEEDED", "OK", 2);
        assertEquals(PageExecutionRecord.Stage.SETTLED, r4.stage());
        assertTrue(r4.isSettled());
        assertEquals("SUCCEEDED", r4.lifecycle());
        assertEquals("OK", r4.messageCode());
        assertEquals(2, r4.publishedRevision());
    }
}
