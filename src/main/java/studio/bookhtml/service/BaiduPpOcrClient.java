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
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.Block;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * 百度智能云 PP-OCRv6（文档标注 v6，接口名 pp_ocrv5）同步识别。
 * 同一 AK/SK 与 PaddleOCR-VL 共用，但为不同产品、独立额度；额度不足时由
 * PageProcessor 在 paddle 系内自动降级，这里只负责把额度/权限失败标识为
 * QuotaExceededException。
 */
@Service
public class BaiduPpOcrClient {
    private static final URI AUTH_URI = URI.create("https://aip.baidubce.com/oauth/2.0/token");
    private static final int MAX_FORM_BYTES = 10 * 1024 * 1024, MAX_API_BYTES = 32 * 1024 * 1024;
    private static final String OPTIONS = "{\"useDocOrientationClassify\":false,\"useDocUnwarping\":false,\"useTextlineOrientation\":false}";
    private static final String FINGERPRINT_VERSION = "ppocr-v6-v1\u0000" + OPTIONS;
    /** 可降级的额度/限流/权限错误码（官方错误码表）。 */
    private static final Set<String> QUOTA_CODES = Set.of("4", "17", "18", "19", "6", "216604");
    /** 图片自身问题，不降级（换通道同样失败，只会重复计费）。 */
    private static final Set<String> IMAGE_CODES = Set.of("216200", "216201", "216202", "216205", "216102");
    private static final Set<String> TOKEN_CODES = Set.of("100", "110", "111");

    private final AppProperties config;
    private final PpOcrProperties ppocr;
    private final ObjectMapper json;
    private final BaiduPpOcrParser parser;
    private final Transport transport;
    private static final int LOCK_STRIPES = 64;
    private final Object[] locks = new Object[LOCK_STRIPES];
    private String accessToken = "";
    private long tokenExpiresAt;
    private long tokenSettingsRevision = -1;
    private SettingsService settings;
    private UsageLedger usage;
    private ProviderResourceRegistry resources;

    @Autowired
    public BaiduPpOcrClient(AppProperties config, PpOcrProperties ppocr, ObjectMapper json, BaiduPpOcrParser parser) {
        this(config, ppocr, json, parser, new JdkTransport());
    }

    BaiduPpOcrClient(AppProperties config, PpOcrProperties ppocr, ObjectMapper json, BaiduPpOcrParser parser, Transport transport) {
        this.config = config;
        this.ppocr = ppocr;
        this.json = json;
        this.parser = parser;
        this.transport = transport;
        for (int i = 0; i < locks.length; i++) locks[i] = new Object();
    }

    @Autowired(required = false) public void setResourceRegistry(ProviderResourceRegistry resources) { this.resources = resources; }
    @Autowired public void setSettings(SettingsService settings) { this.settings = settings; }
    @Autowired public void setUsageLedger(UsageLedger usage) { this.usage = usage; }
    private String apiKey() { return settings == null ? config.baiduOcrApiKey() : settings.state().ppocrApiKey(); }
    private String secretKey() { return settings == null ? config.baiduOcrSecretKey() : settings.state().ppocrSecretKey(); }

    public boolean configured() {
        return !apiKey().isBlank() && !secretKey().isBlank();
    }

