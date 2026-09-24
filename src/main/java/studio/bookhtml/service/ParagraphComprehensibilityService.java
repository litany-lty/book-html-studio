package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 每页段落可理解性自检服务。
 * 结合当前页上下文（全部段落顺序与语境），逐段检测语句不通顺、语义不明、不可理解之处，
 * 并推断最可能表达的原本内容（最大可能性），产出结构化 ContentIssue。
 */
@Service
public class ParagraphComprehensibilityService {
    private static final HttpClient SHARED_HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    @FunctionalInterface
    public interface Transport {
        HttpResponse<InputStream> send(HttpRequest request) throws Exception;
    }

    private static HttpResponse<InputStream> sharedSend(HttpRequest request) throws Exception {
        return SHARED_HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private final QwenAssistProperties config;
    private final ObjectMapper json;
    private final TraditionalConverter converter;
    private final Transport transport;
    private SettingsService settings;

    @Autowired
    public ParagraphComprehensibilityService(QwenAssistProperties config, ObjectMapper json, TraditionalConverter converter) {
        this(config, json, converter, ParagraphComprehensibilityService::sharedSend);
    }

    ParagraphComprehensibilityService(QwenAssistProperties config, ObjectMapper json,
                                     TraditionalConverter converter, Transport transport) {
        this.config = config;
        this.json = json;
        this.converter = converter;
        this.transport = transport;
    }

    @Autowired(required = false)
    public void setSettings(SettingsService settings) {
        this.settings = settings;
    }

    public boolean configured() {
        String key = effectiveApiKey();
        return notBlank(key) && config.isEnabled();
    }

    private String effectiveApiKey() {
        if (settings != null && settings.state() != null && notBlank(settings.state().qwenApiKey())) {
            return settings.state().qwenApiKey().strip();
        }
        return config.getApiKey() == null ? "" : config.getApiKey().strip();
    }

    private String effectiveModel() {
        if (settings != null && settings.state() != null && notBlank(settings.state().qwenModel())) {
            return settings.state().qwenModel().strip();
        }
        return notBlank(config.getModel()) ? config.getModel().strip() : "qwen3.8-max";
    }

    private String effectiveBaseUrl() {
        return notBlank(config.getBaseUrl()) ? config.getBaseUrl().strip() : "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public List<Block> checkPage(String bookId, int pageNumber, List<Block> blocks, BooleanSupplier cancelled) throws Exception {
        if (blocks == null || blocks.isEmpty()) return List.of();
        if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();

        List<Block> candidates = blocks.stream()
                .filter(b -> b != null && notBlank(b.original())
                        && !"figure".equals(b.type())
                        && !"table".equals(b.type())
                        && !"advertisement".equals(b.type()))
                .sorted(Comparator.comparingInt(Block::order))
                .toList();

        if (candidates.isEmpty()) return blocks;

        if (!configured()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "通义千问模型服务尚未配置或未提供 API Key，请在处理设置中配置");
        }

        HttpRequest request = buildRequest(candidates);
        HttpResponse<InputStream> response;
        try {
            response = transport.send(request);
        } catch (Exception e) {
            throw new OcrException("可理解性自检服务网络连接失败：" + e.getMessage());
        }

        if (cancelled != null && cancelled.getAsBoolean()) throw new CancelledException();

        if (response.statusCode() == 429) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "大模型请求频率受限，请稍后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new OcrException("可理解性自检服务响应异常（HTTP " + response.statusCode() + "）");
        }

        byte[] bodyBytes;
        try (InputStream in = response.body()) {
            bodyBytes = in.readAllBytes();
        }

        JsonNode root = json.readTree(bodyBytes);
        JsonNode contentNode = root.at("/choices/0/message/content");
        if (!contentNode.isTextual()) {
            throw new OcrException("自检模型未返回有效的文本内容");
        }

        String contentText = stripFence(contentNode.asText());
        JsonNode findingsJson = json.readTree(contentText);
        JsonNode findingsArray = findingsJson.path("findings");
        if (!findingsArray.isArray()) {
            return blocks;
        }

        Map<String, List<ContentIssue>> newIssuesByBlock = new HashMap<>();
        Map<String, Block> blockMap = new HashMap<>();
        for (Block b : blocks) {
            if (b != null && b.id() != null) blockMap.put(b.id(), b);
        }

        for (JsonNode item : findingsArray) {
            String blockId = item.path("blockId").asText(null);
            if (blockId == null || !blockMap.containsKey(blockId)) continue;
            Block target = blockMap.get(blockId);
            String quote = item.path("quote").asText("");
            String reason = item.path("reason").asText("");
            String inferredText = item.path("inferredText").asText("");
            if (quote.isBlank() || inferredText.isBlank()) continue;

            int start = item.path("start").asInt(-1);
            int end = item.path("end").asInt(-1);
            String text = target.original();

            if (start >= 0 && end <= text.length() && end > start && text.substring(start, end).equals(quote)) {
                // exact match verified
            } else {
                int firstIdx = text.indexOf(quote);
                int lastIdx = text.lastIndexOf(quote);
                if (firstIdx >= 0 && firstIdx == lastIdx) {
                    start = firstIdx;
                    end = firstIdx + quote.length();
                } else {
                    continue;
                }
            }

            if (start > 0 && Character.isLowSurrogate(text.charAt(start))) continue;
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) continue;

            List<ContentIssue> existing = target.issues() == null ? List.of() : target.issues();
            final int s = start, e = end;
            boolean overlapsExisting = existing.stream().anyMatch(i -> i != null && s < i.end() && e > i.start());
            if (overlapsExisting) continue;

