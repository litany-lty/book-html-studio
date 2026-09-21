package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QwenLayoutClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requestUsesVisionJsonModeAndExplicitlyDisablesThinking() throws Exception {
        AtomicReference<HttpRequest> captured = new AtomicReference<>();
        QwenLayoutClient client = new QwenLayoutClient(config(), json, request -> {
            captured.set(request);
            return response(200, envelope("{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\"}]}"));
        });

        List<Block> result = client.assist(smallPng(), List.of(block("a", "text", 0, "原文")), null, () -> false);

        assertEquals(1, result.size());
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", captured.get().uri().toString());
        JsonNode body = json.readTree(requestBody(captured.get()));
        assertEquals("qwen3.8-max", body.path("model").asText());
        assertFalse(body.path("enable_thinking").asBoolean(true));
        assertEquals(8192, body.path("max_tokens").asInt());
        assertEquals("json_object", body.at("/response_format/type").asText());
        assertTrue(body.at("/messages/0/content/1/image_url/url").asText().startsWith("data:image/png;base64,"));
        assertFalse(body.has("thinking"));
        assertEquals(2, body.at("/messages/0/content").size());
        String prompt = body.at("/messages/0/content/0/text").asText();
        assertTrue(prompt.contains("sourceBlocks"));
        assertTrue(prompt.contains("\"original\":\"原文\""));
        assertTrue(prompt.contains("版面偏好=auto"));
        assertTrue(prompt.contains("region_id=full-overview"));
        assertTrue(prompt.contains("sourceBlocks.bbox 始终按 full-overview 完整页坐标解释"));
        assertTrue(prompt.contains("不得仅因语义不通而擅自补字"));
    }

    @Test
    void largePageAddsNamedRegionsWithinOneRequestAndFallsBackAsAGroupWhenBudgetIsExceeded() throws Exception {
        byte[] image = quadrantPng(true);
        QwenLayoutClient client = new QwenLayoutClient(config(), json, request -> response(200, "{}"));

        HttpRequest request = client.request(image, List.of(block("a", "text", 0, "原文")), "vertical");
        JsonNode content = json.readTree(requestBody(request)).at("/messages/0/content");

        assertEquals(10, content.size());
        assertEquals(5, countParts(content, "image_url"));
        for (int i = 0; i < content.size(); i++) {
            assertEquals(i % 2 == 0 ? "text" : "image_url", content.get(i).path("type").asText());
        }
        String serialized = content.toString();
        assertTrue(serialized.contains("region_id=quadrant-top-right"));
        assertTrue(serialized.contains("region_id=quadrant-bottom-right"));
        assertTrue(serialized.contains("region_id=quadrant-top-left"));
        assertTrue(serialized.contains("region_id=quadrant-bottom-left"));
        assertTrue(serialized.contains("full_page_normalized_bbox=[0.500000,0.000000,0.500000,0.500000]"));

        List<Map<String, Object>> budgetFallback = client.visionContent(image, "prompt", image.length);
        assertEquals(2, budgetFallback.size());
        assertEquals("text", budgetFallback.get(0).get("type"));
        assertEquals("image_url", budgetFallback.get(1).get("type"));
    }

    @Test
    void largePageOmitsBlankQuadrants() throws Exception {
        byte[] image = quadrantPng(false);
        QwenLayoutClient client = new QwenLayoutClient(config(), json, request -> response(200, "{}"));

        JsonNode content = json.valueToTree(client.visionContent(image, "prompt", Integer.MAX_VALUE));

        assertEquals(4, content.size());
        assertEquals(2, countParts(content, "image_url"));
        assertTrue(content.toString().contains("region_id=quadrant-top-right"));
        assertFalse(content.toString().contains("region_id=quadrant-bottom-right"));
        assertFalse(content.toString().contains("region_id=quadrant-top-left"));
        assertFalse(content.toString().contains("region_id=quadrant-bottom-left"));
    }

    @Test
    void validResultReordersAndClassifiesWithoutChangingTrustedFields() throws Exception {
        Block first = block("a", "text", 0, "甲");
        Block second = block("b", "text", 1, "乙");
        QwenLayoutClient client = clientWith("{\"blocks\":["
                + "{\"sourceId\":\"b\",\"order\":0,\"type\":\"heading\",\"headingLevel\":2,\"suggestion\":\"疑似标题\",\"uncertain\":true},"
                + "{\"sourceId\":\"a\",\"order\":1,\"type\":\"text\",\"uncertain\":false}]}" );

        List<Block> result = client.assist(new byte[]{1}, List.of(first, second), "vertical", () -> false);

        assertEquals(List.of("b", "a"), result.stream().map(Block::id).toList());
        assertEquals("乙", result.get(0).original());
        assertEquals("乙", result.get(0).simplified());
        assertArrayEquals(second.bbox(), result.get(0).bbox());
        assertEquals(second.sourceIds(), result.get(0).sourceIds());
        assertEquals("heading", result.get(0).type());
        assertEquals(2, result.get(0).headingLevel());
        assertEquals("疑似标题", result.get(0).suggestion());
    }

    @Test
    void ignoresModelTextHtmlAndUnsafeSuggestion() throws Exception {
        QwenLayoutClient client = clientWith("{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,"
                + "\"type\":\"text\",\"original\":\"恶意改文\",\"html\":\"<script>alert(1)</script>\","
                + "\"suggestion\":\"<script>alert(1)</script>\"}]}" );

        Block source = block("a", "text", 0, "可信原文");
        Block result = client.assist(new byte[]{1}, List.of(source), "auto", () -> false).get(0);

        assertEquals("可信原文", result.original());
        assertEquals("text", result.type());
        assertNull(result.suggestion());
    }

    @Test
    void textSourcesCanBeUpgradedToVisualTypesWithoutChangingTrustedFields() throws Exception {
        List<Block> sources = List.of(block("f", "text", 0, "命盘内文字"),
                block("t", "text", 1, "表格内文字"), block("m", "caption", 2, "公式识别文字"));
        String content = "{\"blocks\":["
                + "{\"sourceId\":\"m\",\"order\":0,\"type\":\"formula\"},"
                + "{\"sourceId\":\"f\",\"order\":1,\"type\":\"figure\"},"
                + "{\"sourceId\":\"t\",\"order\":2,\"type\":\"table\"}]}";

        List<Block> result = clientWith(content).assist(new byte[]{1}, sources, "vertical", () -> false);

        assertEquals(List.of("formula", "figure", "table"), result.stream().map(Block::type).toList());
        assertEquals(List.of("公式识别文字", "命盘内文字", "表格内文字"), result.stream().map(Block::original).toList());
        assertArrayEquals(sources.get(2).bbox(), result.get(0).bbox());
        assertArrayEquals(sources.get(0).bbox(), result.get(1).bbox());
        assertArrayEquals(sources.get(1).bbox(), result.get(2).bbox());
        assertEquals(List.of("m", "f", "t"), result.stream().flatMap(block -> block.sourceIds().stream()).toList());
    }

    @Test
    void unknownDuplicateAndMissingReferencesFallBackWithoutLoss() throws Exception {
        List<Block> sources = List.of(block("a", "text", 0, "甲"), block("b", "text", 1, "乙"));
        List<String> invalid = List.of(
                "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"heading\"},{\"sourceId\":\"fake\",\"order\":1,\"type\":\"text\"}]}",
                "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"heading\"},{\"sourceId\":\"a\",\"order\":1,\"type\":\"text\"}]}",
                "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"heading\"}]}"
        );

        for (String content : invalid) {
            List<Block> result = clientWith(content).assist(new byte[]{1}, sources, "auto", () -> false);
            assertEquals(List.of("a", "b"), result.stream().map(Block::id).toList());
            assertEquals(List.of("甲", "乙"), result.stream().map(Block::original).toList());
            assertEquals(List.of("text", "text"), result.stream().map(Block::type).toList());
            assertTrue(result.get(0).suggestion().contains("已保留 OCR 原顺序和分类"));
        }
    }

    @Test
    void figureTableAndFormulaTypesCannotBeDowngradedToText() throws Exception {
        List<Block> sources = List.of(block("f", "figure", 0, "图内文字"), block("t", "table", 1, "表格文字"),
                block("m", "formula", 2, "公式文字"));
        String content = "{\"blocks\":["
                + "{\"sourceId\":\"m\",\"order\":0,\"type\":\"text\"},"
                + "{\"sourceId\":\"f\",\"order\":1,\"type\":\"text\"},"
                + "{\"sourceId\":\"t\",\"order\":2,\"type\":\"heading\"}]}";

        List<Block> result = clientWith(content).assist(new byte[]{1}, sources, "horizontal", () -> false);

        assertEquals(List.of("formula", "figure", "table"), result.stream().map(Block::type).toList());
        assertEquals(3, result.stream().flatMap(block -> block.sourceIds().stream()).distinct().count());
    }

    @Test
    void createsStableUtf16IssueWithoutOverwritingOriginal() throws Exception {
        Block source = block("a", "text", 0, "𠀀□乙");
        String content = "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"□\",\"kind\":\"unreadable\",\"reason\":\"原图破损\",\"inferredText\":\"甲\"}]}]}";

        Block first = clientWith(content).assist(new byte[]{1}, List.of(source), "vertical", () -> false).get(0);
        Block second = clientWith(content).assist(new byte[]{1}, List.of(source), "vertical", () -> false).get(0);

        assertEquals("𠀀□乙", first.original());
        assertEquals(1, first.issues().size());
        assertEquals(2, first.issues().get(0).start());
        assertEquals(3, first.issues().get(0).end());
        assertEquals("甲", first.issues().get(0).inferredText());
        assertFalse(first.issues().get(0).resolved());
        assertEquals(first.issues().get(0).id(), second.issues().get(0).id());
    }

    @Test
    void rejectsAmbiguousIssueQuoteAndUnsafeInference() throws Exception {
        Block repeated = block("a", "text", 0, "甲甲");
        String ambiguous = "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"甲\",\"kind\":\"unreadable\",\"reason\":\"不确定\"}]}]}";
        assertTrue(clientWith(ambiguous).assist(new byte[]{1}, List.of(repeated), "auto", () -> false).get(0).issues().isEmpty());

        Block unique = block("b", "text", 0, "乙");        String unsafe = "{\"blocks\":[{\"sourceId\":\"b\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"乙\",\"kind\":\"suspected\",\"reason\":\"图像疑点\",\"inferredText\":\"<script>x</script>\"}]}]}";
        assertNull(clientWith(unsafe).assist(new byte[]{1}, List.of(unique), "auto", () -> false).get(0).issues().get(0).inferredText());
    }

    @Test
    void occurrenceIndexBindsVerifiedPositionAndAmbiguityKeepsRegionHint() throws Exception {
        // T33：可验证 occurrence 绑定正确那次；不可验证保留区域级提示，不绑第一处
        Block repeated = block("a", "text", 0, "甲乙甲丙");
        String second = "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"甲\",\"kind\":\"suspected\",\"reason\":\"复核\",\"occurrenceIndex\":1}]}]}";
        Block bound = clientWith(second).assist(new byte[]{1}, List.of(repeated), "auto", () -> false).get(0);
        assertEquals(1, bound.issues().size());
        assertEquals(2, bound.issues().get(0).start());
        assertEquals(3, bound.issues().get(0).end());

        String ambiguous = "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"甲\",\"kind\":\"suspected\",\"reason\":\"复核\"}]}]}";
        Block hinted = clientWith(ambiguous).assist(new byte[]{1}, List.of(repeated), "auto", () -> false).get(0);
        assertTrue(hinted.issues().isEmpty());
        assertTrue(hinted.suggestion() != null && hinted.suggestion().contains("无法唯一定位"));

        String context = "{\"blocks\":[{\"sourceId\":\"a\",\"order\":0,\"type\":\"text\",\"issues\":[{"
                + "\"quote\":\"甲\",\"kind\":\"suspected\",\"reason\":\"复核\",\"contextBefore\":\"乙\",\"contextAfter\":\"丙\"}]}]}";
        Block ctx = clientWith(context).assist(new byte[]{1}, List.of(repeated), "auto", () -> false).get(0);
        assertEquals(1, ctx.issues().size());
        assertEquals(2, ctx.issues().get(0).start());
    }

    @Test
    void cancellationPreventsCallAndHttpFailureDoesNotExposeResponseBody() {
        AtomicBoolean called = new AtomicBoolean();
        QwenLayoutClient cancelled = new QwenLayoutClient(config(), json, request -> {
            called.set(true);
            return response(200, "{}");
        });
        assertThrows(CancelledException.class,
                () -> cancelled.assist(new byte[]{1}, List.of(block("a", "text", 0, "原文")), "auto", () -> true));
        assertFalse(called.get());

        TrackingInputStream failedBody = new TrackingInputStream("secret raw response".getBytes(StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked") HttpResponse<InputStream> failedResponse = mock(HttpResponse.class);
        when(failedResponse.statusCode()).thenReturn(500);
        when(failedResponse.body()).thenReturn(failedBody);
        QwenLayoutClient failed = new QwenLayoutClient(config(), json, request -> failedResponse);
        OcrException error = assertThrows(OcrException.class,
                () -> failed.assist(new byte[]{1}, List.of(block("a", "text", 0, "原文")), "auto", () -> false));
        assertFalse(error.getMessage().contains("secret"));
        assertTrue(failedBody.closed);
    }

    @Test
    void rejectsOversizedResponseWithoutRetrying() {
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        byte[] oversized = new byte[2 * 1024 * 1024 + 1];
        QwenLayoutClient client = new QwenLayoutClient(config(), json, request -> {
            calls.set(calls.get() + 1);
            return response(200, oversized);
        });

        OcrException error = assertThrows(OcrException.class,
                () -> client.assist(new byte[]{1}, List.of(block("a", "text", 0, "原文")), "auto", () -> false));
        assertTrue(error.getMessage().contains("过大"));
        assertEquals(1, calls.get());
    }

    private QwenLayoutClient clientWith(String content) throws Exception {
        return new QwenLayoutClient(config(), json, request -> response(200, envelope(content)));
    }

    private QwenAssistProperties config() {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("test-key");
        return config;
    }

    private Block block(String id, String type, int order, String original) {
        double x = .1 + order * .2;
        return new Block(id, type, order, new double[]{x, .1, .1, .2}, "vertical-rl", original, original,
                .9, false, false, null, "paddle", List.of(id), null, new double[]{10, 10, 20, 30});
    }

    private String envelope(String content) throws Exception {
        return json.writeValueAsString(java.util.Map.of("choices", List.of(java.util.Map.of(
                "finish_reason", "stop", "message", java.util.Map.of("content", content)))));
    }

    private byte[] quadrantPng(boolean allQuadrants) throws Exception {
        BufferedImage image = new BufferedImage(2_000, 1_200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.setColor(Color.BLACK);
            graphics.fillRect(1_250, 180, 260, 180);
            if (allQuadrants) {
                graphics.fillRect(1_250, 780, 260, 180);
                graphics.fillRect(250, 180, 260, 180);
                graphics.fillRect(250, 780, 260, 180);
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private byte[] smallPng() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "png", output));
            return output.toByteArray();
        } finally {
            image.flush();
        }
    }

    private static int countParts(JsonNode content, String type) {
        int count = 0;
        for (JsonNode part : content) if (type.equals(part.path("type").asText())) count++;
        return count;
    }

    private static HttpResponse<InputStream> response(int status, String body) {
        return response(status, body.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, byte[] body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(new ByteArrayInputStream(body));
        return response;
    }

    private static String requestBody(HttpRequest request) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() { }
        });
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;
        private TrackingInputStream(byte[] body) { super(body); }
        @Override public void close() {
            closed = true;
            try { super.close(); } catch (java.io.IOException ignored) { }
        }
    }
}
