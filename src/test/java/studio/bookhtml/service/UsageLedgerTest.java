package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.decision.DecisionTransport;
import studio.bookhtml.decision.JevDecisionClient;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private AppProperties app() { return TestConfigs.config(data, "", ""); }
    private SettingsService settings() {
        return new SettingsService(app(), new PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new QwenAssistProperties(), new DecisionProperties(), json);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> totals(Map<String, Object> view) { return (Map<String, Object>) view.get("totals"); }

    @Test
    void accumulatesAcrossRerunsBooksAndRestartWithoutCountingReuseAsRequest() throws Exception {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(a); books.createBookDirectory(b);
            UsageLedger ledger = new UsageLedger(books, settings(), json);
            try (UsageContext.Scope ignored = UsageContext.open(a, 1, "OCR_PAGE")) {
                String first = ledger.start("paddle-aistudio", "PaddleOCR-VL-1.6");
                ledger.pending(first); // poll and resume retain one physical request
                ledger.succeeded(first);
                ledger.cacheReused("paddle-aistudio", "PaddleOCR-VL-1.6");
            }
            try (UsageContext.Scope ignored = UsageContext.open(a, 1, "OCR_PAGE")) {
                ledger.failed(ledger.start("ppocr", "PP-OCRv6")); // physical retry/error, not free
                ledger.succeeded(ledger.start("ppocr", "PP-OCRv6"));
            }
            try (UsageContext.Scope ignored = UsageContext.open(b, 1, "OCR_PAGE")) {
                ledger.start("ppocr", "PP-OCRv6"); // timeout remains unknown
            }
            Map<String, Object> view = ledger.view(a, 0, 50);
            assertEquals(3L, totals(view).get("requests"));
            assertEquals(1L, totals(view).get("cacheHits"));
            assertEquals(2L, totals(view).get("success"));
            assertEquals(1L, totals(view).get("failed"));
            assertEquals(0L, totals(view).get("pending"));
            assertEquals(1L, totals(ledger.view(b, 0, 50)).get("pending"));
            assertEquals(3L, totals(new UsageLedger(books, settings(), json).view(a, 0, 50)).get("requests"));
            Map<String, Object> firstPage = ledger.view(a, 0, 1);
            assertNotNull(firstPage.get("nextCursor"));
            Map<String, Object> nextPage = ledger.view(a, 0, 1, (String) firstPage.get("asOf"),
                    (String) firstPage.get("nextCursor"));
            assertNotEquals(((List<?>) firstPage.get("entries")).get(0), ((List<?>) nextPage.get("entries")).get(0));
        } finally { books.close(); }
    }

    @Test
    void snapshotsPricesKeepsUnknownAndSeparatesCurrencies() throws Exception {
        String id = UUID.randomUUID().toString();
        SettingsService settings = settings();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(id);
            UsageLedger ledger = new UsageLedger(books, settings, json);
            settings.update(json.readTree("""
                {"revision":0,"billing":{"rates":[
                  {"provider":"paddle-aistudio","model":"PaddleOCR-VL-1.6","currency":"CNY","perRequest":"0.2","inputPerMillion":"","outputPerMillion":""},
                  {"provider":"ppocr","model":"PP-OCRv6","currency":"CNY","perRequest":"","inputPerMillion":"","outputPerMillion":""},
                  {"provider":"qwen","model":"qwen3.8-max","currency":"USD","perRequest":"","inputPerMillion":"2","outputPerMillion":"4"},
                  {"provider":"jev","model":"","currency":"USD","perRequest":"","inputPerMillion":"","outputPerMillion":""}
                ]}}
                """));
            try (UsageContext.Scope ignored = UsageContext.open(id, 2, "OCR_PAGE")) {
                ledger.succeeded(ledger.start("paddle-aistudio", "PaddleOCR-VL-1.6"));
                ledger.failed(ledger.start("paddle-aistudio", "PaddleOCR-VL-1.6"));
            }
            try (UsageContext.Scope ignored = UsageContext.open(id, 2, "QWEN_LAYOUT")) {
                String attempt = ledger.start("qwen", "qwen3.8-max");
                ledger.captureUsage(attempt, json.readTree("{\"usage\":{\"input_tokens\":1000000,\"output_tokens\":500000}}"));
                ledger.failed(attempt); // semantic failure does not erase returned token usage
                ledger.start("qwen", "qwen3.8-max"); // uncertain transport
            }
            settings.update(json.readTree("""
                {"revision":1,"billing":{"rates":[
                  {"provider":"paddle-aistudio","model":"PaddleOCR-VL-1.6","currency":"CNY","perRequest":"9","inputPerMillion":"","outputPerMillion":""},
                  {"provider":"ppocr","model":"PP-OCRv6","currency":"CNY","perRequest":"","inputPerMillion":"","outputPerMillion":""},
                  {"provider":"qwen","model":"qwen3.8-max","currency":"USD","perRequest":"","inputPerMillion":"","outputPerMillion":""},
                  {"provider":"jev","model":"","currency":"USD","perRequest":"","inputPerMillion":"","outputPerMillion":""}
                ]}}
                """));
            Map<String, Object> total = totals(ledger.view(id, 0, 50));
            assertEquals(4L, total.get("requests"));
            assertEquals(2L, total.get("unpricedRequests"));
            assertEquals(3L, total.get("unknownInputTokenRequests"));
            assertEquals(List.of(Map.of("currency", "CNY", "amount", "0.2"),
                    Map.of("currency", "USD", "amount", "4")), total.get("estimatedAmounts"));
            assertEquals(List.of(), total.get("reportedAmounts"));
        } finally { books.close(); }
    }

    @Test
    void corruptLedgerBlocksNewSendAndReadAndPaginationHasFixedCutoff() throws Exception {
        String id = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(id);
            UsageLedger ledger = new UsageLedger(books, settings(), json);
            try (UsageContext.Scope ignored = UsageContext.open(id, 3, "OCR_PAGE")) {
                ledger.start("ppocr", "PP-OCRv6");
                Instant cutoff = Instant.now();
                Thread.sleep(5);
                ledger.start("ppocr", "PP-OCRv6");
                Map<String, Object> page = ledger.view(id, 0, 1, cutoff.toString());
                assertEquals(1L, totals(page).get("requests"));
                assertNull(page.get("nextOffset"));
                Files.writeString(books.bookDir(id).resolve("usage").resolve(UUID.randomUUID() + ".json"), "{bad");
                assertThrows(IOException.class, () -> ledger.start("ppocr", "PP-OCRv6"));
                assertThrows(IOException.class, () -> ledger.view(id, 0, 50));
            }
        } finally { books.close(); }
    }

    @Test
    void jevIntentPrecedesTransportAndReturnedUsageSurvivesSemanticFailure() throws Exception {
        String id = UUID.randomUUID().toString();
        BookStore books = new BookStore(app(), json);
        try {
            books.createBookDirectory(id);
            UsageLedger ledger = new UsageLedger(books, settings(), json);
            AtomicInteger sends = new AtomicInteger();
            DecisionTransport transport = (request, deadline, maxBytes, cancelled) -> {
                sends.incrementAndGet();
                assertEquals(1L, totals(ledger.view(id, 0, 50)).get("pending"));
                return new BoundedHttp.Response(200,
                        "{\"usage\":{\"input_tokens\":9,\"output_tokens\":2},\"answers\":{\"q\":{\"type\":\"noul\",\"noul\":2}}}".getBytes());
            };
            JevDecisionClient client = new JevDecisionClient(json, transport);
            client.setUsageLedger(ledger);
            try (UsageContext.Scope ignored = UsageContext.open(id, 3, "JEV_DECISION")) {
                assertThrows(JevDecisionClient.JevCallException.class,
                        () -> client.callOnce("https://api.typesafe.ai/v1/systemone", "dummy", "jev-test",
                                Map.of(), Map.of("q", new JevDecisionClient.QuestionSpec("noul", "Question?", Map.of())),
                                1_000_000_000L, 32768, 65536, () -> false));
            }
            assertEquals(1, sends.get());
            Map<String, Object> total = totals(ledger.view(id, 0, 50));
            assertEquals(1L, total.get("failed"));
            assertEquals(BigInteger.valueOf(9), total.get("inputTokens"));
            assertEquals(BigInteger.valueOf(2), total.get("outputTokens"));
            Files.writeString(books.bookDir(id).resolve("usage").resolve(UUID.randomUUID() + ".json"), "bad");
            try (UsageContext.Scope ignored = UsageContext.open(id, 3, "JEV_DECISION")) {
                assertThrows(JevDecisionClient.JevCallException.class,
                        () -> client.callOnce("https://api.typesafe.ai/v1/systemone", "dummy", "jev-test",
                                Map.of(), Map.of("q", new JevDecisionClient.QuestionSpec("noul", "Question?", Map.of())),
                                1_000_000_000L, 32768, 65536, () -> false));
            }
            assertEquals(1, sends.get());
        } finally { books.close(); }
    }
}