            List<ContentIssue> pendingNew = newIssuesByBlock.computeIfAbsent(blockId, k -> new ArrayList<>());
            boolean overlapsPending = pendingNew.stream().anyMatch(i -> s < i.end() && e > i.start());
            if (overlapsPending) continue;

            int simpleStart = start;
            int simpleEnd = end;
            if (converter != null) {
                try {
                    simpleStart = converter.toSimplified(text.substring(0, start)).length();
                    simpleEnd = converter.toSimplified(text.substring(0, end)).length();
                } catch (Exception ignored) {
                    simpleStart = start;
                    simpleEnd = end;
                }
            }

            String issueId = "comp-" + UUID.nameUUIDFromBytes((blockId + ":" + start + ":" + end).getBytes(StandardCharsets.UTF_8));
            ContentIssue issue = new ContentIssue(issueId, "suspected", start, end, simpleStart, simpleEnd,
                    reason.isBlank() ? "语句不通顺，语义不明" : reason, false, null, inferredText, null);
            pendingNew.add(issue);
        }

        if (newIssuesByBlock.isEmpty()) {
            return blocks;
        }

        List<Block> result = new ArrayList<>(blocks.size());
        for (Block b : blocks) {
            if (b == null) continue;
            List<ContentIssue> added = newIssuesByBlock.get(b.id());
            if (added == null || added.isEmpty()) {
                result.add(b);
            } else {
                List<ContentIssue> merged = new ArrayList<>(b.issues() == null ? List.of() : b.issues());
                merged.addAll(added);
                merged.sort(Comparator.comparingInt(ContentIssue::start).thenComparingInt(ContentIssue::end));
                result.add(new Block(b.id(), b.type(), b.order(), b.bbox(), b.writingMode(),
                        b.original(), b.simplified(), b.confidence(), b.uncertain(), b.reviewed(),
                        b.headingLevel(), b.source(), b.sourceIds(), b.suggestion(), b.sourceRect(),
                        List.copyOf(merged)));
            }
        }
        return List.copyOf(result);
    }

    private HttpRequest buildRequest(List<Block> candidates) throws Exception {
        String systemPrompt = "你是严谨的书籍校对与语义理解专家。你的任务是对当前页面提供的所有段落进行“可理解性自检”。\n"
                + "书籍在 OCR 识别或排版过程中，常会出现错字、漏字、形近字/同音字替换、断句错误，导致局部语句不通顺、语义不连贯或无法理解。\n"
                + "你必须结合当前页面的整体上下文（前后段落的叙事语境、专业术语、主题内容），逐段进行审读：\n"
                + "1. 若段落语句自然流畅、语义清晰，则无需对其报告任何问题。\n"
                + "2. 若发现某个段落中存在【语句不通顺、语义不明、无法理解】的字词或片段：\n"
                + "   - 指明该问题所在的段落 blockId；\n"
                + "   - 提取该段落原文中存在问题的精确子串 quote（必须与段落原文逐字完全一致，不可擅自改动标点或字词）；\n"
                + "   - 指明 quote 在该段落原文中的 0-based 起始索引 start 与结束索引 end（UTF-16 索引，且 quote == original.substring(start, end)）；\n"
                + "   - 结合当前页附近内容分析原因 reason（例如：“在上下文叙述背景下，'某词'疑为 OCR 错别字/断句错误，导致语句不通顺”）；\n"
                + "   - 根据当前页上下文逻辑推断出最大可能性的原本内容 inferredText（即正确的字词或句子）。\n"
                + "3. 只返回严格的 JSON 格式：\n"
                + "{\n"
                + "  \"findings\": [\n"
                + "    {\n"
                + "      \"blockId\": \"段落ID\",\n"
                + "      \"quote\": \"有问题的原文片段\",\n"
                + "      \"start\": 0,\n"
                + "      \"end\": 4,\n"
                + "      \"reason\": \"结合前后文分析的原因说明\",\n"
                + "      \"inferredText\": \"推断的最可能正确内容\"\n"
                + "    }\n"
                + "  ]\n"
                + "}\n"
                + "注意：\n"
                + "- quote 必须在对应 blockId 的原文中存在且逐字相等。\n"
                + "- inferredText 必须是根据上下文推断出的最合理的修正内容。\n"
                + "- 不要给通顺无误的段落挑错，宁缺毋滥。\n"
                + "- 绝不要在 JSON 外部输出任何附加文字。";

        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (Block b : candidates) {
            paragraphs.add(Map.of("id", b.id(), "order", b.order(), "text", b.original()));
        }

        String userPrompt = "以下是当前页面的所有文本段落（按阅读顺序排列）：\n"
                + json.writeValueAsString(paragraphs) + "\n\n"
                + "请进行可理解性自检，结合各段落的前后文语境，找出所有语句不通顺、语义不明的地方并推断最大可能性内容。";

        Map<String, Object> body = Map.of(
                "model", effectiveModel(),
                "enable_thinking", false,
                "max_tokens", 4096,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        String baseUrl = effectiveBaseUrl();
        URI uri = endpoint(baseUrl);

        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Math.max(10, config.getTimeoutSeconds())))
                .header("Authorization", "Bearer " + effectiveApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
    }

    private static URI endpoint(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.strip();
        if (base.endsWith("/chat/completions")) return URI.create(base);
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return URI.create(base + "/chat/completions");
    }

    private static String stripFence(String value) {
        String result = value == null ? "" : value.strip();
        if (result.startsWith("```")) {
            int first = result.indexOf('\n'), last = result.lastIndexOf("```");
            if (first >= 0 && last > first) result = result.substring(first + 1, last).strip();
        }
        return result;
    }
}
