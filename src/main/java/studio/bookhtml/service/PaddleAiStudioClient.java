package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.domain.Block;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

@Service
public class PaddleAiStudioClient {
    private static final int MAX_PNG_BYTES = 10 * 1024 * 1024;
    private static final int MAX_API_BYTES = 4 * 1024 * 1024;
    private static final int MAX_RESULT_BYTES = 64 * 1024 * 1024;
    private static final int LOCK_STRIPES = 64;
    private static final Set<Integer> CONFIRMED_SUBMIT_REJECTIONS = Set.of(400, 401, 403, 429);
    private static final String OPTIONS = "{\"useDocOrientationClassify\":false,\"useDocUnwarping\":false,\"useChartRecognition\":false}";
    private static final String FINGERPRINT_VERSION = "paddle-aistudio-v1\u0000" + OPTIONS;

    private final PaddleAiStudioProperties properties;
    private final AppProperties app;
    private final ObjectMapper json;
    private final PaddleOcrParser parser;
    private final Transport transport;
    private final Waiter waiter;
    private final Object[] locks = new Object[LOCK_STRIPES];

    @Autowired
    public PaddleAiStudioClient(PaddleAiStudioProperties properties, AppProperties app,
                                ObjectMapper json, PaddleOcrParser parser) {
        this(properties, app, json, parser, new JdkTransport(), PaddleAiStudioClient::waitCancellable);
    }

