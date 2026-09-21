package studio.bookhtml.service;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        return !checkReorderOrReclassify(source, result).accepted();
    }

    /** F02：操作专属保真门。禁止用一个布尔函数包办所有变换语义。 */
    public enum GateOp { REORDER_OR_RECLASSIFY, MERGE_TEXT_STRUCTURE, NEW_VISUAL_TRANSCRIPTION }

    public record GateVerdict(boolean accepted, String reason) {
        static GateVerdict ok() { return new GateVerdict(true, null); }
        static GateVerdict no(String reason) { return new GateVerdict(false, reason); }
    }

    public static GateVerdict check(List<Block> source, List<Block> result, GateOp op) {
        return switch (op) {
            case REORDER_OR_RECLASSIFY -> checkReorderOrReclassify(source, result);
            case MERGE_TEXT_STRUCTURE -> checkMergeTextStructure(source, result);
            case NEW_VISUAL_TRANSCRIPTION -> checkNewVisualTranscription(source, result);
        };
    }

    private static Map<String, Block> indexById(List<Block> blocks) {
        Map<String, Block> index = new LinkedHashMap<>();
        if (blocks == null) return index;
        for (Block b : blocks) {
            if (b == null || b.id() == null || b.id().isBlank() || index.containsKey(b.id())) return null;
            index.put(b.id(), b);
        }
        return index;
    }

    /**
     * REORDER_OR_RECLASSIFY：块/来源可追踪；原文精确不变；只变 order/type/heading 等声明字段；
     * 已确认（resolved）疑点不丢。同 ID 改字必须拒绝。
     */
    public static GateVerdict checkReorderOrReclassify(List<Block> source, List<Block> result) {
        Map<String, Block> sources = indexById(source);
        Map<String, Block> results = indexById(result);
        if (sources == null || sources.isEmpty()) return GateVerdict.no("来源为空或 ID 重复");
        if (results == null) return GateVerdict.no("结果为空或 ID 重复");
        if (!results.keySet().equals(sources.keySet())) return GateVerdict.no("输出 ID 集合与来源不一致");
        for (Map.Entry<String, Block> e : sources.entrySet()) {
            Block out = results.get(e.getKey());
            if (!Objects.equals(e.getValue().original(), out.original()))
                return GateVerdict.no("块 " + e.getKey() + " 原文被改写");
            // JR-12: 完整保护已确认疑点的范围、replacement 及 ReviewResolution 核心载荷
            Map<String, studio.bookhtml.domain.ContentIssue> sourceIssues = new LinkedHashMap<>();
            if (e.getValue().issues() != null) for (var issue : e.getValue().issues())
                if (issue != null && issue.id() != null && issue.resolved()) sourceIssues.put(issue.id(), issue);
            Map<String, studio.bookhtml.domain.ContentIssue> outIssues = new LinkedHashMap<>();
            if (out.issues() != null) for (var issue : out.issues())
                if (issue != null && issue.id() != null && issue.resolved()) outIssues.put(issue.id(), issue);
            for (Map.Entry<String, studio.bookhtml.domain.ContentIssue> entry : sourceIssues.entrySet()) {
                studio.bookhtml.domain.ContentIssue sIssue = entry.getValue();
                studio.bookhtml.domain.ContentIssue oIssue = outIssues.get(entry.getKey());
                if (oIssue == null)
                    return GateVerdict.no("块 " + e.getKey() + " 已确认疑点 " + entry.getKey() + " 丢失");
                if (sIssue.start() != oIssue.start() || sIssue.end() != oIssue.end())
                    return GateVerdict.no("块 " + e.getKey() + " 已确认疑点 " + entry.getKey() + " 范围被篡改");
                if (!Objects.equals(sIssue.replacement(), oIssue.replacement()))
                    return GateVerdict.no("块 " + e.getKey() + " 已确认疑点 " + entry.getKey() + " 替换文本被篡改");
                if (!Objects.equals(sIssue.resolution(), oIssue.resolution()))
                    return GateVerdict.no("块 " + e.getKey() + " 已确认疑点 " + entry.getKey() + " 确认载荷被篡改");
            }
        }
        return GateVerdict.ok();
    }

    /**
     * MERGE_TEXT_STRUCTURE：输出 ID 唯一；sourceIds 全部有效且受控覆盖全部来源
     * （每个来源恰好被引用一次）；输出原文等于声明次序的来源串联（仅允许换行/空白差异）。
     * 拆分（同一来源被不同输出块分别引用子区间）不在本轮合同内，一律拒绝。
     */
    public static GateVerdict checkMergeTextStructure(List<Block> source, List<Block> result) {
        Map<String, Block> sources = indexById(source);
        if (sources == null || sources.isEmpty()) return GateVerdict.no("来源为空或 ID 重复");
        if (result == null || result.isEmpty()) return GateVerdict.no("结果为空");
        Set<String> outputIds = new HashSet<>();
        Set<String> covered = new HashSet<>();
        for (Block out : result) {
            if (out == null || out.id() == null || out.id().isBlank() || !outputIds.add(out.id()))
                return GateVerdict.no("输出 ID 为空或重复");
            List<String> refs = out.sourceIds() == null ? List.of() : out.sourceIds();
            boolean visualOnly = Set.of("figure", "table", "formula").contains(out.type());
            if (refs.isEmpty()) {
                if (!visualOnly) return GateVerdict.no("块 " + out.id() + " 缺少来源引用");
                continue;
            }
            for (String ref : refs) {
                if (ref == null || ref.isBlank() || !sources.containsKey(ref))
                    return GateVerdict.no("块 " + out.id() + " 引用未知来源");
                if (!covered.add(ref))
                    return GateVerdict.no("来源 " + ref + " 被重复引用（拆分不在本轮合同内）");
                // JR-12-T03: 检查来源块中已确认疑点是否被丢弃或篡改
                Block src = sources.get(ref);
                if (src != null && src.issues() != null) {
                    for (var issue : src.issues()) {
                        if (issue != null && issue.resolved()) {
                            var outIssue = out.issues() == null ? null : out.issues().stream()
                                    .filter(i -> i != null && issue.id().equals(i.id()))
                                    .findFirst().orElse(null);
                            if (outIssue == null || !outIssue.resolved())
                                return GateVerdict.no("合并丢弃了已确认疑点 " + issue.id());
                            if (!Objects.equals(issue.replacement(), outIssue.replacement())
                                    || !Objects.equals(issue.resolution(), outIssue.resolution()))
                                return GateVerdict.no("合并篡改了已确认疑点 " + issue.id() + " 的确认载荷");
                        }
                    }
                }
            }
            StringBuilder expected = new StringBuilder();
            // JR-12-T03：旧 span→新 span 映射；每个来源的已确认疑点在新块中的起止必须等于来源偏移+原起止
            Map<String, Integer> sourceOffset = new java.util.LinkedHashMap<>();
            for (String ref : refs) {
                sourceOffset.put(ref, expected.length());
                expected.append(sources.get(ref).original() == null ? "" : sources.get(ref).original());
            }
            for (String ref : refs) {
                Block src = sources.get(ref);
                if (src == null || src.issues() == null || src.original() == null) continue;
                int offset = sourceOffset.getOrDefault(ref, 0);
                for (var issue : src.issues()) {
                    if (issue == null || !issue.resolved()) continue;
                    var outIssue = out.issues() == null ? null : out.issues().stream()
                            .filter(i -> i != null && issue.id().equals(i.id()))
                            .findFirst().orElse(null);
                    if (outIssue == null) continue; // 丢弃已在上文拒绝
                    // 新范围必须可解释为旧范围平移；不可靠映射时拒绝变换
                    if (outIssue.start() != offset + issue.start()
                            || outIssue.end() != offset + issue.end()) {
                        // 允许纯空白差异导致的整体偏移？不允许单疑点偏移，必须精确
                        return GateVerdict.no("合并后已确认疑点 " + issue.id()
                                + " 的新范围无可靠 span 映射");
                    }
                    // 新范围截出的正文必须与旧范围一致（防串位）
                    try {
                        String outOriginal = out.original() == null ? "" : out.original();
                        String srcOriginal = src.original();
                        String oldSpan = srcOriginal.substring(issue.start(), issue.end());
                        String newSpan = outOriginal.substring(outIssue.start(), outIssue.end());
                        if (!oldSpan.equals(newSpan))
                            return GateVerdict.no("合并后已确认疑点 " + issue.id() + " 的正文不一致");
                    } catch (Exception e) {
                        return GateVerdict.no("合并后已确认疑点 " + issue.id() + " 的范围越界");
                    }
                }
            }
            if (!stripWhitespace(expected.toString()).equals(stripWhitespace(out.original() == null ? "" : out.original())))
                return GateVerdict.no("块 " + out.id() + " 原文不是来源串联");
        }
        if (!covered.equals(sources.keySet())) return GateVerdict.no("来源覆盖不完整");
        return GateVerdict.ok();
    }

    /**
     * NEW_VISUAL_TRANSCRIPTION：保留原始 OCR；新转录必须绑定原图区域（sourceRect）
     * 与生产者来源引用；允许多个恢复条目共享同一视觉来源（需各自带区域）。
     * 无新图像证据的新文字不得冒充结构变换通过。
     */
    public static GateVerdict checkNewVisualTranscription(List<Block> source, List<Block> result) {
        Map<String, Block> sources = indexById(source);
        if (sources == null || sources.isEmpty()) return GateVerdict.no("来源为空或 ID 重复");
        if (result == null || result.isEmpty()) return GateVerdict.no("结果为空");
        Set<String> outputIds = new HashSet<>();
        Set<String> accounted = new HashSet<>();
        for (Block out : result) {
            if (out == null || out.id() == null || out.id().isBlank() || !outputIds.add(out.id()))
                return GateVerdict.no("输出 ID 为空或重复");
            if (sources.containsKey(out.id())) {
                if (!Objects.equals(sources.get(out.id()).original(), out.original()))
                    return GateVerdict.no("保留块 " + out.id() + " 原文被改写");
                accounted.add(out.id());
                continue;
            }
            List<String> refs = out.sourceIds() == null ? List.of() : out.sourceIds();
            if (refs.isEmpty() || refs.stream().anyMatch(ref -> ref == null || ref.isBlank() || !sources.containsKey(ref)))
                return GateVerdict.no("新块 " + out.id() + " 缺少有效视觉来源引用");
            // JR-12-T04: sourceRect 合法性与边界校验
            if (out.sourceRect() == null || out.sourceRect().length != 4)
                return GateVerdict.no("新块 " + out.id() + " 缺少原图区域证据");
            for (double coord : out.sourceRect()) {
                if (Double.isNaN(coord) || Double.isInfinite(coord) || coord < 0 || coord > 100000)
                    return GateVerdict.no("新块 " + out.id() + " 原图区域坐标非法");
            }
            if (out.sourceRect()[2] <= 0 || out.sourceRect()[3] <= 0)
                return GateVerdict.no("新块 " + out.id() + " 原图区域尺寸非法");
            accounted.addAll(refs);
        }
        if (!accounted.containsAll(sources.keySet())) return GateVerdict.no("部分来源既未保留也未被恢复引用");
        return GateVerdict.ok();
    }

    private static String stripWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
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
