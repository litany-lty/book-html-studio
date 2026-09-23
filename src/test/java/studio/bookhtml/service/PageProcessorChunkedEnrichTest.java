package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * U5：enrich 分组接入。开关开且装配齐走分组，失败回退整页，开关关不碰协调器。
 * 合成图像夹具，不调用模型。
 */
class PageProcessorChunkedEnrichTest {
    private QwenLayoutClient layoutClient;
    private QwenTocRecoveryService tocClient;

    private static Block text(String id, int order, String content) {
        return new Block(id, "text", order, new double[]{.1, .1 + order * .1, .5, .05},
                "horizontal-tb", content, content, 0.9, false, false, null, "paddle",
                List.of(id), null, new double[]{10, 20, 50, 10}, List.of());
    }

    private static Page baseline() {
        List<Block> blocks = List.of(text("b1", 0, "甲乙丙丁之一"), text("b2", 1, "甲乙丙丁之二"));
        return new Page(1, 600, 800, "READY", "paddle-aistudio", blocks, List.of(), false, null, blocks);
    }

    private PageProcessor processor(QwenAssistProperties assistConfig,
                                    QwenAssistCoordinator coordinator,
                                    BufferedImage image) throws Exception {
        BookStore store = mock(BookStore.class);
        when(store.pdf("book")).thenReturn(Path.of("seed.pdf"));
        PdfService pdf = mock(PdfService.class);
        when(pdf.renderForOcr(eq(Path.of("seed.pdf")), eq(1))).thenReturn(image);
        NativeTextExtractor nativeText = mock(NativeTextExtractor.class);
        TesseractService local = mock(TesseractService.class);
        CloudOcrPipeline qwen = mock(CloudOcrPipeline.class);
        try {
            when(qwen.encodeWithin(any())).thenAnswer(inv -> {
                BufferedImage source = inv.getArgument(0);
                return new CloudOcrPipeline.Encoded(new byte[]{1, 2, 3}, source.getWidth(), source.getHeight());
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        PaddleOcrPipeline paddle = mock(PaddleOcrPipeline.class);
        MiniMaxVisionClient mini = mock(MiniMaxVisionClient.class);
        QwenLayoutClient assist = mock(QwenLayoutClient.class); layoutClient=assist;
        when(assist.configured()).thenReturn(true);
        QwenTocRecoveryService toc = mock(QwenTocRecoveryService.class); tocClient=toc;
        when(toc.recover(any(), any(), any())).thenReturn(
                new QwenTocRecoveryService.RecoveryResult(List.of(), false, false, false, null));
        PageProcessor processor = new PageProcessor(store, pdf, nativeText, local, qwen, paddle,
                mini, assist, toc, new SparsePageGuard(), mock(VerticalLayoutNormalizer.class),
                mock(AssistedReviewService.class), new TraditionalConverter());
        processor.setAssistConfig(assistConfig);
        processor.setTaskPlanner(new QwenTaskPlanner(new QwenAssistProperties()));
        processor.setTextReviewClient(mock(QwenTextReviewClient.class));
        if (coordinator != null) processor.setAssistCoordinator(coordinator);
        return processor;
    }

    private static BufferedImage image() {
        BufferedImage image = new BufferedImage(500, 700, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRect(0, 0, 500, 700);
        graphics.dispose();
        return image;
    }

    private static QwenAssistProperties enabled(boolean chunked) {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setChunkedAssist(chunked);
        return config;
    }

    @Test void qw_chunkedPathUsedWhenEnabledAndAssembled() throws Exception {
        QwenAssistCoordinator coordinator = mock(QwenAssistCoordinator.class);
        when(coordinator.coordinate(any(), anyInt(), any(), any(), any(), any(), any(), anyString(),
                anyBoolean(), any())).thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    List<Block> blocks = (List<Block>) inv.getArgument(2);
                    return new QwenAssistCoordinator.CoordinateResult(blocks, "+qwen-chunked-review",
                            List.of(), 1, 0, 0);
                });
        PageProcessor processor = processor(enabled(true), coordinator, image());
        PageProcessor.EnrichResult result =
                processor.enrichBaseline("book", 1, baseline(), "paddle-aistudio", "auto", () -> false);
        assertTrue(result.actualProvider().contains("qwen-chunked-review"), "分组路径已接入");
        assertEquals(2, result.blocks().size());
    }

    @Test void qw_coordinatorFailureFallsBackToLegacyPage() throws Exception {
        QwenAssistCoordinator coordinator = mock(QwenAssistCoordinator.class);
        when(coordinator.coordinate(any(), anyInt(), any(), any(), any(), any(), any(), anyString(),
                anyBoolean(), any())).thenThrow(new OcrException("分组失败"));
        PageProcessor processor = processor(enabled(true), coordinator, image());
        // 旧整页回滚：assistWithQwen 内 qwenLayout 未配置（mock 默认 false）→ 保留基线。
        PageProcessor.EnrichResult result =
                processor.enrichBaseline("book", 1, baseline(), "paddle-aistudio", "auto", () -> false);
        assertEquals(2, result.blocks().size(), "回滚保留基线块");
        assertFalse(result.actualProvider().contains("qwen-chunked-review"));
    }

    @Test void qw_flagOffNeverTouchesCoordinator() throws Exception {
        QwenAssistCoordinator coordinator = mock(QwenAssistCoordinator.class);
        PageProcessor processor = processor(enabled(false), coordinator, image());
        processor.enrichBaseline("book", 1, baseline(), "paddle-aistudio", "auto", () -> false);
        verifyNoInteractions(coordinator);
    }

    @Test void missingLegacyConfigurationIsNotReportedAsCompletedEnhancement() throws Exception {
        PageProcessor processor=processor(enabled(false),null,image());
        when(layoutClient.configured()).thenReturn(false);
        var result=processor.enrichBaseline("book",1,baseline(),"paddle-aistudio","auto",()->false);
        assertFalse(result.complete()); assertEquals(2,result.blocks().size());
    }
    @Test void failedAndRejectedLegacyResultsKeepBaselineAndPartialStatus() throws Exception {
        for(boolean throwError:new boolean[]{true,false}) {
            PageProcessor processor=processor(enabled(false),null,image());
            if(throwError) when(layoutClient.assist(any(),any(),anyString(),any())).thenThrow(new OcrException("fixture failure"));
            else when(layoutClient.assist(any(),any(),anyString(),any())).thenReturn(List.of());
            var result=processor.enrichBaseline("book",1,baseline(),"paddle-aistudio","auto",()->false);
            assertFalse(result.complete()); assertEquals(2,result.blocks().size());
        }
    }
    @Test void failedTocRecoveryDoesNotClaimCompletionOrLaunchAnExtraWholePageCall() throws Exception {
        PageProcessor processor=processor(enabled(false),null,image());
        when(tocClient.recover(any(),any(),any())).thenReturn(
                new QwenTocRecoveryService.RecoveryResult(baseline().blocks(),true,true,false,"fixture failure"));
        var result=processor.enrichBaseline("book",1,baseline(),"paddle-aistudio","auto",()->false);
        assertFalse(result.complete()); assertEquals(2,result.blocks().size());
        verify(layoutClient,never()).assist(any(),any(),anyString(),any());
    }
}