    PaddleAiStudioClient(PaddleAiStudioProperties properties, AppProperties app, ObjectMapper json,
                         PaddleOcrParser parser, Transport transport, Waiter waiter) {
        this.properties = properties;
        this.app = app;
        this.json = json;
        this.parser = parser;
        this.transport = transport;
        this.waiter = waiter;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    public boolean configured() {
        return properties.accessToken() != null && !properties.accessToken().isBlank();
    }

    public List<Block> recognize(byte[] png, int width, int height, String layout,
                                 BooleanSupplier cancelled) throws OcrException {
        if (!configured()) throw new ApiException(HttpStatus.BAD_REQUEST, "PaddleOCR AI Studio Access Token 尚未配置");
        if (png == null || png.length == 0 || png.length > MAX_PNG_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "送识 PNG 超过 PaddleOCR AI Studio 10MB 限制");
        if (width <= 0 || height <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "送识图片尺寸无效");
        validateSubmissionConfiguration();
        String fingerprint = fingerprint(png);
        Object lock = locks[Math.floorMod(fingerprint.hashCode(), locks.length)];
        synchronized (lock) {
            return recognizeLocked(fingerprint, png, width, height, layout, cancelled);
        }
    }

    private List<Block> recognizeLocked(String fingerprint, byte[] png, int width, int height,
                                        String layout, BooleanSupplier cancelled) throws OcrException {
        CacheEntry cache = readCache(fingerprint);
        if (cache != null && cache.result() != null && !cache.result().isNull())
            return channel(parser.parse(cache.result(), width, height, layout));
        if (cache != null && "failed".equals(cache.state()))
            throw new OcrException("PaddleOCR AI Studio 远端任务已失败；为避免重复计费未自动重提");

        long deadline = System.nanoTime() + Duration.ofSeconds(properties.totalTimeoutSeconds()).toNanos();
        String taskId = cache == null ? null : cache.taskId();
        if (taskId == null || taskId.isBlank()) {
            if (cache != null && !"rejected".equals(cache.state()))
                throw new OcrException("PaddleOCR AI Studio 提交结果不确定；为避免重复计费未自动重提");
            checkCancelled(cancelled);
            HttpRequest submission = submissionRequest(png, deadline);
            writeCache(fingerprint, new CacheEntry(fingerprint, null, "submitting", null));
            try {
                taskId = submit(submission, cancelled);
                writeCache(fingerprint, new CacheEntry(fingerprint, taskId, "submitted", null));
            } catch (SubmitRejectedException e) {
                writeCache(fingerprint, new CacheEntry(fingerprint, null, "rejected", null));
                throw e;
            } catch (QuotaExceededException e) {
                writeCache(fingerprint, new CacheEntry(fingerprint, null, "rejected", null));
                throw e;
            } catch (CancelledException e) {
                writeCache(fingerprint, new CacheEntry(fingerprint, null, "submit-unknown", null));
                throw e;
            } catch (Exception e) {
                writeCache(fingerprint, new CacheEntry(fingerprint, null, "submit-unknown", null));
                throw new OcrException("PaddleOCR AI Studio 提交结果不确定；为避免重复计费未自动重提");
            }
        }

        String state = cache == null ? "submitted" : cache.state();
        while (System.nanoTime() < deadline) {
            checkCancelled(cancelled);
            waiter.pause(properties.pollIntervalSeconds(), cancelled);
            checkCancelled(cancelled);
            JsonNode response = sendJson(pollRequest(taskId, deadline), cancelled, MAX_API_BYTES,
                    "PaddleOCR AI Studio 任务查询失败");
            JsonNode data = successData(response, "PaddleOCR AI Studio 任务查询失败");
            state = data.path("state").asText("").toLowerCase(Locale.ROOT);
            writeCache(fingerprint, new CacheEntry(fingerprint, taskId, state, null));
            if (Set.of("pending", "running").contains(state)) continue;
            if ("failed".equals(state))
                throw new OcrException("PaddleOCR AI Studio 远端任务失败；为避免重复计费未自动重提");
            if (!"done".equals(state)) throw new OcrException("PaddleOCR AI Studio 返回未知任务状态");
            URI resultUri = validateResultUri(data.path("resultUrl").path("jsonUrl").asText(""));
            String jsonl = send(downloadRequest(resultUri, deadline), cancelled, MAX_RESULT_BYTES,
                    "PaddleOCR AI Studio 结果下载失败").body();
            JsonNode normalized = normalizeJsonLines(jsonl, width, height);
            List<Block> blocks = channel(parser.parse(normalized, width, height, layout));
            writeCache(fingerprint, new CacheEntry(fingerprint, taskId, "done", normalized));
            return blocks;
        }
        writeCache(fingerprint, new CacheEntry(fingerprint, taskId, state, null));
        throw new OcrException("PaddleOCR AI Studio 任务仍在远端处理中，可稍后重试继续查询（远端任务可能仍计费）");
    }

    private HttpRequest submissionRequest(byte[] png, long deadline) throws OcrException {
        String boundary = "----BookHtmlStudio" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipart(boundary, png);
        if (body.length > MAX_PNG_BYTES + 64 * 1024)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PaddleOCR AI Studio multipart 请求超过安全大小限制");
        return HttpRequest.newBuilder(jobUri())
                .timeout(requestTimeout(deadline))
                .header("Authorization", authorization())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }

    private String submit(HttpRequest request, BooleanSupplier cancelled) throws OcrException {
        JsonNode response;
        try {
            response = sendJson(request, cancelled, MAX_API_BYTES, "PaddleOCR AI Studio 提交失败");
        } catch (HttpFailure e) {
            if (e.status() == 429 || e.status() == 402 || e.status() == 403)
                throw new QuotaExceededException("PaddleOCR AI Studio 额度不足或被拒绝（HTTP " + e.status() + "），可修正配置后重试");
            if (CONFIRMED_SUBMIT_REJECTIONS.contains(e.status()))
                throw new SubmitRejectedException("PaddleOCR AI Studio 提交已被拒绝（HTTP " + e.status() + "），可修正配置后重试");
            throw e;
        }
        JsonNode code = response == null ? null : response.get("code");
        if (!successCode(code))
            throw new SubmitRejectedException("PaddleOCR AI Studio 提交已被远端拒绝，可修正配置后重试");
        JsonNode data = successData(response, "PaddleOCR AI Studio 提交失败");
        String taskId = data.path("jobId").asText("");
        if (!taskId.matches("[A-Za-z0-9._-]{1,200}")) throw new OcrException("PaddleOCR AI Studio 提交响应缺少合法 jobId");
        return taskId;
    }

    private HttpRequest pollRequest(String taskId, long deadline) throws OcrException {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,200}"))
            throw new OcrException("PaddleOCR AI Studio 本地任务缓存中的 jobId 无效");
        URI target = URI.create(jobUri().toString() + "/" + taskId);
        return HttpRequest.newBuilder(target).timeout(requestTimeout(deadline))
                .header("Authorization", authorization()).header("Accept", "application/json").GET().build();
    }

