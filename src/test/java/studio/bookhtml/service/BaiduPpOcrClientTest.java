package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.domain.Block;

import java.net.http.HttpRequest;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BaiduPpOcrClientTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test void successCachesNormalizedResultWithoutLeakingToken() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200, "{\"access_token\":\"secret-token\",\"expires_in\":3600}"),
                response(200, "{\"page_result\":[{\"lines\":[\"甲乙\",\"丙丁\"],\"rec_boxes\":[[10,20,100,60],[10,70,100,110]],\"probability\":[0.9,0.5]}],\"log_id\":1}")));
        BaiduPpOcrClient client = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), transport);
        List<Block> blocks = client.recognize(png(), 1000, 800, "auto", () -> false);
        assertEquals(2, blocks.size());
        assertEquals("ppocr-line-1", blocks.get(0).id());
        assertEquals("ppocr", blocks.get(0).source());
        assertEquals(2, transport.requests.size());
        String cache = Files.readString(Files.list(temp.resolve("cache/ppocr")).findFirst().orElseThrow());
        assertFalse(cache.contains("secret-token"));
        assertTrue(cache.contains("line-1"));
        ScriptedTransport none = new ScriptedTransport(List.of());
        List<Block> cached = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), none).recognize(png(), 1000, 800, "auto", () -> false);
        assertEquals(2, cached.size());
        assertTrue(none.requests.isEmpty());
    }

    @Test void quotaErrorIsMarkedForFallbackAndLeavesNoCache() {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200, "{\"access_token\":\"t\",\"expires_in\":3600}"),
                response(200, "{\"error_code\":17,\"error_msg\":\"Open api daily request limit reached\"}")));
        BaiduPpOcrClient client = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), transport);
        QuotaExceededException error = assertThrows(QuotaExceededException.class,
                () -> client.recognize(png(), 1000, 800, "auto", () -> false));
        assertTrue(error.getMessage().contains("额度"));
        assertTrue(Files.notExists(temp.resolve("cache/ppocr")) || isEmptyDir(temp.resolve("cache/ppocr")));
    }

    @Test void imageErrorIsNotQuota() {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200, "{\"access_token\":\"t\",\"expires_in\":3600}"),
                response(200, "{\"error_code\":216202,\"error_msg\":\"image size error\"}")));
        BaiduPpOcrClient client = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), transport);
        OcrException error = assertThrows(OcrException.class,
                () -> client.recognize(png(), 1000, 800, "auto", () -> false));
        assertFalse(error instanceof QuotaExceededException);
    }

    @Test void http429IsQuota() {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200, "{\"access_token\":\"t\",\"expires_in\":3600}"),
                response(429, "throttled")));
        BaiduPpOcrClient client = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), transport);
        assertThrows(QuotaExceededException.class, () -> client.recognize(png(), 1000, 800, "auto", () -> false));
    }

    @Test void expiredTokenRefreshesOnceThenSucceeds() throws Exception {
        ScriptedTransport transport = new ScriptedTransport(List.of(
                response(200, "{\"access_token\":\"old\",\"expires_in\":3600}"),
                response(200, "{\"error_code\":110,\"error_msg\":\"Access token invalid\"}"),
                response(200, "{\"access_token\":\"fresh\",\"expires_in\":3600}"),
                response(200, "{\"page_result\":[{\"lines\":[\"甲\"],\"rec_boxes\":[[10,20,100,60]]}],\"log_id\":2}")));
        BaiduPpOcrClient client = new BaiduPpOcrClient(config(temp), ppocr(), json, new BaiduPpOcrParser(), transport);
        List<Block> blocks = client.recognize(png(), 1000, 800, "auto", () -> false);
        assertEquals(1, blocks.size());
        assertEquals(4, transport.requests.size());
    }

    private AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false,
                "api-key", "secret-key", "PaddleOCR-VL-1.6",
                "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task", 5, 5, 1);
    }

    private static PpOcrProperties ppocr() {
        return new PpOcrProperties("https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5", 5);
    }

    private static byte[] png() {
        return new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3};
    }

    private static boolean isEmptyDir(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    private static BaiduPpOcrClient.Response response(int status, String body) {
        return new BaiduPpOcrClient.Response(status, body);
    }

    private static final class ScriptedTransport implements BaiduPpOcrClient.Transport {
        final Deque<BaiduPpOcrClient.Response> responses;
        final List<HttpRequest> requests = new ArrayList<>();

        ScriptedTransport(List<BaiduPpOcrClient.Response> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        public BaiduPpOcrClient.Response send(HttpRequest request, java.util.function.BooleanSupplier cancelled, int maxBytes) {
            requests.add(request);
            if (responses.isEmpty()) throw new AssertionError("unexpected request " + request.uri().getPath());
            return responses.removeFirst();
        }
    }
}