    public List<Block> recognize(byte[] png, int width, int height, String layout, BooleanSupplier cancelled) throws OcrException {
        if (!configured()) throw new ApiException(HttpStatus.BAD_REQUEST, "PP-OCRv6 尚未配置百度 OCR API Key 与 Secret Key");
        if (png == null || png.length == 0 || png.length > 10 * 1024 * 1024)
            throw new ApiException(HttpStatus.BAD_REQUEST, "送识 PNG 超过 PP-OCRv6 10MB 限制");
        if (width <= 0 || height <= 0) throw new ApiException(HttpStatus.BAD_REQUEST, "送识图片尺寸无效");
        String hash = fingerprint(png);
        Object lock = locks[Math.floorMod(hash.hashCode(), locks.length)];
        ProviderResourceRegistry.Permit permit = null;
        if (resources != null) {
            try {
                permit = resources.acquire(ProviderResourceRegistry.POOL_OCR, true, Duration.ofSeconds(30), cancelled);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
        }
        try {
            synchronized (lock) {
                return recognizeLocked(hash, png, width, height, layout, cancelled);
            }
        } finally {
            if (permit != null) permit.close();
        }
    }

    private List<Block> recognizeLocked(String hash, byte[] png, int width, int height, String layout, BooleanSupplier cancelled) throws OcrException {
        CacheEntry cache = readCache(hash);
        if (cache != null && cache.result() != null && !cache.result().isNull()) {
            List<Block> blocks = parser.parse(cache.result(), width, height, layout);
            if (usage != null) try { usage.cacheReused("ppocr", "PP-OCRv6"); }
            catch (IOException e) { throw new OcrException("用量账本不可用，缓存命中未交付", e); }
            return blocks;
        }
        checkCancelled(cancelled);
        OcrCall call = call(png, cancelled, false);
        try {
            JsonNode normalized = normalize(call.response(), width, height);
            List<Block> blocks = parser.parse(normalized, width, height, layout);
            writeCache(hash, new CacheEntry(hash, normalized));
            return blocks;
        } catch (Exception e) {
            if (e instanceof OcrException ocr) throw ocr;
            throw new OcrException("PP-OCRv6 用量或结果保存失败", e);
        }
    }

    private record OcrCall(JsonNode response, String attemptId) {}
    private OcrCall call(byte[] png, BooleanSupplier cancelled, boolean retriedToken) throws OcrException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("image", Base64.getEncoder().encodeToString(png));
        form.put("useDocOrientationClassify", "false");
        form.put("useDocUnwarping", "false");
        form.put("useTextlineOrientation", "false");
        String body = form(form);
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_FORM_BYTES)
            throw new ApiException(HttpStatus.BAD_REQUEST, "PP-OCRv6 Base64 表单超过 10MB 限制");
        String token = token(cancelled);
        URI target = withToken(jobUri(), token);
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(Math.max(10, ppocr.requestTimeoutSeconds())))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        String attemptId = null;
        if (usage != null) try { attemptId = usage.start("ppocr", "PP-OCRv6"); }
        catch (IOException e) { throw new OcrException("用量账本不可用，禁止发送 PP-OCRv6 请求", e); }
        JsonNode response = sendJson(request, cancelled, attemptId);
        String code = errorCode(response);
        if (code == null) {
            if (usage != null) try { usage.succeeded(attemptId); }
            catch (IOException e) { throw new OcrException("用量账本更新失败", e); }
            return new OcrCall(response, attemptId);
        }
        if (usage != null) try { usage.failed(attemptId); } catch (IOException e) { throw new OcrException("用量账本更新失败", e); }
        if (TOKEN_CODES.contains(code) && !retriedToken) {
            synchronized (this) {
                accessToken = "";
                tokenExpiresAt = 0;
            }
            return call(png, cancelled, true);
        }
        if (QUOTA_CODES.contains(code)) throw new QuotaExceededException(quotaMessage(code, response));
        if (IMAGE_CODES.contains(code)) throw new OcrException("PP-OCRv6 拒绝了送识图片（错误码 " + code + "），请检查图片格式与尺寸");
        throw new OcrException("PP-OCRv6 识别失败（错误码 " + code + "）");
    }

    private static String quotaMessage(String code, JsonNode response) {
        String hint = switch (code) {
            case "17", "19", "216604" -> "PP-OCRv6 额度已用完，可在百度控制台购买次数包或开通按量后付费";
            case "18", "4" -> "PP-OCRv6 请求限流（QPS/集群），可稍后重试";
            case "6" -> "当前应用未勾选 PP-OCRv6 接口权限，需在百度控制台为应用勾选后重试";
            default -> "PP-OCRv6 配额不足";
        };
        return hint;
    }

    JsonNode normalize(JsonNode response, int width, int height) throws OcrException {
        JsonNode pages = response.path("page_result");
        if (!pages.isArray() || pages.size() != 1) throw new OcrException("PP-OCRv6 返回页数异常");
        JsonNode page = pages.get(0);
        JsonNode lines = page.path("lines");
        JsonNode boxes = page.path("rec_boxes");
        JsonNode probabilities = page.path("probability");
        if (!lines.isArray()) throw new OcrException("PP-OCRv6 结果缺少 lines");
        if (!boxes.isArray() || boxes.size() != lines.size()) throw new OcrException("PP-OCRv6 行与坐标数量不一致");
        ObjectNode normalized = json.createObjectNode();
        ArrayNode normalizedPages = normalized.putArray("pages");
        ObjectNode normalizedPage = normalizedPages.addObject();
        ObjectNode meta = normalizedPage.putObject("meta");
        meta.put("page_width", width);
        meta.put("page_height", height);
        ArrayNode layouts = normalizedPage.putArray("layouts");
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).isTextual()) throw new OcrException("PP-OCRv6 行文本无效");
            JsonNode box = boxes.get(i);
            if (!box.isArray() || box.size() != 4) throw new OcrException("PP-OCRv6 行坐标无效");
            double[] values = new double[4];
            for (int k = 0; k < 4; k++) {
                if (!box.get(k).isNumber() || !Double.isFinite(values[k] = box.get(k).asDouble()))
                    throw new OcrException("PP-OCRv6 行坐标无效");
            }
            if (values[0] < 0 || values[1] < 0 || values[2] <= values[0] || values[3] <= values[1])
                throw new OcrException("PP-OCRv6 行坐标无效");
            ObjectNode layout = layouts.addObject();
            layout.put("layout_id", "line-" + (i + 1));
            layout.put("type", "text");
            layout.put("text", lines.get(i).asText());
            ArrayNode position = layout.putArray("position");
            position.add(values[0]);
            position.add(values[1]);
            position.add(values[2] - values[0]);
            position.add(values[3] - values[1]);
            if (probabilities.isArray() && i < probabilities.size() && probabilities.get(i).isNumber()) {
                double confidence = probabilities.get(i).asDouble();
                if (Double.isFinite(confidence) && confidence >= 0 && confidence <= 1) layout.put("confidence", confidence);
            }
        }
        return normalized;
    }

    private synchronized String token(BooleanSupplier cancelled) throws OcrException {
        long revision = settings == null ? -1 : settings.state().revision();
        if (revision != tokenSettingsRevision) { accessToken = ""; tokenExpiresAt = 0; tokenSettingsRevision = revision; }
        long now = System.currentTimeMillis();
        if (!accessToken.isBlank() && now < tokenExpiresAt) return accessToken;
        Map<String, String> query = new LinkedHashMap<>();
        query.put("grant_type", "client_credentials");
        query.put("client_id", apiKey());
        query.put("client_secret", secretKey());
        HttpRequest request = HttpRequest.newBuilder(URI.create(AUTH_URI + "?" + form(query)))
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody()).build();
        String authAttemptId = null;
        if (usage != null) try { authAttemptId = usage.start("ppocr-auth", "token"); } catch (Exception ignored) {}
        JsonNode response;
        try {
            response = sendJson(request, cancelled, authAttemptId);
            String code = errorCode(response);
            if (code != null) {
                if (usage != null && authAttemptId != null) try { usage.failed(authAttemptId); } catch (Exception ignored) {}
                throw new OcrException("百度 OCR 认证失败（错误码 " + code + "）");
            }
            if (usage != null && authAttemptId != null) try { usage.succeeded(authAttemptId); } catch (Exception ignored) {}
        } catch (Exception e) {
            if (usage != null && authAttemptId != null) try { usage.failed(authAttemptId); } catch (Exception ignored) {}
            throw e;
        }
        String token = response.path("access_token").asText("");
        if (token.isBlank()) throw new OcrException("百度 OCR 认证失败");
        long expires = Math.max(60, response.path("expires_in").asLong(3600));
        accessToken = token;
        tokenExpiresAt = now + Math.max(30, expires - 60) * 1000;
        return accessToken;
    }

    private JsonNode sendJson(HttpRequest request, BooleanSupplier cancelled, String attemptId) throws OcrException {
        try {
            checkCancelled(cancelled);
            Response response = transport.send(request, cancelled, MAX_API_BYTES);
            if (usage != null && attemptId != null && (response.status() < 200 || response.status() >= 300)) usage.failed(attemptId);
            if (response.status() == 429) throw new QuotaExceededException("PP-OCRv6 请求限流（HTTP 429），可稍后重试");
            if (response.status() >= 300 && response.status() < 400) throw new OcrException("PP-OCRv6 请求失败（已拒绝重定向）");
            if (response.status() < 200 || response.status() >= 300) throw new OcrException("PP-OCRv6 请求失败（HTTP " + response.status() + "）");
            JsonNode root = json.readTree(response.body());
            if (usage != null && attemptId != null) usage.captureUsage(attemptId, root);
            return root;
        } catch (CancelledException | OcrException | ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 请求失败", e);
        }
    }

    private static String errorCode(JsonNode node) {
        JsonNode code = node == null ? null : node.get("error_code");
        if (code == null || code.isNull()) return null;
        if (code.isNumber() && code.asInt() == 0) return null;
        if (code.isTextual() && ("0".equals(code.asText()) || code.asText().isBlank())) return null;
        return code.isTextual() ? code.asText().strip() : String.valueOf(code.asInt());
    }

    private URI jobUri() throws OcrException {
        try {
            URI base = URI.create(ppocr.url().replaceAll("/+$", ""));
            if (!"https".equalsIgnoreCase(base.getScheme()) || !"aip.baidubce.com".equalsIgnoreCase(base.getHost())
                    || base.getUserInfo() != null || (base.getPort() != -1 && base.getPort() != 443)
                    || !"/rest/2.0/ocr/v1/pp_ocrv5".equals(base.getPath())
                    || base.getQuery() != null || base.getFragment() != null) throw new IllegalArgumentException();
            return base;
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 任务地址配置无效");
        }
    }

    private static URI withToken(URI uri, String token) throws OcrException {
        try {
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(),
                    (uri.getQuery() == null ? "" : uri.getQuery() + "&") + "access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8), null);
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 请求地址无效");
        }
    }

    String fingerprint(byte[] png) throws OcrException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(FINGERPRINT_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ppocr.url().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(png);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new OcrException("无法计算送识图片摘要", e);
        }
    }

    private Path cachePath(String hash) {
        return config.dataDir().resolve("cache").resolve("ppocr").resolve(hash + ".json");
    }

    private CacheEntry readCache(String hash) throws OcrException {
        Path path = cachePath(hash);
        if (!Files.exists(path)) return null;
        try {
            CacheEntry entry = json.readValue(path.toFile(), CacheEntry.class);
            if (!hash.equals(entry.inputHash())) throw new IOException();
            return entry;
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 本地任务缓存损坏", e);
        }
    }

    private void writeCache(String hash, CacheEntry entry) throws OcrException {
        Path path = cachePath(hash), tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            setPrivate(path.getParent(), true);
            json.writeValue(tmp.toFile(), entry);
            setPrivate(tmp, false);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            setPrivate(path, false);
        } catch (Exception e) {
            throw new OcrException("PP-OCRv6 本地任务缓存保存失败", e);
        }
    }

    private static void setPrivate(Path path, boolean directory) {
        try {
            Files.setPosixFilePermissions(path, directory
                    ? Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                    : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {}
    }

    private static String form(Map<String, String> values) {
        StringJoiner out = new StringJoiner("&");
        values.forEach((k, v) -> out.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return out.toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
    }

    record CacheEntry(String inputHash, JsonNode result) {}
    record Response(int status, String body) {}

    @FunctionalInterface
    interface Transport {
        Response send(HttpRequest request, BooleanSupplier cancelled, int maxBytes) throws Exception;
    }

    private static final class JdkTransport implements Transport {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        public Response send(HttpRequest request, BooleanSupplier cancelled, int maxBytes) throws Exception {
            long deadline = System.nanoTime() + request.timeout().orElse(Duration.ofSeconds(60)).toNanos();
            var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response = await(future, null, deadline, cancelled);
            InputStream stream = response.body();
            var body = java.util.concurrent.CompletableFuture.supplyAsync(() -> read(stream, maxBytes, cancelled));
            try {
                return new Response(response.statusCode(), await(body, stream, deadline, cancelled));
            } finally {
                close(stream);
            }
        }

        private static String read(InputStream in, int maxBytes, BooleanSupplier cancelled) {
            try (in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0, read;
                while ((read = in.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    total += read;
                    if (total > maxBytes) throw new java.util.concurrent.CompletionException(new OcrException("PP-OCRv6 响应超过安全大小限制"));
                    out.write(buffer, 0, read);
                }
                return out.toString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }

        private static <T> T await(java.util.concurrent.CompletableFuture<T> future, InputStream stream, long deadline, BooleanSupplier cancelled) throws Exception {
            while (true) {
                try {
                    checkCancelled(cancelled);
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        future.cancel(true);
                        close(stream);
                        throw new OcrException("PP-OCRv6 网络响应超时");
                    }
                    return future.get(Math.min(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 200), java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException ignored) {
                } catch (InterruptedException e) {
                    future.cancel(true);
                    close(stream);
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                } catch (CancelledException e) {
                    future.cancel(true);
                    close(stream);
                    throw e;
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) cause = cause.getCause();
                    if (cause instanceof OcrException ocr) throw ocr;
                    throw new IOException("PP-OCRv6 网络读写失败");
                }
            }
        }

        private static void close(InputStream stream) {
            if (stream != null) try {
                stream.close();
            } catch (IOException ignored) {}
        }
    }
}
