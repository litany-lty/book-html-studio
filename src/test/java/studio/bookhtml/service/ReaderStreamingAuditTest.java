package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReaderStreamingAuditTest {
    @TempDir Path directory;

    @Test void latestAttemptUsesCreationNotOldEventCount() {
        var progress = new ProcessingProgressService();
        UUID old = progress.begin("a", 1, 0);
        progress.plan("a", 1, old, "CHUNK", 100);
        for (int i = 0; i < 70; i++) progress.unitDone("a", 1, old, true);
        UUID next = progress.begin("a", 1, 2);
        assertEquals(next, progress.latest("a", 1).attemptId());
        progress.unitDone("a", 1, old, true);
        assertEquals(next, progress.latest("a", 1).attemptId());
        assertNull(progress.latest("b", 1));
    }

    @Test void concurrentUnitCompletionCannotLoseCountsOrMutateTerminal() throws Exception {
        var progress = new ProcessingProgressService(); UUID attempt = progress.begin("a", 1, 0);
        progress.stage("a", 1, attempt, "REVIEW"); progress.plan("a", 1, attempt, "CHUNK", 100);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 100; i++) { final boolean ok = i % 2 == 0; work.add(pool.submit(() -> progress.unitDone("a", 1, attempt, ok))); }
            for (Future<?> future : work) future.get(3, TimeUnit.SECONDS);
            var counts = progress.latest("a", 1).units();
            assertEquals(50, counts.succeeded()); assertEquals(50, counts.failed());
            progress.finish("a", 1, attempt, "PARTIAL", "DONE", true);
            var terminal = progress.latest("a", 1);
            progress.stage("a", 1, attempt, "OCR"); progress.unitDone("a", 1, attempt, true);
            assertEquals(terminal, progress.latest("a", 1));
        } finally { pool.shutdownNow(); }
    }

    @Test void stageCheckpointDoesNotFakeProgressFromInFlightUpdates() {
        var progress = new ProcessingProgressService(); UUID attempt = progress.begin("a", 1, 0);
        var first = progress.latest("a", 1);
        progress.inFlight("a", 1, attempt, 1);
        assertEquals(first.lastProgressAt(), progress.latest("a", 1).lastProgressAt());
        assertEquals(0, progress.latest("a", 1).units().succeeded());
        progress.stage("a", 1, attempt, "REVIEW"); progress.plan("a", 1, attempt, "CHUNK", 3);
        progress.unitDone("a", 1, attempt, true);
        progress.stage("a", 1, attempt, "PUBLISHING");
        assertEquals(1, progress.latest("a", 1).units().total());
        assertEquals(0, progress.latest("a", 1).units().succeeded());
    }

    @Test void telemetryEvictsTerminalEntriesWithoutDroppingLiveWork() {
        var progress = new ProcessingProgressService();
        UUID live = progress.begin("a", 1, 0);
        for (int i = 2; i < 750; i++) {
            UUID id = progress.begin("a", i, 0);
            assertNotNull(id); progress.finish("a", i, id, "SUCCEEDED", "DONE", false);
        }
        assertNotNull(progress.snapshot("a", 1, live)); assertNull(progress.latest("a", 2));
    }

    @Test void refreshingLimitsCannotMintPermitsAndCloseIsConcurrentIdempotent() throws Exception {
        QwenAssistProperties properties = new QwenAssistProperties(); properties.setMaxConcurrentRequests(2);
        QwenRequestGate gate = new QwenRequestGate(properties);
        var one = gate.acquire(true, Duration.ofMillis(10)); var two = gate.acquire(true, Duration.ofMillis(10));
        properties.setMaxConcurrentRequests(1); gate.refresh();
        assertNull(gate.acquire(true, Duration.ofMillis(20))); one.close();
        assertEquals(1, gate.inFlight()); assertNull(gate.acquire(true, Duration.ofMillis(20)));
        Thread a = new Thread(two::close), b = new Thread(two::close); a.start(); b.start(); a.join(); b.join();
        assertEquals(0, gate.inFlight()); try(var next = gate.acquire(true, Duration.ofMillis(20))) { assertNotNull(next); }
        assertEquals(0, gate.inFlight());
    }

    @Test void coldMetadataAndColdProjectionDoNotScanTheBook() {
        BookStore store = mock(BookStore.class);
        Book book = new Book("book", "主题", "book.pdf", 10000, Instant.now(), Instant.now(), 0, 0);
        when(store.readBook("book")).thenReturn(book);
        BookService books = new BookService(store, null, TestConfigs.config(directory, "", ""));
        assertEquals(book, books.metadata("book"));
        BookPresentationService presentation = new BookPresentationService(store);
        assertNotNull(presentation.cachedProfile("book"));
        verify(store, never()).readPage(anyString(), anyInt());
    }

    @Test void contextIsBoundedBookScopedAndUsesOriginalRatherThanSuggestions() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        BookStore store = spy(new BookStore(TestConfigs.config(directory, "", ""), mapper));
        try {
            String book = UUID.randomUUID().toString(); store.createBookDirectory(book);
            store.writeBook(new Book(book, "光学研究", "a.pdf", 200, Instant.now(), Instant.now(), 0, 0));
            Block block = new Block("b", "text", 0, new double[]{.1,.2,.8,.1}, "horizontal-tb",
                    "原书可核对证据".repeat(400), "MODEL_GUESS", .95, true, false, null, "paddle", List.of("b"),
                    "DO_NOT_USE_SUGGESTION", null, List.of());
            for (int n = 1; n <= 200; n++) store.writePage(book, new Page(n, 600, 800, "READY", "paddle", List.of(block), List.of(), false, null, List.of(block)), false);
            clearInvocations(store);
            var service = new BookContextService(store); var context = service.capture(book, 100);
            assertEquals(book, context.bookId()); assertEquals("光学研究", context.bookTitle());
            assertTrue(context.excerpts().stream().mapToInt(e -> e.text().length()).sum() <= 8000);
            assertFalse(context.excerpts().stream().anyMatch(e -> e.text().contains("MODEL_GUESS") || e.text().contains("SUGGESTION")));
            verify(store, atMost(14)).readPage(eq(book), anyInt()); verify(store, never()).readPage(argThat(id -> !book.equals(id)), anyInt());
            assertEquals(context.fingerprint(), service.capture(book, 100).fingerprint());
        } finally { store.close(); }
    }

    @Test void clippingCannotSplitSurrogatePairs() {
        assertEquals("甲", BookContextService.clip("甲\uD840\uDC00乙", 2));
        assertEquals("甲\uD840\uDC00", BookContextService.clip("甲\uD840\uDC00乙", 3));
    }
}
