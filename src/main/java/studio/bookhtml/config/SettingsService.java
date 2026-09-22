package studio.bookhtml.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** One immutable runtime snapshot; admissions and replacement share the same monitor. */
@Service
public class SettingsService {
    public static final String QWEN_CN = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String QWEN_INTL = "https://dashscope-intl.aliyuncs.com/compatible-mode/v1";
    private static final Map<String, String> LEGACY_HOSTS = Map.of(
            "cn-beijing", "dashscope.aliyuncs.com", "ap-southeast-1", "dashscope-intl.aliyuncs.com",
            "us-east-1", "dashscope-us.aliyuncs.com", "cn-hongkong", "cn-hongkong.dashscope.aliyuncs.com");
    private static final Set<PosixFilePermission> DIR_PERMS = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> FILE_PERMS = Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private final ObjectMapper json;
    private final Path directory;
    private final Path file;
    private final QwenAssistProperties qwen;
    private final DecisionProperties jev;
    private volatile State state;
    private int activeWork;

    public record State(long revision, String defaultProvider, boolean fallbackEnabled,
                        String paddleAccessToken, String ppocrApiKey, String ppocrSecretKey,
                        boolean qwenEnabled, String qwenApiKey, String qwenRegion, String qwenWorkspaceId, String qwenModel,
                        boolean jevEnabled, String jevApiKey, String jevModel,
                        Long budgetUnits, boolean allowCloudData, List<Rate> billingRates) {}
    public record Rate(String provider, String model, String currency, String perRequest,
                       String inputPerMillion, String outputPerMillion) {}

    public SettingsService(AppProperties app, PaddleAiStudioProperties paddle,
                           QwenAssistProperties qwen, DecisionProperties jev, ObjectMapper json) {
        this.json = json;
        this.qwen = qwen;
        this.jev = jev;
        this.directory = app.dataDir().toAbsolutePath().normalize().resolve(".settings");
        this.file = directory.resolve("settings.json");
        String[] qwenLocation = parseQwenLocation(qwen.getBaseUrl());
        State baseline = new State(0, "paddle-aistudio", false, paddle.accessToken(),
                app.baiduOcrApiKey(), app.baiduOcrSecretKey(), qwen.isEnabled() && has(qwen.getApiKey()), qwen.getApiKey(),
                qwenLocation[0], qwenLocation[1], qwen.getModel(),
                "SHADOW".equalsIgnoreCase(jev.getMode()) || "ASSIST".equalsIgnoreCase(jev.getMode()),
                jev.getApiKey(), jev.getModel(), jev.getMonetaryBudgetMinor() != null && jev.getMonetaryBudgetMinor() > 0
                ? jev.getMonetaryBudgetMinor() : null, jev.isAllowCloudData(), defaultRates(qwen.getModel(), jev.getModel()));
        this.state = readOrBaseline(baseline);
        applyMutable(state);
    }

    public State state() { return state; }
    public synchronized boolean busy() { return activeWork != 0; }
    public synchronized Lease beginWork() { activeWork++; return new Lease(this); }
    private synchronized void endWork() { if (activeWork <= 0) throw new IllegalStateException("settings lease underflow"); activeWork--; }
    public static final class Lease implements AutoCloseable {
        private SettingsService owner;
        private Lease(SettingsService owner) { this.owner = owner; }
        @Override public synchronized void close() { if (owner != null) { owner.endWork(); owner = null; } }
    }