    private HttpRequest downloadRequest(URI uri, long deadline) throws OcrException {
        return HttpRequest.newBuilder(uri).timeout(requestTimeout(deadline)).header("Accept", "application/jsonl, application/json").GET().build();
    }

    private Response send(HttpRequest request, BooleanSupplier cancelled, int maxBytes, String safeMessage)
            throws OcrException {
        try {
            checkCancelled(cancelled);
            Response response = transport.send(request, cancelled, maxBytes);
            if (response.status() == 429 || response.status() == 402 || response.status() == 403)
                throw new QuotaExceededException(safeMessage + "（HTTP " + response.status() + "，额度不足或被拒绝）");
            if (response.status() >= 300 && response.status() < 400)
                throw new HttpFailure(safeMessage + "（已拒绝重定向）", response.status());
            if (response.status() < 200 || response.status() >= 300)
                throw new HttpFailure(safeMessage + "（HTTP " + response.status() + "）", response.status());
            return response;
        } catch (CancelledException | OcrException | ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException(safeMessage, e);
        }
    }

    private JsonNode sendJson(HttpRequest request, BooleanSupplier cancelled, int maxBytes, String safeMessage)
            throws OcrException {
        try { return json.readTree(send(request, cancelled, maxBytes, safeMessage).body()); }
        catch (OcrException e) { throw e; }
        catch (Exception e) { throw new OcrException(safeMessage + "（JSON 无效）"); }
    }

    private JsonNode successData(JsonNode root, String safeMessage) throws OcrException {
        if (root == null || !root.isObject()) throw new OcrException(safeMessage + "（响应结构无效）");
        JsonNode code = root.get("code");
        if (!successCode(code))
            throw new OcrException(safeMessage);
        JsonNode data = root.get("data");
        if (data == null || !data.isObject()) throw new OcrException(safeMessage + "（响应结构无效）");
        return data;
    }

    private static boolean successCode(JsonNode code) {
        return code == null || code.isNull() || (code.isNumber() && code.asInt() == 0)
                || (code.isTextual() && "0".equals(code.asText()));
    }

