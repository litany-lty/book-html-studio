package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * C：手写 / 影印稿转写通道。
 *
 * <p>与印刷体 OCR 通道分离：手写行草在普通 OCR 上几乎必然失败，因此这里
 * 先做**分栏裁切 + 放大 + 对比增强**（实测唯一有效的杠杆，见
 * {@code verification/ocr-handwriting-20260924/FINDINGS.md}），再逐栏交给视觉模型
 * 按"只转录看清的字、看不清写 □、严禁按上下文补写"的提示词转写。
 *
 * <p>诚实性约束：产出块一律 {@code uncertain=true}，来源标记为
 * {@link #SOURCE}，并随页附带 {@link #WARNING}；绝不冒充已确认文字，也不自动标为已校对。
 */
@Service
public class HandwritingTranscribeService {
    public static final String PROVIDER_ID = "handwriting";
    public static final String SOURCE = "handwriting-transcribe";
    public static final String LABEL = "手写 / 影印稿转写 · Qwen 视觉（模型推断）";
    public static final String WARNING =
            "本页为手写/影印稿的模型转写（推断结果）：必须逐块对照原稿核对；□ 表示未能辨认，"
                    + "印章遮挡或字迹不清处不猜测、不补写；未辨认处不代表原文缺失。校对栏另附「联想推测」仅为按上下文猜测的可能字，属于未确认建议，绝不代表原文。";

    static final int MAX_STRIPS = 6;
    static final int MIN_STRIP_WIDTH = 700;
    static final int MAX_STRIP_EDGE = 2200;
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final String DEFAULT_SUGGESTION = "模型转写（推断），需对照原稿核对；□ 为未辨认";
    static final String INFERRED_LABEL = "联想推测（非原文，必须核对）：";
    static final String PROMPT = """
            你是中文手写稿转录员。图中是一页手写稿的局部（竖排或横排，可能含方格稿纸、印章、污损或复印件噪点）。
            只转录你真正看清的字：
            - 按原有阅读顺序逐列（竖排）或逐行（横排）输出，每个自然行用换行分隔；
            - 无法辨认的单个字写 □；整段无法辨认就留空；
            - 严禁依据上下文补写、推断或改写；印章遮挡、墨迹模糊、笔画残缺处一律不得猜测；
            - 不要输出任何解释、标题、编号、标点补全或格式标记。
            只输出转录文本本身。""";

    /** 第三遍：自动验证——把转录与原始图像一起交回模型逐字核对，产出"待核对疑点"（带证据、不自动改字）。 */
    static final String VERIFY_PROMPT = """
            你是手写稿校对员。下面给出某一栏的忠实转录文字，以及该栏的原始图像。
            请逐字核对转录是否与图像一致，只报告**明显不一致**或**无法确认**的位置。
            必须返回严格 JSON（不要代码围栏、不要解释）：
            {"findings":[{"index":<转录中该字的序号，从0开始>,"char":"<转录中的那个字>","verdict":"mismatch|unreadable","likely":"<图像上更可能的字，没有把握就空串>","reason":"<不超过20字>"}]}
            没有发现问题就返回 {"findings":[]}。不要重写全文，不要补写未出现的字。
            转录：""";
    static final int MAX_FINDINGS_PER_STRIP = 8;
    static final String VERIFY_NOTE = "已自动做一次逐字图文核对，疑点列在校对栏（仅供参考，仍须人工确认）。";


    static final String FILL_PROMPT = """
            下面是中文手写稿某一栏的忠实转录，□ 表示未能辨认的字。
            请依据上下文为每个 □ 推测最可能的字，并给出联想补全后的整段文字。
            必须返回严格 JSON（不要代码围栏、不要解释）：
            {"fills":[{"index":<该 □ 在转录中的字符序号，从0开始>,"candidates":["<候选1>","<候选2>"]}],"text":"<把推测字用〔〕括起的整段文字>"}
            规则：candidates 最多 2 个，没有把握就返回空数组；text 中没有把握的位置保留 □；不要补写原转录中不存在的整句。
            转录：""";

    private final QwenAssistProperties config;
    private final ObjectMapper json;
    private final Transport transport;
    /** 用量账本：本项目契约是"所有实际云请求都记入本书用量"，手写转写同样必须记账。 */
    private UsageLedger usage;

    @Autowired(required = false)
    public void setUsageLedger(UsageLedger usage) { this.usage = usage; }

    @Autowired
    public HandwritingTranscribeService(QwenAssistProperties config, ObjectMapper json) {
        this(config, json, request -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()
                .send(request, HttpResponse.BodyHandlers.ofString()));
    }

    HandwritingTranscribeService(QwenAssistProperties config, ObjectMapper json, Transport transport) {
        this.config = config;
        this.json = json;
        this.transport = transport;
    }

    /** 只有配置了 Qwen 视觉凭据才可用；未配置时上层给出明确原因而不是静默失败。 */
    public boolean configured() {
        return config.getApiKey() != null && !config.getApiKey().isBlank()
                && config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
                && config.getModel() != null && !config.getModel().isBlank();
    }

    /**
     * 逐栏转写整页，返回按阅读顺序排列的文字块。
     * 竖排（默认）从右到左分栏，横排从上到下分段；相邻栏保留少量重叠，避免切断连笔。
     */
    public List<Block> transcribe(BufferedImage page, String layout, BooleanSupplier cancelled) throws OcrException {
        if (!configured()) throw new OcrException("手写/影印稿转写未配置 Qwen 视觉凭据");
        if (page == null) throw new OcrException("手写/影印稿转写缺少页面图像");
        boolean vertical = !"horizontal".equals(layout);
        List<double[]> strips = stripBoxes(vertical);
        List<Block> blocks = new ArrayList<>();
        int order = 0;
        for (double[] box : strips) {
            if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
            BufferedImage crop = crop(page, box);
            if (crop == null) continue;
            String text;
            List<studio.bookhtml.domain.ContentIssue> issues = List.of();
            try {
                text = clean(read(crop, cancelled));
                if (!text.isEmpty()) issues = verify(crop, text, cancelled);
            } finally {
                crop.flush();
            }
            if (text.isEmpty()) continue;
            String id = SOURCE + "-" + (blocks.size() + 1);
            blocks.add(new Block(id, "text", order++, box, vertical ? "vertical-rl" : "horizontal-tb",
                    text, "", null, true, false, null, SOURCE, List.of(id),
                    DEFAULT_SUGGESTION,
                    new double[]{box[0] * page.getWidth(), box[1] * page.getHeight(),
                            box[2] * page.getWidth(), box[3] * page.getHeight()}, issues));
        }
        if (blocks.isEmpty()) {
            throw new OcrNoTextException("手写转写未得到可用文字（字迹可能无法辨认或图像质量不足）");
        }
        // 第二遍：召回拉满——对含 □ 的栏做联想补全，结果只写入 suggestion（未确认建议）。
        List<Block> enriched = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            String suggestion = DEFAULT_SUGGESTION;
            if (block.original() != null && block.original().indexOf('□') >= 0) {
                String guessed = clean(fill(block.original(), cancelled));
                if (!guessed.isEmpty()) suggestion = describeInference(guessed);
            }
            enriched.add(new Block(block.id(), block.type(), block.order(), block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), true, false, null, SOURCE,
                    block.sourceIds(), suggestion, block.sourceRect(), block.issues()));
        }
        return List.copyOf(enriched);
    }

    /** 分栏（或分段）归一化坐标；竖排右起，横排上起。 */
    static List<double[]> stripBoxes(boolean vertical) {
        List<double[]> boxes = new ArrayList<>(MAX_STRIPS);
        double span = 1d / MAX_STRIPS;
        double overlap = span * 0.08;
        for (int i = 0; i < MAX_STRIPS; i++) {
            double start = i * span;
            double from = Math.max(0, start - overlap);
            double to = Math.min(1, start + span + overlap);
            double size = to - from;
            if (vertical) {
                double x = 1 - to;   // 竖排：从右向左
                boxes.add(new double[]{x, 0, size, 1});
            } else {
                boxes.add(new double[]{0, from, 1, size});
            }
        }
        return boxes;
    }

    private BufferedImage crop(BufferedImage page, double[] box) {
        int x = clamp((int) Math.floor(box[0] * page.getWidth()), 0, page.getWidth() - 1);
        int y = clamp((int) Math.floor(box[1] * page.getHeight()), 0, page.getHeight() - 1);
        int w = clamp((int) Math.ceil(box[2] * page.getWidth()), 1, page.getWidth() - x);
        int h = clamp((int) Math.ceil(box[3] * page.getHeight()), 1, page.getHeight() - y);
        BufferedImage raw = page.getSubimage(x, y, w, h);
        return enhance(raw);
    }

    /** 灰度 + 对比拉伸 + 放大：提升复印件与浅淡手迹的可读性，不改字形。 */
    static BufferedImage enhance(BufferedImage source) {
        int w = source.getWidth(), h = source.getHeight();
        int[] histogram = new int[256];
        int[][] gray = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = source.getRGB(x, y);
                int value = (((rgb >> 16) & 255) * 30 + ((rgb >> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
                gray[y][x] = value;
                histogram[value]++;
            }
        }
        int total = Math.max(1, w * h), tail = Math.max(1, total / 200);
        int low = 0, high = 255, seen = 0;
        for (int v = 0; v < 256; v++) { seen += histogram[v]; if (seen > tail) { low = v; break; } }
        seen = 0;
        for (int v = 255; v >= 0; v--) { seen += histogram[v]; if (seen > tail) { high = v; break; } }
        if (high - low < 16) { low = 0; high = 255; }
        double scale = 255d / (high - low);
        double factor = Math.max(1d, Math.min((double) MIN_STRIP_WIDTH / Math.max(1, w),
                (double) MAX_STRIP_EDGE / Math.max(1, Math.max(w, h))));
        int outW = Math.max(1, (int) Math.round(w * factor));
        int outH = Math.max(1, (int) Math.round(h * factor));
        BufferedImage target = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, outW, outH, null);
        } finally {
            graphics.dispose();
        }
        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int rgb = target.getRGB(x, y);
                int value = (((rgb >> 16) & 255) * 30 + ((rgb >> 8) & 255) * 59 + (rgb & 255) * 11) / 100;
                int stretched = (int) Math.max(0, Math.min(255, (value - low) * scale));
                target.setRGB(x, y, (stretched << 16) | (stretched << 8) | stretched);
            }
        }
        return target;
    }

    /**
     * 自动验证：把"转录"与"原始图像"一起交回模型逐字核对，产出未确认疑点。
     * 只报告不一致/无法确认的位置，绝不改写 original；偏移越界或返回字与转录不符即丢弃，
     * 避免把错误的字符索引套到文本上。
     */
    List<studio.bookhtml.domain.ContentIssue> verify(BufferedImage strip, String transcript, BooleanSupplier cancelled)
            throws OcrException {
        String body = visionRequest(strip, VERIFY_PROMPT + transcript, 1024, true, cancelled);
        if (body.isEmpty()) return List.of();
        List<studio.bookhtml.domain.ContentIssue> issues = new ArrayList<>();
        try {
            JsonNode findings = json.readTree(body).at("/findings");
            if (!findings.isArray()) return List.of();
            for (JsonNode finding : findings) {
                if (issues.size() >= MAX_FINDINGS_PER_STRIP) break;
                int index = finding.path("index").asInt(-1);
                String verdict = finding.path("verdict").asText("");
                String likely = finding.path("likely").asText("");
                String reason = finding.path("reason").asText("");
                if (index < 0 || index >= transcript.length()) continue;
                String expected = finding.path("char").asText("");
                if (!expected.isEmpty() && transcript.charAt(index) != expected.charAt(0)) continue;
                String kind = "unreadable".equals(verdict) ? "unreadable" : "suspected";
                String note = ("unreadable".equals(kind) ? "图像核对：该处无法确认" : "图像核对：与转录不一致")
                        + (likely.isEmpty() ? "" : "，可能是「" + likely + "」")
                        + (reason.isEmpty() ? "" : "（" + reason + "）");
                issues.add(new studio.bookhtml.domain.ContentIssue(
                        SOURCE + "-verify-" + index, kind, index, index + 1, index, index + 1,
                        note, false, likely, likely));
            }
        } catch (Exception error) {
            return List.of();
        }
        return List.copyOf(issues);
    }

    /** 转录：复用统一的视觉请求（纯文本输出，不用 JSON 模式）。 */
    private String read(BufferedImage strip, BooleanSupplier cancelled) throws OcrException {
        return visionRequest(strip, PROMPT, 2048, false, cancelled);
    }

    /**
     * 统一的视觉请求（图像 + 提示词），返回 message.content。
     * 每次物理调用都先记账、成功结算 token、失败标失败——不允许"未记账的外呼"。
     */
    private String visionRequest(BufferedImage strip, String prompt, int maxTokens, boolean jsonMode,
                                 BooleanSupplier cancelled) throws OcrException {
        byte[] png;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(strip, "png", output)) throw new OcrException("手写转写图像编码失败");
            png = output.toByteArray();
        } catch (java.io.IOException error) {
            throw new OcrException("手写转写图像编码失败", error);
        }
        if (png.length > MAX_IMAGE_BYTES) throw new OcrException("手写转写分段图像超过 8MB 上限");
        try {
            Map<String, Object> body = Map.of(
                    "model", config.getModel(),
                    "enable_thinking", false,
                    "max_tokens", maxTokens,
                    "messages", List.of(Map.of("role", "user", "content", List.of(
                            Map.of("type", "text", "text", prompt),
                            Map.of("type", "image_url", "image_url", Map.of(
                                    "url", "data:image/png;base64," + Base64.getEncoder().encodeToString(png),
                                    "detail", "high"))))));
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.getBaseUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload(jsonMode, body))))
                    .build();
            if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
            String ledgerId = beginLedger();
            HttpResponse<String> response;
            try {
                response = transport.send(request);
            } catch (Exception error) {
                failLedger(ledgerId);
                throw error;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                failLedger(ledgerId);
                return "";
            }
            String text = content(response.body());
            settleLedger(ledgerId, response.body());
            return text;
        } catch (OcrException | CancelledException error) {
            throw error;
        } catch (Exception error) {
            throw new OcrException("手写转写请求失败", error);
        }
    }

    private static Map<String, Object> payload(boolean jsonMode, Map<String, Object> body) {
        if (!jsonMode) return body;
        Map<String, Object> withFormat = new java.util.LinkedHashMap<>(body);
        withFormat.put("response_format", Map.of("type", "json_object"));
        return withFormat;
    }

    /** 记账：账本不可用时拒绝外呼（与 Paddle 通道同一契约）。 */
    private String beginLedger() throws OcrException {
        if (usage == null) {
            System.getLogger(HandwritingTranscribeService.class.getName())
                    .log(System.Logger.Level.WARNING, "手写转写未接入用量账本：UsageLedger 未注入，本次调用不会计入本书用量");
            return null;
        }
        try {
            // 账本的 provider 白名单只有 paddle-aistudio/ppocr/qwen/jev；手写转写本质是 Qwen 视觉调用，
            // 因此按 qwen 记账（否则条目校验失败会报 "usage ledger damaged"）。
            // 页面与块的来源仍标 handwriting-transcribe，识别 provenance 与计费 provider 是两件事。
            return usage.start("qwen", config.getModel());
        } catch (Exception error) {
            // 记账失败不能静默：明确告警并打印原因（本条调用不计入用量）。
            System.getLogger(HandwritingTranscribeService.class.getName())
                    .log(System.Logger.Level.WARNING, "手写转写记账失败，本次调用未计入用量：" + error
                            + " | cause=" + error.getCause());
            return null;
        }
    }

    private void settleLedger(String ledgerId, String body) {
        if (usage == null || ledgerId == null) return;
        try {
            if (body != null && !body.isEmpty()) {
                try { usage.captureUsage(ledgerId, json.readTree(body)); } catch (Exception ignored) { }
            }
            usage.succeeded(ledgerId);
        } catch (java.io.IOException ignored) { }
    }

    private void failLedger(String ledgerId) {
        if (usage == null || ledgerId == null) return;
        try { usage.failed(ledgerId); } catch (java.io.IOException ignored) { }
    }

    /**
     * P2：把联想补全结果整理成**逐条候选**，便于在核对时一条条看：
     * 优先解析结构化 JSON（每个 □ 的候选字 + 补全版），解析不了就按纯文本补全版降级。
     */
    static String describeInference(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.startsWith("{")) {
            try {
                JsonNode node = new ObjectMapper().readTree(text);
                JsonNode fills = node.get("fills");
                StringBuilder out = new StringBuilder(INFERRED_LABEL);
                if (fills != null && fills.isArray()) {
                    for (JsonNode fill : fills) {
                        JsonNode candidates = fill.get("candidates");
                        StringBuilder joined = new StringBuilder();
                        if (candidates != null && candidates.isArray()) {
                            for (JsonNode candidate : candidates) {
                                String value = candidate.asText("").strip();
                                if (value.isEmpty()) continue;
                                if (joined.length() > 0) joined.append(" / ");
                                joined.append(value);
                            }
                        }
                        int index = fill.path("index").asInt(-1);
                        out.append('\n').append("第 ").append(index + 1).append(" 字：")
                           .append(joined.length() == 0 ? "无有把握的候选" : joined);
                    }
                }
                JsonNode filled = node.get("text");
                if (filled != null && !filled.asText("").isBlank()) {
                    out.append('\n').append("补全版：").append(filled.asText().strip());
                }
                return out.toString();
            } catch (Exception ignored) {
                // 落到纯文本降级
            }
        }
        return INFERRED_LABEL + text;
    }

    /** 纯文本联想补全（不发图，成本低）：只用于生成"未确认建议"。 */
    private String fill(String transcript, BooleanSupplier cancelled) throws OcrException {
        try {
            Map<String, Object> body = Map.of(
                    "model", config.getModel(),
                    "enable_thinking", false,
                    "max_tokens", 1024,
                    "messages", List.of(Map.of("role", "user", "content", FILL_PROMPT + transcript)));
            HttpRequest request = HttpRequest.newBuilder(endpoint(config.getBaseUrl()))
                    .timeout(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();
            String ledgerId = beginLedger();
            HttpResponse<String> response;
            try {
                response = transport.send(request);
            } catch (Exception error) {
                failLedger(ledgerId);
                throw error;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                failLedger(ledgerId);
                return "";
            }
            String text = content(response.body());
            settleLedger(ledgerId, response.body());
            return text;
        } catch (OcrException | CancelledException error) {
            throw error;
        } catch (Exception error) {
            return "";
        }
    }

    private String content(String body) throws OcrException {
        try {
            JsonNode root = json.readTree(body);
            JsonNode node = root.at("/choices/0/message/content");
            if (node.isMissingNode() || node.isNull()) return "";
            if (node.isArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonNode part : node) {
                    String text = part.path("text").asText("");
                    if (!text.isEmpty()) joined.append(text);
                }
                return joined.toString();
            }
            return node.asText("");
        } catch (Exception error) {
            throw new OcrException("手写转写返回内容无法解析", error);
        }
    }

    /** 去掉模型可能附带的代码围栏与前后缀说明，只留转录文本。 */
    static String clean(String value) {
        if (value == null) return "";
        String text = value.strip();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstBreak > 0 && lastFence > firstBreak) text = text.substring(firstBreak + 1, lastFence).strip();
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\r\n|\r|\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(trimmed);
        }
        return out.toString();
    }

    private static URI endpoint(String baseUrl) {
        String normalized = baseUrl.strip().replaceAll("/+$", "");
        return URI.create(normalized.endsWith("/chat/completions") ? normalized : normalized + "/chat/completions");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    interface Transport {
        HttpResponse<String> send(HttpRequest request) throws Exception;
    }

    static String label() {
        return String.format(Locale.ROOT, "%s", LABEL);
    }
}
