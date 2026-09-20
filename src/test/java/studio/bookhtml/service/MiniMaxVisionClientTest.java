package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Block;
import java.nio.file.Path;
import java.net.http.HttpResponse;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MiniMaxVisionClientTest {
    @TempDir Path temp;
    private Block line(String id,String text,double x){return new Block(id,"text",0,new double[]{x,.1,.1,.2},"vertical-rl",text,text,null,true,false,null,"qwen",List.of(id),null,null);}
    @Test void rebuildsTextOnlyFromQwenIdsAndRestoresOmittedLines()throws Exception{MiniMaxVisionClient client=new MiniMaxVisionClient(TestConfigs.config(temp,"q","m"),new ObjectMapper(),r->null);List<Block>result=client.merge("{\"blocks\":[{\"id\":\"x\",\"type\":\"heading\",\"order\":0,\"bbox\":[0.7,0.1,0.1,0.2],\"writingMode\":\"vertical-rl\",\"sourceIds\":[\"a\"],\"suggestion\":\"幻觉文字不能覆盖\",\"uncertain\":true}]}",List.of(line("a","真文",.7),line("b","勿丢",.2)));assertEquals("真文",result.get(0).original());assertEquals("勿丢",result.get(1).original());assertTrue(result.get(1).suggestion().contains("自动补回"));}
    @Test void unknownSourceDoesNotDiscardKnownOriginal()throws Exception{MiniMaxVisionClient client=new MiniMaxVisionClient(TestConfigs.config(temp,"q","m"),new ObjectMapper(),r->null);List<Block>result=client.merge("{\"blocks\":[{\"type\":\"text\",\"bbox\":[0,0,0.1,0.1],\"writingMode\":\"horizontal-tb\",\"sourceIds\":[\"fake\"]}]}",List.of(line("a","原文",.1)));assertEquals(1,result.size());assertEquals("原文",result.get(0).original());assertTrue(result.get(0).suggestion().contains("自动补回"));}
    @Test void realMalformedCoordinatePatternUsesOcrUnionAndKeepsValidFigureObject()throws Exception{MiniMaxVisionClient client=new MiniMaxVisionClient(TestConfigs.config(temp,"q","m"),new ObjectMapper(),r->null);Block a=line("R-qwen-line-1","财运也罢",.7),b=line("R-qwen-line-2","另一行",.2);String fixture="{\"blocks\":["+
            "{\"id\":\"valid-figure\",\"type\":\"figure\",\"order\":0,\"bbox\":{\"x\":0.4,\"y\":0.4,\"width\":0.2,\"height\":0.2},\"writingMode\":\"vertical-rl\",\"sourceIds\":[]},"+
            "{\"id\":\"bad-text-box\",\"type\":\"text\",\"order\":1,\"bbox\":[0.5984,0.2583,0.65,0.4],\"writingMode\":\"vertical-rl\",\"sourceIds\":[\"R-qwen-line-1\"]},"+
            "{\"id\":\"bad-table-box\",\"type\":\"table\",\"order\":2,\"bbox\":[0.2,0.2,0.9,0.9],\"writingMode\":\"vertical-rl\",\"sourceIds\":[\"R-qwen-line-2\"]},"+
            "{\"id\":\"bad-empty-figure\",\"type\":\"figure\",\"order\":3,\"bbox\":{\"x\":0.9,\"y\":0.9,\"width\":0.4,\"height\":0.4},\"writingMode\":\"vertical-rl\",\"sourceIds\":[]}] }";List<Block>result=client.merge(fixture,List.of(a,b));assertEquals(3,result.size());assertEquals("figure",result.get(0).type());assertArrayEquals(a.bbox(),result.get(1).bbox(),1e-9);assertEquals("财运也罢",result.get(1).original());assertTrue(result.get(2).suggestion().contains("图框无效"));assertDoesNotThrow(()->BlockValidator.validate(result));}
    @Test void rejectsTruncatedSuccessfulHttpResponse()throws Exception{@SuppressWarnings("unchecked")HttpResponse<String>response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}");MiniMaxVisionClient client=new MiniMaxVisionClient(TestConfigs.config(temp,"q","m"),new ObjectMapper(),r->response);assertThrows(OcrException.class,()->client.assist(new byte[1],List.of(line("a","原文",.1)),"vertical",()->false));}
}
