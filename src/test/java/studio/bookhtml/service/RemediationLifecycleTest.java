package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.api.PageReprocessRequest;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Single-page, zero-cloud fixtures with explicit phase latches; no reading-window dispatcher races. */
class RemediationLifecycleTest {
    @TempDir Path data;
    private BookStore store;
    private JobService jobs;
    private PageProcessor processor;
    private SettingsService settings;
    private ProcessingProgressService progress;
    private String bookId;
    private UUID reservation;

    @BeforeEach void setup() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var app = TestConfigs.config(data, "", "");
        store = spy(new BookStore(app, json));
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        store.writeBook(new Book(bookId, "fixture", "fixture.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        store.writePage(bookId, result("旧可读正文").page(), false);
        processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("test-only-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json);
        jobs = new JobService(store, mock(BookService.class), processor);
        jobs.setSettings(settings);
        progress = new ProcessingProgressService();
        jobs.setProgress(progress);
        reservation = UUID.randomUUID();
        jobs.reserveReading(reservation, bookId);
    }

    @AfterEach void close() {
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    private ProcessingResult result(String content) {
        var block = new Block("b1", "text", 0, new double[]{.1, .1, .5, .06}, "horizontal-tb",
                content, content, .9, false, false, null, "paddle", List.of("b1"), null,
                new double[]{10, 20, 50, 10}, List.of());
        return new ProcessingResult(new Page(1, 600, 800, "READY", "paddle-aistudio", List.of(block),
                List.of(), false, null, List.of(block)), ProcessingResult.Category.TEXT);
    }

    private PageReprocessRequest request(String id, int revision, boolean assist, boolean overwrite) {
        return new PageReprocessRequest(revision, id, overwrite, "paddle-aistudio", assist);
    }

    private Job submit(PageReprocessRequest request) {
        return jobs.requestReprocess(reservation, bookId, 1, request, "paddle-aistudio", "auto", false, request.assistEnabled());
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "bounded condition was not reached");
    }

    @Test void acceptedReplaySurvivesPublicationAndNeverCreatesSecondAttempt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(processor.processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return result("新识别正文"); });
        Page before = store.readPage(bookId, 1);
        byte[] beforeBytes = java.nio.file.Files.readAllBytes(store.pagePath(bookId, 1));
        var request = request("same-operation", BookStore.revisionOrZero(before), false, false);
        Job accepted = submit(request);
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertArrayEquals(beforeBytes, java.nio.file.Files.readAllBytes(store.pagePath(bookId, 1)),
                    "queued/running must not change persisted content or revision");
            var attempt = jobs.attemptSnapshot().get(bookId + ":1");
            assertEquals(attempt.attemptId(), progress.latest(bookId, 1).attemptId());
            assertTrue(progress.latest(bookId, 1).canRead());
            assertEquals(accepted.id(), submit(request).id());
        } finally { release.countDown(); }
        await(() -> !jobs.readingJobActive(reservation, 1));
        assertEquals(accepted.id(), submit(request).id(), "own publication must not invalidate a known operation");
        assertEquals(store.readPage(bookId, 1).revision().intValue(), progress.latest(bookId, 1).publishedRevision());
        assertEquals("SUCCEEDED", progress.latest(bookId, 1).lifecycle());
        assertThrows(ApiException.class, () -> submit(request("same-operation", store.readPage(bookId, 1).revision(), false, false)));
        assertThrows(ApiException.class, () -> submit(request("same-operation", request.expectedRevision(), false, true)));
        assertThrows(ApiException.class, () -> submit(request("new-operation", request.expectedRevision(), false, false)));
        verify(processor, times(1)).processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any());
    }

    @Test void baselineAndEnhancedSnapshotsUseActualCommitRevisions() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(processor.processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any())).thenReturn(result("基线可读正文"));
        when(processor.enrichBaseline(eq(bookId), eq(1), any(), anyString(), anyString(), any())).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            return new PageProcessor.EnrichResult(result("增强可读正文").page().blocks(), "paddle-aistudio+qwen", List.of());
        });
        submit(request("with-assist", BookStore.revisionOrZero(store.readPage(bookId, 1)), true, false));
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(store.readPage(bookId, 1).revision().intValue(), progress.latest(bookId, 1).publishedRevision());
            assertEquals("OCR_READABLE", progress.latest(bookId, 1).contentAvailability());
        } finally { release.countDown(); }
        await(() -> !jobs.readingJobActive(reservation, 1));
        assertEquals("ENHANCED", progress.latest(bookId, 1).contentAvailability());
        assertEquals(store.readPage(bookId, 1).revision().intValue(), progress.latest(bookId, 1).publishedRevision());
    }

    @Test void initialOcrFailureAlwaysTerminatesAndJournalFailurePreventsDispatch() throws Exception {
        when(processor.processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenThrow(new IllegalStateException("synthetic failure"));
        submit(request("failure", BookStore.revisionOrZero(store.readPage(bookId, 1)), false, false));
        await(() -> !jobs.readingJobActive(reservation, 1));
        assertEquals("FAILED", progress.latest(bookId, 1).lifecycle());
        assertEquals("READY", store.readPage(bookId, 1).status());
        assertFalse(settings.busy());
        clearInvocations(processor);
        Path journalPath = store.pageAttemptsPath(bookId);
        doThrow(new IOException("synthetic disk failure")).when(store).writeSidecar(eq(journalPath), any());
        assertThrows(ApiException.class, () -> jobs.submitReserved(reservation, bookId, new JobRequest("1", "paddle-aistudio", "auto", false, true, false)));
        verifyNoInteractions(processor);
        assertFalse(jobs.readingJobActive(reservation, 1));
        assertFalse(settings.busy());
    }

    @Test void closeWaitsForPhysicalWorkerFinalizerAndRejectsNewAdmission() throws Exception {
        var entered = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(processor.processBaseline(eq(bookId), eq(1), anyString(), anyString(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    entered.countDown();
                    boolean interrupted = false;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    try {
                        while (true) {
                            try {
                                assertTrue(release.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
                                break;
                            } catch (InterruptedException cancellation) {
                                interrupted = true;
                                cancelled.countDown();
                            }
                        }
                    } finally { if (interrupted) Thread.currentThread().interrupt(); }
                    return result("取消后返回的正文");
                });
        byte[] previous = java.nio.file.Files.readAllBytes(store.pagePath(bookId, 1));
        submit(request("close-drain", BookStore.revisionOrZero(store.readPage(bookId, 1)), false, false));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        var closer = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var closing = closer.submit(jobs::close);
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            assertFalse(closing.isDone(), "close must not return while the physical worker still owns its finalizer");
            assertTrue(settings.busy());
            release.countDown();
            closing.get(5, TimeUnit.SECONDS);
            assertFalse(settings.busy());
            assertFalse(jobs.readingJobActive(reservation, 1));
            assertEquals("CANCELLED", progress.latest(bookId, 1).lifecycle());
            assertArrayEquals(previous, java.nio.file.Files.readAllBytes(store.pagePath(bookId, 1)));
            assertThrows(ApiException.class, () -> jobs.submitReserved(reservation, bookId,
                    new JobRequest("1", "paddle-aistudio", "auto", false, true, false)));
        } finally { release.countDown(); closer.shutdownNow(); }
    }

}
