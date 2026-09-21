package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import studio.bookhtml.service.BoundedHttp;

/**
 * J04：测试 profile 专用合成传输。解析请求中的题目声明并返回形状合法的合成答案，
 * reportedModel 固定为 mock-synthetic，usage 缺失（UNKNOWN）。绝不用于生产，
 * 只在 DecisionProperties.provider=MOCK 时装配。
 */
public class MockDecisionTransport implements DecisionTransport {
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public BoundedHttp.Response send(HttpRequest request, long deadlineNanos, int maxBytes,
                                     BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) throw new RuntimeException("mock cancelled");
        try {
            // 同步 BodyPublisher（ofByteArray/ofString）在 subscribe 返回前完成投递；满足本客户端构造的请求。
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            request.bodyPublisher().ifPresent(publisher -> publisher.subscribe(
                    new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                        public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                            s.request(Long.MAX_VALUE);
                        }
                        public void onNext(java.nio.ByteBuffer item) {
                            byte[] chunk = new byte[item.remaining()];
                            item.get(chunk);
                            out.write(chunk, 0, chunk.length);
                        }
                        public void onError(Throwable t) { done.countDown(); }
                        public void onComplete() { done.countDown(); }
                    }));
            done.await(5, java.util.concurrent.TimeUnit.SECONDS);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = json.readValue(out.toByteArray(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> questions = (Map<String, Object>) body.getOrDefault("questions", Map.of());
            Map<String, Object> answers = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : questions.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> question = (Map<String, Object>) e.getValue();
                String type = String.valueOf(question.getOrDefault("type", ""));
                @SuppressWarnings("unchecked")
                Map<String, String> criteria = (Map<String, String>) question.getOrDefault("criteria", Map.of());
                switch (type) {
                    case "choice" -> {
                        String first = criteria.keySet().stream().sorted().findFirst().orElse("C0");
                        Map<String, Double> distribution = new LinkedHashMap<>();
                        double rest = criteria.isEmpty() ? 0 : 0.4 / Math.max(1, criteria.size() - 1);
                        for (String key : criteria.keySet()) distribution.put(key, key.equals(first) ? 0.6 : rest);
                        answers.put(e.getKey(), Map.of("type", "choice", "choice", first,
                                "probabilities", distribution, "confidence", 0.3));
                    }
                    case "noul" -> answers.put(e.getKey(), Map.of("type", "noul", "noul", 0.5));
                    case "score" -> answers.put(e.getKey(), Map.of("type", "score", "score", 2.0,
                            "probabilities", Map.of("2", 1.0), "confidence", 0.3));
                    default -> answers.put(e.getKey(), Map.of("type", type));
                }
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("answers", answers);
            response.put("model", "mock-synthetic");
            byte[] bytes = json.writeValueAsBytes(response);
            if (bytes.length > maxBytes) throw new RuntimeException("mock response too large");
            return new BoundedHttp.Response(200, bytes);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("mock transport 失败", e);
        }
    }

    public static String describe() {
        return "mock-synthetic:合成答案，仅测试 profile";
    }

    public static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
