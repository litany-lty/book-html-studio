package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.store.BookStore;

import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OcrSingleFlightRaceTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private BookStore store;
    private UsageLedger usage;

    @BeforeEach
    void setUp() throws Exception {
        AppProperties app = TestConfigs.config(temp, "", "");
        store = new BookStore(app, json);
        studio.bookhtml.config.SettingsService settings = new studio.bookhtml.config.SettingsService(app,
                new studio.bookhtml.config.PaddleAiStudioProperties("", null, null, 60, 180, 5),
                new studio.bookhtml.config.QwenAssistProperties(),
                new studio.bookhtml.config.DecisionProperties(), json);
        usage = new UsageLedger(store, settings, json);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    @Test
    void concurrentRequestsForSameImageExecuteSingleFlightOnlyOnce() throws Exception {
        // Mock transport with latency to maximize race window
        CountDownLatch inFlight = new CountDownLatch(1);
        AtomicInteger ocrCalls = new AtomicInteger();
        AtomicInteger authCalls = new AtomicInteger();

        BaiduPpOcrClient.Transport transport = (request, cancelled, maxBytes) -> {
            String uri = request.uri().toString();
            if (uri.contains("oauth/2.0/token")) {
                authCalls.incrementAndGet();
                return new BaiduPpOcrClient.Response(200, "{\"access_token\":\"secret-token\",\"expires_in\":3600}");
            } else {
                ocrCalls.incrementAndGet();
                try {
                    inFlight.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return new BaiduPpOcrClient.Response(200,
                        "{\"page_result\":[{\"lines\":[\"单飞第一行\",\"单飞第二行\"],\"rec_boxes\":[[10,20,100,60],[10,70,100,110]],\"probability\":[0.9,0.8]}],\"log_id\":100}");
            }
        };

        AppProperties app = config(temp);
        PpOcrProperties ppocr = new PpOcrProperties("https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5", 30);
        BaiduPpOcrClient client = new BaiduPpOcrClient(app, ppocr, json, new BaiduPpOcrParser(), transport);
        client.setUsageLedger(usage);

        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        List<Future<List<Block>>> futures = new ArrayList<>();
        byte[] pngData = png();

        for (int i = 0; i < threads; i++) {
            futures.add(exec.submit(() -> {
                barrier.await();
                try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
                    return client.recognize(pngData, 1000, 800, "auto", () -> false);
                }
            }));
        }

        // Release the simulated in-flight OCR request
        inFlight.countDown();

        for (var f : futures) {
            List<Block> blocks = f.get(5, TimeUnit.SECONDS);
            assertEquals(2, blocks.size());
            assertEquals("ppocr-line-1", blocks.get(0).id());
        }
        exec.shutdownNow();

        assertEquals(1, authCalls.get(), "认证请求仅发起 1 次");
        assertEquals(1, ocrCalls.get(), "单飞条带锁保证相同图片仅发起 1 次物理 OCR 识别");
    }

    @Test
    void tokenExpiredTriggersSingleAuthRefreshAndRetriesOcrPost() throws Exception {
        AtomicInteger authCalls = new AtomicInteger();
        AtomicInteger ocrCalls = new AtomicInteger();

        BaiduPpOcrClient.Transport transport = (request, cancelled, maxBytes) -> {
            String uri = request.uri().toString();
            if (uri.contains("oauth/2.0/token")) {
                authCalls.incrementAndGet();
                return new BaiduPpOcrClient.Response(200, "{\"access_token\":\"refreshed-token\",\"expires_in\":3600}");
            } else {
                int call = ocrCalls.incrementAndGet();
                if (call == 1) {
                    // First call returns token expired code 110
                    return new BaiduPpOcrClient.Response(200, "{\"error_code\":110,\"error_msg\":\"Access token invalid or expired\"}");
                } else {
                    return new BaiduPpOcrClient.Response(200,
                            "{\"page_result\":[{\"lines\":[\"刷新后文本\"],\"rec_boxes\":[[10,20,100,60]],\"probability\":[0.95]}],\"log_id\":101}");
                }
            }
        };

        AppProperties app = config(temp);
        PpOcrProperties ppocr = new PpOcrProperties("https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5", 30);
        BaiduPpOcrClient client = new BaiduPpOcrClient(app, ppocr, json, new BaiduPpOcrParser(), transport);
        client.setUsageLedger(usage);

        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        List<Block> blocks;
        try (UsageContext.Scope ignored = UsageContext.open(bookId, 1, "OCR_PAGE")) {
            blocks = client.recognize(png(), 1000, 800, "auto", () -> false);
        }
        assertEquals(1, blocks.size());
        assertEquals("刷新后文本", blocks.get(0).original());

        // Token was fetched initially, failed with 110, then refreshed (2 auth calls total)
        assertEquals(2, authCalls.get(), "初始与过期后共发起 2 次认证调用");
        assertEquals(2, ocrCalls.get(), "初次失败与刷新后重试共发起 2 次 OCR POST");
    }

    private static AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false,
                "api-key", "secret-key", "PaddleOCR-VL-1.6",
                "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task", 5, 5, 1);
    }

    private static byte[] png() {
        return new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1f, 0x15, (byte) 0xc4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0a, 0x49, 0x44, 0x41,
                0x54, 0x78, (byte) 0x9c, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0d, 0x0a, 0x2d, (byte) 0xb4, 0x00,
                0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44, (byte) 0xae,
                0x42, 0x60, (byte) 0x82
        };
    }
}
