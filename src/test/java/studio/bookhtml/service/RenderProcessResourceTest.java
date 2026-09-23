package studio.bookhtml.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RenderProcessResourceTest {

    @TempDir Path temp;

    private Path blankPdf(String name) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(400, 600)));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void reclaimOrphanedOutputsCleansOnlyRenderFiles() throws IOException {
        Path tmpDir = temp.resolve("render-tmp");
        Files.createDirectories(tmpDir);

        Path orphan1 = tmpDir.resolve("render-uuid-1.png");
        Path orphan2 = tmpDir.resolve("render-uuid-2.png");
        Path keepFile = tmpDir.resolve("other-data.json");
        Path userDoc = tmpDir.resolve("my-book.pdf");

        Files.writeString(orphan1, "fake png 1");
        Files.writeString(orphan2, "fake png 2");
        Files.writeString(keepFile, "{}");
        Files.writeString(userDoc, "pdf content");

        ResourceBudgetManager manager = new ResourceBudgetManager(2, 50 * 1024 * 1024, 5_000);
        RenderBudget budget = new RenderBudget(manager);
        IsolatedPdfRender isolated = new IsolatedPdfRender(tmpDir, budget, 30, "java", "");

        isolated.reclaimOrphanedOutputs();

        assertFalse(Files.exists(orphan1), "Orphan render-1 must be deleted");
        assertFalse(Files.exists(orphan2), "Orphan render-2 must be deleted");
        assertTrue(Files.exists(keepFile), "Non-render file must be preserved");
        assertTrue(Files.exists(userDoc), "Non-render user file must be preserved");
    }

    @Test
    void workerCommandBuildsFiniteHeapAndHeadlessArgs() {
        Path pdf = temp.resolve("test.pdf");
        Path out = temp.resolve("out.png");

        var cmd = IsolatedPdfRender.workerCommand("java", "app.jar", pdf, 1, "ocr", 1800, out);
        assertTrue(cmd.contains("-Djava.awt.headless=true"), "Must be headless");
        assertTrue(cmd.stream().anyMatch(s -> s.startsWith("-Xmx")), "Must specify finite -Xmx heap");
    }
}
