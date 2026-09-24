package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.config.QwenAssistProperties;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HandwritingTranscribeServiceTest {
    final ObjectMapper json=new ObjectMapper();
    QwenAssistProperties config(){var c=new QwenAssistProperties();c.setApiKey("test-key");return c;}
    HandwritingTranscribeService service(HandwritingTranscribeService.Transport transport){return new HandwritingTranscribeService(config(),json,transport);}
    @SuppressWarnings("unchecked") HttpResponse<InputStream> response(int status,String text){var r=(HttpResponse<InputStream>)mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);when(r.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));when(r.body()).thenReturn(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));return r;}
    String envelope(String text,String findings)throws Exception{return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",json.writeValueAsString(Map.of("text",text,"findings",json.readTree(findings))))))));}
    BufferedImage page(){return ScanRecoveryTest.manuscript(true,true);}
    @Test void stripsAreOrderedAndCoverThePageExactlyWithoutOverlap(){
        for(boolean vertical:List.of(true,false)) {
            var boxes=HandwritingTranscribeService.stripBoxes(page(),vertical);double total=0;
            for(int i=0;i<boxes.size();i++){var b=boxes.get(i);assertTrue(b[0]>=0&&b[1]>=0&&b[0]+b[2]<=1.00001&&b[1]+b[3]<=1.00001);
                total+=vertical?b[2]:b[3];if(i>0){var prev=boxes.get(i-1);assertEquals(vertical?prev[0]:prev[1]+prev[3],vertical?b[0]+b[2]:b[1],.00001);}}
            assertEquals(1,total,.00001);assertEquals(6,boxes.size());
        }
    }
    @Test void transcriptionIsUnconfirmedAndOnlyOneVisionCallPerRegion()throws Exception {
        var calls=new AtomicInteger();var service=service(r->{calls.incrementAndGet();return response(200,envelope("可辨认的转录文字","[]"));});
        var blocks=service.transcribe(page(),"auto",()->false);assertEquals(6,blocks.size());assertEquals(6,calls.get());
        for(var b:blocks){assertTrue(b.uncertain());assertFalse(b.reviewed());assertEquals(HandwritingTranscribeService.SOURCE,b.source());assertEquals("vertical-rl",b.writingMode());assertTrue(b.suggestion().contains("核对"));}
    }
    @Test void unresolvedCharactersDoNotTriggerExtraPureTextCompletionRequests()throws Exception {
        var calls=new AtomicInteger();var service=service(r->{calls.incrementAndGet();return response(200,envelope("結果□□很高興","[]"));});
        var blocks=service.transcribe(page(),"vertical",()->false);
        assertEquals(6,calls.get());assertEquals("結果□□很高興",blocks.get(0).original());
        assertEquals(2,blocks.get(0).issues().size());assertTrue(blocks.get(0).issues().stream().allMatch(i->!i.resolved()&&i.replacement()==null));
    }
    @Test void imageFindingIsOnlyAnUnconfirmedCandidate()throws Exception {
        var blocks=service(r->response(200,envelope("結果很好","[{\"index\":1,\"char\":\"果\",\"verdict\":\"mismatch\",\"likely\":\"實\",\"reason\":\"笔画不符\"}]"))).transcribe(page(),"vertical",()->false);
        var issue=blocks.get(0).issues().get(0);assertEquals("結果很好",blocks.get(0).original());assertEquals("實",issue.inferredText());assertNull(issue.replacement());assertFalse(issue.resolved());
    }
    @Test void wrongCharacterAndFractionalIndexesAreDiscarded()throws Exception {
        String findings="[{\"index\":1.9,\"char\":\"乙\",\"verdict\":\"mismatch\"},{\"index\":1,\"char\":\"丁\",\"verdict\":\"mismatch\"}]";
        assertTrue(HandwritingTranscribeService.parsedIssues("b","甲乙丙",json.readTree(findings)).isEmpty());
    }
    @Test void supplementaryCharacterHasOneCodePointAndCorrectUtf16Range()throws Exception {
        var findings=json.readTree("[{\"index\":1,\"char\":\"𠮷\",\"verdict\":\"mismatch\",\"likely\":\"吉\"}]");
        var issue=HandwritingTranscribeService.parsedIssues("b","甲𠮷乙",findings).get(0);assertEquals(1,issue.start());assertEquals(3,issue.end());
    }
    @Test void laterFailurePreservesPriorRegionsAndStopsAdditionalSends()throws Exception {
        var calls=new AtomicInteger();var blocks=service(r->calls.incrementAndGet()==1?response(200,envelope("先恢复的片段","[]")):response(429,"{}"))
                .transcribe(page(),"auto",()->false);
        assertEquals(2,calls.get());assertEquals("先恢复的片段",blocks.get(0).original());assertEquals(5,blocks.stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());
    }
    @Test void nothingRecoveredIsNotAConfirmedBlank() {
        assertThrows(OcrNoTextException.class,()->service(r->response(200,envelope("","[]"))).transcribe(page(),"vertical",()->false));
    }
    @Test void cancellationStopsBeforeSending() {
        assertThrows(CancelledException.class,()->service(r->{throw new AssertionError("must not send");}).transcribe(page(),"vertical",()->true));
    }
    @Test void enhancementIsBoundedAndDoesNotModifySource() {
        var raw=page();int[] before=raw.getRGB(0,0,raw.getWidth(),raw.getHeight(),null,0,raw.getWidth());
        var enhanced=HandwritingTranscribeService.enhance(raw);assertTrue(Math.max(enhanced.getWidth(),enhanced.getHeight())<=2200);
        assertArrayEquals(before,raw.getRGB(0,0,raw.getWidth(),raw.getHeight(),null,0,raw.getWidth()));
        for(int y=0;y<enhanced.getHeight();y+=100)for(int x=0;x<enhanced.getWidth();x+=100){int rgb=enhanced.getRGB(x,y);assertEquals((rgb>>16)&255,rgb&255);}
    }
    @Test void veryTallInputIsDownscaledRatherThanKeptAboveTheBound() {
        var tall=new BufferedImage(20,4000,BufferedImage.TYPE_INT_RGB);var scaled=HandwritingTranscribeService.enhance(tall);
        assertEquals(2200,scaled.getHeight());scaled.flush();tall.flush();
    }
    @Test void productionWithoutAuditOrResourceDependenciesFailsBeforeTransport() {
        var real=new HandwritingTranscribeService(config(),json);
        OcrException failure=assertThrows(OcrException.class,()->real.transcribe(page(),"auto",()->false));assertTrue(failure.getMessage().contains("未装配"));
    }
    @Test void auditWriteFailureCannotFallThroughToTransport()throws Exception {
        var sent=new AtomicInteger();var service=service(r->{sent.incrementAndGet();return response(200,envelope("不应发生","[]"));});
        var ledger=mock(UsageLedger.class);when(ledger.prepare(anyString(),anyString())).thenThrow(new IOException("fixture audit failure"));service.setUsageLedger(ledger);
        assertThrows(OcrException.class,()->service.transcribe(page(),"auto",()->false));assertEquals(0,sent.get());
    }
    @Test void errorResponseBodyIsClosed()throws Exception {
        var closed=new AtomicBoolean();HttpResponse<InputStream> r=response(500,"{}");
        when(r.body()).thenReturn(new ByteArrayInputStream(new byte[0]){@Override public void close(){closed.set(true);}});
        assertThrows(OcrException.class,()->service(req->r).transcribe(page(),"auto",()->false));assertTrue(closed.get());
    }
    @Test void responseTruncationAndMalformedJsonAreNotAcceptedAsCleanText()throws Exception {
        for(String payload:List.of("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}","{\"choices\":[{\"message\":{\"content\":\"not JSON\"}}]}"))
            assertThrows(OcrException.class,()->service(r->response(200,payload)).transcribe(page(),"auto",()->false));
    }
    @Test void cleanRetainsContentWithoutCodeFences(){assertEquals("甲乙\n丙丁",HandwritingTranscribeService.clean("```text\n甲乙\n\n丙丁\n```"));assertEquals("",HandwritingTranscribeService.clean(null));}
    @Test void configuredRequiresEnabledCredentials(){var c=config();c.setEnabled(false);var service=new HandwritingTranscribeService(c,json,r->response(200,"{}"));assertFalse(service.configured());}
}
