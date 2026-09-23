package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.api.PageReprocessRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.CloudConsent;
import studio.bookhtml.domain.Job;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.OperationEpochStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationEpochReplayTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private JobService jobs;
    private CloudConsentService consentService;
    private OperationEpochStore epochStore;
    private String bookId;
    private UUID reservation;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(dataDir, "", "");
        store = new BookStore(app, json);
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "幂等测试书", "test.pdf", 5, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        for (int n = 1; n <= 5; n++) {
            store.writePage(bookId, Page.pending(n, 600, 800), false);
        }

        BookService books = mock(BookService.class);
        when(books.get(bookId)).thenReturn(book);
        PageProcessor processor = mock(PageProcessor.class);
        jobs = new JobService(store, books, processor);

        consentService = new CloudConsentService(store.consentStore(), store.policyStore(), store.epochStore());
        epochStore = store.epochStore();
        jobs.setCloudConsentService(consentService);
        jobs.setOperationEpochStore(epochStore);

        // Authorize book for testing
        var policy = consentService.getReadingPolicy("local-owner");
        consentService.createConsent("local-owner", new CreateConsentRequest(
                "op-setup", policy.policyRevision(), CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"), "AUTO_CURRENT", 1, 2, true, false, false, false,
                1, 8, CloudConsent.MonetaryLimits.cny(1000), null));

        reservation = UUID.randomUUID();
        jobs.reserveReading(reservation, bookId);
    }

    @AfterEach
    void tearDown() {
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }

    @Test
    void replayWithSameIdAndSameFingerprintReturnsExistingJob() {
        PageReprocessRequest req1 = new PageReprocessRequest(0, "client-op-101", false, "paddle-aistudio", false);
        Job job1 = jobs.requestReprocess(reservation, bookId, 1, req1, "paddle-aistudio", "auto", false, false);
        assertNotNull(job1);

        // Replay with identical parameters
        Job job2 = jobs.requestReprocess(reservation, bookId, 1, req1, "paddle-aistudio", "auto", false, false);
        assertEquals(job1.id(), job2.id(), "同 operationId + 同参数重放必须返回相同 Job 回执");
    }

    @Test
    void replayWithSameIdAndChangedParametersReturnsConflict() {
        PageReprocessRequest req1 = new PageReprocessRequest(0, "client-op-conflict", false, "paddle-aistudio", false);
        jobs.requestReprocess(reservation, bookId, 1, req1, "paddle-aistudio", "auto", false, false);

        // Replay with changed assist parameter
        PageReprocessRequest req2 = new PageReprocessRequest(0, "client-op-conflict", false, "paddle-aistudio", true);
        ApiException ex = assertThrows(ApiException.class, () ->
                jobs.requestReprocess(reservation, bookId, 1, req2, "paddle-aistudio", "auto", false, true));
        assertEquals(HttpStatus.CONFLICT, ex.status());
        assertTrue(ex.getMessage().contains("相同操作 ID 但参数不一致"));
    }

    @Test
    void expiredEpochReturnsGone410() {
        // Explicitly supply an old/expired epoch
        PageReprocessRequest req = new PageReprocessRequest(0, "client-op-old-epoch", false, "paddle-aistudio", false, 0L);
        ApiException ex = assertThrows(ApiException.class, () ->
                jobs.requestReprocess(reservation, bookId, 1, req, "paddle-aistudio", "auto", false, false));
        assertEquals(HttpStatus.GONE, ex.status());
    }

    @Test
    void epochCapGovernancePreventsSilentDiscarding() throws Exception {
        var state = epochStore.getState(bookId);
        assertEquals(1L, state.epoch());
        assertTrue(state.activeOperations().size() <= OperationEpochStore.MAX_OPERATIONS_PER_EPOCH);
    }
}