    public Map<String, Object> view() {
        State s = state;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("revision", s.revision());
        out.put("busy", busy());
        Map<String, Object> paddleMap = new LinkedHashMap<>();
        paddleMap.put("accessTokenSet", has(s.paddleAccessToken()));

        paddleMap.put("configured", has(s.paddleAccessToken()));
        paddleMap.put("model", "PaddleOCR-VL-1.6");
        Map<String, Object> ppocrMap = new LinkedHashMap<>();
        ppocrMap.put("apiKeySet", has(s.ppocrApiKey()));

        ppocrMap.put("secretKeySet", has(s.ppocrSecretKey()));

        ppocrMap.put("configured", has(s.ppocrApiKey()) && has(s.ppocrSecretKey()));
        ppocrMap.put("model", "PP-OCRv6");
        out.put("ocr", Map.of("defaultProvider", s.defaultProvider(), "fallbackEnabled", s.fallbackEnabled(),
                "paddleAiStudio", paddleMap,
                "ppocr", ppocrMap));
        Map<String, Object> qwenMap = new LinkedHashMap<>();
        qwenMap.put("enabled", s.qwenEnabled());
        qwenMap.put("apiKeySet", has(s.qwenApiKey()));

        qwenMap.put("region", s.qwenRegion());
        qwenMap.put("workspaceId", s.qwenWorkspaceId());
        qwenMap.put("model", s.qwenModel());
        qwenMap.put("baseUrl", qwenUrl(s.qwenRegion(), s.qwenWorkspaceId()));
        qwenMap.put("configured", s.qwenEnabled() && has(s.qwenApiKey()));
        out.put("qwen", qwenMap);
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("enabled", s.jevEnabled());
        j.put("apiKeySet", has(s.jevApiKey()));

        j.put("model", s.jevModel());
        j.put("mode", jev.getMode());
        j.put("budgetUnits", s.budgetUnits() == null ? "" : s.budgetUnits().toString());
        j.put("budgetUnitLabel", "本地调用核算单位（每次 JEV 调用预留 1 单位，非美元）");
        j.put("allowCloudData", s.allowCloudData());
        j.put("calibrationStatus", jev.getCalibrationStatus());
        String reason = jev.availabilityReason();
        j.put("available", reason == null);
        j.put("reason", reason == null ? "" : reason);
        out.put("jev", j);
        out.put("billing", Map.of("rates", s.billingRates()));
        return out;
    }

