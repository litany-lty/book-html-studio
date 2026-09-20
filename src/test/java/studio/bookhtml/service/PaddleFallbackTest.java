package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaddleFallbackTest {
    @TempDir Path temp;

    private static BufferedImage inky(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(Color.BLACK);
        g.fillRect(50, 50, 500, 100);
        g.dispose();
        return image;
    }

    private static Block textBlock(String id, String source) {
        return new Block(id, "text", 0, new double[]{.1, .1, .3, .2}, "horizontal-tb",
                "甲乙丙丁戊己", "甲乙丙丁戊己", 0.9, false, false, null, source, List.of(id), null, new double[]{0, 0, 30, 20});
    }

    private PageProcessor processor(BookStore store, PdfService pdf, NativeTextExtractor nativeText, PaddleOcrPipeline paddle) {
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class);
        when(toc.recover(any(), any(), any())).thenReturn(new QwenTocRecoveryService.RecoveryResult(List.of(), false, false, false, null));
        QwenLayoutClient assist = mock(QwenLayoutClient.class);
        when(assist.configured()).thenReturn(false);
        return new PageProcessor(store, pdf, nativeText, mock(TesseractService.class), mock(CloudOcrPipeline.class),
                paddle, mock(MiniMaxVisionClient.class), assist, toc,
                new SparsePageGuard(), mock(VerticalLayoutNormalizer.class), mock(AssistedReviewService.class), new TraditionalConverter());
    }

    private void stubPage(BookStore store, PdfService pdf, NativeTextExtractor nativeText, BufferedImage image) throws Exception {
        Path file = temp.resolve("book.pdf");
        Files.writeString(file, "pdf");
        when(store.pdf("book")).thenReturn(file);
        when(store.readPage("book", 1)).thenReturn(new Page(1, 600, 800, "PENDING", "", List.of(), List.of(), false, null, List.of()));
        when(nativeText.extract(file, 1, "auto")).thenReturn(Optional.empty());
        when(pdf.renderForOcr(file, 1)).thenReturn(image);
    }

    @Test void quotaOnPrimaryFallsBackToPpocrWithWarning() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        when(paddle.configured("paddle")).thenReturn(true);
        when(paddle.configured("paddle-aistudio")).thenReturn(false);
        when(paddle.configured("ppocr")).thenReturn(true);
        BufferedImage image = inky(600, 800);
        when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("paddle"), any()))
                .thenThrow(new QuotaExceededException("PaddleOCR-VL 额度不足"));
        when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("ppocr"), any()))
                .thenReturn(List.of(textBlock("ppocr-line-1", "ppocr")));
        stubPage(store, pdf, nativeText, image);
        try {
            Page page = processor(store, pdf, nativeText, paddle).process("book", 1, "paddle", "auto", false, false, () -> false);
            assertEquals("READY", page.status());
            assertEquals("ppocr", page.provider());
            assertTrue(page.blocks().stream().anyMatch(b -> b.id().equals("ppocr-line-1")));
            assertTrue(page.warnings().stream().anyMatch(w -> w.contains("自动改用") && w.contains("PP-OCRv6")));
        } finally {
            image.flush();
        }
    }

    @Test void allChannelsQuotaExhaustedSurfacesOriginalError() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        when(paddle.configured(anyString())).thenReturn(true);
        BufferedImage image = inky(600, 800);
        when(paddle.recognize(eq(image), eq("auto"), eq(false), anyString(), any()))
                .thenThrow(new QuotaExceededException("额度不足"));
        stubPage(store, pdf, nativeText, image);
        try {
            assertThrows(QuotaExceededException.class,
                    () -> processor(store, pdf, nativeText, paddle).process("book", 1, "paddle", "auto", false, false, () -> false));
        } finally {
            image.flush();
        }
    }

    @Test void ordinaryFailureDoesNotFallBack() throws Exception {
        BookStore store = mock(BookStore.class);
        PdfService pdf = mock(PdfService.class);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        when(paddle.configured("paddle")).thenReturn(true);
        when(paddle.configured("ppocr")).thenReturn(true);
        BufferedImage image = inky(600, 800);
        when(paddle.recognize(eq(image), eq("auto"), eq(false), eq("paddle"), any()))
                .thenThrow(new OcrException("图片解析失败"));
        stubPage(store, pdf, nativeText, image);
        try {
            OcrException error = assertThrows(OcrException.class,
                    () -> processor(store, pdf, nativeText, paddle).process("book", 1, "paddle", "auto", false, false, () -> false));
            assertEquals("图片解析失败", error.getMessage());
            verify(paddle, never()).recognize(any(), anyString(), anyBoolean(), eq("ppocr"), any());
        } finally {
            image.flush();
        }
    }
}
