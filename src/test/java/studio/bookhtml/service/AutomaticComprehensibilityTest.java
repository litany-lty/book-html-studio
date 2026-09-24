package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticComprehensibilityTest {
    final ObjectMapper json=new ObjectMapper();
    Block block(String text){return new Block("b","text",0,new double[]{.1,.1,.8,.1},"horizontal-tb",text,text,.95,false,false,null,"ocr",List.of("b"),null,null,List.of());}
    @SuppressWarnings("unchecked") HttpResponse<InputStream> response(String payload){var r=(HttpResponse<InputStream>)mock(HttpResponse.class);when(r.statusCode()).thenReturn(200);when(r.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));when(r.body()).thenReturn(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));return r;}
    String envelope(String data)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",data)))));}
    ParagraphComprehensibilityService service(ParagraphComprehensibilityService.Transport transport){var c=new QwenAssistProperties();c.setApiKey("test-key");return new ParagraphComprehensibilityService(c,json,new TraditionalConverter(),transport);}
    @Test void defaultLocalSelfCheckNeedsNoCredentialsAndNeverRewritesText(){
        var s=new ParagraphComprehensibilityService(new QwenAssistProperties(),json,new TraditionalConverter(),r->{throw new AssertionError("no network");});
        var original=block("甲□乙�丙");var checked=s.checkLocal(List.of(original));
        assertEquals(original.original(),checked.get(0).original());assertEquals(2,checked.get(0).issues().size());
        assertTrue(checked.get(0).issues().stream().noneMatch(ContentIssue::resolved));assertTrue(checked.get(0).issues().stream().allMatch(i->i.replacement()==null));
        assertEquals(checked,s.checkLocal(checked));
    }
    @Test void reviewedBlockAndConfirmedIssueAreNeverAltered()throws Exception {
        Block b=block("甲□乙");var reviewed=new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),b.simplified(),b.confidence(),false,true,null,b.source(),b.sourceIds(),null,null,List.of());
        var s=service(r->{throw new AssertionError("reviewed text must not be sent");});
        assertEquals(List.of(reviewed),s.check("book",1,List.of(reviewed),()->false).blocks());
    }
    @Test void chunksAreBoundedAndIncompleteCoverageIsExplicit()throws Exception {
        var calls=new AtomicInteger();var s=service(r->{calls.incrementAndGet();return response(envelope("{\"findings\":[]}"));});
        var result=s.check("book",1,List.of(block("甲".repeat(12000))),()->false);
        assertEquals(4,calls.get());assertFalse(result.complete());assertEquals(4,result.completed());assertEquals(12000,result.blocks().get(0).original().length());
    }
    @Test void sameEvidenceReusesCacheWithoutAnotherPhysicalCall()throws Exception {
        var calls=new AtomicInteger();var s=service(r->{calls.incrementAndGet();return response(envelope("{\"findings\":[]}"));});
        var input=List.of(block("原有文本"));assertTrue(s.check("book",1,input,()->false).complete());assertTrue(s.check("book",1,input,()->false).complete());assertEquals(1,calls.get());
        s.check("other-book",1,input,()->false);assertEquals(2,calls.get());
    }
    @Test void malformedResponsesCannotBePresentedAsNoProblems()throws Exception {
        for(String body:List.of("not-json","{}","{\"findings\":{},\"findings\":[]}","{\"findings\":[]} trailing")) {
            var s=service(r->response(envelope(body)));assertFalse(s.check("book",1,List.of(block("内容文本")),()->false).complete());
        }
    }
    @Test void fractionalOffsetsAndForeignBlocksNeverAttachFindings()throws Exception {
        String fields="{\"findings\":[{\"blockId\":\"b\",\"start\":0.5,\"end\":1,\"quote\":\"甲\"},{\"blockId\":\"foreign\",\"start\":0,\"end\":1,\"quote\":\"甲\"}]}";
        var result=service(r->response(envelope(fields))).check("book",1,List.of(block("甲乙")),()->false);
        assertTrue(result.blocks().get(0).issues().isEmpty());
    }
    @Test void ambiguousQuoteIsNotRelocatedToAnArbitraryOccurrence()throws Exception {
        String fields="{\"findings\":[{\"blockId\":\"b\",\"start\":99,\"end\":100,\"quote\":\"甲\",\"inferredText\":\"乙\"}]}";
        var result=service(r->response(envelope(fields))).check("book",1,List.of(block("甲乙甲")),()->false);
        assertTrue(result.blocks().get(0).issues().isEmpty());
    }
    @Test void existingLocalPlaceholderCanReceiveCandidateButRemainsUnresolved()throws Exception {
        String fields="{\"findings\":[{\"blockId\":\"b\",\"start\":1,\"end\":2,\"quote\":\"□\",\"inferredText\":\"乙\"}]}";
        var result=service(r->response(envelope(fields))).check("book",1,List.of(block("甲□丙")),()->false);
        assertEquals("甲□丙",result.blocks().get(0).original());var issue=result.blocks().get(0).issues().get(0);
        assertEquals("乙",issue.inferredText());assertNull(issue.replacement());assertFalse(issue.resolved());assertEquals(1,result.blocks().get(0).issues().size());
    }
    @Test void statusFailureClosesBodyAndKeepsUnchangedBaseline()throws Exception {
        var closed=new AtomicBoolean();var response=response("ignored");when(response.statusCode()).thenReturn(429);
        when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]){@Override public void close(){closed.set(true);}});
        var original=block("原文保留");var result=service(r->response).check("book",1,List.of(original),()->false);
        assertFalse(result.complete());assertTrue(closed.get());assertEquals(original.original(),result.blocks().get(0).original());
    }
    @Test void cancellationDoesNotSpendAnAdditionalCall() {
        assertThrows(CancelledException.class,()->service(r->{throw new AssertionError("no send");}).check("book",1,List.of(block("原文")),()->true));
    }
}
