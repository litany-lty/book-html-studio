package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * U5：确定性合并。按计划顺序合并，不按返回先后；失败组保留原文；
 * 子任务不保存整页；用量上下文按线程显式传播。
 */
class QwenAssistCoordinatorTest {

    private final ObjectMapper json = new ObjectMapper();
    private QwenAssistCoordinator coordinator;

    @AfterEach void close() {
        if (coordinator != null) coordinator.close();
    }

    private static Block text(String id, int order, String content) {
        return new Block(id, "text", order, new double[]{.1, .1 + order * .05, .5, .04},
                "horizontal-tb", content, content, 0.9, false, false, null, "paddle",
                List.of(id), null, new double[]{10, 20, 50, 10}, List.of());
    }

    private static byte[] png() {
        return new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> httpResponse(String chunkId, String findingsJson) {
        String content = "{\"chunkId\":\"" + chunkId + "\",\"findings\":" + findingsJson + "}";
        String envelope = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + jsonEscape(content) + "}}]}";
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static String jsonEscape(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private QwenAssistCoordinator coordinator(QwenTextReviewClient.Transport transport,
                                              QwenLayoutClient structure) {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setEnabled(true);
        config.setApiKey("test-key");
        config.setBaseUrl("http://127.0.0.1:9/");
        config.setModel("qwen-test");
        config.setTimeoutSeconds(5);
        QwenRequestGate gate = new QwenRequestGate(new QwenAssistProperties());
        QwenTextReviewClient review = new QwenTextReviewClient(config, json, transport);
        review.setRequestGate(gate);
        coordinator = new QwenAssistCoordinator();
        coordinator.setRequestGate(gate);
        coordinator.setReviewClient(review);
        coordinator.setStructureClient(structure);
        coordinator.setConverter(new TraditionalConverter());
        return coordinator;
    }

    private static QwenLayoutClient silentStructure() {
        QwenLayoutClient structure = mock(QwenLayoutClient.class);
        when(structure.configured()).thenReturn(false);
        return structure;
    }

    private static Map<String, String> parents(List<Block> blocks) {
        Map<String, String> map = new HashMap<>();
        for (Block block : blocks) map.put(block.id(), block.original());
        return map;
    }

    private static Map<String, byte[]> regions(QwenTaskPlanner.PlannedReview plan) {
        Map<String, byte[]> map = new HashMap<>();
        for (QwenTaskPlanner.ChunkTask chunk : plan.chunks()) map.put(chunk.chunkId(), png());
        map.put("__overview__", png());
        return map;
    }

    /**
     * 可读请求体的智能替身：按请求内 chunkId 回显合法发现（首 2 字），
     * 指定 chunk 延迟以倒置完成顺序。BodyPublisher 经订阅读取。
     */
    private static String readRequestBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElseThrow();
        var future = new java.util.concurrent.CompletableFuture<String>();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                out.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(out.toString(StandardCharsets.UTF_8));
            }
        });
        return future.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static QwenTextReviewClient.Transport smartDouble(String slowChunkId) {
        return request -> {
            String body = readRequestBody(request);
            // 请求体为外层 JSON：prompt 内引号均转义为\\\"（Java 字符串内写做 \\\\\\\"）。
            java.util.regex.Matcher idMatcher = java.util.regex.Pattern.compile(
                    "\\\\\"chunkId\\\\\":\\\\\"([^\"\\\\]+)\\\\\"").matcher(body);
            String chunkId = idMatcher.find() ? idMatcher.group(1) : "review-00";
            if (chunkId.equals(slowChunkId)) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            java.util.regex.Matcher owned = java.util.regex.Pattern.compile(
                    "\\\\\"sourceId\\\\\":\\\\\"([^\"\\\\]+)\\\\\","
                            + "\\\\\"slice\\\\\":\\\\\"[^\"\\\\]+\\\\\","
                            + "\\\\\"start\\\\\":0,\\\\\"end\\\\\":(\\d+),"
                            + "\\\\\"text\\\\\":\\\\\"((?:(?!\\\\\").)+)\\\\\"")
                    .matcher(body);
            StringBuilder findings = new StringBuilder("[");
            boolean first = true;
            while (owned.find()) {
                String sourceId = owned.group(1);
                String text = owned.group(3).replace("\\\\\"", "\"").replace("\\\\", "\\");
                if (text.length() < 2) continue;
                String quote = text.substring(0, 2);
                if (!first) findings.append(",");
                first = false;
                findings.append("{\"sourceId\":\"").append(sourceId).append("\",\"start\":0,\"end\":2,")
                        .append("\"quote\":\"").append(quote).append("\",")
                        .append("\"kind\":\"suspected\",\"candidateText\":\"").append(quote).append("\",")
                        .append("\"reason\":\"字形\"}");
            }
            findings.append("]");

            return httpResponse(chunkId, findings.toString());
        };
    }

