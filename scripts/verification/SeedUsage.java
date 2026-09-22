import com.fasterxml.jackson.databind.ObjectMapper;
import studio.bookhtml.config.*;
import studio.bookhtml.service.UsageContext;
import studio.bookhtml.service.UsageLedger;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** Synthetic, zero-network ledger for the existing SeedBook fixture; never user data. */
public class SeedUsage {
    private static final String BOOK = "aaaaaaaa-1111-1111-1111-111111111111";
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SeedUsage <fresh SeedBook data directory>");
        Path data = Path.of(args[0]).toAbsolutePath();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        Path bookDir = data.resolve("books").resolve(BOOK);
        var book = json.readTree(Files.readAllBytes(bookDir.resolve("book.json")));
        if (!"合成证据书".equals(book.path("title").asText())
                || Files.exists(bookDir.resolve("usage")) || Files.exists(data.resolve(".settings")))
            throw new IllegalStateException("Only a fresh SeedBook fixture without settings or usage is allowed");
        AppProperties app = new AppProperties(data, 0, 0, 0, null, null, null, null, 0, null, null, null, 0, false);
        QwenAssistProperties qwen = new QwenAssistProperties(); qwen.setEnabled(false);
        SettingsService settings = new SettingsService(app,
                new PaddleAiStudioProperties("", null, null, 0, 0, 0), qwen, new DecisionProperties(), json);
        settings.update(json.valueToTree(Map.of("revision", 0, "billing", Map.of("rates", List.of(
                rate("paddle-aistudio", "PaddleOCR-VL-1.6", "CNY", "0.05", "", ""),
                rate("ppocr", "PP-OCRv6", "CNY", "0.01", "", ""),
                rate("qwen", "qa-qwen", "CNY", "", "2", "8"),
                rate("jev", "qa-jev", "USD", "", "1", "4"))))));
        BookStore books = new BookStore(app, json);
        try {
            UsageLedger ledger = new UsageLedger(books, settings, json);
            for (int i = 0; i < 56; i++) {
                String provider = i < 10 || i >= 50 ? "paddle-aistudio" : i < 20 || i >= 40 ? "ppocr" : i < 30 ? "qwen" : "jev";
                String model = Map.of("paddle-aistudio", "PaddleOCR-VL-1.6", "ppocr", "PP-OCRv6", "qwen", "qa-qwen", "jev", "qa-jev").get(provider);
                try (UsageContext.Scope ignored = UsageContext.open(BOOK, i % 3 + 1, "jev".equals(provider) ? "JEV_DECISION" : "OCR_PAGE")) {
                    if (i >= 50 && i < 55) { ledger.cacheReused(provider, model); continue; }
                    String attempt = ledger.start(provider, model);
                    if (i >= 20 && i < 40) ledger.usage(attempt, 1000L, 100L);
                    if (i < 40) ledger.succeeded(attempt);
                    else if (i < 50) ledger.failed(attempt);
                    // The last request intentionally stays SENT_UNKNOWN.
                }
            }
            System.out.println("SYNTHETIC ONLY: 56 records, 51 requests, 5 cache reuses; CNY 0.628 and USD 0.014 estimates; 11 unpriced. No network calls.");
        } finally { books.close(); }
    }
    private static Map<String, String> rate(String provider, String model, String currency, String request, String input, String output) {
        return Map.of("provider", provider, "model", model, "currency", currency,
                "perRequest", request, "inputPerMillion", input, "outputPerMillion", output);
    }
}
