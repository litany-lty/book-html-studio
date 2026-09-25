package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.Block;

import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QwenOcrClientTest {
    @TempDir Path temp;
    @Test void parsesLocationsAndAssignsIdsAfterVerticalReadingSort()throws Exception{QwenOcrClient client=new QwenOcrClient(TestConfigs.config(temp,"key",""),new ObjectMapper(),r->null);String body="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"ocr_result\":{\"words_info\":[{\"text\":\"左\",\"location\":[100,100,120,100,120,300,100,300]},{\"text\":\"右\",\"location\":[700,100,720,100,720,300,700,300]}]}}]}}]}}";List<Block>blocks=client.parse(body,1000,500,"vertical");assertEquals("右",blocks.get(0).original());assertEquals("qwen-line-1",blocks.get(0).id());assertEquals("左",blocks.get(1).original());assertEquals("qwen-line-2",blocks.get(1).id());assertEquals(.7,blocks.get(0).bbox()[0],1e-9);assertArrayEquals(new double[]{700,100,720,100,720,300,700,300},blocks.get(0).sourceRect());}
    @Test void rejectsInvalidOrTruncatedResponse(){QwenOcrClient client=new QwenOcrClient(TestConfigs.config(temp,"key",""),new ObjectMapper(),r->null);assertThrows(OcrException.class,()->client.parse("{}",100,100,"auto"));assertThrows(OcrException.class,()->client.parse("{\"output\":{\"choices\":[{\"finish_reason\":\"length\"}]}}",100,100,"auto"));}
    @Test void missingKeyFailsBeforeNetwork(){QwenOcrClient client=new QwenOcrClient(TestConfigs.config(temp,"",""),new ObjectMapper(),r->{throw new AssertionError();});assertThrows(ApiException.class,()->client.recognize(new byte[1],200,200,"auto",()->false));}
    @Test void doesNotRetry429InsideTheOcrAdapter()throws Exception{@SuppressWarnings("unchecked")HttpResponse<String>response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(429);AtomicInteger calls=new AtomicInteger();QwenOcrClient client=new QwenOcrClient(TestConfigs.config(temp,"key",""),new ObjectMapper(),r->{calls.incrementAndGet();return response;});assertThrows(OcrException.class,()->client.recognize(new byte[1],200,200,"auto",()->false));assertEquals(1,calls.get());}
    @Test void locationPreferredOverRotateRect()throws Exception{assertArrayEquals(new double[]{.1,.2,.2,.2},QwenOcrClient.normalize(new double[]{100,200,300,200,300,400,100,400},1000,1000),1e-9);}
    @Test void collectsEveryWordsInfoGroupAcrossContentItems()throws Exception{QwenOcrClient client=new QwenOcrClient(TestConfigs.config(temp,"key",""),new ObjectMapper(),r->null);String body="{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[{\"ocr_result\":{\"words_info\":[{\"text\":\"右\",\"location\":[700,100,720,100,720,300,700,300]}]}},{\"ocr_result\":{\"words_info\":[{\"text\":\"左\",\"location\":[100,100,120,100,120,300,100,300]}]}}]}}]}}";List<Block>blocks=client.parse(body,1000,500,"vertical");assertEquals(2,blocks.size());assertEquals(List.of("右","左"),blocks.stream().map(Block::original).toList());}
}
