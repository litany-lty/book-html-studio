package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;

import static org.junit.jupiter.api.Assertions.*;

class PaddleCacheMigrationTest {
    @TempDir Path temp;

    private AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false,
                "api-key", "secret-key", "PaddleOCR-VL-1.6",
                "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task", 5, 5, 1);
    }

    private static String resultJson() {
        return "{\"pages\":[{\"layouts\":[{\"layout_id\":\"a\",\"type\":\"vertical_text\",\"text\":\"原文\",\"position\":[800,100,50,400]}]}]}";
    }

    @Test void legacyCacheMigratesWithoutRebilling() throws Exception {
        ObjectMapper json = new ObjectMapper();
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 1, 2, 3};
        // 先用旧哈希写入已完成的缓存
        String legacy = PaddleOcrClientTestHelper.legacy(png);
        Path legacyPath = temp.resolve("cache/paddle").resolve(legacy + ".json");
        Files.createDirectories(legacyPath.getParent());
        var result = json.readTree(resultJson());
        json.writeValue(legacyPath.toFile(), new PaddleOcrClient.CacheEntry(legacy, "task-old", "done", result));

        PaddleOcrClient client = new PaddleOcrClient(config(temp), json, new PaddleOcrParser(),
                (request, cancelled, max) -> { throw new AssertionError("旧缓存命中不得再发网络请求"); },
                (s, c) -> {});
        List<Block> blocks = client.recognize(png, 1000, 800, "auto", () -> false);
        assertEquals(1, blocks.size());
        assertEquals("原文", blocks.get(0).original());
        // 已迁移到新指纹
        String fresh = PaddleOcrClientTestHelper.fingerprint(config(temp), png);
        assertTrue(Files.exists(temp.resolve("cache/paddle").resolve(fresh + ".json")));
    }

    @Test void modelChangeIsolatesCache() throws Exception {
        ObjectMapper json = new ObjectMapper();
        byte[] png = new byte[]{(byte) 137, 80, 78, 71, 4, 5, 6};
        String v1 = PaddleOcrClientTestHelper.fingerprint(config(temp), png);
        AppProperties other = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false,
                "api-key", "secret-key", "PaddleOCR-VL-9.9",
                "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task", 5, 5, 1);
        String v2 = PaddleOcrClientTestHelper.fingerprint(other, png);
        assertNotEquals(v1, v2, "模型变更必须隔离缓存");
    }

    static final class PaddleOcrClientTestHelper {
        static String legacy(byte[] png) throws Exception {
            var m = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(m.digest(png));
        }

        static String fingerprint(AppProperties config, byte[] png) throws Exception {
            var m = java.security.MessageDigest.getInstance("SHA-256");
            m.update("paddle-v1\u0000".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            m.update((byte) 0);
            m.update(config.paddleModel().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            m.update((byte) 0);
            m.update(png);
            return java.util.HexFormat.of().formatHex(m.digest());
        }
    }
}
