package studio.bookhtml.service;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OptimizationOcrCancellationTest {
    @Test void cancelledCloudPageStopsBeforeEncodingOrCallingTheProvider() {
        var qwen = mock(QwenOcrClient.class);
        var pdf = mock(PdfService.class);
        var pipeline = new CloudOcrPipeline(qwen, pdf);
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThrows(CancelledException.class, () -> pipeline.recognize(image, "auto", false, () -> true));
        verifyNoInteractions(pdf, qwen);
    }

    @Test void cancellationDuringCloudEncodingStopsBeforeProviderAdmission() throws Exception {
        var qwen = mock(QwenOcrClient.class);
        var pdf = mock(PdfService.class);
        var cancelled = new AtomicBoolean();
        when(pdf.png(any())).thenAnswer(call -> { cancelled.set(true); return new byte[]{1}; });
        var pipeline = new CloudOcrPipeline(qwen, pdf);
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThrows(CancelledException.class, () -> pipeline.recognize(image, "auto", false, cancelled::get));
        verifyNoInteractions(qwen);
    }

    @Test void cancelledPaddlePageStopsBeforeImageEncoding() {
        var paddle = mock(PaddleOcrClient.class);
        var studio = mock(PaddleAiStudioClient.class);
        var ppocr = mock(BaiduPpOcrClient.class);
        var encoder = mock(CloudOcrPipeline.class);
        var pipeline = new PaddleOcrPipeline(paddle, studio, ppocr, encoder);
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThrows(CancelledException.class, () -> pipeline.recognize(image, "auto", false, "paddle-aistudio", () -> true));
        verifyNoInteractions(encoder, paddle, studio, ppocr);
    }
}
