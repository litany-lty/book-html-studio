package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import studio.bookhtml.domain.Block;

import java.util.*;

/**
 * PP-OCRv6 行级结果转 Block。输入为 BaiduPpOcrClient 归一化后的中间结构，
 * 与 PaddleOcrParser 的输入形状一致（pages/meta/layouts/position），
 * 因此 IssueImageService 可用同一套缓存解析逻辑定位红框。
 */
@Component
public class BaiduPpOcrParser {
    public List<Block> parse(JsonNode root, int imageWidth, int imageHeight, String requestedLayout) throws OcrException {
        try {
            JsonNode pages = root.path("pages");
            if (!pages.isArray() || pages.isEmpty()) throw new OcrException("PP-OCRv6 结果缺少 pages");
            if (pages.size() != 1) throw new OcrException("PP-OCRv6 单页任务返回了异常页数");
            JsonNode page = pages.get(0);
            int coordinateWidth = dimension(page.path("meta").path("page_width"), imageWidth);
            int coordinateHeight = dimension(page.path("meta").path("page_height"), imageHeight);
            JsonNode layouts = page.path("layouts");
            if (!layouts.isArray()) throw new OcrException("PP-OCRv6 结果缺少 layouts");
            List<Block> blocks = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int order = 0;
            for (JsonNode layout : layouts) {
                if (!layout.isObject()) continue;
                String text = layout.path("text").asText("").strip();
                if (text.isEmpty()) continue;
                double[] raw = coordinates(layout);
                double[] bbox = normalize(raw, coordinateWidth, coordinateHeight);
                Double confidence = confidence(layout.path("confidence"));
                String remoteId = layout.path("layout_id").asText("").strip();
                String base = remoteId.isBlank() ? "line-" + (order + 1) : remoteId.replaceAll("[^A-Za-z0-9._-]", "-");
                if (base.length() > 96) base = base.substring(0, 96);
                String id = "ppocr-" + base;
                int duplicate = 2;
                while (!ids.add(id)) id = "ppocr-" + base + "-" + (duplicate++);
                String mode = writingMode(requestedLayout, bbox);
                blocks.add(new Block(id, "text", order++, bbox, mode, text, text, confidence,
                        true, false, null, "ppocr", List.of(id), null, raw));
            }
            if (blocks.isEmpty()) throw new OcrException("PP-OCRv6 未返回可用文字行");
            BlockValidator.validate(blocks);
            return List.copyOf(blocks);
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 结果 JSON 或坐标无效", e);
        }
    }

    private static String writingMode(String requested, double[] bbox) {
        if ("vertical".equals(requested)) return "vertical-rl";
        if ("horizontal".equals(requested)) return "horizontal-tb";
        return bbox[3] > bbox[2] * 1.5 ? "vertical-rl" : "horizontal-tb";
    }

    private static Double confidence(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull() || !node.isNumber()) return null;
        double value = node.asDouble();
        if (!Double.isFinite(value) || value < 0 || value > 1) return null;
        return value;
    }

    private static double[] coordinates(JsonNode layout) throws OcrException {
        JsonNode position = layout.get("position");
        if (position == null || !position.isArray() || position.size() < 4) throw new OcrException("PP-OCRv6 内容块缺少必要坐标");
        for (int i = 0; i < 4; i++) if (!position.get(i).isNumber()) throw new OcrException("PP-OCRv6 坐标无效");
        return new double[]{position.get(0).asDouble(), position.get(1).asDouble(), position.get(2).asDouble(), position.get(3).asDouble()};
    }

    private static int dimension(JsonNode value, int fallback) {
        return value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : fallback;
    }

    private static double[] normalize(double[] raw, int width, int height) throws OcrException {
        if (width <= 0 || height <= 0 || raw.length != 4
                || !Double.isFinite(raw[0]) || !Double.isFinite(raw[1]) || !Double.isFinite(raw[2]) || !Double.isFinite(raw[3])
                || raw[0] < 0 || raw[1] < 0 || raw[2] <= 0 || raw[3] <= 0
                || raw[0] + raw[2] > width + 1 || raw[1] + raw[3] > height + 1) throw new OcrException("PP-OCRv6 内容块坐标越界");
        double right = Math.min(width, raw[0] + raw[2]), bottom = Math.min(height, raw[1] + raw[3]);
        double[] box = {raw[0] / width, raw[1] / height, (right - raw[0]) / width, (bottom - raw[1]) / height};
        try {
            BlockValidator.validateBbox(box);
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 内容块坐标无效", e);
        }
        return box;
    }
}
