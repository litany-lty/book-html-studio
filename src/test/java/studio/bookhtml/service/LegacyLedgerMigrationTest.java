package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.DecisionProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LegacyLedgerMigrationTest {

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
    void migratesLegacyDirectoryWithoutDeletingLegacySourceFiles() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            Path bookDir = dir.resolve("books").resolve(bookId);
            Path legacyUsageDir = bookDir.resolve("usage");
            Files.createDirectories(legacyUsageDir);

            // Write 3 legacy JSON entries directly to usage/
            String id1 = UUID.randomUUID().toString();
            String id2 = UUID.randomUUID().toString();
            Instant now = Instant.now();

            UsageLedger.Entry e1 = new UsageLedger.Entry(id1, bookId, now.minusSeconds(10), now.minusSeconds(10),
                    1, "OCR_PAGE", "ppocr", "PP-OCRv6", "SUCCEEDED", 100L, 50L, "UNKNOWN", null, null, null);
            UsageLedger.Entry e2 = new UsageLedger.Entry(id2, bookId, now, now,
                    1, "OCR_PAGE", "ppocr", "PP-OCRv6", "SENT_UNKNOWN", null, null, "UNKNOWN", null, null, null);

            Files.write(legacyUsageDir.resolve(id1 + ".json"), json.writeValueAsBytes(e1));
            Files.write(legacyUsageDir.resolve(id2 + ".json"), json.writeValueAsBytes(e2));

            // Start UsageLedger: triggers migration
            UsageLedger ledger = new UsageLedger(books, settings(), json);
            Map<String, Object> view = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) view.get("totals");

            assertEquals(2L, totals.get("requests"));
            assertEquals(1L, totals.get("success"));
            assertEquals(1L, totals.get("pending"));

            // Check that legacy files are NOT deleted
            assertTrue(Files.exists(legacyUsageDir.resolve(id1 + ".json")));
            assertTrue(Files.exists(legacyUsageDir.resolve(id2 + ".json")));

            // Check that usage-v2 manifest exists and is healthy
            Path v2Dir = bookDir.resolve("usage-v2");
            assertTrue(Files.exists(v2Dir.resolve("manifest.json")));

            UsageLedger.AuditResult audit = ledger.auditIntegrity(bookId);
            assertTrue(audit.healthy());
            assertNull(audit.errorMessage());

            Map<String, Object> diag = ledger.diagnose(bookId);
            assertEquals(true, diag.get("healthy"));
            assertEquals(2, diag.get("totalTrackedCalls"));
        } finally {
            books.close();
        }
    }
}
