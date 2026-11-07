package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OptimizationUsageLedgerPaginationTest {
    @TempDir Path data;

    @Test void legacyRefreshPreservesNanosecondsAndCannotRepeatTheCursorEntry() throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var app = TestConfigs.config(data, "", "");
        var settings = new SettingsService(app, new PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new QwenAssistProperties(), new DecisionProperties(), json);
        var firstInstant = Instant.parse("2026-11-07T05:09:14.110658318Z");
        var secondInstant = firstInstant.plusSeconds(1);
        var book = UUID.randomUUID().toString();
        var books = new BookStore(app, json);
        try {
            books.createBookDirectory(book);
            var writer = new UsageLedger(books, settings, json);
            String firstId, secondId;
            try (var time = mockStatic(Instant.class, CALLS_REAL_METHODS);
                 var context = UsageContext.open(book, 1, "OCR_PAGE")) {
                time.when(Instant::now).thenReturn(firstInstant);
                firstId = writer.start("ppocr", "PP-OCRv6");
                time.when(Instant::now).thenReturn(secondInstant);
                secondId = writer.start("ppocr", "PP-OCRv6");
            }

            var reader = new UsageLedger(books, settings, json);
            var first = reader.view(book, 0, 1, "2030-01-01T00:00:00Z");
            assertEquals(firstId, rows(first).get(0).get("id"));
            assertEquals(firstInstant, rows(first).get(0).get("createdAt"));
            assertNotNull(first.get("nextCursor"));
            var second = reader.view(book, 0, 1, (String) first.get("asOf"), (String) first.get("nextCursor"));
            assertEquals(List.of(secondId), rows(second).stream().map(row -> row.get("id")).toList());
            assertEquals(secondInstant, rows(second).get(0).get("createdAt"));
            assertNull(second.get("nextCursor"));
            assertEquals(firstInstant, rows(reader.view(book, 0, 2, "2030-01-01T00:00:00Z")).get(0).get("createdAt"));
        } finally { books.close(); }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("entries");
    }
}
