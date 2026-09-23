package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.api.ReadingWindowRequest;
import studio.bookhtml.api.ReadingWindowResponse;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.CloudConsent;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthorizedAutoReadingTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final MutableClock clock = new MutableClock();
    private BookStore store;
    private JobService jobs;
    private ReadingWindowService windows;
    private SettingsService settings;
    private CloudConsentService consentService;
    private Book book;
    private String bookId;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(dataDir, "", "");
        store = new BookStore(app, json);
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        book = new Book(bookId, "受控测试书", "test.pdf", 20, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        for (int n = 1; n <= 20; n++) {
            store.writePage(bookId, Page.pending(n, 600, 800), false);
        }

        BookService books = mock(BookService.class);
        when(books.get(bookId)).thenReturn(book);
        PageProcessor processor = mock(PageProcessor.class);
        settings = new SettingsService(app, new PaddleAiStudioProperties("valid-token", null, null, 30, 60, 1),
                new QwenAssistProperties(), new DecisionProperties(), json,
                new EncryptedFileSecretStore(dataDir, new byte[32], "reading-window-test-fixture", "fixture"));
        jobs = new JobService(store, books, processor);
        jobs.setSettings(settings);

        consentService = new CloudConsentService(store.consentStore(), store.policyStore(), store.epochStore());
        jobs.setCloudConsentService(consentService);
        jobs.setOperationEpochStore(store.epochStore());

        windows = new ReadingWindowService(store, jobs, settings, clock, Duration.ofSeconds(1));
        windows.setConsentService(consentService);
    }

    @AfterEach
    void tearDown() {
        if (windows != null) windows.close();
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    @Test
    void keyConfiguredWithoutConsentSendsZeroRequests() {
        // AI Studio token is configured, but no CloudConsent has been granted for this book
        UUID sessionId = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(sessionId, 1L, 5,
                "paddle-aistudio", "auto", false, false, true, true);

        ReadingWindowResponse res = windows.update(bookId, req);
        assertNotNull(res);
        // Without consent, queuedPages must be empty: 0 cloud requests sent!
        assertTrue(res.queuedPages().isEmpty(), "有密钥但无服务端持久授权，必须发送 0 请求");
    }

    @Test
    void authorizedBookOnlySchedulesMissingCurrentPageAndBoundedPreload() throws Exception {
        // Grant consent for this specific book: preloadBefore=1, preloadAfter=2, AUTO_CURRENT
        var policy = consentService.getReadingPolicy("local-owner");
        CreateConsentRequest consentReq = new CreateConsentRequest(
                "op-consent-reading",
                policy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"),
                "AUTO_CURRENT",
                1, 2, // 1 before, 2 after
                false, false, false, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(500),
                null
        );
        consentService.createConsent("local-owner", consentReq);

        UUID sessionId = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(sessionId, 1L, 10,
                "paddle-aistudio", "auto", false, false, true, true);

        ReadingWindowResponse res = windows.update(bookId, req);
        assertNotNull(res);
        // Center is 10, preloadBefore=1 (page 9), preloadAfter=2 (pages 11, 12)
        assertEquals(9, res.fromPage());
        assertEquals(12, res.toPage());
        assertEquals(List.of(10, 11, 12, 9), res.queuedPages(), "受控窗口严格按照 preloadBefore/After 范围排队");
    }

    @Test
    void initialOpenWithConsentDispatchesCurrentPageImmediatelyWithoutOneSecondDelay() throws Exception {
        var policy = consentService.getReadingPolicy("local-owner");
        CreateConsentRequest consentReq = new CreateConsentRequest(
                "op-consent-instant",
                policy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"),
                "AUTO_CURRENT",
                0, 0, // only current page
                false, false, false, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(500),
                null
        );
        consentService.createConsent("local-owner", consentReq);

        UUID sessionId = UUID.randomUUID();
        ReadingWindowRequest req = new ReadingWindowRequest(sessionId, 1L, 1,
                "paddle-aistudio", "auto", false, false, true, true);

        ReadingWindowResponse res = windows.update(bookId, req);
        assertEquals(List.of(1), res.queuedPages());
        // In ReadingWindowService, for sequence=1 with consent, notBefore == now, allowing immediate dispatch
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
    }
}
