package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComprehensibilityApiTest {

    @Test
    void comprehensibilityCheckDelegatesToBookServiceAndReturnsPayload() throws Exception {
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
                mock(BaiduPpOcrClient.class), mock(studio.bookhtml.config.PpOcrProperties.class));

        ContentIssue issue = new ContentIssue("comp-1", "suspected", 0, 4, 0, 4, "语句不通顺", false, null, "推断内容");
        Block block = new Block("b1", "text", 0, new double[]{0, 0, 1, 1}, "horizontal-tb",
                "原文内容", "原文内容", 0.9, false, false, null, "manual", List.of(), null, null, List.of(issue));
        Page updatedPage = new Page(3, 800, 1000, "READY", "manual", List.of(block), List.of(), false, null, List.of(block));

        when(books.checkComprehensibility("book-A", 3)).thenReturn(updatedPage);

        Map<String, Object> result = controller.comprehensibilityCheck("book-A", 3);
        assertNotNull(result);
        assertEquals(3, result.get("pageNumber"));
        assertEquals("READY", result.get("status"));
        verify(books).checkComprehensibility("book-A", 3);
    }
}
