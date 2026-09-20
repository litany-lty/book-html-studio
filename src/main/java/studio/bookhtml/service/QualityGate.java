package studio.bookhtml.service;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import studio.bookhtml.domain.Block;

/**
 * 阶段3：质量门禁——混合页覆盖、空白/失败分类、来源丢失/重复。
 *
 * <p>所有判断均为启发式阈值（暂定参数），失败时取保守侧：宁可多走一次图像识别、
 * 保留原始结果并明确警告，也不静默交付漏识或伪造空白。
 */
public final class QualityGate {
    private QualityGate() {}

    /** 混合页：原生文字很少但图像墨量明显，说明还有扫描内容，必须走图像识别。 */
    public static boolean shouldPreferOcr(List<Block> nativeBlocks, BufferedImage image) {
        if (nativeBlocks == null || nativeBlocks.isEmpty() || image == null) return true;
        int chars = nativeBlocks.stream().filter(Objects::nonNull).map(Block::original)
                .filter(Objects::nonNull).mapToInt(s -> (int) s.codePoints().filter(cp -> !Character.isWhitespace(cp)).count()).sum();
        double area = nativeBlocks.stream().filter(Objects::nonNull).map(Block::bbox)
                .filter(b -> b != null && b.length == 4)
                .mapToDouble(b -> Math.max(0, b[2]) * Math.max(0, b[3])).sum();
        double ink = inkRatio(image, 210);
        // 字符多且覆盖大：可信原生，直接用
        if (chars >= 200 && area >= 0.05) return false;
        // 字符极少但墨多：混合页风险
        if (chars < 80 && ink > 0.015 && area < 0.08) return true;
        if (area < 0.015 && ink > 0.008) return true;
        return false;
    }

    /** 真空白：图像信息极少（复用 SparsePageGuard 阈值）。 */
    public static boolean isTrueBlank(BufferedImage image) {
        if (image == null) return false;
        return SparsePageGuard.hasExtremelyLowVisualInformation(image);
    }

    public static double inkRatio(BufferedImage image, int threshold) {
        int step = Math.max(1, Math.min(image.getWidth(), image.getHeight()) / 500);
        long ink = 0, total = 0;
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int gray = (((rgb >> 16) & 255) * 30 + ((rgb >> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
                if (gray < threshold) ink++;
                total++;
            }
        }
        return ink / (double) Math.max(1, total);
    }

    /** 来源门禁：辅助结果必须恰好引用全部来源一次，不得丢失/重复/新增。 */
    public static boolean hasSourceLossOrDuplication(List<Block> source, List<Block> result) {
        if (source == null || result == null) return true;
        Set<String> sourceIds = new HashSet<>();
        for (Block b : source) {
            if (b == null || b.id() == null || b.id().isBlank() || !sourceIds.add(b.id())) return true;
        }
        Set<String> resultIds = new HashSet<>();
        for (Block b : result) {
            if (b == null || b.id() == null || !resultIds.add(b.id())) return true;
        }
        return !resultIds.equals(sourceIds);
    }

    public static int nonSpace(String value) {
        return value == null ? 0 : (int) value.codePoints().filter(cp -> !Character.isWhitespace(cp)).count();
    }

    public static int totalChars(List<Block> blocks) {
        if (blocks == null) return 0;
        return blocks.stream().filter(Objects::nonNull).map(Block::original)
                .filter(Objects::nonNull).mapToInt(QualityGate::nonSpace).sum();
    }

    /** 是否纯插图页（无文字但有视觉块）。 */
    public static boolean isFigureOnly(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) return false;
        boolean hasVisual = false;
        for (Block b : blocks) {
            if (b == null) continue;
            if (Set.of("figure", "table", "formula").contains(b.type())) {
                hasVisual = true;
                if (nonSpace(b.original()) > 0) return false;
            } else if (nonSpace(b.original()) > 0) {
                return false;
            }
        }
        return hasVisual;
    }
}
