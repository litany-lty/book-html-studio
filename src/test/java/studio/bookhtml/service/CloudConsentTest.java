package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.CreateConsentRequest;
import studio.bookhtml.api.ReadingPolicyUpdateRequest;
import studio.bookhtml.api.RevokeConsentRequest;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.CloudConsent;
import studio.bookhtml.domain.ReadingPolicy;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CloudConsentStore;
import studio.bookhtml.store.OperationEpochStore;
import studio.bookhtml.store.ReadingPolicyStore;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CloudConsentTest {
    @TempDir Path dataDir;
    private ObjectMapper json;
    private BookStore store;
    private CloudConsentStore consentStore;
    private ReadingPolicyStore policyStore;
    private OperationEpochStore epochStore;
    private CloudConsentService consentService;

    @BeforeEach
    void setUp() throws Exception {
        json = new ObjectMapper().findAndRegisterModules();
        AppProperties props = TestConfigs.config(dataDir, "", "");
        store = new BookStore(props, json);
        consentStore = store.consentStore();
        policyStore = store.policyStore();
        epochStore = store.epochStore();
        consentService = new CloudConsentService(consentStore, policyStore, epochStore);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void createAndPersistBookScopedConsent() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        ReadingPolicy initialPolicy = consentService.getReadingPolicy("user-1");
        assertEquals(1L, initialPolicy.policyRevision());

        CreateConsentRequest req = new CreateConsentRequest(
                "op-001",
                initialPolicy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"),
                "AUTO_CURRENT",
                1, 2,
                true, false, false, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(1000),
                null
        );

        CloudConsent consent = consentService.createConsent("user-1", req);
        assertNotNull(consent.consentId());
        assertEquals("user-1", consent.subjectId());
        assertEquals(bookId, consent.scope().bookId());
        assertTrue(consent.isValid());
        assertTrue(consent.permitsBook(bookId));
        assertFalse(consent.permitsBook("other-book"));
        assertTrue(consent.permitsProvider("paddle-aistudio"));
        assertFalse(consent.permitsProvider("qwen"));
        assertTrue(consent.permitsEnhance(false));
        assertFalse(consent.permitsEnhance(true)); // adjacent not allowed

        // Verify ReadingPolicy updated with consent summary
        ReadingPolicy updatedPolicy = consentService.getReadingPolicy("user-1");
        assertTrue(updatedPolicy.policyRevision() > initialPolicy.policyRevision());
        assertEquals(1, updatedPolicy.validConsents().size());
        assertEquals(consent.consentId(), updatedPolicy.validConsents().get(0).consentId());

        // Verify authorization check
        assertDoesNotThrow(() -> consentService.validateAuthorization("user-1", bookId, "paddle-aistudio", false, false));
        assertThrows(ApiException.class, () -> consentService.validateAuthorization("user-1", "other-book", "paddle-aistudio", false, false));
        assertThrows(ApiException.class, () -> consentService.validateAuthorization("user-1", bookId, "qwen", false, false));
        assertThrows(ApiException.class, () -> consentService.validateAuthorization("user-1", bookId, "paddle-aistudio", false, true)); // regional recovery not allowed
    }

    @Test
    void revocationImmediatelyDeniesNewOutboundRequests() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        ReadingPolicy policy = consentService.getReadingPolicy("user-1");
        CreateConsentRequest req = new CreateConsentRequest(
                "op-002",
                policy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio"),
                "AUTO_CURRENT",
                1, 2,
                true, false, false, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(1000),
                null
        );

        CloudConsent consent = consentService.createConsent("user-1", req);
        assertTrue(consent.isValid());
        assertDoesNotThrow(() -> consentService.validateAuthorization("user-1", bookId, "paddle-aistudio", false, false));

        // Revoke consent
        ReadingPolicy currentPolicy = consentService.getReadingPolicy("user-1");
        CloudConsent revoked = consentService.revokeConsent("user-1", consent.consentId(), currentPolicy.policyRevision(), "op-revoke-1");
        assertNotNull(revoked.revokedAt());
        assertFalse(revoked.isValid());

        // Subsequent authorization checks fail immediately
        assertThrows(ApiException.class, () -> consentService.validateAuthorization("user-1", bookId, "paddle-aistudio", false, false));

        // Attempting to revoke with another subject fails with 403
        assertThrows(ApiException.class, () -> consentService.revokeConsent("attacker", consent.consentId(), null, "op-hack"));
    }

    @Test
    void consentSurvivesServerRestart() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        ReadingPolicy policy = consentService.getReadingPolicy("user-persist");
        CreateConsentRequest req = new CreateConsentRequest(
                "op-persist",
                policy.policyRevision(),
                CloudConsent.Scope.book(bookId),
                List.of("paddle-aistudio", "ppocr"),
                "AUTO_CURRENT",
                1, 2,
                true, false, true, false,
                1, 8,
                CloudConsent.MonetaryLimits.cny(5000),
                null
        );

        CloudConsent created = consentService.createConsent("user-persist", req);
        assertTrue(created.isValid());

        // Close store and reopen from same directory
        store.close();
        AppProperties props = TestConfigs.config(dataDir, "", "");
        store = new BookStore(props, json);
        consentService = new CloudConsentService(store.consentStore(), store.policyStore(), store.epochStore());

        CloudConsent loaded = consentService.findActiveConsent("user-persist", bookId);
        assertNotNull(loaded);
        assertEquals(created.consentId(), loaded.consentId());
        assertTrue(loaded.isValid());
        assertTrue(loaded.permitsProvider("ppocr"));
        assertTrue(loaded.permitsRegionalRecovery());
    }

    @Test
    void policyRevisionConflictDetection() throws Exception {
        ReadingPolicy policy = consentService.getReadingPolicy("user-rev");
        long currentRev = policy.policyRevision();

        // Stale expectedRevision throws 409 Conflict
        assertThrows(ApiException.class, () -> consentService.updateReadingPolicy("user-rev",
                new ReadingPolicyUpdateRequest(currentRev - 1, new ReadingPolicy.WindowConfig(2, 3, "MANUAL"))));

        // Correct revision succeeds and increments revision
        ReadingPolicy updated = consentService.updateReadingPolicy("user-rev",
                new ReadingPolicyUpdateRequest(currentRev, new ReadingPolicy.WindowConfig(2, 3, "MANUAL")));
        assertEquals(currentRev + 1, updated.policyRevision());
        assertEquals(2, updated.defaultWindow().preloadBefore());
        assertEquals(3, updated.defaultWindow().preloadAfter());
    }
}
