package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerAdmissionScaleTest {

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
    void appendLatencyDoesNotScaleLinearlyWithHistorySize() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            int totalRecords = 600;
            long first100Start = System.nanoTime();
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 0; i < 100; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }
            long first100Duration = System.nanoTime() - first100Start;

            // Fill middle records
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 100; i < totalRecords - 100; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }

            // Measure last 100 records
            long last100Start = System.nanoTime();
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = totalRecords - 100; i < totalRecords; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }
            long last100Duration = System.nanoTime() - last100Start;

            // In an O(1) amortized WAL, last 100 operations should not be 10x slower than the first 100
            // (whereas O(N) file listing of 600 files would exhibit substantial IO amplification)
            double ratio = (double) last100Duration / (double) Math.max(1, first100Duration);
            assertTrue(ratio < 10.0, "Append latency scaled too much with history: ratio = " + ratio);

            Map<String, Object> view = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) view.get("totals");
            assertEquals((long) totalRecords, totals.get("requests"));
            assertEquals((long) totalRecords, totals.get("success"));
        } finally {
            books.close();
        }
    }
}
