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
import studio.bookhtml.store.DurableEventJournal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LedgerCheckpointRecoveryTest {

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
    void checkpointTriggeredOnSegmentSealAndReplayedOnRestart() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            // Record 260 entries to trigger segment sealing (> 256 records)
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                for (int i = 0; i < 260; i++) {
                    String id = ledger.start("ppocr", "PP-OCRv6");
                    ledger.succeeded(id);
                }
            }

            Map<String, Object> view = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) view.get("totals");
            assertEquals(260L, totals.get("requests"));
            assertEquals(260L, totals.get("success"));

            // Check that sealed directory contains at least one segment
            Path v2Dir = dir.resolve("books").resolve(bookId).resolve("usage-v2");
            Path sealedDir = v2Dir.resolve("sealed");
            assertTrue(Files.exists(sealedDir));
            long sealedCount = Files.list(sealedDir).count();
            assertTrue(sealedCount >= 1, "Expected at least 1 sealed segment");

            // Verify a new UsageLedger instance restores state from checkpoint + tail replay
            UsageLedger restarted = new UsageLedger(books, settings(), json);
            Map<String, Object> restartedView = restarted.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> restartedTotals = (Map<String, Object>) restartedView.get("totals");
            assertEquals(260L, restartedTotals.get("requests"));
            assertEquals(260L, restartedTotals.get("success"));
        } finally {
            books.close();
        }
    }

    @Test
    void preservesUnknownDebtsAndRecoversTruncatedTailOnRestart() throws Exception {
        String bookId = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(bookId);
            UsageLedger ledger = new UsageLedger(books, settings(), json);

            String unknownCallId;
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                unknownCallId = ledger.start("ppocr", "PP-OCRv6");
                // Timeout or crash: left as SENT_UNKNOWN (which maps to pending)
            }

            Map<String, Object> view = ledger.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> totals = (Map<String, Object>) view.get("totals");
            assertEquals(1L, totals.get("requests"));
            assertEquals(1L, totals.get("pending"));

            // Simulate process crash during appending to active segment: corrupt partial tail
            Path v2Dir = dir.resolve("books").resolve(bookId).resolve("usage-v2");
            Path activeDir = v2Dir.resolve("active");
            Path activeFile = Files.list(activeDir).findFirst().orElseThrow();
            try (FileChannel fc = FileChannel.open(activeFile, StandardOpenOption.WRITE)) {
                fc.position(fc.size());
                fc.write(ByteBuffer.wrap(new byte[]{0, 0, 0, 50, 0, 0})); // Truncated incomplete frame
            }

            // Restart ledger: tail recovery cleanly truncates partial frame without dropping unknown call
            UsageLedger restarted = new UsageLedger(books, settings(), json);
            Map<String, Object> restartedView = restarted.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> restartedTotals = (Map<String, Object>) restartedView.get("totals");
            assertEquals(1L, restartedTotals.get("requests"));
            assertEquals(1L, restartedTotals.get("pending"));

            // Able to append subsequent calls cleanly
            try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                String second = restarted.start("ppocr", "PP-OCRv6");
                restarted.succeeded(second);
            }

            Map<String, Object> afterView = restarted.view(bookId, 0, 10);
            @SuppressWarnings("unchecked")
            Map<String, Object> afterTotals = (Map<String, Object>) afterView.get("totals");
            assertEquals(2L, afterTotals.get("requests"));
            assertEquals(1L, afterTotals.get("pending"));
            assertEquals(1L, afterTotals.get("success"));
        } finally {
            books.close();
        }
    }
}
