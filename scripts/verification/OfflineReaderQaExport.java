import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.BookService;
import studio.bookhtml.service.ExportService;
import studio.bookhtml.service.PdfService;
import studio.bookhtml.store.BookStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Isolated real export fixture. Does not start a server or call an OCR provider. */
public class OfflineReaderQaExport {
    public static void main(String[] args) throws Exception {
        Path data = Path.of(args[0]);
        Path output = Path.of(args[1]);
        var config = new AppProperties(data, 10, 100, 600, "", "", "", "", 1, "", "", "", 1, false);
        var json = new ObjectMapper().findAndRegisterModules();
        String id = "bbbbbbbb-2222-2222-2222-222222222222";
        BookStore store = new BookStore(config, json);
        try {
            store.createBookDirectory(id);
            try (PDDocument pdf = new PDDocument()) {
                for (int n = 1; n <= 14; n++) pdf.addPage(new PDPage(new PDRectangle(300, 400)));
                pdf.save(store.pdf(id).toFile());
            }
            store.writeBook(new Book(id, "离线完整性样本", "fixture.pdf", 14, Instant.EPOCH, Instant.EPOCH, 14, 0));
            for (int n = 1; n <= 14; n++) {
                Block heading = new Block("h" + n, "heading", 0, new double[]{.1,.1,.8,.1}, "horizontal-tb",
                        "第 " + n + " 章", "第 " + n + " 章", .99, false, false, 2, "test", List.of(), null, null);
                Block body = new Block("b" + n, "text", 1, new double[]{.1,.25,.8,.4}, "horizontal-tb",
                        "原文".repeat(n == 14 ? 1500 : 70) + "獨特繁體詞" + n,
                        "正文".repeat(n == 14 ? 1500 : 70) + "独特简体词" + n,
                        .99, n == 1, false, null, "test", List.of(), null, null,
                        n == 1 ? List.of(new ContentIssue("issue-1", "suspected", 0, 2, 0, 2, "合成疑点", false, null, null)) : List.of());
                var blocks = new ArrayList<>(List.of(heading, body));
                if (n == 14) blocks.add(new Block("h14-lower", "heading", 2, new double[]{.1,.8,.8,.08}, "horizontal-tb",
                        "第十四章 后段", "第十四章 后段", .99, false, false, 2, "test", List.of(), null, null));
                store.writePage(id, new Page(n, 300, 400, "READY", "test", blocks, List.of(), false, null), false);
            }
            PdfService pdf = new PdfService(config);
            ExportService exports = new ExportService(new BookService(store, pdf, config), store, pdf, json);
            try (var stream = Files.newOutputStream(output)) { exports.writeZip(id, stream); }
        } finally { store.close(); }
    }
}
