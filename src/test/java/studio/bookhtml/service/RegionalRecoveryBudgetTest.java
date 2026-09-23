package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RegionalRecoveryBudgetTest {
    static BufferedImage white() {
        var image = new BufferedImage(800, 1000, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 800, 1000);
        g.dispose();
        return image;
    }

    static BufferedImage manuscript() {
        var image = white();
        var g = image.createGraphics();
        // Add dark watermark
        g.setColor(Color.DARK_GRAY);
        g.fillOval(220, 290, 540, 560);
        // Add text strokes
        g.setColor(Color.BLACK);
        for (int x = 50; x < 750; x += 58) {
            for (int y = 55; y < 940; y += 45) {
                g.drawLine(x, y, x + 17, y + 6);
                g.drawLine(x + 9, y - 4, x + 9, y + 25);
                g.drawLine(x, y + 21, x + 19, y + 21);
            }
        }
        g.dispose();
        return image;
    }

    static Block text(String id, String value, double[] box) {
        return new Block(id, "text", 0, box, "horizontal-tb", value, value, 0.9, true, false, null, "paddle", List.of(id), null, null);
    }

    static Block figure() {
        return new Block("raw", "figure", 0, new double[]{0, 0, 1, 1}, "horizontal-tb", "", "", null, true, false, null, "paddle", List.of("raw"), null, null);
    }

    @Test
    void recoverWithFullBudgetAttemptsUpToFourStarts() throws Exception {
        BufferedImage image = manuscript();
        AtomicInteger physicalStarts = new AtomicInteger();

        OcrTextRecovery.Result result = OcrTextRecovery.recover(image, List.of(figure()), "auto", () -> false, 4, (crop, layout, stop) -> {
            physicalStarts.incrementAndGet();
            return List.of(text("b" + physicalStarts.get(), "已恢复文字", new double[]{.1, .1, .8, .8}));
        });

        assertNotNull(result);
        assertTrue(physicalStarts.get() <= 4, "逻辑区域启动次数不能超过 4 次");
        assertNotNull(result.warning());
        assertTrue(result.warning().contains(OcrTextRecovery.RECOVERED));
        assertTrue(result.blocks().stream().anyMatch(b -> "已恢复文字".equals(b.original())));
    }

    @Test
    void budgetExhaustedRetainsRemainingRegionsAsUnresolvedWithoutDiscardingResults() throws Exception {
        BufferedImage image = manuscript();
        AtomicInteger physicalStarts = new AtomicInteger();

        // Budget is strictly 1 start
        OcrTextRecovery.Result result = OcrTextRecovery.recover(image, List.of(figure()), "auto", () -> false, 1, (crop, layout, stop) -> {
            physicalStarts.incrementAndGet();
            return List.of(text("b1", "首个区域文字", new double[]{.1, .1, .8, .8}));
        });

        assertEquals(1, physicalStarts.get(), "预算耗尽后不得再发起新的物理调用");
        assertNotNull(result);
        assertTrue(result.blocks().stream().anyMatch(b -> "首个区域文字".equals(b.original())), "已恢复文字必须保留");
        // Remaining unattempted regions must be marked as ocr-region-unresolved
        assertTrue(result.blocks().stream().anyMatch(b -> "ocr-region-unresolved".equals(b.source())),
                "未执行的区域必须保留为 ocr-region-unresolved 证据，不能被抹去或伪造空白");
    }

    @Test
    void zeroBudgetThrowsOcrEmptyUnresolvedWhenNoTextFound() {
        BufferedImage image = manuscript();
        OcrException ex = assertThrows(OcrException.class, () ->
                OcrTextRecovery.recover(image, List.of(figure()), "auto", () -> false, 0, (crop, layout, stop) -> {
                    fail("预算为 0 不得执行识别");
                    return List.of();
                }));

        assertTrue(ex.getMessage().contains("[OCR_EMPTY_UNRESOLVED]"), "零预算且无字时必须抛出 OCR_EMPTY_UNRESOLVED");
    }

    @Test
    void originalImageEvidenceRetainedWithoutWatermarkErasure() throws Exception {
        BufferedImage image = manuscript();
        OcrTextRecovery.Result result = OcrTextRecovery.recover(image, List.of(figure()), "horizontal", () -> false, 4, (crop, layout, stop) ->
                List.of(text("b", "正文", new double[]{.1, .1, .8, .8}))
        );

        // Raw input blocks are retained in evidence
        assertTrue(result.blocks().stream().anyMatch(b -> "raw".equals(b.id())), "原始 figure 块必须作为证据保留");
    }
}
