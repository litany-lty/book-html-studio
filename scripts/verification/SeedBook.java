import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import studio.bookhtml.domain.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;

public class SeedBook {
    static Block text(String id, int order, String text) {
        return new Block(id, "text", order, new double[]{.1, .1 + order * .1, .5, .06}, "horizontal-tb",
                text, text, 0.9, false, false, null, "local", List.of(id), null,
                new double[]{10, 20 + order * 10, 50, 10}, List.of());
    }
    public static void main(String[] a) throws Exception {
        Path data = Path.of(a[0]);
        String id = "aaaaaaaa-1111-1111-1111-111111111111";
        Path dir = data.resolve("books").resolve(id);
        Files.createDirectories(dir.resolve("pages"));
        ObjectMapper json = new ObjectMapper();
        json.findAndRegisterModules();
        json.enable(SerializationFeature.INDENT_OUTPUT);
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < 3; i++) doc.addPage(new PDPage(new PDRectangle(600, 800)));
            doc.save(dir.resolve("source.pdf").toFile());
        }
        Instant now = Instant.now();
        json.writeValue(dir.resolve("book.json").toFile(),
                new Book(id, "合成证据书", "seed.pdf", 3, now, now, 0, 0));
        ContentIssue issue = new ContentIssue("issue-1", "suspected", 0, 2, 0, 2, "合成疑点", false, "", null);
        Block b1 = new Block("b1", "text", 0, new double[]{.1, .1, .5, .06}, "horizontal-tb",
                "甲乙丙丁戊己", "甲乙丙丁戊己", 0.9, true, false, null, "local", List.of("b1"), null,
                new double[]{10, 20, 50, 10}, List.of(issue));
        Block b2 = text("b2", 1, "庚辛壬癸子丑");
        Page p1 = new Page(1, 600, 800, "READY", "local", List.of(b1, b2), List.of(), false, null,
                List.of(b1, b2), 0);
        Page p2 = new Page(2, 600, 800, "READY", "local", List.of(text("c1", 0, "寅卯辰巳午未")), List.of(), false, null,
                List.of(text("c1", 0, "寅卯辰巳午未")), 0);
        json.writeValue(dir.resolve("pages/1.json").toFile(), p1);
        json.writeValue(dir.resolve("pages/2.json").toFile(), p2);
        json.writeValue(dir.resolve("pages/3.json").toFile(), Page.pending(3, 600, 800));
        System.out.println("seeded " + id);
    }
}
