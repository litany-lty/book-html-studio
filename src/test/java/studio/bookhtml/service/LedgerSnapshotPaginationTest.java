package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerSnapshotPaginationTest {

    @TempDir
    Path tempDir;

    private Path dir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws IOException {
        dir = tempDir.toRealPath();
    }

    private AppProperties app() {
        return TestConfigs.config(dir, "", "");
    }

    private SettingsService settings() {
        return new SettingsService(app(), new PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new QwenAssistProperties(), new DecisionProperties(), json);
    }

    @Test
    void frozenSnapshotTokenProtectsAgainstConcurrentAppends() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            // Record 5 entries initially
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 0; i < 5; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }

            // View page 1: generates snapshotToken
            Map<String, Object> page1 = ledger.view(bookId, 0, 2);
            String token = (String) page1.get("snapshotToken");
            assertNotNull(token);
            assertEquals("FROZEN_LEDGER_PREFIX", page1.get("consistency"));
            @SuppressWarnings("unchecked")
            Map<String, Object> t1 = (Map<String, Object>) page1.get("totals");
            assertEquals(5L, t1.get("requests"));
            String cursor1 = (String) page1.get("nextCursor");
            assertNotNull(cursor1);

            // Concurrently add 10 more entries
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 0; i < 10; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }

            // Unpinned query sees 15 total requests
            Map<String, Object> unpinned = ledger.view(bookId, 0, 2);
            @SuppressWarnings("unchecked")
            Map<String, Object> unpinnedTotals = (Map<String, Object>) unpinned.get("totals");
            assertEquals(15L, unpinnedTotals.get("requests"));

            // Pinned query with previous token STILL sees exactly 5 requests and original items!
            Map<String, Object> page2 = ledger.view(bookId, 0, 2, null, cursor1, token);
            @SuppressWarnings("unchecked")
            Map<String, Object> t2 = (Map<String, Object>) page2.get("totals");
            assertEquals(5L, t2.get("requests"), "Pinned snapshot must not leak later concurrent appends");
            @SuppressWarnings("unchecked")
            List<?> entries2 = (List<?>) page2.get("entries");
            assertEquals(2, entries2.size());

            // Page 3 with next cursor
            String cursor2 = (String) page2.get("nextCursor");
            assertNotNull(cursor2);
            Map<String, Object> page3 = ledger.view(bookId, 0, 2, null, cursor2, token);
            @SuppressWarnings("unchecked")
            List<?> entries3 = (List<?>) page3.get("entries");
            assertEquals(1, entries3.size()); // 5 entries total: 2 + 2 + 1
            assertNull(page3.get("nextCursor"));

            // Non-existent or invalid token throws 410 GONE
            ApiException gone = assertThrows(ApiException.class, () ->
                    ledger.view(bookId, 0, 2, null, null, "invalid-token"));
            assertEquals(HttpStatus.GONE, gone.status());
        } finally {
            books.close();
        }
    }
}
