package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParagraphComprehensibilityServiceTest {

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> mockResponse(int statusCode, String jsonBody) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(jsonBody.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    @Test
    void checkPage_extractsFindingsAndCreatesIssuesWithInferredText() throws Exception {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("test-key");
        config.setEnabled(true);
        ObjectMapper json = new ObjectMapper();
        TraditionalConverter converter = new TraditionalConverter();

        String modelJson = """
        {
          "choices": [
            {
              "finish_reason": "stop",
              "message": {
                "content": "{\\"findings\\":[{\\"blockId\\":\\"b1\\",\\"quote\\":\\"語句不通順\\",\\"start\\":0,\\"end\\":5,\\"reason\\":\\"上下文敘述學術原理，此處字詞錯漏\\",\\"inferredText\\":\\"語義通順\\"}]}"
              }
            }
          ]
        }
        """;

        AtomicReference<HttpRequest> sentRequest = new AtomicReference<>();
        ParagraphComprehensibilityService service = new ParagraphComprehensibilityService(config, json, converter, req -> {
            sentRequest.set(req);
            return mockResponse(200, modelJson);
        });

        Block b1 = new Block("b1", "text", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                "語句不通順的地方需要自檢", "语句不通顺的地方需要自检", 0.95, false, false, null, "ocr", List.of(), null, null, List.of());
        Block b2 = new Block("b2", "text", 1, new double[]{0, 1, 1, 1}, "horizontal-tb",
                "第二段內容上下文正常", "第二段内容上下文正常", 0.98, false, false, null, "ocr", List.of(), null, null, List.of());

        var checked = service.check("book-1", 1, List.of(b1, b2), () -> false);
        assertTrue(checked.complete());
        List<Block> result = checked.blocks();

        assertNotNull(sentRequest.get());
        assertEquals(2, result.size());
        Block checkedB1 = result.get(0);
        assertEquals(1, checkedB1.issues().size());

        ContentIssue issue = checkedB1.issues().get(0);
        assertEquals("suspected", issue.kind());
        assertEquals(0, issue.start());
        assertEquals(5, issue.end());
        assertEquals("上下文敘述學術原理，此處字詞錯漏", issue.reason());
        assertEquals("語義通順", issue.inferredText());
        assertFalse(issue.resolved());

        Block checkedB2 = result.get(1);
        assertTrue(checkedB2.issues().isEmpty());
    }

    @Test
    void checkPage_toleratesSlightOffsetDriftByLocatingExactQuote() throws Exception {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("test-key");
        config.setEnabled(true);
        ObjectMapper json = new ObjectMapper();
        TraditionalConverter converter = new TraditionalConverter();

        // Model reports start=5, end=8 (wrong offset), but quote "錯別字" actually exists at index 2..5
        String modelJson = """
        {
          "choices": [
            {
              "finish_reason": "stop",
              "message": {
                "content": "{\\"findings\\":[{\\"blockId\\":\\"b1\\",\\"quote\\":\\"錯別字\\",\\"start\\":5,\\"end\\":8,\\"reason\\":\\"筆畫辨析失誤\\",\\"inferredText\\":\\"正確字\\"}]}"
              }
            }
          ]
        }
        """;

        ParagraphComprehensibilityService service = new ParagraphComprehensibilityService(config, json, converter, req -> mockResponse(200, modelJson));

        Block b1 = new Block("b1", "text", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                "包含錯別字的文章段落", "包含错别字的文章段落", 0.95, false, false, null, "ocr", List.of(), null, null, List.of());

        var checked = service.check("book-1", 1, List.of(b1), () -> false);
        assertTrue(checked.complete());
        List<Block> result = checked.blocks();
        assertEquals(1, result.get(0).issues().size());
        ContentIssue issue = result.get(0).issues().get(0);

        assertEquals(2, issue.start());
        assertEquals(5, issue.end());
        assertEquals("正確字", issue.inferredText());
    }

    @Test
    void checkPage_skipsWhenNotConfigured() {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("");
        config.setEnabled(true);
        ObjectMapper json = new ObjectMapper();
        TraditionalConverter converter = new TraditionalConverter();

        ParagraphComprehensibilityService service = new ParagraphComprehensibilityService(config, json, converter, req -> {
            throw new AssertionError("Should not be called");
        });

        Block b1 = new Block("b1", "text", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                "文本段落", "文本段落", 0.95, false, false, null, "ocr", List.of(), null, null, List.of());

        ApiException ex = assertThrows(ApiException.class, () -> service.checkPage("book-1", 1, List.of(b1), () -> false));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertTrue(ex.getMessage().contains("尚未配置"));
    }

    @Test
    void checkPage_doesNotOverlapExistingIssues() throws Exception {
        QwenAssistProperties config = new QwenAssistProperties();
        config.setApiKey("test-key");
        config.setEnabled(true);
        ObjectMapper json = new ObjectMapper();
        TraditionalConverter converter = new TraditionalConverter();

        String modelJson = """
        {
          "choices": [
            {
              "finish_reason": "stop",
              "message": {
                "content": "{\\"findings\\":[{\\"blockId\\":\\"b1\\",\\"quote\\":\\"別字\\",\\"start\\":3,\\"end\\":5,\\"reason\\":\\"重疊問題\\",\\"inferredText\\":\\"新推斷\\"}]}"
              }
            }
          ]
        }
        """;

        ParagraphComprehensibilityService service = new ParagraphComprehensibilityService(config, json, converter, req -> mockResponse(200, modelJson));

        ContentIssue existing = new ContentIssue("exist-1", "suspected", 2, 5, 2, 5, "既有校對", true, "替換字", null);
        Block b1 = new Block("b1", "text", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                "包含錯別字的文章段落", "包含错别字的文章段落", 0.95, false, false, null, "ocr", List.of(), null, null, List.of(existing));

        var checked = service.check("book-1", 1, List.of(b1), () -> false);
        assertTrue(checked.complete());
        List<Block> result = checked.blocks();
        // Overlapping finding must be dropped, retaining existing issue unchanged
        assertEquals(1, result.get(0).issues().size());
        assertEquals("exist-1", result.get(0).issues().get(0).id());
    }
}