    JsonNode normalizeJsonLines(String value, int fallbackWidth, int fallbackHeight) throws OcrException {
        try {
            ObjectNode normalized = json.createObjectNode();
            ArrayNode pages = normalized.putArray("pages");
            int pageIndex = 0;
            for (String line : value.lines().map(String::strip).filter(part -> !part.isEmpty()).toList()) {
                JsonNode root = json.readTree(line);
                JsonNode result = root == null ? null : root.get("result");
                JsonNode parsingResults = result == null ? null : result.get("layoutParsingResults");
                if (result == null || !result.isObject() || parsingResults == null || !parsingResults.isArray()
                        || parsingResults.isEmpty()) throw new OcrException("PaddleOCR AI Studio JSONL 缺少版面结果");
                int width = dimension(result.path("dataInfo").get("width"), fallbackWidth);
                int height = dimension(result.path("dataInfo").get("height"), fallbackHeight);
                for (JsonNode parsingResult : parsingResults) {
                    JsonNode list = parsingResult.path("prunedResult").get("parsing_res_list");
                    if (list == null || !list.isArray()) throw new OcrException("PaddleOCR AI Studio 结果缺少 parsing_res_list");
                    List<Layout> layouts = new ArrayList<>();
                    int sequence = 0;
                    for (JsonNode block : list) layouts.add(layout(block, sequence++));
                    layouts.sort(Comparator.comparingInt(Layout::order).thenComparingInt(Layout::sequence));
                    ObjectNode page = pages.addObject();
                    page.put("page_index", pageIndex++);
                    ObjectNode meta = page.putObject("meta"); meta.put("page_width", width); meta.put("page_height", height);
                    ArrayNode output = page.putArray("layouts");
                    Set<String> ids = new HashSet<>();
                    for (Layout item : layouts) {
                        String base = "aistudio-" + item.id(); String id = base; int duplicate = 2;
                        while (!ids.add(id)) id = base + "-" + duplicate++;
                        ObjectNode layout = output.addObject();
                        layout.put("layout_id", id); layout.put("type", item.label()); layout.put("text", item.content());
                        ArrayNode position = layout.putArray("position");
                        position.add(item.x1()); position.add(item.y1()); position.add(item.x2() - item.x1()); position.add(item.y2() - item.y1());
                    }
                }
            }
            if (pages.isEmpty()) throw new OcrException("PaddleOCR AI Studio JSONL 结果为空");
            return normalized;
        } catch (OcrException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio JSONL 或坐标无效", e);
        }
    }

    private Layout layout(JsonNode block, int sequence) throws OcrException {
        if (block == null || !block.isObject()) throw new OcrException("PaddleOCR AI Studio 版面块无效");
        JsonNode bbox = block.get("block_bbox");
        if (bbox == null || !bbox.isArray() || bbox.size() != 4) throw new OcrException("PaddleOCR AI Studio 版面块缺少 bbox");
        double[] values = new double[4];
        for (int i = 0; i < 4; i++) {
            if (!bbox.get(i).isNumber() || !Double.isFinite(values[i] = bbox.get(i).asDouble()))
                throw new OcrException("PaddleOCR AI Studio bbox 无效");
        }
        if (values[0] < 0 || values[1] < 0 || values[2] <= values[0] || values[3] <= values[1])
            throw new OcrException("PaddleOCR AI Studio bbox 无效");
        String label = block.path("block_label").asText("").strip();
        if (label.isEmpty() || label.length() > 80) throw new OcrException("PaddleOCR AI Studio 版面类型无效");
        JsonNode contentNode = block.get("block_content");
        if (contentNode != null && !contentNode.isNull() && !contentNode.isTextual())
            throw new OcrException("PaddleOCR AI Studio 版面文字无效");
        String content = contentNode == null || contentNode.isNull() ? "" : contentNode.asText();
        String remoteId = scalarId(block.get("block_id"), sequence + 1);
        int order = integer(block.get("block_order"), sequence);
        return new Layout(remoteId, label, content, values[0], values[1], values[2], values[3], order, sequence);
    }

    private static String scalarId(JsonNode node, int fallback) throws OcrException {
        String value;
        if (node == null || node.isNull()) value = Integer.toString(fallback);
        else if (node.isTextual() || node.isIntegralNumber()) value = node.asText();
        else throw new OcrException("PaddleOCR AI Studio block_id 无效");
        value = value.replaceAll("[^A-Za-z0-9._-]", "-");
        if (value.isBlank()) value = Integer.toString(fallback);
        return value.substring(0, Math.min(80, value.length()));
    }

    private static int integer(JsonNode node, int fallback) throws OcrException {
        if (node == null || node.isNull()) return fallback;
        if (!node.canConvertToInt() || node.asInt() < 0) throw new OcrException("PaddleOCR AI Studio block_order 无效");
        return node.asInt();
    }

    private static int dimension(JsonNode node, int fallback) throws OcrException {
        if (node == null || node.isMissingNode() || node.isNull()) return fallback;
        if (!node.canConvertToInt() || node.asInt() <= 0) throw new OcrException("PaddleOCR AI Studio 页面尺寸无效");
        return node.asInt();
    }

