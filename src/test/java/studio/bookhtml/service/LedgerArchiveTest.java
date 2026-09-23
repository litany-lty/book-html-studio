package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerArchiveTest {

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
    void adjustmentAdjustsTokensWithoutIncreasingRequestCount() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            String callId;
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                callId = ledger.start("ppocr", "PP-OCRv6");
                ledger.usage(callId, 100L, 50L);
                ledger.succeeded(callId);
            }

            Map<String, Object> view1 = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> t1 = (Map<String, Object>) view1.get("totals");
            assertEquals(1L, t1.get("requests"));
            assertEquals(BigInteger.valueOf(100), t1.get("inputTokens"));
            assertEquals(BigInteger.valueOf(50), t1.get("outputTokens"));

            // Record adjustment (+20 input, +10 output)
            ledger.recordAdjustment(bookId, callId, 20L, 10L, "Correction for late invoice");

            Map<String, Object> view2 = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> t2 = (Map<String, Object>) view2.get("totals");
            assertEquals(1L, t2.get("requests"), "Adjustment must not increment physical request count");
            assertEquals(BigInteger.valueOf(120), t2.get("inputTokens"));
            assertEquals(BigInteger.valueOf(60), t2.get("outputTokens"));
        } finally {
            books.close();
        }
    }

    @Test
    void archivingPreservesUnknownDebts() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            // Record enough to seal at least one segment
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 0; i < 260; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    if (i == 0) {
                        // Leave first call as unknown debt
                    } else {
                        ledger.succeeded(id);
                    }
                }
            }

            Path v2Dir = dir.resolve("books").resolve(bookId).resolve("usage-v2");
            Path archivesDir = v2Dir.resolve("archives");

            // Attempt archiving older records
            ledger.archive(bookId, Instant.now().plusSeconds(3600));

            // View still preserves the pending/unknown call
            Map<String, Object> view = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) view.get("totals");
            assertEquals(260L, totals.get("requests"));
            assertEquals(1L, totals.get("pending"));
            assertEquals(259L, totals.get("success"));
        } finally {
            books.close();
        }
    }
}
