package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import studio.bookhtml.config.*;
import studio.bookhtml.decision.DecisionOutboundGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class SettingsControllerTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper();
    @Test void keepsSecretsPrivatePersistsAndClearsEnvBaseline() throws Exception {
        SettingsService settings = service();
        SettingsController controller = new SettingsController(settings, json);
        MockHttpServletRequest get = request("GET");
        Map<String, Object> initial = controller.get(get).getBody();
        String token = (String) initial.get("csrfToken");
        assertFalse(json.writeValueAsString(initial).contains("env-studio-secret"));
        assertFalse(((Map<?, ?>)((Map<?, ?>) initial.get("ocr")).get("paddleAiStudio")).containsKey("accessToken"));
        assertTrue(json.writeValueAsString(initial).contains("accessTokenSet"));
        assertEquals("no-store", controller.get(get).getHeaders().getCacheControl());
        MockHttpServletRequest put = request("PUT");
        put.setSession((MockHttpSession) get.getSession()); put.addHeader("X-Settings-Token", token);
        put.addHeader("Origin", "http://127.0.0.1:18765");
        put.setContent("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"accessToken\":\"\"},\"ppocr\":{\"apiKey\":\"new-key\"}},\"qwen\":{\"region\":\"ap-southeast-1\",\"workspaceId\":\"llm-123\"}}".getBytes(StandardCharsets.UTF_8));
        assertEquals(1L, controller.put(put).getBody().get("revision"));
        assertEquals("env-studio-secret", settings.state().paddleAccessToken());
        assertEquals("new-key", settings.state().ppocrApiKey());
        assertEquals("https://llm-123.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1", ((Map<?, ?>) settings.view().get("qwen")).get("baseUrl"));
        Path dir = data.resolve(".settings"), file = dir.resolve("settings.json");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(dir));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
        String persisted=Files.readString(file);
        assertFalse(persisted.contains("env-studio-secret")); assertFalse(persisted.contains("new-key"));
        assertEquals(2,json.readTree(persisted).path("schemaVersion").intValue());
        assertTrue(json.readTree(persisted).path("secretRefs").path("paddleAccessToken").textValue().startsWith("enc:v1:"));
        SettingsService restarted = service(); assertEquals("new-key", restarted.state().ppocrApiKey()); assertEquals(1, restarted.state().revision());
        restarted.update(json.readTree("{\"revision\":1,\"ocr\":{\"paddleAiStudio\":{\"clearAccessToken\":true}}}"));
        assertEquals("", service().state().paddleAccessToken());
    }
    @Test void rejectsStaleInvalidAndBusyWithoutChangingSnapshot() throws Exception {
        SettingsService settings = service();
        assertStatus(HttpStatus.CONFLICT, () -> settings.update(json.readTree("{\"revision\":2}")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> settings.update(json.readTree("{\"revision\":0,\"qwen\":{\"baseUrl\":\"https://evil.example\"}}")));
        assertStatus(HttpStatus.BAD_REQUEST, () -> settings.update(json.readTree("{\"revision\":0,\"jev\":{\"budgetUnits\":\"0\"}}")));
        try (SettingsService.Lease ignored = settings.beginWork()) {
            assertTrue(settings.busy()); assertStatus(HttpStatus.CONFLICT, () -> settings.update(json.readTree("{\"revision\":0}")));
        }
        assertFalse(settings.busy()); assertEquals(0, settings.state().revision());
    }
    @Test void admissionAndUpdateCannotPassEachOtherUnchecked() throws Exception {
        SettingsService settings = service(); var executor = Executors.newFixedThreadPool(2);
        CountDownLatch admitted = new CountDownLatch(1), updateAttempted = new CountDownLatch(1);
        try {
            var work = executor.submit(() -> {
                try (SettingsService.Lease ignored = settings.beginWork()) {
                    admitted.countDown(); if (!updateAttempted.await(2, TimeUnit.SECONDS)) throw new AssertionError("update did not run");
                    return settings.state().revision();
                }
            });
            var update = executor.submit(() -> {
                if (!admitted.await(2, TimeUnit.SECONDS)) throw new AssertionError("work did not admit");
                try {
                    ApiException error = assertThrows(ApiException.class, () -> settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"defaultProvider\":\"ppocr\"}}")));
                    assertEquals(HttpStatus.CONFLICT, error.status());
                } finally { updateAttempted.countDown(); }
                return settings.state().revision();
            });
            assertEquals(0L, work.get(2, TimeUnit.SECONDS)); assertEquals(0L, update.get(2, TimeUnit.SECONDS));
            settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"defaultProvider\":\"ppocr\"}}")); assertEquals(1L, settings.state().revision());
        } finally { executor.shutdownNow(); }
    }
    @Test void requiresSessionTokenSameOriginAndLoopback() throws Exception {
        SettingsController controller = new SettingsController(service(), json);
        MockHttpServletRequest get = request("GET"); String token = (String) controller.get(get).getBody().get("csrfToken");
        MockHttpServletRequest noToken = request("PUT"); noToken.setSession((MockHttpSession) get.getSession());
        noToken.setContent("{\"revision\":0}".getBytes(StandardCharsets.UTF_8)); assertStatus(HttpStatus.FORBIDDEN, () -> controller.put(noToken));
        MockHttpServletRequest cross = request("PUT"); cross.setSession((MockHttpSession) get.getSession());
        cross.addHeader("X-Settings-Token", token); cross.addHeader("Origin", "http://localhost:9999");
        cross.setContent("{\"revision\":0}".getBytes(StandardCharsets.UTF_8)); assertStatus(HttpStatus.FORBIDDEN, () -> controller.put(cross));
        MockHttpServletRequest remote = request("GET"); remote.setRemoteAddr("192.0.2.1"); assertStatus(HttpStatus.FORBIDDEN, () -> controller.get(remote));
        MockHttpServletRequest tooLarge = request("PUT"); tooLarge.setSession((MockHttpSession) get.getSession());
        tooLarge.addHeader("X-Settings-Token", token); tooLarge.setContent(new byte[16_385]);
        assertStatus(HttpStatus.PAYLOAD_TOO_LARGE, () -> controller.put(tooLarge));
    }
    @Test void invalidSecretNeverEchoesInput() throws Exception {
        SettingsService settings = service(); String sentinel = "private-key-DO-NOT-ECHO\n";
        ApiException error = assertThrows(ApiException.class, () -> settings.update(json.readTree("{\"revision\":0,\"ocr\":{\"paddleAiStudio\":{\"accessToken\":\"private-key-DO-NOT-ECHO\\n\"}}}")));
        assertEquals(HttpStatus.BAD_REQUEST, error.status()); assertFalse(error.getMessage().contains(sentinel.strip()));
        assertEquals("env-studio-secret", settings.state().paddleAccessToken());
    }
    @Test void appliesQwenAndJevRuntimeFieldsWithoutTurningOffIntoOutbound() throws Exception {
        QwenAssistProperties qwen = new QwenAssistProperties(); DecisionProperties jev = new DecisionProperties();
        SettingsService settings = service(qwen, jev); assertEquals("OFF", jev.getMode());
        DecisionOutboundGate gate = new DecisionOutboundGate(jev);
        assertFalse(gate.check(new DecisionOutboundGate.GateRequest(DecisionOutboundGate.Purpose.JEV,"TYPESAFE",1_000_000,new DecisionOutboundGate.CapabilityView(false,"test-key","test-model"))).allowed());
        settings.update(json.readTree("{\"revision\":0,\"qwen\":{\"enabled\":true,\"apiKey\":\"qwen-key\",\"region\":\"cn-hongkong\",\"model\":\"qwen3.8-max\"},\"jev\":{\"enabled\":true,\"apiKey\":\"jev-key\",\"model\":\"jev-1.13.0\",\"budgetUnits\":\"3\",\"allowCloudData\":true}}"));
        assertEquals("https://cn-hongkong.dashscope.aliyuncs.com/compatible-mode/v1", qwen.getBaseUrl());
        assertEquals("qwen-key", qwen.getApiKey()); assertEquals("SHADOW", jev.getMode());
        assertEquals(3L, jev.getMonetaryBudgetMinor()); assertTrue(jev.isAllowCloudData()); assertEquals("jev-key", jev.getApiKey());
        settings.update(json.readTree("{\"revision\":1,\"jev\":{\"enabled\":false}}")); assertEquals("OFF", jev.getMode());
        assertFalse(gate.check(new DecisionOutboundGate.GateRequest(DecisionOutboundGate.Purpose.JEV,"TYPESAFE",1_000_000,new DecisionOutboundGate.CapabilityView(false,"jev-key","jev-1.13.0"))).allowed());
    }
    @Test void rejectsCorruptAndSymlinkedPrivateSettings() throws Exception {
        Path dir = Files.createDirectory(data.resolve(".settings"));
        Files.setPosixFilePermissions(dir, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        Path file = dir.resolve("settings.json"); Files.writeString(file, "not-json");
        Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        assertThrows(IllegalStateException.class, this::service); Files.delete(file);
        Path target = data.resolve("target.json"); Files.writeString(target, "{}"); Files.createSymbolicLink(file, target);
        assertThrows(IllegalStateException.class, this::service);
    }
    @Test void explicitUpdatesKeepClearAndRejectAmbiguousOrMaskedValues() throws Exception {
        SettingsService s=service();
        s.update(json.readTree("{\"revision\":0,\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"explicit-canary\"}}}"));
        assertEquals("explicit-canary",s.state().qwenApiKey());
        assertFalse(json.writeValueAsString(s.view()).contains("explicit-canary")); assertFalse(s.state().toString().contains("explicit-canary"));
        s.update(json.readTree("{\"revision\":1,\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"\"}}}"));
        assertEquals("explicit-canary",s.state().qwenApiKey());
        for(String value:java.util.List.of("****","REDACTED","   ")) {
            var body=json.createObjectNode().put("revision",2);
            body.putObject("secretUpdates").putObject("qwenApiKey").put("value",value);
            assertStatus(HttpStatus.BAD_REQUEST,()->s.update(body));
        }
        assertStatus(HttpStatus.BAD_REQUEST,()->s.update(json.readTree("{\"revision\":2,\"qwen\":{\"apiKey\":\"legacy-canary\"},\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"other-canary\"}}}")));
        s.update(json.readTree("{\"revision\":2,\"secretUpdates\":{\"qwenApiKey\":{\"clearSecret\":true}}}"));
        assertEquals("",service().state().qwenApiKey());
    }
    @Test void environmentModeAllowsOnlyNonSecretChanges() throws Exception {
        var env=SecretStore.environment(Map.of("paddleAccessToken","env-studio-secret"));
        var s=service(new QwenAssistProperties(),new DecisionProperties(),env);
        s.update(json.readTree("{\"revision\":0,\"ocr\":{\"defaultProvider\":\"ppocr\"}}"));
        assertFalse(Files.readString(data.resolve(".settings/settings.json")).contains("env-studio-secret"));
        assertStatus(HttpStatus.CONFLICT,()->s.update(json.readTree("{\"revision\":1,\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"not-allowed\"}}}")));
        assertEquals(1,s.state().revision()); assertEquals("env-studio-secret",s.state().paddleAccessToken());
        assertFalse((Boolean)((Map<?,?>)s.view().get("secretStorage")).get("writable"));
    }
    @Test void validatedLegacyMigrationPreservesRevisionAndEffectiveCredentials() throws Exception {
        var s=service(); s.update(json.readTree("{\"revision\":0,\"qwen\":{\"apiKey\":\"legacy-canary\"}}"));
        Path file=data.resolve(".settings/settings.json"); Files.write(file,json.writeValueAsBytes(s.state()));
        var migrated=service(); assertEquals(s.state(),migrated.state());
        assertFalse(Files.readString(file).contains("legacy-canary")); assertFalse(Files.readString(file).contains("env-studio-secret"));
    }
    @Test void unavailableMigrationStoreDoesNotDestroyLegacySettings() throws Exception {
        var s=service(); s.update(json.readTree("{\"revision\":0,\"qwen\":{\"apiKey\":\"legacy-canary\"}}"));
        Path file=data.resolve(".settings/settings.json"); byte[] before=json.writeValueAsBytes(s.state()); Files.write(file,before);
        var error=assertThrows(IllegalStateException.class,()->service(new QwenAssistProperties(),new DecisionProperties(),SecretStore.environment(Map.of())));
        assertArrayEquals(before,Files.readAllBytes(file)); assertNull(error.getCause()); assertFalse(error.getMessage().contains("legacy-canary"));
    }
    private SettingsService service() { return service(new QwenAssistProperties(), new DecisionProperties()); }
    private SettingsService service(QwenAssistProperties qwen, DecisionProperties jev) {
        // Deliberately synthetic test key. Production never generates or stores its master key here.
        return service(qwen,jev,new EncryptedFileSecretStore(data,new byte[32],"settings-controller-fixture","fixture"));
    }
    private SettingsService service(QwenAssistProperties qwen, DecisionProperties jev, SecretStore store) {
        AppProperties app = new AppProperties(data,300,5000,2400,"tesseract","","qwen3.5-ocr","https://dashscope.aliyuncs.com/api/v1",60,"","MiniMax-M3","https://api.minimax.cn/v1",60,false,"","","PaddleOCR-VL-1.6","https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task",60,180,5);
        PaddleAiStudioProperties paddle = new PaddleAiStudioProperties("env-studio-secret","https://paddleocr.aistudio-app.com/api/v2/ocr/jobs","PaddleOCR-VL-1.6",60,180,5);
        return new SettingsService(app,paddle,qwen,jev,json,store);
    }
    private static MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest(method,"/api/settings");
        request.setScheme("http"); request.setServerName("127.0.0.1"); request.setServerPort(18765); request.setRemoteAddr("127.0.0.1"); return request;
    }
    private static void assertStatus(HttpStatus expected, Throwing action) {
        ApiException error = assertThrows(ApiException.class, action::run); assertEquals(expected,error.status());
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    @Test
    void writeOnlyUpdatesDoNotReturnAnyProviderSecret() throws Exception {
        SettingsService settings = service();
        Map<String, String> values = Map.of(
                "paddleAccessToken", "CANARY_PADDLE_NOT_A_CREDENTIAL",
                "ppocrApiKey", "CANARY_PPOCR_KEY_NOT_A_CREDENTIAL",
                "ppocrSecretKey", "CANARY_PPOCR_SECRET_NOT_A_CREDENTIAL",
                "qwenApiKey", "CANARY_QWEN_NOT_A_CREDENTIAL",
                "jevApiKey", "CANARY_JEV_NOT_A_CREDENTIAL");
        var body = json.createObjectNode().put("revision", 0);
        var updates = body.putObject("secretUpdates");
        values.forEach((name, value) -> updates.putObject(name).put("value", value));
        String output = json.writeValueAsString(settings.update(body))
                + json.writeValueAsString(settings.view()) + settings.state();
        for (String value : values.values()) {
            assertFalse(output.contains(value));
            assertFalse(output.contains(java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8))));
        }
        assertEquals(values.get("qwenApiKey"), settings.state().qwenApiKey());
        settings.update(json.readTree("{\"revision\":1}"));
        assertEquals(values.get("qwenApiKey"), settings.state().qwenApiKey(), "omission keeps the stored secret");
        settings.update(json.readTree("{\"revision\":2,\"secretUpdates\":{\"qwenApiKey\":{\"clearSecret\":true}}}"));
        assertEquals("", settings.state().qwenApiKey());
        assertEquals(values.get("jevApiKey"), settings.state().jevApiKey());
    }

    @Test
    void masksAndAmbiguousSecretUpdatesAreRejectedWithoutMutation() throws Exception {
        SettingsService settings = service();
        for (String update : java.util.List.of(
                "{\"revision\":0,\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"****\"}}}",
                "{\"revision\":0,\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"new-canary\",\"clearSecret\":true}}}",
                "{\"revision\":0,\"qwen\":{\"apiKey\":\"old-canary\"},\"secretUpdates\":{\"qwenApiKey\":{\"value\":\"new-canary\"}}}",
                "{\"revision\":0,\"secretUpdates\":{\"qwenApiKey\":{\"value\":123}}}")) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> settings.update(json.readTree(update)));
            assertEquals(0, settings.state().revision());
        }
    }

}