    @Test void qw05_mergeFollowsPlanOrderNotReturnOrder() throws Exception {
        List<Block> baseline = new ArrayList<>();
        for (int i = 0; i < 20; i++) baseline.add(text("b" + i, i, "甲乙丙丁正文" + i));
        // review-00 延迟 500ms：完成顺序与计划顺序必然倒置（C、A、B 返回）。
        QwenAssistCoordinator coordinator = coordinator(smartDouble("review-00"), silentStructure());
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        assertEquals(2, plan.chunks().size());
        QwenAssistCoordinator.CoordinateResult result = coordinator.coordinate(
                "book-a", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        assertEquals(2, result.succeededChunks(), "两组都成功，不取最先返回者");
        assertEquals(20, result.blocks().size());
        for (int i = 0; i < 20; i++) {
            assertEquals("b" + i, result.blocks().get(i).id(), "合并按计划顺序，不按返回先后");
            assertEquals(i, result.blocks().get(i).order());
        }
        // 每块恰好一条发现，挂在正确父块上（无拼接、无错标邻字）。
        for (int i = 0; i < 20; i++) {
            List<ContentIssue> issues = result.blocks().get(i).issues();
            assertEquals(1, issues.size(), "块 b" + i + " 恰好一条发现");
            assertEquals("甲乙", issues.get(0).inferredText());
        }
    }

    @Test void qw05_structureOrderAppliedWhenValid() throws Exception {
        List<Block> baseline = List.of(
                text("b1", 0, "甲乙丙丁之一"),
                text("b2", 1, "甲乙丙丁之二"));
        QwenLayoutClient structure = mock(QwenLayoutClient.class);
        when(structure.configured()).thenReturn(true);
        when(structure.structurePlan(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("b2", "b1"));
        QwenAssistCoordinator coordinator =
                coordinator(request -> httpResponse("review-00", "[]"), structure);
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        QwenAssistCoordinator.CoordinateResult result = coordinator.coordinate(
                "book-a", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        assertEquals(List.of("b2", "b1"),
                result.blocks().stream().map(Block::id).toList());
        assertEquals(0, result.blocks().get(0).order());
        assertEquals(1, result.blocks().get(1).order());
    }

    @Test void qw10_failedChunkKeepsOriginalsWithPartial() throws Exception {
        List<Block> baseline = new ArrayList<>();
        for (int i = 0; i < 8; i++) baseline.add(text("b" + i, i, "正文内容" + i + "填充文字"));
        QwenAssistCoordinator coordinator = coordinator(request -> {
            throw new RuntimeException("transport down");
        }, silentStructure());
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        QwenAssistCoordinator.CoordinateResult result = coordinator.coordinate(
                "book-a", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        assertEquals(0, result.succeededChunks());
        assertTrue(result.failedChunks() > 0);
        // 失败组保留原文：块身份、文字、来源不变。
        assertEquals(baseline.size(), result.blocks().size());
        for (int i = 0; i < baseline.size(); i++) {
            assertEquals(baseline.get(i).original(), result.blocks().get(i).original());
        }
    }

    @Test void qw06_illegalFindingRejectedWhileLegalKept() throws Exception {
        List<Block> baseline = List.of(text("b1", 0, "甲乙丙丁戊己"));
        String findings = "["
                + "{\"sourceId\":\"b1\",\"start\":0,\"end\":2,\"quote\":\"甲乙\","
                + "\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"字形\"},"
                + "{\"sourceId\":\"b1\",\"start\":0,\"end\":2,\"quote\":\"甲乙\","
                + "\"kind\":\"suspected\",\"candidateText\":\"甲乙\",\"reason\":\"x\",\"bbox\":[0,0,1,1]},"
                + "{\"sourceId\":\"unknown\",\"start\":0,\"end\":1,\"quote\":\"甲\","
                + "\"kind\":\"suspected\",\"candidateText\":\"甲\",\"reason\":\"x\"}"
                + "]";
        QwenAssistCoordinator coordinator = coordinator(
                request -> httpResponse("review-00", findings), silentStructure());
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        QwenAssistCoordinator.CoordinateResult result = coordinator.coordinate(
                "book-a", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        List<ContentIssue> issues = result.blocks().get(0).issues();
        assertEquals(1, issues.size(), "非法部分拒绝并保留来源");
        ContentIssue issue = issues.get(0);
        assertEquals(0, issue.start());
        assertEquals(2, issue.end());
        assertEquals("甲乙", issue.inferredText());
        assertFalse(issue.resolved(), "普通核对不产生已确认结论");
        assertTrue(result.blocks().get(0).uncertain());
    }

    @Test void qw13_usageContextPropagatedPerThreadWithoutMixing() throws Exception {
        List<String> seenBooks = new java.util.concurrent.CopyOnWriteArrayList<>();
        QwenAssistCoordinator coordinator = coordinator(request -> {
            UsageContext.Value value = UsageContext.current();
            seenBooks.add(value == null ? "null" : value.bookId() + ":" + value.operation());
            return httpResponse("review-00", "[]");
        }, silentStructure());
        List<Block> baseline = List.of(text("b1", 0, "甲乙丙丁戊己庚辛"));
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        coordinator.coordinate("book-A", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        coordinator.coordinate("book-B", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        assertTrue(seenBooks.stream().anyMatch(s -> s.startsWith("book-A:QWEN_")),
                "子任务显式携带 book/page/task：" + seenBooks);
        assertTrue(seenBooks.stream().anyMatch(s -> s.startsWith("book-B:QWEN_")),
                "线程复用不串书：" + seenBooks);
        assertFalse(seenBooks.contains("null"), "工作线程内 scope 必须打开");
    }

    @Test void qw_invalidStructureOrderFallsBack() throws Exception {
        List<Block> baseline = List.of(
                text("b1", 0, "甲乙丙丁之一"),
                text("b2", 1, "甲乙丙丁之二"));
        QwenLayoutClient structure = mock(QwenLayoutClient.class);
        when(structure.configured()).thenReturn(true);
        // 缺失 ID：整体拒绝，回退已验证顺序。
        when(structure.structurePlan(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of("b2"));
        QwenAssistCoordinator coordinator =
                coordinator(request -> httpResponse("review-00", "[]"), structure);
        QwenTaskPlanner planner = new QwenTaskPlanner(new QwenAssistProperties());
        QwenTaskPlanner.PlannedReview plan = planner.planReview(baseline, "v1", "u3.1");
        QwenAssistCoordinator.CoordinateResult result = coordinator.coordinate(
                "book-a", 1, baseline, parents(baseline), plan, regions(plan),
                null, "auto", true, () -> false);
        assertEquals(List.of("b1", "b2"),
                result.blocks().stream().map(Block::id).toList());
    }
}
