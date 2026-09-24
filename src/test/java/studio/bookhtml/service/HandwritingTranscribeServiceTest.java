package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;

import java.awt.image.BufferedImage;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C：手写/影印稿转写通道。
 * 关注三件事：分栏几何是否正确、产出是否一律"模型推断·待核对"、失败是否给出可读原因。
 */
class HandwritingTranscribeServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    private HandwritingTranscribeService service(HandwritingTranscribeService.Transport transport) {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("test-key");
        config.setModel("qwen3.8-max");
        return new HandwritingTranscribeService(config, json, transport);
    }

    private static HttpResponse<String> ok(String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return 200; }
            @Override public java.net.http.HttpRequest request() { return null; }
            @Override public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
            @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
            @Override public String body() { return body; }
            @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
            @Override public java.net.URI uri() { return java.net.URI.create("https://example.invalid/chat/completions"); }
            @Override public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
        };
    }

    private static BufferedImage page(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int v = (x * 7 + y * 3) % 200 + 20;
                image.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return image;
    }

    @Test
    void stripsAreOrderedRightToLeftForVerticalTextAndStayInPage() {
        List<double[]> strips = HandwritingTranscribeService.stripBoxes(true);
        assertEquals(HandwritingTranscribeService.MAX_STRIPS, strips.size());
        double previousX = 2;
        for (double[] box : strips) {
            assertTrue(box[0] >= 0 && box[0] <= 1, "x 必须在页内");
            assertTrue(box[0] + box[2] <= 1.0001, "栏宽不得越界");
            assertTrue(box[0] < previousX, "竖排必须从右到左");
            previousX = box[0];
        }
    }

    @Test
    void stripsAreOrderedTopToBottomForHorizontalText() {
        List<double[]> strips = HandwritingTranscribeService.stripBoxes(false);
        double previousY = -1;
        for (double[] box : strips) {
            assertTrue(box[1] > previousY, "横排必须从上到下");
            previousY = box[1];
        }
    }

    @Test
    void transcriptionIsAlwaysMarkedUncertainAndNeverAutoReviewed() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandwritingTranscribeService service = service(request -> {
            calls.incrementAndGet();
            return ok("{\"choices\":[{\"message\":{\"content\":\"一、雖俱童妾。\\n女兒人官之。\"}}]}");
        });
        List<Block> blocks = service.transcribe(page(1200, 1600), "auto", () -> false);
        assertFalse(blocks.isEmpty());
        for (Block block : blocks) {
            assertEquals("text", block.type());
            assertTrue(block.uncertain(), "模型转写必须标记为待核对");
            assertFalse(block.reviewed(), "模型转写绝不能自动视为已人工校对");
            assertEquals(HandwritingTranscribeService.SOURCE, block.source());
            assertEquals("vertical-rl", block.writingMode());
            assertTrue(block.suggestion().contains("核对"));
        }
        assertTrue(calls.get() >= 1);
    }

    @Test
    void emptyTranscriptionRaisesReadableNoTextError() {
        HandwritingTranscribeService service = service(request ->
                ok("{\"choices\":[{\"message\":{\"content\":\"   \"}}]}"));
        OcrNoTextException error = assertThrows(OcrNoTextException.class,
                () -> service.transcribe(page(900, 1200), "vertical", () -> false));
        assertTrue(error.getMessage().contains("手写转写未得到可用文字"));
    }

    @Test
    void cancellationStopsBeforeCallingTheModel() {
        HandwritingTranscribeService service = service(request -> {
            throw new AssertionError("取消后不应再发起请求");
        });
        assertThrows(CancelledException.class, () -> service.transcribe(page(900, 1200), "vertical", () -> true));
    }

    @Test
    void enhanceUpscalesSmallStripsAndKeepsGrayscale() {
        BufferedImage small = page(300, 400);
        BufferedImage enhanced = HandwritingTranscribeService.enhance(small);
        assertTrue(enhanced.getWidth() >= 300, "小栏必须放大以便模型辨认");
        for (int y = 0; y < enhanced.getHeight(); y += 37) {
            for (int x = 0; x < enhanced.getWidth(); x += 41) {
                int rgb = enhanced.getRGB(x, y);
                assertEquals((rgb >> 16) & 255, rgb & 255, "增强后应为灰度");
            }
        }
    }

    @Test
    void illegibleCharactersGetInferredSuggestionsWithoutOverwritingOriginal() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandwritingTranscribeService service = service(request -> {
            int n = calls.incrementAndGet();
            int phases = HandwritingTranscribeService.MAX_STRIPS * 2;
            if (n <= phases) {
                // 奇数：逐栏图像转录（含未辨认 □）；偶数：自动图文核对
                if (n % 2 == 1) return ok("{\"choices\":[{\"message\":{\"content\":\"結果□□很高興\"}}]}");
                return ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}]}");
            }
            // 之后：纯文本联想补全
            return ok("{\"choices\":[{\"message\":{\"content\":\"結果〔很〕〔是〕高興\"}}]}");
        });
        List<Block> blocks = service.transcribe(page(1200, 1600), "vertical", () -> false);
        assertEquals(HandwritingTranscribeService.MAX_STRIPS, blocks.size());
        Block block = blocks.get(0);
        assertEquals("結果□□很高興", block.original(), "original 必须保留未辨认标记，不得被推测覆盖");
        assertTrue(block.suggestion().startsWith(HandwritingTranscribeService.INFERRED_LABEL), "推测只能进未确认建议");
        assertTrue(block.suggestion().contains("〔很〕"), "建议应包含按上下文推测的字");
        assertTrue(block.uncertain(), "带推测的转写仍是待核对");
        assertEquals(HandwritingTranscribeService.MAX_STRIPS * 3, calls.get(), "每栏：转录 + 自动核对 + 联想补全");
    }

    @Test
    void legibleStripsSkipInferenceCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandwritingTranscribeService service = service(request -> {
            int n = calls.incrementAndGet();
            if (n % 2 == 1) return ok("{\"choices\":[{\"message\":{\"content\":\"全部可辨\"}}]}");
            return ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[]}\"}}]}");
        });
        List<Block> blocks = service.transcribe(page(1200, 1600), "vertical", () -> false);
        assertTrue(blocks.stream().allMatch(b -> b.suggestion().equals(HandwritingTranscribeService.DEFAULT_SUGGESTION)));
        assertEquals(HandwritingTranscribeService.MAX_STRIPS * 2, calls.get(), "无 □ 时不做联想补全，但仍做自动核对");
    }

    @Test
    void verificationTurnsFindingsIntoUnconfirmedIssues() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandwritingTranscribeService service = service(request -> {
            int n = calls.incrementAndGet();
            if (n % 2 == 1) return ok("{\"choices\":[{\"message\":{\"content\":\"結果很好高興\"}}]}");
            return ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[{\\\"index\\\":1,\\\"char\\\":\\\"果\\\",\\\"verdict\\\":\\\"mismatch\\\",\\\"likely\\\":\\\"實\\\",\\\"reason\\\":\\\"笔画不符\\\"}]}\"}}]}");
        });
        List<Block> blocks = service.transcribe(page(1200, 1600), "vertical", () -> false);
        Block block = blocks.get(0);
        assertEquals("結果很好高興", block.original(), "自动验证绝不能改写原文");
        assertFalse(block.issues().isEmpty(), "核对发现必须形成待核对疑点");
        var issue = block.issues().get(0);
        assertFalse(issue.resolved(), "自动验证结果绝不能被标为已解决");
        assertEquals(1, issue.start());
        assertEquals("實", issue.inferredText());
        assertTrue(issue.reason().contains("图像核对"));
    }

    @Test
    void verificationDiscardsFindingsWhoseCharacterDoesNotMatch() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HandwritingTranscribeService service = service(request -> {
            int n = calls.incrementAndGet();
            if (n % 2 == 1) return ok("{\"choices\":[{\"message\":{\"content\":\"甲乙丙\"}}]}");
            // index=1 的字是「乙」，返回的 char 不符 → 必须丢弃，避免把错误索引套到文本上
            return ok("{\"choices\":[{\"message\":{\"content\":\"{\\\"findings\\\":[{\\\"index\\\":1,\\\"char\\\":\\\"丁\\\",\\\"verdict\\\":\\\"mismatch\\\",\\\"likely\\\":\\\"戊\\\"}]}\"}}]}");
        });
        List<Block> blocks = service.transcribe(page(1200, 1600), "vertical", () -> false);
        assertTrue(blocks.stream().allMatch(b -> b.issues().isEmpty()), "字不符的核对结论必须丢弃");
    }

    @Test
    void inferenceBreakdownListsPerCharacterCandidates() {
        String structured = HandwritingTranscribeService.describeInference(
                "{\"fills\":[{\"index\":2,\"candidates\":[\"很\",\"蠻\"]},{\"index\":3,\"candidates\":[]}],\"text\":\"結果〔很〕□很高興\"}");
        assertTrue(structured.startsWith(HandwritingTranscribeService.INFERRED_LABEL), "逐条候选仍标注为未确认推测");
        assertTrue(structured.contains("第 3 字：很 / 蠻"), structured);
        assertTrue(structured.contains("第 4 字：无有把握的候选"), structured);
        assertTrue(structured.contains("补全版：結果〔很〕□很高興"), structured);
        // 模型没按 JSON 返回时的纯文本降级
        String plain = HandwritingTranscribeService.describeInference("結果〔很〕高興");
        assertTrue(plain.startsWith(HandwritingTranscribeService.INFERRED_LABEL));
        assertTrue(plain.contains("〔很〕"));
    }

    @Test
    void cleanRemovesCodeFencesAndBlankLines() {
        assertEquals("甲乙\n丙丁", HandwritingTranscribeService.clean("```text\n甲乙\n\n丙丁\n```"));
        assertEquals("", HandwritingTranscribeService.clean(null));
    }

    @Test
    void reportsNotConfiguredWithoutCredentials() {
        HandwritingTranscribeService unconfigured = new HandwritingTranscribeService(
                new QwenAssistProperties(), json, request -> ok("{}"));
        assertFalse(unconfigured.configured());
        OcrException error = assertThrows(OcrException.class,
                () -> unconfigured.transcribe(page(600, 800), "vertical", () -> false));
        assertTrue(error.getMessage().contains("未配置 Qwen 视觉凭据"));
    }
}
