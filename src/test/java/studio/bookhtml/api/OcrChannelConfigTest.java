package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Pattern;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.PpOcrProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.service.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OcrChannelConfigTest {
    @Test
    void reportsBothChannelsIndependentlyAndKeepsBaiduDefault() {
        Map<String, Object> baiduOnly = controller(true, false).config();
        assertEquals("paddle", baiduOnly.get("defaultProvider"));
        assertEquals(true, provider(baiduOnly, "paddle").get("available"));
        assertEquals(false, provider(baiduOnly, "paddle-aistudio").get("available"));
        assertEquals(true, channel(baiduOnly, "paddle").get("configured"));
        assertEquals(false, channel(baiduOnly, "paddle-aistudio").get("configured"));

        Map<String, Object> studioOnly = controller(false, true).config();
        assertEquals("paddle", studioOnly.get("defaultProvider"));
        assertEquals(false, provider(studioOnly, "paddle").get("available"));
        assertEquals(true, provider(studioOnly, "paddle-aistudio").get("available"));
        assertEquals(false, channel(studioOnly, "paddle").get("configured"));
        assertEquals(true, channel(studioOnly, "paddle-aistudio").get("configured"));
        assertNotEquals(provider(studioOnly, "paddle").get("quotaSource"),
                provider(studioOnly, "paddle-aistudio").get("quotaSource"));
    }

    @Test
    void configDoesNotExposeCredentialsOrEndpointSecrets() throws Exception {
        Map<String, Object> config = controller(true, true).config();
        String json = new ObjectMapper().writeValueAsString(config);

        assertFalse(json.contains("baidu-api-key-value"));
        assertFalse(json.contains("baidu-secret-key-value"));
        assertFalse(json.contains("studio-access-token-value"));
        assertFalse(json.contains("endpoint-user"));
        assertFalse(json.contains("endpoint-password"));
        assertFalse(json.contains("access_token=leak"));
        assertFalse(json.contains("key=hidden"));
        assertFalse(json.contains("?"));
        assertFalse(json.contains("@"));
        assertEquals("https://baidu.example/task", channel(config, "paddle").get("endpoint"));
        assertEquals("https://studio.example/jobs", channel(config, "paddle-aistudio").get("endpoint"));
    }

    @Test
    void jobRequestAcceptsAiStudioProviderWithoutChangingNullDefault() throws Exception {
        Pattern constraint = JobRequest.class.getDeclaredField("provider").getAnnotation(Pattern.class);
        assertNotNull(constraint);
        assertTrue(java.util.regex.Pattern.matches(constraint.regexp(), "paddle-aistudio"));
        assertTrue(java.util.regex.Pattern.matches(constraint.regexp(), "ppocr"));
        assertEquals("paddle-aistudio",
                new JobRequest("1-20", "paddle-aistudio", "auto", true, false, true).providerOrDefault());
        assertEquals("ppocr",
                new JobRequest("1-20", "ppocr", "auto", true, false, true).providerOrDefault());
        assertEquals("paddle", new JobRequest("1-20", null, "auto", true, false, true).providerOrDefault());
    }

    @Test
    void reportsPpocrChannelAlongsideBaiduAndStudio() {
        Map<String, Object> config = controller(true, true).config();
        assertEquals(true, provider(config, "ppocr").get("available"));
        assertEquals(true, channel(config, "ppocr").get("configured"));
        assertEquals("PP-OCRv6", channel(config, "ppocr").get("model"));
        assertNotEquals(channel(config, "paddle").get("quotaSource"), channel(config, "ppocr").get("quotaSource"));

        Map<String, Object> none = controller(false, false).config();
        assertEquals(false, provider(none, "ppocr").get("available"));
    }

    private static ApiController controller(boolean baiduConfigured, boolean studioConfigured) {
        PaddleOcrClient baidu = mock(PaddleOcrClient.class);
        when(baidu.configured()).thenReturn(baiduConfigured);
        PaddleAiStudioClient aiStudio = mock(PaddleAiStudioClient.class);
        when(aiStudio.configured()).thenReturn(studioConfigured);
        BaiduPpOcrClient ppocr = mock(BaiduPpOcrClient.class);
        when(ppocr.configured()).thenReturn(baiduConfigured);
        PpOcrProperties ppocrProperties = new PpOcrProperties(
                "https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5", 5);
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        MiniMaxVisionClient miniMax = mock(MiniMaxVisionClient.class);
        QwenLayoutClient qwenAssist = mock(QwenLayoutClient.class);
        when(qwen.configured()).thenReturn(false);
        when(miniMax.configured()).thenReturn(false);
        when(qwenAssist.configured()).thenReturn(false);
        QwenAssistProperties assistProperties = new QwenAssistProperties();
        AppProperties properties = new AppProperties(Path.of("target/test-unused"), 300, 5000, 2400,
                "tesseract", "", "qwen3.5-ocr", "https://dashscope.example/v1?key=hidden", 5,
                "", "MiniMax-M3", "https://minimax.example/v1?key=hidden", 5, false,
                "baidu-api-key-value", "baidu-secret-key-value", "PaddleOCR-VL-1.6",
                "https://endpoint-user:endpoint-password@baidu.example/task?access_token=leak", 5, 5, 1);
        PaddleAiStudioProperties aiStudioProperties = new PaddleAiStudioProperties(
                "studio-access-token-value",
                "https://endpoint-user:endpoint-password@studio.example/jobs?access_token=leak",
                "PaddleOCR-VL-1.6", 5, 5, 1);
        return new ApiController(mock(BookService.class), mock(JobService.class), mock(ExportService.class),
                properties, qwen, miniMax, baidu, qwenAssist, assistProperties,
                mock(IssueImageService.class), new ObjectMapper(), aiStudio, aiStudioProperties, ppocr, ppocrProperties);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> provider(Map<String, Object> config, String id) {
        return ((List<Map<String, Object>>) config.get("providers")).stream()
                .filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> channel(Map<String, Object> config, String id) {
        return ((List<Map<String, Object>>) config.get("ocrChannels")).stream()
                .filter(item -> id.equals(item.get("id"))).findFirst().orElseThrow();
    }
}
