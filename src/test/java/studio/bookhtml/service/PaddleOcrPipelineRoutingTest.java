package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaddleOcrPipelineRoutingTest {
    private final PaddleOcrClient baidu = mock(PaddleOcrClient.class);
    private final PaddleAiStudioClient aiStudio = mock(PaddleAiStudioClient.class);
    private final BaiduPpOcrClient ppocr = mock(BaiduPpOcrClient.class);
    private final CloudOcrPipeline encoder = mock(CloudOcrPipeline.class);
    private final PaddleOcrPipeline pipeline = new PaddleOcrPipeline(baidu, aiStudio, ppocr, encoder);

    @Test
    void selectedAiStudioChannelCallsOnlyAiStudio() throws Exception {
        BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{1}, 300, 400);
        when(encoder.encodeWithin(image)).thenReturn(encoded);
        when(aiStudio.recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class)))
                .thenReturn(List.of(block("studio", "paddle-aistudio")));

        List<Block> result = pipeline.recognize(image, "auto", false, "paddle-aistudio", () -> false);

        assertEquals("studio", result.get(0).id());
        verify(aiStudio).recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class));
        verifyNoInteractions(baidu);
        image.flush();
    }

    @Test
    void legacyBaiduCallRemainsReadableButOverloadDefaultsToAiStudio() throws Exception {
        BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{2}, 300, 400);
        when(encoder.encodeWithin(image)).thenReturn(encoded);
        when(baidu.recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class)))
                .thenReturn(List.of(block("baidu", "paddle")));
        when(aiStudio.recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class)))
                .thenReturn(List.of(block("studio", "paddle-aistudio")));

        assertEquals("baidu", pipeline.recognize(image, "auto", false, "paddle", () -> false).get(0).id());
        assertEquals("studio", pipeline.recognize(image, "auto", false, () -> false).get(0).id());

        verify(baidu).recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class));
        verify(aiStudio).recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class));
        image.flush();
    }

    @Test
    void selectedPpocrChannelCallsOnlyPpocr() throws Exception {
        BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{6}, 300, 400);
        when(encoder.encodeWithin(image)).thenReturn(encoded);
        when(ppocr.recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class)))
                .thenReturn(List.of(block("ppocr-line-1", "ppocr")));

        List<Block> result = pipeline.recognize(image, "auto", false, "ppocr", () -> false);

        assertEquals("ppocr-line-1", result.get(0).id());
        verify(ppocr).recognize(same(encoded.bytes()), eq(300), eq(400), eq("auto"), any(BooleanSupplier.class));
        verifyNoInteractions(baidu, aiStudio);
        image.flush();
    }

    @Test
    void selectedChannelFailureDoesNotFallBack() throws Exception {
        BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{3}, 300, 400);
        when(encoder.encodeWithin(image)).thenReturn(encoded);
        when(aiStudio.recognize(any(byte[].class), anyInt(), anyInt(), anyString(), any(BooleanSupplier.class)))
                .thenThrow(new OcrException("AI Studio failed"));

        OcrException error = assertThrows(OcrException.class,
                () -> pipeline.recognize(image, "auto", false, "paddle-aistudio", () -> false));

        assertEquals("AI Studio failed", error.getMessage());
        verifyNoInteractions(baidu);
        image.flush();
    }

    @Test
    void baiduFailureDoesNotFallBackToAiStudio() throws Exception {
        BufferedImage image = new BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB);
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{5}, 300, 400);
        when(encoder.encodeWithin(image)).thenReturn(encoded);
        when(baidu.recognize(any(byte[].class), anyInt(), anyInt(), anyString(), any(BooleanSupplier.class)))
                .thenThrow(new OcrException("Baidu failed"));

        OcrException error = assertThrows(OcrException.class,
                () -> pipeline.recognize(image, "auto", false, "paddle", () -> false));

        assertEquals("Baidu failed", error.getMessage());
        verifyNoInteractions(aiStudio);
        image.flush();
    }

    @Test
    void splitPagesKeepRightLeftPrefixesAndSourceRectOnAiStudioRoute() throws Exception {
        BufferedImage image = splitCandidate();
        CloudOcrPipeline.Encoded encoded = new CloudOcrPipeline.Encoded(new byte[]{4}, 200, 200);
        double[] sourceRect = {20, 10, 80, 70};
        when(encoder.encodeWithin(any(BufferedImage.class))).thenReturn(encoded);
        when(aiStudio.recognize(any(byte[].class), eq(200), eq(200), eq("vertical"), any(BooleanSupplier.class)))
                .thenReturn(List.of(new Block("line-1", "text", 7, new double[]{.1, .2, .4, .3},
                        "vertical-rl", "原文", "原文", .9, false, false, null,
                        "paddle-aistudio", List.of("line-1"), null, sourceRect)));

        List<Block> result = pipeline.recognize(image, "vertical", true, "paddle-aistudio", () -> false);

        assertEquals(List.of("R-line-1", "L-line-1"), result.stream().map(Block::id).toList());
        assertEquals(List.of("paddle-aistudio:R", "paddle-aistudio:L"), result.stream().map(Block::source).toList());
        assertArrayEquals(new double[]{.55, .2, .2, .3}, result.get(0).bbox(), 1e-9);
        assertArrayEquals(new double[]{.05, .2, .2, .3}, result.get(1).bbox(), 1e-9);
        assertArrayEquals(sourceRect, result.get(0).sourceRect());
        assertArrayEquals(sourceRect, result.get(1).sourceRect());
        verify(aiStudio, times(2)).recognize(any(byte[].class), eq(200), eq(200), eq("vertical"), any(BooleanSupplier.class));
        verifyNoInteractions(baidu);
        image.flush();
    }

    private static Block block(String id, String source) {
        return new Block(id, "text", 0, new double[]{.1, .1, .2, .2}, "horizontal-tb",
                "文", "文", .9, false, false, null, source, List.of(id), null, null);
    }

    private static BufferedImage splitCandidate() {
        BufferedImage image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 400, 200);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(20, 20, 150, 160);
            graphics.fillRect(230, 20, 150, 160);
        } finally {
            graphics.dispose();
        }
        assertTrue(CloudOcrPipeline.shouldSplit(image));
        return image;
    }
}
