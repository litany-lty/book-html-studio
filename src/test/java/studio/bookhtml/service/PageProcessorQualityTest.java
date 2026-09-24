package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PageProcessorQualityTest {
    @TempDir Path temp;

    private static Block nativeBlock(String text) {
        return new Block("native-0", "text", 0, new double[]{.1, .1, .02, .02}, "horizontal-tb",
                text, text, null, true, false, null, "native", null, null, null);
    }

    private static BufferedImage inky(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h / 2);
        g.dispose();
        return image;
    }

    private static BufferedImage white(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    @Test void hybridPageFallsBackToOcrInsteadOfMissingScan() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        TesseractService local = mock(TesseractService.class);
        CloudOcrPipeline qwen = mock(CloudOcrPipeline.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        var mini = mock(MiniMaxVisionClient.class);
        var assist = mock(QwenLayoutClient.class);
        var toc = mock(QwenTocRecoveryService.class);
        PageProcessor processor = new PageProcessor(store, pdf, nativeText, local, qwen, paddle, mini, assist, toc,
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
        Path file = temp.resolve("hybrid.pdf");
        Files.writeString(file, "pdf");
        when(store.pdf("book")).thenReturn(file);
        when(store.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.of(List.of(nativeBlock("甲"))));
        BufferedImage preview = inky(600, 800);
        BufferedImage ocrImage = inky(1200, 1600);
        when(pdf.render(file, 1, 900)).thenReturn(preview);
        when(pdf.renderForOcr(file, 1)).thenReturn(ocrImage);
        Block ocr = new Block("paddle-1", "text", 0, new double[]{.1, .1, .5, .5}, "horizontal-tb",
                "扫描正文内容", "扫描正文内容", 0.9, false, false, null, "paddle", List.of("paddle-1"), null, new double[]{0, 0, 100, 100});
        when(paddle.recognize(eq(ocrImage), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(ocr));
        Page page = processor.process("book", 1, "paddle", "auto", false, false, () -> false).page();
        try {
            assertEquals("paddle", page.provider());
            assertTrue(page.warnings().stream().anyMatch(w -> w.contains("覆盖不足")));
            assertTrue(page.blocks().stream().anyMatch(b -> b.id().equals("paddle-1")));
        } finally {
            preview.flush();
            ocrImage.flush();
        }
    }

    @Test void trueBlankReturnsReadyEmptyInsteadOfFailed() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        TesseractService local = mock(TesseractService.class);
        CloudOcrPipeline qwen = mock(CloudOcrPipeline.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        PageProcessor processor = new PageProcessor(store, pdf, nativeText, local, qwen, paddle,
                mock(MiniMaxVisionClient.class), mock(QwenLayoutClient.class), mock(QwenTocRecoveryService.class),
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
        Path file = temp.resolve("blank.pdf");
        Files.writeString(file, "pdf");
        when(store.pdf("book")).thenReturn(file);
        when(store.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());
        BufferedImage blank = white(600, 800);
        when(pdf.renderForOcr(file, 1)).thenReturn(blank);
        Block empty = new Block("paddle-1", "text", 0, new double[]{.1, .1, .5, .2}, "horizontal-tb", "   ", "   ", null, true, false, null, "paddle", List.of("paddle-1"), null, new double[]{0, 0, 50, 20});
        when(paddle.recognize(eq(blank), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(empty));
        Page page = processor.process("book", 1, "paddle", "auto", false, false, () -> false).page();
        try {
            assertEquals("READY", page.status());
            assertTrue(page.provider().contains("blank"));
            assertTrue(page.blocks().isEmpty());
            assertTrue(page.warnings().stream().anyMatch(w -> w.contains("近空白页")));
        } finally {
            blank.flush();
        }
    }

    @Test void assistSourceLossFallsBackWithWarning() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        QwenLayoutClient assist = mock(QwenLayoutClient.class);
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class);
        when(toc.recover(any(), any(), any())).thenReturn(new QwenTocRecoveryService.RecoveryResult(List.of(), false, false, false, null));
        when(assist.configured()).thenReturn(true);
        PageProcessor processor = new PageProcessor(store, pdf, nativeText, mock(TesseractService.class), mock(CloudOcrPipeline.class),
                paddle, mock(MiniMaxVisionClient.class), assist, toc,
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
        Path file = temp.resolve("gate.pdf");
        Files.writeString(file, "pdf");
        when(store.pdf("book")).thenReturn(file);
        when(store.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());
        BufferedImage image = white(600, 800);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(50, 50, 500, 100);
        g.dispose();
        when(pdf.renderForOcr(file, 1)).thenReturn(image);
        Block a = new Block("paddle-a", "text", 0, new double[]{.1, .1, .3, .2}, "horizontal-tb", "甲乙丙丁", "甲乙丙丁", 0.9, false, false, null, "paddle", List.of("paddle-a"), null, new double[]{0, 0, 30, 20});
        Block b = new Block("paddle-b", "text", 1, new double[]{.1, .4, .3, .2}, "horizontal-tb", "戊己庚辛", "戊己庚辛", 0.9, false, false, null, "paddle", List.of("paddle-b"), null, new double[]{0, 0, 30, 20});
        when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("paddle"), any())).thenReturn(List.of(a, b));
        // 辅助丢失 b，只返回 a
        Block a2 = new Block("paddle-a", "text", 0, new double[]{.1, .1, .3, .2}, "horizontal-tb", "甲乙丙丁", "甲乙丙丁", 0.9, false, false, null, "paddle", List.of("paddle-a"), "建议", new double[]{0, 0, 30, 20});
        when(assist.assist(any(), any(), any(), any())).thenReturn(List.of(a2));
        ObjectMapper json = new ObjectMapper();
        // encodeWithin 需要真实 pdf.png；mock CloudOcrPipeline
        CloudOcrPipeline qwenPipe = mock(CloudOcrPipeline.class);
        when(qwenPipe.encodeWithin(any())).thenReturn(new CloudOcrPipeline.Encoded(new byte[]{1, 2, 3}, 100, 100));
        PageProcessor processor2 = new PageProcessor(store, pdf, nativeText, mock(TesseractService.class), qwenPipe,
                paddle, mock(MiniMaxVisionClient.class), assist, toc,
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
        Page page = processor2.process("book", 1, "paddle", "auto", false, true, () -> false).page();
        try {
            assertTrue(page.blocks().stream().anyMatch(x -> x.id().equals("paddle-b")), "丢失来源不得悄悄交付");
            assertTrue(page.warnings().stream().anyMatch(w -> w.contains("来源校验失败")));
        } finally {
            image.flush();
        }
        assertNotNull(json);
    }

    @Test void processAutomaticallyInvokesComprehensibilityCheckWhenConfigured() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        CloudOcrPipeline qwen = mock(CloudOcrPipeline.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        ParagraphComprehensibilityService compService = mock(ParagraphComprehensibilityService.class);
        when(compService.configured()).thenReturn(true);

        PageProcessor processor = new PageProcessor(store, pdf, nativeText, mock(TesseractService.class), qwen,
                paddle, mock(MiniMaxVisionClient.class), mock(QwenLayoutClient.class), mock(QwenTocRecoveryService.class),
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
        processor.setComprehensibilityService(compService);

        Path file = temp.resolve("comp.pdf");
        Files.writeString(file, "pdf");
        when(store.pdf("book-comp")).thenReturn(file);
        when(store.readPage("book-comp", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));

        Block block = new Block("native-0", "text", 0, new double[]{.1, .1, .5, .5}, "horizontal-tb",
                "这是一段测试段落内容，用于验证自动自检。".repeat(15), "这是一段测试段落内容，用于验证自动自检。".repeat(15),
                null, true, false, null, "native", null, null, null);
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.of(List.of(block)));
        when(compService.checkPage(eq("book-comp"), eq(1), any(), any())).thenReturn(List.of(block));

        processor.process("book-comp", 1, "native", "auto", false, false, () -> false);
        verify(compService, times(1)).checkPage(eq("book-comp"), eq(1), any(), any());
    }
}
