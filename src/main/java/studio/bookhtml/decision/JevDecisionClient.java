package studio.bookhtml.decision;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import studio.bookhtml.service.BoundedHttp;
import studio.bookhtml.service.UsageLedger;

/**
 * J04：单次传输、严格响应校验。每次真正发送登记独立 physicalAttemptId（由调用方传入）；
 * 首版关闭隐式重试与自动跨渠道 fallback；429/5xx/timeout/parse error 分别展示；
 * 用户明确重试产生可核算的新 attempt。业务代码只消费归一化 DTO，不直接解析任意 JsonNode。
 */
@Service
public class JevDecisionClient {
    public enum Kind {
        PROTOCOL, UNAUTHORIZED, INVALID_REQUEST, RATE_LIMITED, SERVER_ERROR,
        NETWORK, TIMEOUT, CANCELLED, TOO_LARGE
    }

    public static final class JevCallException extends Exception {
        private final Kind kind;
        public JevCallException(Kind kind, String message) { super(message); this.kind = kind; }
        public JevCallException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }
        public Kind kind() { return kind; }
    }

    public record QuestionSpec(String type, String instructions, Map<String, String> criteria) {
        public QuestionSpec {
            if (!Set.of("choice", "noul", "score").contains(type))
                throw new IllegalArgumentException("问题类型非法");
            if (instructions == null || instructions.isBlank())
                throw new IllegalArgumentException("instructions 为空");
            criteria = criteria == null ? Map.of() : Map.copyOf(criteria);
        }
    }

    public record ScoreAnswer(String questionId, double score,
                              Map<String, Double> probabilities, Double confidence) {
        public ScoreAnswer {
            if (Double.isNaN(score) || Double.isInfinite(score))
                throw new IllegalArgumentException("score 非法");
            probabilities = probabilities == null ? Map.of() : Map.copyOf(probabilities);
        }
    }

    public record CallResult(DecisionModels.NormalizedChoice choice, DecisionModels.NormalizedNoul gap,
                             Map<String, ScoreAnswer> scores, Map<String, Long> usage,
                             String reportedModel, String providerRequestId, String responseHash) {
        public CallResult {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            usage = usage == null ? null : Map.copyOf(usage);
        }
    }

    private final ObjectMapper json;
    private final ObjectReader strictReader;
    private final DecisionTransport transport;
    private UsageLedger usageLedger;

    public JevDecisionClient(ObjectMapper json, DecisionTransport transport) {
        this.json = json;
        this.strictReader = json.copy().reader()
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.transport = transport;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUsageLedger(UsageLedger usageLedger) { this.usageLedger = usageLedger; }

    /**
     * 单次物理发送。endpoint 由调用方传入冻结的官方地址（生产）或测试 loopback（测试 profile）。
     * 异常信息只含状态与原因分类，绝不含密钥、正文与原始 body。
     */
    public CallResult callOnce(String endpoint, String apiKey, String model,
                               Map<String, Object> state, Map<String, QuestionSpec> questions,
                               long deadlineNanos, int maxRequestBytes, int maxResponseBytes,
                               BooleanSupplier cancelled) throws JevCallException {
        if (endpoint == null || endpoint.isBlank()) throw new JevCallException(Kind.PROTOCOL, "endpoint 为空");
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            throw new JevCallException(Kind.PROTOCOL, "endpoint 非法", e);
        }
        // 固定官方 HTTPS 端点；测试 loopback 仅限测试 profile 的 http 本地地址
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
            throw new JevCallException(Kind.PROTOCOL, "endpoint 非法");
        if (apiKey == null || apiKey.isBlank()) throw new JevCallException(Kind.UNAUTHORIZED, "缺失 API key");
        if (model == null || model.isBlank()) throw new JevCallException(Kind.PROTOCOL, "模型为空");
        if (questions == null || questions.isEmpty()) throw new JevCallException(Kind.PROTOCOL, "问题为空");
        byte[] body;
        try {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("model", model);
            request.put("state", state);
            Map<String, Object> questionMap = new LinkedHashMap<>();
            for (Map.Entry<String, QuestionSpec> e : questions.entrySet()) {
                Map<String, Object> question = new LinkedHashMap<>();
                question.put("type", e.getValue().type());
                question.put("instructions", e.getValue().instructions());
                question.put("criteria", e.getValue().criteria());
                questionMap.put(e.getKey(), question);
            }
            request.put("questions", questionMap);
            body = json.writeValueAsBytes(request);
        } catch (IOException e) {
            throw new JevCallException(Kind.PROTOCOL, "请求序列化失败", e);
        }
        if (body.length > maxRequestBytes)
            throw new JevCallException(Kind.TOO_LARGE, "请求超过最大字节限制");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        String usageAttempt = null;
        if (usageLedger != null) {
            try { usageAttempt = usageLedger.start("jev", model); }
            catch (IOException e) { throw new JevCallException(Kind.PROTOCOL, "用量账本不可用，禁止决策外呼"); }
        }
        BoundedHttp.Response response;
        try {
            response = transport.send(request, deadlineNanos, maxResponseBytes, cancelled);
        } catch (BoundedHttp.BoundedHttpException e) {
            throw switch (e.kind()) {
                case TIMEOUT -> new JevCallException(Kind.TIMEOUT, "决策请求总时限耗尽", e);
                case CANCELLED -> new JevCallException(Kind.CANCELLED, "决策请求已取消", e);
                case TOO_LARGE -> new JevCallException(Kind.TOO_LARGE, "决策响应超过最大字节限制", e);
                case IO -> new JevCallException(Kind.NETWORK, "决策网络读写失败", e);
            };
        } catch (IOException e) {
            throw new JevCallException(Kind.NETWORK, "决策网络读写失败", e);
        }
        try {
        int status = response.status();
        if (status == 401) throw new JevCallException(Kind.UNAUTHORIZED, "供应商拒绝授权");
        if (status == 422) throw new JevCallException(Kind.INVALID_REQUEST, "供应商拒绝请求体");
        if (status == 429) throw new JevCallException(Kind.RATE_LIMITED, "供应商限流");
        if (status == 529 || (status >= 500 && status <= 599))
            throw new JevCallException(Kind.SERVER_ERROR, "供应商服务端错误");
        if (status < 200 || status >= 300)
            throw new JevCallException(Kind.PROTOCOL, "供应商返回非预期状态");
        String raw = new String(response.body(), StandardCharsets.UTF_8);
        String responseHash = DecisionHash.sha256Hex(raw);
        JsonNode root;
        try {
            root = strictReader.readTree(raw);
        } catch (IOException e) {
            throw new JevCallException(Kind.PROTOCOL, "决策响应非合法 JSON", e);
        }
        if (usageAttempt != null) {
            try { usageLedger.captureUsage(usageAttempt, root); }
            catch (IOException e) { throw new JevCallException(Kind.PROTOCOL, "用量账本不可用，决策响应未采用"); }
        }
        if (root == null || !root.isObject() || !root.has("answers") || !root.get("answers").isObject())
            throw new JevCallException(Kind.PROTOCOL, "决策响应缺少 answers");
        JsonNode answers = root.get("answers");
        if (answers.size() != questions.size())
            throw new JevCallException(Kind.PROTOCOL, "决策响应题目数量不符");
        for (String id : questions.keySet())
            if (!answers.has(id)) throw new JevCallException(Kind.PROTOCOL, "决策响应缺少必答题");
        for (var field : (Iterable<Map.Entry<String, JsonNode>>) answers::fields)
            if (!questions.containsKey(field.getKey()))
                throw new JevCallException(Kind.PROTOCOL, "决策响应含非预期题目");
        DecisionModels.NormalizedChoice choice = null;
        DecisionModels.NormalizedNoul gap = null;
        Map<String, ScoreAnswer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, QuestionSpec> e : questions.entrySet()) {
            String id = e.getKey();
            QuestionSpec spec = e.getValue();
            JsonNode answer = answers.get(id);
            if (!answer.isObject() || !spec.type().equals(answer.path("type").asText(null)))
                throw new JevCallException(Kind.PROTOCOL, "决策答案类型不符");
            switch (spec.type()) {
                case "choice" -> {
                    if (choice != null) throw new JevCallException(Kind.PROTOCOL, "重复 Choice 题");
                    choice = parseChoice(id, spec, answer);
                }
                case "noul" -> {
                    if (gap != null) throw new JevCallException(Kind.PROTOCOL, "重复 Noul 题");
                    gap = parseNoul(id, answer);
                }
                case "score" -> scores.put(id, parseScore(id, answer));
                default -> throw new JevCallException(Kind.PROTOCOL, "未知问题类型");
            }
        }
        Map<String, Long> usage = parseUsage(root);
        String reportedModel = root.has("model") && root.get("model").isTextual()
                ? root.get("model").asText() : null;
        String requestId = null;
        for (String key : List.of("requestId", "request_id", "id"))
            if (root.has(key) && root.get(key).isTextual()) { requestId = root.get(key).asText(); break; }
        if (usageAttempt != null) {
            try { usageLedger.succeeded(usageAttempt); }
            catch (IOException e) { throw new JevCallException(Kind.PROTOCOL, "用量账本不可用，决策响应未采用"); }
        }
        return new CallResult(choice, gap, scores, usage, reportedModel, requestId, responseHash);
        } catch (JevCallException e) {
            if (usageAttempt != null) {
                try { usageLedger.failed(usageAttempt); }
                catch (IOException ignored) { throw new JevCallException(Kind.PROTOCOL, "用量账本不可用，决策响应未采用"); }
            }
            throw e;
        }
    }

    private DecisionModels.NormalizedChoice parseChoice(String id, QuestionSpec spec, JsonNode answer)
            throws JevCallException {
        String selected = answer.has("choice") && answer.get("choice").isTextual()
                ? answer.get("choice").asText() : null;
        if (selected == null || !spec.criteria().containsKey(selected))
            throw new JevCallException(Kind.PROTOCOL, "未知候选别名");
        JsonNode probabilities = answer.get("probabilities");
        if (probabilities == null || !probabilities.isObject() || probabilities.isEmpty())
            throw new JevCallException(Kind.PROTOCOL, "概率分布缺失");
        Map<String, Double> distribution = new LinkedHashMap<>();
        double sum = 0;
        for (var field : (Iterable<Map.Entry<String, JsonNode>>) probabilities::fields) {
            if (!spec.criteria().containsKey(field.getKey()))
                throw new JevCallException(Kind.PROTOCOL, "概率含未知别名");
            if (!field.getValue().isNumber())
                throw new JevCallException(Kind.PROTOCOL, "概率值非法");
            double probability = field.getValue().asDouble();
            if (Double.isNaN(probability) || Double.isInfinite(probability)
                    || probability < 0 || probability > 1)
                throw new JevCallException(Kind.PROTOCOL, "概率值非法");
            distribution.put(field.getKey(), probability);
            sum += probability;
        }
        // 分布求和容差为本地严格规则（真实合同未核实前不放宽，不强行归一化修复）
        if (sum < 0.99 || sum > 1.01) throw new JevCallException(Kind.PROTOCOL, "概率分布不满足契约");
        Double confidence = null;
        if (answer.has("confidence")) {
            if (!answer.get("confidence").isNumber())
                throw new JevCallException(Kind.PROTOCOL, "confidence 非法");
            confidence = answer.get("confidence").asDouble();
            if (Double.isNaN(confidence) || Double.isInfinite(confidence)
                    || confidence < 0 || confidence > 1)
                throw new JevCallException(Kind.PROTOCOL, "confidence 非法");
        }
        List<String> missing = new ArrayList<>(spec.criteria().keySet());
        missing.removeAll(distribution.keySet());
        if (!missing.isEmpty()) throw new JevCallException(Kind.PROTOCOL, "概率分布缺项");
        try {
            return new DecisionModels.NormalizedChoice(id, selected, distribution, confidence);
        } catch (IllegalArgumentException e) {
            throw new JevCallException(Kind.PROTOCOL, "答案归一化失败", e);
        }
    }

    private DecisionModels.NormalizedNoul parseNoul(String id, JsonNode answer) throws JevCallException {
        if (!answer.has("noul") || !answer.get("noul").isNumber())
            throw new JevCallException(Kind.PROTOCOL, "noul 缺失");
        double pYes = answer.get("noul").asDouble();
        try {
            return new DecisionModels.NormalizedNoul(id, pYes);
        } catch (IllegalArgumentException e) {
            throw new JevCallException(Kind.PROTOCOL, "noul 非法", e);
        }
    }

    private ScoreAnswer parseScore(String id, JsonNode answer) throws JevCallException {
        if (!answer.has("score") || !answer.get("score").isNumber())
            throw new JevCallException(Kind.PROTOCOL, "score 缺失");
        double score = answer.get("score").asDouble();
        Map<String, Double> distribution = new LinkedHashMap<>();
        JsonNode probabilities = answer.get("probabilities");
        if (probabilities != null && probabilities.isObject())
            for (var field : (Iterable<Map.Entry<String, JsonNode>>) probabilities::fields) {
                if (!field.getValue().isNumber()) throw new JevCallException(Kind.PROTOCOL, "score 分布非法");
                double probability = field.getValue().asDouble();
                if (Double.isNaN(probability) || Double.isInfinite(probability)
                        || probability < 0 || probability > 1)
                    throw new JevCallException(Kind.PROTOCOL, "score 分布非法");
                distribution.put(field.getKey(), probability);
            }
        Double confidence = null;
        if (answer.has("confidence") && answer.get("confidence").isNumber()) {
            confidence = answer.get("confidence").asDouble();
            if (Double.isNaN(confidence) || Double.isInfinite(confidence)
                    || confidence < 0 || confidence > 1)
                throw new JevCallException(Kind.PROTOCOL, "score confidence 非法");
        }
        try {
            return new ScoreAnswer(id, score, distribution, confidence);
        } catch (IllegalArgumentException e) {
            throw new JevCallException(Kind.PROTOCOL, "score 归一化失败", e);
        }
    }

    private Map<String, Long> parseUsage(JsonNode root) throws JevCallException {
        if (!root.has("usage")) return null;
        JsonNode usage = root.get("usage");
        if (!usage.isObject()) throw new JevCallException(Kind.PROTOCOL, "usage 非法");
        Map<String, Long> result = new LinkedHashMap<>();
        for (String key : List.of("input_tokens", "output_tokens")) {
            if (!usage.has(key)) continue;
            if (!usage.get(key).isNumber() || usage.get(key).asLong() < 0)
                throw new JevCallException(Kind.PROTOCOL, "usage 非法");
            result.put(key, usage.get(key).asLong());
        }
        return result;
    }
}
