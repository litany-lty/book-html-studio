package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * U5：局部核对请求/解析。本地 transport 替身固定响应；真实性能与准确性未验证。
 */
class QwenTextReviewClientTest {

    private final ObjectMapper json = new ObjectMapper();

    private static QwenAssistProperties config() {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setEnabled(true);
        config.setApiKey("test-key");
        config.setBaseUrl("http://127.0.0.1:9/");
        config.setModel("qwen-test");
        config.setTimeoutSeconds(5);
        return config;
    }

    private static QwenTaskPlanner.ChunkTask chunk(String id, String sourceId, String text) {
        return new QwenTaskPlanner.ChunkTask(id, QwenTaskPlanner.Kind.TEXT_REVIEW.name(),
                List.of(new QwenTaskPlanner.OwnedRange(sourceId, 0, text.length())),
                List.of(), 0, QwenTextReviewClient.PROMPT_VERSION, "u3.1");
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> httpResponse(int status, String body) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static String envelope(String chunkId, String findingsJson) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + "\"{\\\"chunkId\\\":\\\"" + chunkId + "\\\",\\\"findings\\\":" + findingsJson.replace("\"", "\\\"") + "}\""
                + "}}]}";
    }

    private QwenTextReviewClient client(QwenTextReviewClient.Transport transport, QwenRequestGate gate) {
        QwenTextReviewClient client = new QwenTextReviewClient(config(), json, transport);
        client.setRequestGate(gate);
        return client;
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    @Test void qw06_validFindingsAcceptedWithStrictOffsets() throws Exception {
        String text = "天人合一之道";
        QwenTaskPlanner.ChunkTask task = chunk("review-00", "s1", text);
        String findings = "[{\"sourceId\":\"s1\",\"start\":0,\"end\":4,"
                + "\"quote\":\"天人合一\",\"kind\":\"suspected\","
                + "\"candidateText\":\"天人合一\",\"reason\":\"字形依据\"}]";
        AtomicInteger calls = new AtomicInteger();
        QwenTextReviewClient client = client(request -> {
            calls.incrementAndGet();
            return httpResponse(200, envelope("review-00", findings));
        }, new QwenRequestGate(new QwenAssistProperties()));
        QwenRequestGate.Budget budget = new QwenRequestGate(new QwenAssistProperties()).newBudget();
        QwenTextReviewClient.ReviewResult result = client.reviewChunk(task, Map.of("s1", text),
                png(), null, true, budget, () -> false);
        assertEquals(1, result.findings().size());
        QwenTextReviewClient.ChunkFinding finding = result.findings().get(0);
        assertEquals("s1", finding.sourceId());
        assertEquals(0, finding.start());
        assertEquals(4, finding.end());
        assertEquals("天人合一", finding.quote());
        assertEquals(0, result.dropped());
        assertEquals(1, calls.get());
    }

    @Test void qw07_contextOnlyAndUnknownSourcesRejected() throws Exception {
        String owned = "甲乙丙丁";
        QwenTaskPlanner.ChunkTask task = new QwenTaskPlanner.ChunkTask("review-01",
                QwenTaskPlanner.Kind.TEXT_REVIEW.name(),
                List.of(new QwenTaskPlanner.OwnedRange("owned", 0, owned.length())),
                List.of(new QwenTaskPlanner.ContextRange("ctx", 0, 4)), 1,
                QwenTextReviewClient.PROMPT_VERSION, "u3.1");
        String findings = "["
                + "{\"sourceId\":\"ctx\",\"start\":0,\"end\":2,\"quote\":\"甲乙\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\"},"
                + "{\"sourceId\":\"ghost\",\"start\":0,\"end\":1,\"quote\":\"甲\",\"kind\":\"suspected\",\"candidateText\":\"甲\",\"reason\":\"x\"},"
                + "{\"sourceId\":\"owned\",\"start\":0,\"end\":2,\"quote\":\"甲乙\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\"},"
                + "{\"sourceId\":\"owned\",\"start\":0,\"end\":2,\"quote\":\"甲乙\",\"kind\":\"weird\",\"candidateText\":\"甲乙\",\"reason\":\"x\"},"
                + "{\"sourceId\":\"owned\",\"start\":0,\"end\":2,\"quote\":\"甲乙\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\",\"bbox\":[0,0,1,1]},"
                + "{\"sourceId\":\"owned\",\"start\":0,\"end\":9,\"quote\":\"甲乙丙丁\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\"},"
                + "{\"sourceId\":\"owned\",\"start\":1,\"end\":2,\"quote\":\"错字\",\"kind\":\"suspected\",\"candidateText\":\"乙\",\"reason\":\"x\"}"
                + "]";
        QwenTextReviewClient client = client(
                request -> httpResponse(200, envelope("review-01", findings)),
                new QwenRequestGate(new QwenAssistProperties()));
        QwenRequestGate.Budget budget = new QwenRequestGate(new QwenAssistProperties()).newBudget();
        QwenTextReviewClient.ReviewResult result = client.reviewChunk(task,
                Map.of("owned", owned, "ctx", "甲乙丙丁"), png(), null, true, budget, () -> false);
        assertEquals(1, result.findings().size(), "仅合法 owned 发现进入正式结果");
        assertEquals(6, result.dropped(), "上下文修改/未知块/非法类型/bbox/越界/quote 不等全部丢弃");
    }

    @Test void qw08_surrogateBoundariesRejected() throws Exception {
        String text = "甲" + "\uD83D\uDE00" + "乙";
        QwenTaskPlanner.ChunkTask task = chunk("review-02", "s1", text);
        // start 落在代理对中间（emoji 占索引 1-2，start=2 为低代理）。
        String findings = "[{\"sourceId\":\"s1\",\"start\":2,\"end\":3,"
                + "\"quote\":\"" + "\uDE00" + "\",\"kind\":\"suspected\",\"candidateText\":\"x\",\"reason\":\"x\"}]";
        QwenTextReviewClient client = client(
                request -> httpResponse(200, envelope("review-02", findings)),
                new QwenRequestGate(new QwenAssistProperties()));
        QwenTextReviewClient.ReviewResult result = client.reviewChunk(task, Map.of("s1", text),
                png(), null, true, new QwenRequestGate(new QwenAssistProperties()).newBudget(), () -> false);
        assertTrue(result.findings().isEmpty());
        assertEquals(1, result.dropped());
    }

    @Test void qw_chunkIdentityMismatchRejectsWholeChunk() {
        QwenTaskPlanner.ChunkTask task = chunk("review-03", "s1", "甲乙");
        QwenTextReviewClient client = client(
                request -> httpResponse(200, envelope("review-99", "[]")),
                new QwenRequestGate(new QwenAssistProperties()));
        assertThrows(OcrException.class, () -> client.reviewChunk(task, Map.of("s1", "甲乙"),
                png(), null, true, new QwenRequestGate(new QwenAssistProperties()).newBudget(), () -> false));
    }

    @Test void qw11_rateLimitedRetriesWithoutHoldingSlot() throws Exception {
        String text = "甲乙丙丁";
        QwenTaskPlanner.ChunkTask task = chunk("review-04", "s1", text);
        String findings = "[{\"sourceId\":\"s1\",\"start\":0,\"end\":2,"
                + "\"quote\":\"甲乙\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\"}]";
        AtomicInteger calls = new AtomicInteger();
        HttpResponse<InputStream> limited = mock(HttpResponse.class);
        when(limited.statusCode()).thenReturn(429);
        when(limited.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After", List.of("0")), (a, b) -> true));
        when(limited.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        QwenRequestGate gate = new QwenRequestGate(new QwenAssistProperties());
        QwenTextReviewClient client = client(request -> {
            if (calls.incrementAndGet() == 1) return limited;
            return httpResponse(200, envelope("review-04", findings));
        }, gate);
        QwenRequestGate.Budget budget = gate.newBudget();
        int before = budget.remaining();
        QwenTextReviewClient.ReviewResult result = client.reviewChunk(task, Map.of("s1", text),
                png(), null, true, budget, () -> false);
        assertEquals(1, result.findings().size());
        assertEquals(2, calls.get(), "429 重试受总预算约束");
        assertEquals(before - 2, budget.remaining(), "每次物理 HTTP 尝试均计入硬预算");
        assertEquals(0, gate.inFlight(), "槽位已释放");
    }

    @Test void qw_budgetExhaustedKeepsOriginalsWithoutCall() {
        QwenTaskPlanner.ChunkTask task = chunk("review-05", "s1", "甲乙");
        AtomicInteger calls = new AtomicInteger();
        QwenTextReviewClient client = client(request -> {
            calls.incrementAndGet();
            return httpResponse(200, envelope("review-05", "[]"));
        }, new QwenRequestGate(new QwenAssistProperties()));
        QwenRequestGate.Budget budget = new QwenRequestGate(new QwenAssistProperties()).newBudget();
        assertTrue(budget.reserve(8));
        assertThrows(OcrException.class, () -> client.reviewChunk(task, Map.of("s1", "甲乙"),
                png(), null, true, budget, () -> false));
        assertEquals(0, calls.get(), "预算不足不发出调用，保留原文");
    }

    @Test void qw_cacheHitDoesNotRecallOrForgeTokens() throws Exception {
        String text = "甲乙丙丁";
        QwenTaskPlanner.ChunkTask task = chunk("review-06", "s1", text);
        String findings = "[{\"sourceId\":\"s1\",\"start\":0,\"end\":2,"
                + "\"quote\":\"甲乙\",\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\"}]";
        AtomicInteger calls = new AtomicInteger();
        QwenTextReviewClient client = client(request -> {
            calls.incrementAndGet();
            return httpResponse(200, envelope("review-06", findings));
        }, new QwenRequestGate(new QwenAssistProperties()));
        QwenRequestGate.Budget budget = new QwenRequestGate(new QwenAssistProperties()).newBudget();
        Map<String, String> parents = Map.of("s1", text);
        client.reviewChunk(task, parents, png(), null, true, budget, () -> false);
        QwenTextReviewClient.ReviewResult second =
                client.reviewChunk(task, parents, png(), null, true, budget, () -> false);
        assertEquals(1, calls.get(), "缓存命中不调用");
        assertEquals(1, second.findings().size());
    }
}
