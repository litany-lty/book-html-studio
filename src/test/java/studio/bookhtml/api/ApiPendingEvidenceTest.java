package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiPendingEvidenceTest {
    @Test void pageReadReturnsPendingSummaryWithoutFullLocate() throws Exception {
        BookService books = mock(BookService.class);
        JobService jobs = mock(JobService.class);
        ExportService export = mock(ExportService.class);
        studio.bookhtml.config.AppProperties config = mock(studio.bookhtml.config.AppProperties.class);
        QwenOcrClient qwen = mock(QwenOcrClient.class);
        MiniMaxVisionClient miniMax = mock(MiniMaxVisionClient.class);
        PaddleOcrClient paddle = mock(PaddleOcrClient.class);
        QwenLayoutClient assist = mock(QwenLayoutClient.class);
        studio.bookhtml.config.QwenAssistProperties assistConfig = mock(studio.bookhtml.config.QwenAssistProperties.class);
        IssueImageService issueImages = mock(IssueImageService.class);
        PaddleAiStudioClient aiStudio = mock(PaddleAiStudioClient.class);
        studio.bookhtml.config.PaddleAiStudioProperties aiStudioConfig = mock(studio.bookhtml.config.PaddleAiStudioProperties.class);
        ApiController controller = new ApiController(books, jobs, export, config, qwen, miniMax,
                paddle, assist, assistConfig, issueImages, new ObjectMapper(), aiStudio, aiStudioConfig,
                mock(studio.bookhtml.service.BaiduPpOcrClient.class), mock(studio.bookhtml.config.PpOcrProperties.class));

        ContentIssue issue = new ContentIssue("i1", "suspected", 0, 1, 0, 1, "图像依据", false, null, null);
        Block block = new Block("b", "text", 0, new double[]{.2, .2, .2, .2}, "horizontal-tb",
                "甲乙", "甲乙", .8, true, false, null, "paddle", List.of("b"), null, null, List.of(issue));
        Page page = new Page(1, 600, 800, "READY", "paddle", List.of(block), List.of(), false, null, List.of(block));
        IssueImageService.Snippet summary = new IssueImageService.Snippet("region", 0,
                block.bbox(), List.of(), new byte[0], block.bbox(), new byte[0]);
        when(issueImages.summarize(page)).thenReturn(Map.of("i1", summary));

        Method payload = ApiController.class.getDeclaredMethod("pagePayload", String.class, Page.class);
        payload.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) payload.invoke(controller, "book", page);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.get("issueImages");
        assertNotNull(meta);
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) meta.get("i1");
        assertEquals(true, entry.get("pending"));
        assertTrue(((String) entry.get("src")).contains("/issues/i1/image"));
        // 阶段2：正文读取不得触发高清定位
        verify(issueImages, never()).locate(any(), any());
    }
}
