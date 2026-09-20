package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** R05：渲染/导出/OCR 临时文件隔离，清理只处理自有已结束文件。 */
class TmpIsolationTest {
    @TempDir Path temp;

    private RenderBudget quietBudget() {
        return new RenderBudget(8, Long.MAX_VALUE, 5000, () -> 0, () -> 8L * 1024 * 1024 * 1024);
    }

    private Path blankPdf(String name) throws Exception {
        Path pdf = temp.resolve(name);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(600, 800)));
            document.save(pdf.toFile());
        }
        return pdf;
    }

    private String javaBin() {
        return System.getProperty("java.home") + java.io.File.separator + "bin" + java.io.File.separator + "java";
    }

    @Test void renderPressureNeverDeletesActiveExportFile() throws Exception {
        Path renderDir = temp.resolve("data/tmp/render");
        Path exportDir = temp.resolve("data/tmp/export");
        Files.createDirectories(renderDir);
        Files.createDirectories(exportDir);
        // 渲染目录超过小配额，导出目录有活跃暂存
        Files.write(renderDir.resolve("render-old-1.png"), new byte[600]);
        Files.write(renderDir.resolve("render-old-2.png"), new byte[600]);
        byte[] activeExport = "active-export-content".getBytes(StandardCharsets.UTF_8);
        Files.write(exportDir.resolve("export-active.zip.part"), activeExport);
        IsolatedPdfRender render = new IsolatedPdfRender(renderDir, quietBudget(), 120, javaBin(),
                System.getProperty("java.class.path", ""), 512);
        BufferedImage image = render.renderIsolated(blankPdf("pressure.pdf"), 1, false, 600, 8L * 1024 * 1024, () -> false);
        try {
            assertTrue(image.getWidth() > 0);
        } finally {
            image.flush();
        }
        assertArrayEquals(activeExport, Files.readAllBytes(exportDir.resolve("export-active.zip.part")),
                "渲染清理绝不能删除活跃导出文件");
        long renderTotal = 0;
        try (var stream = Files.list(renderDir)) {
            for (Path p : stream.filter(Files::isRegularFile).toList()) renderTotal += Files.size(p);
        }
        assertTrue(renderTotal <= 512, "渲染目录必须收敛到自有配额内");
    }

    @Test void orphanReclaimDeletesOnlyOwnRenderOutputs() throws Exception {
        Path renderDir = temp.resolve("data2/tmp/render");
        Path exportDir = temp.resolve("data2/tmp/export");
        Files.createDirectories(renderDir);
        Files.createDirectories(exportDir);
        Files.write(renderDir.resolve("render-orphan.png"), new byte[]{1, 2, 3});
        Files.write(renderDir.resolve("keep.txt"), new byte[]{4});
        Files.write(exportDir.resolve("export-active.zip.part"), new byte[]{5});
        IsolatedPdfRender render = new IsolatedPdfRender(renderDir, quietBudget(), 120, javaBin(),
                System.getProperty("java.class.path", ""));
        render.reclaimOrphanedOutputs();
        assertTrue(Files.notExists(renderDir.resolve("render-orphan.png")));
        assertTrue(Files.exists(renderDir.resolve("keep.txt")));
        assertTrue(Files.exists(exportDir.resolve("export-active.zip.part")));
    }

    @Test void exportReclaimDeletesOnlyOwnStaging() throws Exception {
        Path data = temp.resolve("data3");
        AppProperties config = new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false);
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        Path exportDir = store.exportTmpDir();
        Files.createDirectories(exportDir);
        Files.write(exportDir.resolve("export-stale.zip.part"), new byte[]{9});
        Files.write(exportDir.resolve("keep.txt"), new byte[]{8});
        ExportService service = new ExportService(mock(BookService.class), store, mock(PdfService.class),
                new ObjectMapper(), null);
        service.reclaimOrphanedStaging();
        assertTrue(Files.notExists(exportDir.resolve("export-stale.zip.part")));
        assertTrue(Files.exists(exportDir.resolve("keep.txt")));
    }

    @Test void scopedTmpRootsAreDistinct() throws Exception {
        Path data = temp.resolve("data4");
        AppProperties config = new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false);
        BookStore store = new BookStore(config, new ObjectMapper().findAndRegisterModules());
        assertEquals(store.tmpDir().resolve("render"), store.renderTmpDir());
        assertEquals(store.tmpDir().resolve("export"), store.exportTmpDir());
        assertEquals(store.tmpDir().resolve("ocr"), store.ocrTmpDir());
    }
}
