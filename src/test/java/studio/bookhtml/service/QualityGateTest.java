package studio.bookhtml.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import static org.junit.jupiter.api.Assertions.*;

class QualityGateTest {
    private static Block block(String id, double[] bbox, String text) {
        return new Block(id, "text", 0, bbox, "horizontal-tb", text, text, 0.9, false, false, null, "native", List.of(id), null, null);
    }

    private static BufferedImage white(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return image;
    }

    private static BufferedImage inky(int w, int h) {
        BufferedImage image = white(w, h);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, w, h / 2);
        g.dispose();
        return image;
    }

    @Test void smallNativeWithHeavyInkPrefersOcr() {
        Block tiny = block("n0", new double[]{.1, .1, .02, .02}, "甲");
        BufferedImage image = inky(400, 400);
        try {
            assertTrue(QualityGate.shouldPreferOcr(List.of(tiny), image));
        } finally {
            image.flush();
        }
    }

    @Test void denseNativeKeepsTextLayer() {
        String text = "甲乙丙丁戊己庚辛壬癸".repeat(15);
        Block dense = block("n0", new double[]{.05, .05, .8, .8}, text);
        BufferedImage image = inky(400, 400);
        try {
            assertFalse(QualityGate.shouldPreferOcr(List.of(dense), image));
        } finally {
            image.flush();
        }
    }

    @Test void trueBlankDetectedOnWhitePage() {
        BufferedImage image = white(400, 400);
        try {
            assertTrue(QualityGate.isTrueBlank(image));
            assertFalse(QualityGate.isTrueBlank(inky(400, 400)));
        } finally {
            image.flush();
        }
    }

    @Test void sourceLossOrDuplicationDetected() {
        Block a = block("a", new double[]{0, 0, .4, .2}, "甲");
        Block b = block("b", new double[]{0, .3, .4, .2}, "乙");
        assertFalse(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(b, a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a, a)));
        assertTrue(QualityGate.hasSourceLossOrDuplication(List.of(a, b), List.of(a, b, block("c", new double[]{0, 0, .1, .1}, "丙"))));
    }

    @Test void figureOnlyDetected() {
        Block fig = new Block("f", "figure", 0, new double[]{0, 0, 1, 1}, "horizontal-tb", "", "", null, false, false, null, "paddle", List.of("f"), null, null);
        assertTrue(QualityGate.isFigureOnly(List.of(fig)));
        assertFalse(QualityGate.isFigureOnly(List.of(block("t", new double[]{0, 0, .5, .2}, "文字"))));
    }
}
