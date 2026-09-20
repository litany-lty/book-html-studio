package studio.bookhtml.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BaiduPpOcrParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final BaiduPpOcrParser parser = new BaiduPpOcrParser();

    @Test void parsesLinesWithBoxesAndConfidence() throws Exception {
        JsonNode normalized = normalized(
                "[\"甲乙丙丁\",\"戊己庚辛\"]",
                "[[10,20,100,60],[10,70,100,110]]",
                "[0.9,0.5]");
        List<Block> blocks = parser.parse(normalized, 1000, 800, "auto");
        assertEquals(2, blocks.size());
        assertEquals("ppocr-line-1", blocks.get(0).id());
        assertEquals("text", blocks.get(0).type());
        assertEquals("甲乙丙丁", blocks.get(0).original());
        assertEquals(0.9, blocks.get(0).confidence());
        assertEquals(0.5, blocks.get(1).confidence());
        assertEquals("ppocr", blocks.get(0).source());
        assertEquals(List.of("ppocr-line-1"), blocks.get(0).sourceIds());
        assertArrayEquals(new double[]{0.01, 0.025, 0.09, 0.05}, blocks.get(0).bbox(), 1e-9);
        assertArrayEquals(new double[]{10, 20, 90, 40}, blocks.get(0).sourceRect(), 1e-9);
        assertEquals("horizontal-tb", blocks.get(0).writingMode());
        assertTrue(blocks.get(0).uncertain());
    }

    @Test void tallBoxesInferVerticalAndRequestedLayoutWins() throws Exception {
        JsonNode normalized = normalized("[\"豎排長列\"]", "[[10,20,50,400]]", "[0.8]");
        List<Block> auto = parser.parse(normalized, 1000, 800, "auto");
        assertEquals("vertical-rl", auto.get(0).writingMode());
        List<Block> horizontal = parser.parse(normalized, 1000, 800, "horizontal");
        assertEquals("horizontal-tb", horizontal.get(0).writingMode());
    }

    @Test void skipsBlankLinesButRejectsEmptyResult() throws Exception {
        JsonNode allBlank = normalized("[\"   \"]", "[[10,20,100,60]]", "[0.9]");
        assertThrows(OcrException.class, () -> parser.parse(allBlank, 1000, 800, "auto"));
    }

    @Test void rejectsMismatchedCoordinates() throws Exception {
        BaiduPpOcrClient client = client();
        JsonNode bad = json.readTree("{\"page_result\":[{\"lines\":[\"甲\",\"乙\"],\"rec_boxes\":[[10,20,100,60]]}]}");
        assertThrows(OcrException.class, () -> client.normalize(bad, 1000, 800));
        JsonNode invalidBox = json.readTree("{\"page_result\":[{\"lines\":[\"甲\"],\"rec_boxes\":[[100,60,10,20]]}]}");
        assertThrows(OcrException.class, () -> client.normalize(invalidBox, 1000, 800));
        JsonNode twoPages = json.readTree("{\"page_result\":[{\"lines\":[],\"rec_boxes\":[]},{\"lines\":[],\"rec_boxes\":[]}]}");
        assertThrows(OcrException.class, () -> client.normalize(twoPages, 1000, 800));
    }

    private JsonNode normalized(String lines, String boxes, String probabilities) throws Exception {
        BaiduPpOcrClient client = client();
        JsonNode raw = json.readTree("{\"page_result\":[{\"lines\":" + lines + ",\"rec_boxes\":" + boxes + ",\"probability\":" + probabilities + "}]}");
        return client.normalize(raw, 1000, 800);
    }

    private BaiduPpOcrClient client() {
        return new BaiduPpOcrClient(
                new studio.bookhtml.config.AppProperties(java.nio.file.Path.of("target/test-unused"), 300, 5000, 2400,
                        "tesseract", "", "qwen3.5-ocr", "https://dashscope.example/v1", 5,
                        "", "MiniMax-M3", "https://minimax.example/v1", 5, false,
                        "api-key", "secret-key", "PaddleOCR-VL-1.6",
                        "https://aip.baidubce.com/rest/2.0/brain/online/v2/paddle-vl-parser/task", 5, 5, 1),
                new studio.bookhtml.config.PpOcrProperties("https://aip.baidubce.com/rest/2.0/ocr/v1/pp_ocrv5", 5),
                json, parser, (request, cancelled, maxBytes) -> { throw new AssertionError("unexpected network"); });
    }
}
