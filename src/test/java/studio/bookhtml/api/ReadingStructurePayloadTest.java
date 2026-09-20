package studio.bookhtml.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.config.PaddleAiStudioProperties;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.service.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadingStructurePayloadTest {
    @Test
    @SuppressWarnings("unchecked")
    void pageEndpointReturnsRelationshipDiagramAsReadOnlyFigureView() {
        BookService books = mock(BookService.class);
        IssueImageService issueImages = mock(IssueImageService.class);
        Block relation = new Block("relation", "text", 0, new double[]{.1, .2, .4, .3}, "horizontal-tb",
                "甲乙丙丁\n相 \\uparrow\\uparrow\\uparrow\\uparrow\n冲 戊己庚辛",
                "甲乙丙丁\n相 \\uparrow\\uparrow\\uparrow\\uparrow\n冲 戊己庚辛",
                .8, true, false, null, "paddle", List.of("relation"), null, null);
        Page source = new Page(21, 600, 800, "READY", "paddle", List.of(relation), List.of(), false, null);
        when(books.page("book", 21)).thenReturn(source);
        when(issueImages.locate("book", source)).thenReturn(Map.of());
        ApiController controller = new ApiController(books, mock(JobService.class), mock(ExportService.class),
                mock(AppProperties.class), mock(QwenOcrClient.class), mock(MiniMaxVisionClient.class),
                mock(PaddleOcrClient.class), mock(QwenLayoutClient.class), mock(QwenAssistProperties.class),
                issueImages, new ObjectMapper(), mock(PaddleAiStudioClient.class),
                mock(PaddleAiStudioProperties.class), mock(studio.bookhtml.service.BaiduPpOcrClient.class),
                mock(studio.bookhtml.config.PpOcrProperties.class));

        Map<String, Object> payload = controller.page("book", 21);
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");

        assertEquals("figure", blocks.get(0).get("type"));
        assertEquals(relation.original(), blocks.get(0).get("original"));
        assertEquals("text", source.blocks().get(0).type());
    }
}
