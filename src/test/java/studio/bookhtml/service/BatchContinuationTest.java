package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchContinuationTest {

    @TempDir
    Path data;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private JobService jobs;
    private BookService books;
    private PageProcessor processor;
    private SettingsService settings;
    private Book book;
    private String bookId;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(data, "", "");
        store = new BookStore(app, json);
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        book = new Book(bookId, "batch-test", "batch-test.pdf", 5, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);

        books = mock(BookService.class);
        when(books.get(bookId)).thenReturn(book);

        processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("test-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json,
                new EncryptedFileSecretStore(data, new byte[32], "batch-continuation-test-fixture", "fixture"));

        jobs = new JobService(store, books, processor);
        jobs.setSettings(settings);
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    private static ProcessingResult textResult(int page, String text) {
        Block block = new Block("b" + page, "text", 0, new double[]{.1, .1, .8, .2}, "horizontal-tb",
                text, text, .98, false, false, null, "paddle", List.of("b" + page), null, null, List.of());
        return new ProcessingResult(new Page(page, 600, 800, "READY", "paddle-aistudio",
                List.of(block), List.of(), false, null, List.of(block)), ProcessingResult.Category.TEXT);
    }

    private static void await(BooleanSupplier predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean(), "Condition was not met within timeout");
    }

    @Test
    void testLightweightDescriptorQueueBounds() {
        int pageCount = 5000;
        long start = System.nanoTime();
        List<JobService.PageTaskDescriptor> descriptors = new ArrayList<>(pageCount);
        for (int i = 1; i <= pageCount; i++) {
            descriptors.add(new JobService.PageTaskDescriptor(bookId, i, 0, false));
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertEquals(pageCount, descriptors.size());
        assertTrue(durationMs < 200, "5000 PageTaskDescriptors should allocate rapidly in under 200ms");

        JobService.PageTaskDescriptor first = descriptors.get(0);
        assertEquals(bookId, first.bookId());
        assertEquals(1, first.pageNumber());
        assertEquals(0, first.expectedRevision());
        assertFalse(first.force());

        JobService.PageTaskDescriptor last = descriptors.get(pageCount - 1);
        assertEquals(pageCount, last.pageNumber());
    }

    @Test
    void testContinuationSkipsReadyPages() throws Exception {
        // Setup pages: 1, 2, 4 are READY; 3, 5 are PENDING
        store.writePage(bookId, new Page(1, 600, 800, "READY", "paddle-aistudio", List.of(), List.of(), false, null, List.of()), false);
        store.writePage(bookId, new Page(2, 600, 800, "READY", "paddle-aistudio", List.of(), List.of(), false, null, List.of()), false);
        store.writePage(bookId, Page.pending(3, 600, 800), false);
        store.writePage(bookId, new Page(4, 600, 800, "READY", "paddle-aistudio", List.of(), List.of(), false, null, List.of()), false);
        store.writePage(bookId, Page.pending(5, 600, 800), false);

        when(processor.processBaseline(eq(bookId), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int p = inv.getArgument(1);
                    return textResult(p, "Page " + p + " processed");
                });

        // Submit batch job without force (continuation mode)
        JobRequest request = new JobRequest("1-5", "paddle-aistudio", "auto", false, false, false);
        jobs.submit(bookId, request);

        // Await completion
        await(() -> {
            Job j = store.readJob(bookId);
            return j != null && ("COMPLETED".equals(j.status()) || "COMPLETED_WITH_ERRORS".equals(j.status()));
        });

        Job completed = store.readJob(bookId);
        assertEquals("COMPLETED", completed.status());
        assertEquals(5, completed.completed());

        // Verify processor was called ONLY for pages 3 and 5!
        verify(processor, never()).processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any());
        verify(processor, never()).processBaseline(eq(bookId), eq(2), anyString(), anyString(), anyBoolean(), any());
        verify(processor, times(1)).processBaseline(eq(bookId), eq(3), anyString(), anyString(), anyBoolean(), any());
        verify(processor, never()).processBaseline(eq(bookId), eq(4), anyString(), anyString(), anyBoolean(), any());
        verify(processor, times(1)).processBaseline(eq(bookId), eq(5), anyString(), anyString(), anyBoolean(), any());
    }

    @Test
    void testForceReprocessOverwritesReadyPages() throws Exception {
        // Setup all 5 pages as READY
        for (int i = 1; i <= 5; i++) {
            store.writePage(bookId, new Page(i, 600, 800, "READY", "paddle-aistudio", List.of(), List.of(), false, null, List.of()), false);
        }

        when(processor.processBaseline(eq(bookId), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int p = inv.getArgument(1);
                    return textResult(p, "Page " + p + " reprocessed");
                });

        // Submit batch job with force = true
        JobRequest request = new JobRequest("1-5", "paddle-aistudio", "auto", false, true, false);
        jobs.submit(bookId, request);

        await(() -> {
            Job j = store.readJob(bookId);
            return j != null && ("COMPLETED".equals(j.status()) || "COMPLETED_WITH_ERRORS".equals(j.status()));
        });

        Job completed = store.readJob(bookId);
        assertEquals("COMPLETED", completed.status());
        assertEquals(5, completed.completed());

        // With force = true, all 5 pages should be processed
        for (int i = 1; i <= 5; i++) {
            verify(processor, times(1)).processBaseline(eq(bookId), eq(i), anyString(), anyString(), anyBoolean(), any());
        }
    }

    @Test
    void testConfigLeaseChangeSafetyDuringBatch() throws Exception {
        store.writePage(bookId, Page.pending(1, 600, 800), false);
        store.writePage(bookId, Page.pending(2, 600, 800), false);

        when(processor.processBaseline(eq(bookId), anyInt(), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    int p = inv.getArgument(1);
                    Thread.sleep(50);
                    return textResult(p, "Page " + p + " done");
                });

        JobRequest request = new JobRequest("1-2", "paddle-aistudio", "auto", false, false, false);
        jobs.submit(bookId, request);

        // While running, job holds Settings lease: updating settings must be safely rejected with CONFLICT!
        Thread.sleep(20);
        ApiException ex = assertThrows(ApiException.class, () ->
                settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"accessToken\":\"new-token\"}}}")));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.status());
        assertTrue(ex.getMessage().contains("当前有 OCR 或决策任务排队、运行或取消中，请完成后再保存设置"));

        // Wait for job to complete
        await(() -> {
            Job j = store.readJob(bookId);
            return j != null && "COMPLETED".equals(j.status());
        });

        Job completed = store.readJob(bookId);
        assertEquals("COMPLETED", completed.status());
        assertEquals(2, completed.completed());

        // After job finishes and lease is released, settings update succeeds!
        assertFalse(settings.busy());
        settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"accessToken\":\"new-token\"}}}"));
        assertEquals("new-token", settings.state().paddleAccessToken());
    }
}
