package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.Block;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaddleOcrParserTest {
    private final ObjectMapper json=new ObjectMapper();private final PaddleOcrParser parser=new PaddleOcrParser();
    @Test void parsesCoordinatesTypesAndPlainTableWithoutTrustingHtml()throws Exception{
        String value="""
                {"pages":[{"meta":{"page_width":2000,"page_height":1600},"layouts":[
                  {"layout_id":"v1","type":"vertical_text","text":"繁體原文","position":[300,100,50,400],"span_boxes":{}},
                  {"layout_id":"t1","type":"table","text":"<table><tr><td>命</td><td>宮</td></tr></table>","position":[100,200,180,160]},
                  {"layout_id":"n1","type":"number","text":"三八","polygon":[[900,700],[940,700],[940,760],[900,760]]},
                  {"layout_id":"h1","type":"doc_title","text":"卷首","position":[100,20,300,60]},
                  {"layout_id":"x1","type":"seal","text":"印章字","position":[20,20,40,50]}
                ]}]}
                """;
        List<Block>blocks=parser.parse(json.readTree(value),1000,800,"auto");
        assertEquals(5,blocks.size());assertEquals("vertical-rl",blocks.get(0).writingMode());assertArrayEquals(new double[]{.15,.0625,.025,.25},blocks.get(0).bbox(),1e-9);
        assertEquals("table",blocks.get(1).type());assertFalse(blocks.get(1).original().contains("<table>"));assertTrue(blocks.get(1).original().contains("命"));
        assertEquals("page-number",blocks.get(2).type());assertEquals("heading",blocks.get(3).type());assertEquals("text",blocks.get(4).type());assertTrue(blocks.get(4).uncertain());assertTrue(blocks.get(4).suggestion().contains("未知布局类型"));
        BlockValidator.validate(blocks);
    }
    @Test void rejectsMissingOrOutOfBoundsCoordinates(){assertThrows(OcrException.class,()->parser.parse(json.readTree("{\"pages\":[{\"layouts\":[{\"type\":\"text\",\"text\":\"x\"}]}]}"),100,100,"auto"));assertThrows(OcrException.class,()->parser.parse(json.readTree("{\"pages\":[{\"layouts\":[{\"type\":\"text\",\"text\":\"x\",\"position\":[90,0,20,10]}]}]}"),100,100,"auto"));}
    @Test void rebuildsReliableDirectoryPairsWithoutLosingPageNumbers()throws Exception{String value="""
            {"pages":[{"layouts":[{"layout_id":"toc","type":"vertical_text","text":"末尾四五","position":[0,0,500,700],"span_boxes":[
              {"text":"斷行業","location":[]},{"text":"一","location":[[10,10],[20,10],[20,90],[10,90]]},
              {"text":"論進財","location":[]},{"text":"9","location":[[30,10],[40,10],[40,90],[30,90]]},
              {"text":"論升遷","location":[]},{"text":"一七","location":[[50,10],[60,10],[60,90],[50,90]]},
              {"text":"補述","location":[]},{"text":"四五","location":[[70,10],[80,10],[80,90],[70,90]]}
            ]}]}]}
            """;Block block=parser.parse(json.readTree(value),1000,1000,"auto").get(0);assertEquals("斷行業 一\n論進財 9\n論升遷 一七\n補述 四五",block.original());assertEquals("paddle-span",block.source());}
    @Test void ordinaryBodySpansDoNotTriggerDirectoryRebuild()throws Exception{String value="""
            {"pages":[{"layouts":[{"layout_id":"body","type":"vertical_text","text":"正文原句不能改","position":[0,0,500,700],"span_boxes":[
              {"text":"正文","location":[[1,1],[2,1],[2,2],[1,2]]},{"text":"一","location":[[3,1],[4,1],[4,2],[3,2]]},
              {"text":"內容","location":[[5,1],[6,1],[6,2],[5,2]]},{"text":"二","location":[[7,1],[8,1],[8,2],[7,2]]},
              {"text":"結尾","location":[[9,1],[10,1],[10,2],[9,2]]},{"text":"三","location":[[11,1],[12,1],[12,2],[11,2]]}
            ]}]}]}
            """;Block block=parser.parse(json.readTree(value),1000,1000,"auto").get(0);assertEquals("正文原句不能改",block.original());assertEquals("paddle",block.source());}
}
