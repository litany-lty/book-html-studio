package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.ReadingWindowCommand;
import studio.bookhtml.api.ReadingWindowRequest;
import studio.bookhtml.api.ReadingWindowResponse;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.config.EncryptedFileSecretStore;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MultiReaderSessionTest {

    @TempDir
    Path data;

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private BookStore store;
    private JobService jobs;
    private ReadingWindowService windows;
    private SettingsService settings;
    private BookService books;
    private PageProcessor processor;

    private Book bookA;
    private Book bookB;

    @BeforeEach
    void setup() throws Exception {
        AppProperties app = TestConfigs.config(data, "", "");
        store = spy(new BookStore(app, json));

        String idA = UUID.randomUUID().toString();
        store.createBookDirectory(idA);
        bookA = new Book(idA, "book-a", "book-a.pdf", 5, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(bookA);
        for (int n = 1; n <= 5; n++) store.writePage(idA, Page.pending(n, 600, 800), false);

        String idB = UUID.randomUUID().toString();
        store.createBookDirectory(idB);
        bookB = new Book(idB, "book-b", "book-b.pdf", 5, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(bookB);
        for (int n = 1; n <= 5; n++) store.writePage(idB, Page.pending(n, 600, 800), false);

        books = mock(BookService.class);
        when(books.get(idA)).thenReturn(bookA);
        when(books.get(idB)).thenReturn(bookB);

        processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("test-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json,
                new EncryptedFileSecretStore(data, new byte[32], "reading-window-test-fixture", "fixture"));
        jobs = new JobService(store, books, processor);
        jobs.setSettings(settings);
        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        if (windows != null) windows.close();
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    private ReadingWindowRequest request(UUID id, long sequence, int page) {
        return new ReadingWindowRequest(id, sequence, page, "paddle-aistudio", "auto", false, false, true, true);
    }

    @Test
    void testConcurrentReadingSessionsAcrossDifferentBooks() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        // Open reading window for Book A
        ReadingWindowResponse resA = windows.update(bookA.id(), request(sessionA, 1, 1));
        assertNotNull(resA);
        assertTrue(resA.enabled());
        assertEquals("SETTLING", resA.status());

        // Open reading window for Book B concurrently - must succeed without conflict!
        ReadingWindowResponse resB = windows.update(bookB.id(), request(sessionB, 1, 1));
        assertNotNull(resB);
        assertTrue(resB.enabled());
        assertEquals("SETTLING", resB.status());

        // Query both sessions
        ReadingWindowResponse getA = windows.get(bookA.id(), sessionA);
        assertEquals(sessionA, getA.sessionId());
        assertTrue(getA.enabled());

        ReadingWindowResponse getB = windows.get(bookB.id(), sessionB);
        assertEquals(sessionB, getB.sessionId());
        assertTrue(getB.enabled());

        // Advance past settle delay (1s) and tick
        clock.advance(Duration.ofSeconds(2));
        windows.tick();

        // Both sessions should be active/processing and enabled
        ReadingWindowResponse activeA = windows.get(bookA.id(), sessionA);
        ReadingWindowResponse activeB = windows.get(bookB.id(), sessionB);
        assertTrue(List.of("ACTIVE", "PROCESSING").contains(activeA.status()));
        assertTrue(List.of("ACTIVE", "PROCESSING").contains(activeB.status()));
        assertTrue(activeA.enabled());
        assertTrue(activeB.enabled());
    }

    @Test
    void testSameBookConcurrentSessionConflict() {
        UUID session1 = UUID.randomUUID();
        UUID session2 = UUID.randomUUID();

        // Start session 1 on Book A
        windows.update(bookA.id(), request(session1, 1, 1));

        // Start session 2 on Book A with different session ID -> CONFLICT
        ApiException ex = assertThrows(ApiException.class, () ->
                windows.update(bookA.id(), request(session2, 1, 1)));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(ex.getMessage().contains("已有其他书籍或标签页的阅读窗口，请先停止原窗口"));
    }

    @Test
    void testMultiTabReadOnlyCoexistenceAndTombstones() {
        UUID sessionA = UUID.randomUUID();
        UUID sessionB = UUID.randomUUID();

        windows.update(bookA.id(), request(sessionA, 1, 1));
        windows.update(bookB.id(), request(sessionB, 1, 1));

        // Stop session A
        ReadingWindowResponse stoppedA = windows.stop(bookA.id(), new ReadingWindowCommand(sessionA, 2L));
        assertEquals("STOPPING", stoppedA.status());

        // Query stopped session A returns tombstone
        ReadingWindowResponse getA = windows.get(bookA.id(), sessionA);
        assertFalse(getA.enabled());

        // Query active session B still active
        ReadingWindowResponse getB = windows.get(bookB.id(), sessionB);
        assertTrue(getB.enabled());

        // Unknown session query returns IDLE
        UUID unknown = UUID.randomUUID();
        ReadingWindowResponse getUnknown = windows.get(bookA.id(), unknown);
        assertEquals("IDLE", getUnknown.status());
        assertFalse(getUnknown.enabled());
    }

    @Test
    void testSessionLeaseExpirationAndReservationRelease() {
        UUID sessionA = UUID.randomUUID();
        windows.update(bookA.id(), request(sessionA, 1, 1));

        // Advance clock by 6 minutes (LEASE is 5 minutes)
        clock.advance(Duration.ofMinutes(6));
        windows.tick();

        ReadingWindowResponse expiredA = windows.get(bookA.id(), sessionA);
        assertFalse(expiredA.enabled());
        assertEquals("EXPIRED", expiredA.status());

        // After expiry and finish, a new session on Book A can now be opened without conflict
        UUID newSessionA = UUID.randomUUID();
        ReadingWindowResponse resNew = windows.update(bookA.id(), request(newSessionA, 1, 1));
        assertTrue(resNew.enabled());
        assertEquals(newSessionA, resNew.sessionId());
    }

    @Test
    void testSwitchingBooksExplicitStopAllowsNewSession() {
        UUID session1 = UUID.randomUUID();
        windows.update(bookA.id(), request(session1, 1, 1));

        // Explicitly stop session 1
        windows.stop(bookA.id(), new ReadingWindowCommand(session1, 2L));

        // Starting session 2 on Book A succeeds immediately
        UUID session2 = UUID.randomUUID();
        ReadingWindowResponse res2 = windows.update(bookA.id(), request(session2, 1, 1));
        assertTrue(res2.enabled());
        assertEquals(session2, res2.sessionId());
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