    private static List<Block> channel(List<Block> blocks) {
        return blocks.stream().map(block -> {
            String source = block.source() != null && block.source().contains("span")
                    ? "paddle-aistudio-span" : "paddle-aistudio";
            return new Block(block.id(), block.type(), block.order(), block.bbox(), block.writingMode(),
                    block.original(), block.simplified(), block.confidence(), block.uncertain(), block.reviewed(),
                    block.headingLevel(), source, block.sourceIds(), block.suggestion(), block.sourceRect(), block.issues());
        }).toList();
    }

    private byte[] multipart(String boundary, byte[] png) throws OcrException {
        String model = properties.model();
        if (model == null || model.isBlank() || model.length() > 120 || model.contains("\r") || model.contains("\n"))
            throw new OcrException("PaddleOCR AI Studio 模型配置无效");
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(png.length + 1024);
            field(output, boundary, "model", model);
            field(output, boundary, "optionalPayload", OPTIONS);
            output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"page.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(png); output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
            return output.toByteArray();
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio multipart 请求生成失败", e);
        }
    }

    private static void field(ByteArrayOutputStream output, String boundary, String name, String value) throws Exception {
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private URI jobUri() throws OcrException {
        try {
            URI uri = URI.create(properties.jobUrl().replaceAll("/+$", ""));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"paddleocr.aistudio-app.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !"/api/v2/ocr/jobs".equals(uri.getPath()) || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio 任务地址配置无效");
        }
    }

    static URI validateResultUri(String raw) throws OcrException {
        try {
            URI uri = URI.create(raw); String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean official = host.equals("paddleocr.aistudio-app.com") || host.endsWith(".bcebos.com") || host.endsWith(".baidubce.com");
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || isIpLiteral(host) || !official)
                throw new IllegalArgumentException();
            return uri;
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio 结果地址未通过安全校验");
        }
    }

    private String authorization() throws OcrException {
        String token = properties.accessToken();
        if (token == null || token.isBlank() || token.length() > 4096 || token.contains("\r") || token.contains("\n"))
            throw new OcrException("PaddleOCR AI Studio Access Token 配置无效");
        return "Bearer " + token;
    }

    private void validateSubmissionConfiguration() throws OcrException {
        jobUri();
        authorization();
        String model = properties.model();
        if (model == null || model.isBlank() || model.length() > 120 || model.contains("\r") || model.contains("\n"))
            throw new OcrException("PaddleOCR AI Studio 模型配置无效");
    }

    private Duration requestTimeout(long deadline) throws OcrException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new OcrException("PaddleOCR AI Studio 请求总时限已到");
        long configured = Duration.ofSeconds(properties.requestTimeoutSeconds()).toNanos();
        return Duration.ofNanos(Math.max(1, Math.min(configured, remaining)));
    }

    private Path cachePath(String fingerprint) {
        return app.dataDir().resolve("cache").resolve("paddle-aistudio").resolve(fingerprint + ".json");
    }

    private CacheEntry readCache(String fingerprint) throws OcrException {
        Path path = cachePath(fingerprint);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        try {
            CacheEntry entry = json.readValue(path.toFile(), CacheEntry.class);
            if (!fingerprint.equals(entry.inputHash())) throw new IllegalArgumentException();
            return entry;
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio 本地任务缓存损坏", e);
        }
    }

    private void writeCache(String fingerprint, CacheEntry entry) throws OcrException {
        Path path = cachePath(fingerprint), temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent()); setPrivate(path.getParent(), true);
            json.writeValue(temporary.toFile(), entry); setPrivate(temporary, false);
            try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
            setPrivate(path, false);
        } catch (Exception e) {
            throw new OcrException("PaddleOCR AI Studio 本地任务缓存保存失败", e);
        }
    }

    private String fingerprint(byte[] png) throws OcrException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8)); digest.update((byte)0);
            digest.update(properties.model().getBytes(StandardCharsets.UTF_8)); digest.update((byte)0); digest.update(png);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new OcrException("无法计算 PaddleOCR AI Studio 送识摘要", e);
        }
    }

    private static void setPrivate(Path path, boolean directory) {
        try { Files.setPosixFilePermissions(path, directory
                ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException | java.io.IOException ignored) {}
    }

    private static boolean isIpLiteral(String host) {
        return host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}") || host.contains(":");
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    private static void waitCancellable(int seconds, BooleanSupplier cancelled) {
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) {
            checkCancelled(cancelled);
            try { Thread.sleep(Math.min(200, Math.max(1, Duration.ofNanos(end - System.nanoTime()).toMillis()))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancelledException(); }
        }
    }

    record CacheEntry(String inputHash, String taskId, String state, JsonNode result) {}
    record Response(int status, String body) {}
    private record Layout(String id, String label, String content, double x1, double y1, double x2, double y2,
                          int order, int sequence) {}

    @FunctionalInterface interface Transport {
        Response send(HttpRequest request, BooleanSupplier cancelled, int maxBytes) throws Exception;
    }
    @FunctionalInterface interface Waiter { void pause(int seconds, BooleanSupplier cancelled); }

    private static final class HttpFailure extends OcrException {
        private final int status;
        private HttpFailure(String message, int status) { super(message); this.status = status; }
        private int status() { return status; }
    }

    private static final class SubmitRejectedException extends OcrException {
        private SubmitRejectedException(String message) { super(message); }
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        @Override public Response send(HttpRequest request, BooleanSupplier cancelled, int maxBytes) throws Exception {
            if ("GET".equals(request.method()) && request.headers().firstValue("Authorization").isEmpty()) {
                PaddleOcrClient.Response response = PaddleOcrClient.signedGet(request.uri(),
                        request.timeout().orElse(Duration.ofSeconds(60)), cancelled, maxBytes);
                return new Response(response.status(), response.body());
            }
            long deadline = System.nanoTime() + request.timeout().orElse(Duration.ofSeconds(60)).toNanos();
            CompletableFuture<HttpResponse<InputStream>> responseFuture = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = await(responseFuture, null, deadline, cancelled);
            InputStream stream = response.body();
            CompletableFuture<String> bodyFuture = CompletableFuture.supplyAsync(() -> read(stream, maxBytes, cancelled));
            try { return new Response(response.statusCode(), await(bodyFuture, stream, deadline, cancelled)); }
            finally { close(stream); }
        }

        private static String read(InputStream input, int maxBytes, BooleanSupplier cancelled) {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int total = 0, read;
                while ((read = input.read(buffer)) != -1) {
                    checkCancelled(cancelled); total += read;
                    if (total > maxBytes) throw new CompletionException(new OcrException("PaddleOCR AI Studio 响应超过安全大小限制"));
                    output.write(buffer, 0, read);
                }
                return output.toString(StandardCharsets.UTF_8);
            } catch (java.io.IOException e) { throw new CompletionException(e); }
        }

        private static <T> T await(CompletableFuture<T> future, InputStream stream, long deadline,
                                   BooleanSupplier cancelled) throws Exception {
            while (true) {
                try {
                    checkCancelled(cancelled); long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) { future.cancel(true); close(stream); throw new OcrException("PaddleOCR AI Studio 网络响应超时"); }
                    return future.get(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 200), TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                } catch (InterruptedException e) {
                    future.cancel(true); close(stream); Thread.currentThread().interrupt(); throw new CancelledException();
                } catch (CancelledException e) {
                    future.cancel(true); close(stream); throw e;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof OcrException ocr) throw ocr;
                    throw new java.io.IOException("PaddleOCR AI Studio 网络读写失败");
                }
            }
        }

        private static void close(InputStream stream) {
            if (stream != null) try { stream.close(); } catch (java.io.IOException ignored) {}
        }
    }
}