    public synchronized Map<String, Object> update(JsonNode body) {
        if (body == null || !body.isObject()) bad();
        only(body, "revision", "ocr", "qwen", "jev", "billing");
        JsonNode revision = body.get("revision");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()) bad();
        if (revision.longValue() != state.revision()) throw new ApiException(HttpStatus.CONFLICT, "设置已被其他修改覆盖，请刷新后重试");
        if (state.revision() == Long.MAX_VALUE) bad();
        if (activeWork != 0) throw new ApiException(HttpStatus.CONFLICT, "当前有 OCR 或决策任务排队、运行或取消中，请完成后再保存设置");
        State old = state;
        JsonNode o = section(body, "ocr", "defaultProvider", "fallbackEnabled", "paddleAiStudio", "ppocr");
        JsonNode p = section(o, "paddleAiStudio", "accessToken", "clearAccessToken");
        JsonNode b = section(o, "ppocr", "apiKey", "clearApiKey", "secretKey", "clearSecretKey");
        JsonNode q = section(body, "qwen", "enabled", "apiKey", "clearApiKey", "region", "workspaceId", "model");
        JsonNode j = section(body, "jev", "enabled", "apiKey", "clearApiKey", "model", "budgetUnits", "allowCloudData");
        JsonNode billing = section(body, "billing", "rates");
        String provider = string(o, "defaultProvider", old.defaultProvider());
        if (!Set.of("paddle-aistudio", "ppocr").contains(provider)) bad();
        String region = string(q, "region", old.qwenRegion());
        if (!LEGACY_HOSTS.containsKey(region)) bad();
        String workspaceId = string(q, "workspaceId", old.qwenWorkspaceId());
        if (!validWorkspaceId(workspaceId)) bad();
        String qwenModel = model(q, "model", old.qwenModel());
        String jevModel = model(j, "model", old.jevModel());
        Long budget = budget(j, old.budgetUnits());
        State next = new State(old.revision() + 1, provider, bool(o, "fallbackEnabled", old.fallbackEnabled()),
                secret(p, "accessToken", "clearAccessToken", old.paddleAccessToken()),
                secret(b, "apiKey", "clearApiKey", old.ppocrApiKey()),
                secret(b, "secretKey", "clearSecretKey", old.ppocrSecretKey()),
                bool(q, "enabled", old.qwenEnabled()), secret(q, "apiKey", "clearApiKey", old.qwenApiKey()),
                region, workspaceId, qwenModel, bool(j, "enabled", old.jevEnabled()),
                secret(j, "apiKey", "clearApiKey", old.jevApiKey()), jevModel, budget,
                bool(j, "allowCloudData", old.allowCloudData()), rates(billing, old.billingRates()));
        write(next);
        if ("ASSIST".equalsIgnoreCase(jev.getMode())
                && (!Objects.equals(old.jevModel(), next.jevModel())
                || !Objects.equals(old.jevApiKey(), next.jevApiKey()))) jev.setMode("SHADOW");
        state = next;
        applyMutable(next);
        return view();
    }

    private void applyMutable(State s) {
        qwen.setEnabled(s.qwenEnabled()); qwen.setApiKey(s.qwenApiKey());
        qwen.setBaseUrl(qwenUrl(s.qwenRegion(), s.qwenWorkspaceId())); qwen.setModel(s.qwenModel());
        jev.setApiKey(s.jevApiKey()); jev.setModel(s.jevModel());
        jev.setAllowCloudData(s.allowCloudData()); jev.setMonetaryBudgetMinor(s.budgetUnits());
        if (!s.jevEnabled()) jev.setMode("OFF");
        else if (!"ASSIST".equalsIgnoreCase(jev.getMode())) jev.setMode("SHADOW");
    }

    private State readOrBaseline(State baseline) {
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) requirePrivateDirectory(directory);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return baseline;
            requirePrivateDirectory(directory);
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS).equals(FILE_PERMS)
                    || Files.size(file) > 16_384) throw new IOException("unsafe settings file");
            State saved = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(Files.readAllBytes(file), State.class);
            if (saved == null || saved.revision() < 1 || !Set.of("paddle-aistudio", "ppocr").contains(saved.defaultProvider())
                    || !LEGACY_HOSTS.containsKey(saved.qwenRegion()) || saved.qwenWorkspaceId() == null
                    || saved.paddleAccessToken() == null
                    || saved.ppocrApiKey() == null || saved.ppocrSecretKey() == null || saved.qwenApiKey() == null
                    || saved.jevApiKey() == null || saved.qwenModel() == null || saved.jevModel() == null
                    || saved.budgetUnits() != null && saved.budgetUnits() <= 0
                    || !validWorkspaceId(saved.qwenWorkspaceId()) || !validModel(saved.qwenModel())
                    || !validModel(saved.jevModel()) || !validSecret(saved.paddleAccessToken())
                    || !validSecret(saved.ppocrApiKey()) || !validSecret(saved.ppocrSecretKey())
                    || !validSecret(saved.qwenApiKey()) || !validSecret(saved.jevApiKey())
                    || !validRates(saved.billingRates())) throw new IOException("invalid settings");
            return saved;
        } catch (Exception e) {
            // Jackson exceptions can quote fragments of malformed private JSON; never surface them in startup logs.
            throw new IllegalStateException("私有设置文件损坏或权限不安全；请修复后重启，不会静默清空设置");
        }
    }

    private void write(State next) {
        Path temp = null;
        try {
            ensurePrivateDirectory(directory);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("unsafe settings file");
            }
            temp = Files.createTempFile(directory, "settings-", ".tmp");
            Files.setPosixFilePermissions(temp, FILE_PERMS);
            Files.write(temp, json.writeValueAsBytes(next), StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            Files.setPosixFilePermissions(file, FILE_PERMS);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "私有设置保存失败；旧设置仍生效");
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private static void ensurePrivateDirectory(Path dir) throws IOException {
        Path parent = dir.getParent();
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe data directory");
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(dir);
            Files.setPosixFilePermissions(dir, DIR_PERMS);
        }
        requirePrivateDirectory(dir);
    }
    private static void requirePrivateDirectory(Path dir) throws IOException {
        if (Files.isSymbolicLink(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("unsafe settings directory");
        if (!Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(DIR_PERMS))
            throw new IOException("settings directory permissions are not private");
    }
    private static JsonNode section(JsonNode parent, String key, String... allowed) {
        if (parent == null || !parent.has(key)) return null;
        JsonNode section = parent.get(key);
        if (!section.isObject()) bad();
        only(section, allowed); return section;
    }
    private static void only(JsonNode node, String... allowed) {
        if (node == null) return;
        Set<String> names = Set.of(allowed);
        node.fieldNames().forEachRemaining(name -> { if (!names.contains(name)) bad(); });
    }
    private static String string(JsonNode n, String key, String fallback) {
        if (n == null || !n.has(key)) return fallback;
        JsonNode v = n.get(key); if (!v.isTextual()) bad(); return v.textValue();
    }
    private static boolean bool(JsonNode n, String key, boolean fallback) {
        if (n == null || !n.has(key)) return fallback;
        JsonNode v = n.get(key); if (!v.isBoolean()) bad(); return v.booleanValue();
    }
    private static String model(JsonNode n, String key, String fallback) {
        String value = string(n, key, fallback);
        if (!value.isEmpty() && !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")) bad();
        return value;
    }
    private static String secret(JsonNode n, String key, String clearKey, String fallback) {
        boolean clear = bool(n, clearKey, false);
        String value = string(n, key, "");
        if (clear && !value.isEmpty()) bad();
        if (clear) return "";
        if (value.isEmpty()) return fallback;
        if (value.length() > 4096 || value.chars().anyMatch(c -> c < 32 || c == 127)) bad();
        return value;
    }
    private static Long budget(JsonNode n, Long fallback) {
        if (n == null || !n.has("budgetUnits")) return fallback;
        JsonNode v = n.get("budgetUnits");
        if (!v.isTextual() || !v.textValue().matches("[1-9][0-9]{0,17}")) bad();
        try { return Long.parseLong(v.textValue()); } catch (NumberFormatException e) { bad(); return null; }
    }
    private static boolean has(String s) { return s != null && !s.isBlank(); }
    private static List<Rate> defaultRates(String qwenModel, String jevModel) {
        return List.of(new Rate("paddle-aistudio", "PaddleOCR-VL-1.6", "CNY", "", "", ""),
                new Rate("ppocr", "PP-OCRv6", "CNY", "", "", ""),
                new Rate("qwen", qwenModel, "CNY", "", "", ""),
                new Rate("jev", jevModel, "USD", "", "", ""));
    }
    private static List<Rate> rates(JsonNode n, List<Rate> fallback) {
        if (n == null || !n.has("rates")) return fallback;
        JsonNode rows = n.get("rates");
        if (!rows.isArray() || rows.size() != 4) bad();
        List<Rate> result = new ArrayList<>();
        for (JsonNode row : rows) {
            if (!row.isObject()) bad();
            only(row, "provider", "model", "currency", "perRequest", "inputPerMillion", "outputPerMillion");
            result.add(new Rate(string(row, "provider", null), string(row, "model", null),
                    string(row, "currency", null), string(row, "perRequest", null),
                    string(row, "inputPerMillion", null), string(row, "outputPerMillion", null)));
        }
        if (!validRates(result)) bad();
        return List.copyOf(result);
    }
    private static boolean validRates(List<Rate> rates) {
        if (rates == null || rates.size() != 4) return false;
        List<String> providers = List.of("paddle-aistudio", "ppocr", "qwen", "jev");
        for (int i = 0; i < 4; i++) {
            Rate r = rates.get(i);
            if (r == null || !providers.get(i).equals(r.provider()) || !validModel(r.model())
                    || !Set.of("CNY", "USD").contains(r.currency())
                    || !validPrice(r.perRequest()) || !validPrice(r.inputPerMillion())
                    || !validPrice(r.outputPerMillion())) return false;
            if (i < 2 && (!r.inputPerMillion().isEmpty() || !r.outputPerMillion().isEmpty())) return false;
            if (i >= 2 && !r.perRequest().isEmpty()) return false;
        }
        return true;
    }
    private static boolean validPrice(String price) {
        if (price == null) return false;
        if (price.isEmpty()) return true;
        if (!price.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,8})?")) return false;
        try { return new BigDecimal(price).signum() >= 0; } catch (NumberFormatException e) { return false; }
    }
    private static boolean validWorkspaceId(String s) { return s != null && (s.isEmpty() || s.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")); }
    private static boolean validModel(String s) { return s != null && (s.isEmpty() || s.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,119}")); }
    private static boolean validSecret(String s) { return s != null && s.length() <= 4096 && s.chars().noneMatch(c -> c < 32 || c == 127); }
    public static String qwenUrl(String region, String workspaceId) {
        String host = workspaceId == null || workspaceId.isBlank() ? LEGACY_HOSTS.get(region)
                : workspaceId + "." + region + ".maas.aliyuncs.com";
        if (host == null) throw new IllegalArgumentException("unknown Qwen region");
        return "https://" + host + "/compatible-mode/v1";
    }
    private static String[] parseQwenLocation(String baseUrl) {
        if (baseUrl == null) return new String[]{"cn-beijing", ""};
        baseUrl = baseUrl.replaceAll("/+$", "");
        for (var item : LEGACY_HOSTS.entrySet())
            if (("https://" + item.getValue() + "/compatible-mode/v1").equals(baseUrl)) return new String[]{item.getKey(), ""};
        for (String region : LEGACY_HOSTS.keySet()) {
            String suffix = "." + region + ".maas.aliyuncs.com/compatible-mode/v1";
            if (baseUrl.startsWith("https://") && baseUrl.endsWith(suffix)) {
                String id = baseUrl.substring("https://".length(), baseUrl.length() - suffix.length());
                if (validWorkspaceId(id) && !id.isEmpty()) return new String[]{region, id};
            }
        }
        throw new IllegalStateException("Qwen 辅助地址不是已批准的官方域名；不会自动跨区替换");
    }
    private static void bad() { throw new ApiException(HttpStatus.BAD_REQUEST, "设置请求字段无效"); }
}
