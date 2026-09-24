package studio.bookhtml.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.*;
import studio.bookhtml.decision.DecisionModels;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceAndCompletionTest {
    final ObjectMapper json=new ObjectMapper();
    Block block(String text,List<ContentIssue> issues) {
        return new Block("b","text",0,null,"horizontal-tb",text,text,.99,false,true,null,"manual",List.of("b"),null,null,issues);
    }
    ContentIssue issue(int start,int end,boolean resolved,String replacement,String inferred) {
        return new ContentIssue("i"+start,"suspected",start,end,start,end,"test",resolved,replacement,inferred);
    }
    @Test void confirmedEditsAreAppliedWithoutChangingTheSource() {
        var b=block("旧词甲乙",List.of(issue(0,2,true,"新术语",null)));
        assertEquals("新术语甲乙",EvidenceTextResolver.resolve(b).orElseThrow().value());
        assertEquals("旧词甲乙",b.original());assertEquals("新术语",b.issues().get(0).replacement());
    }
    @Test void unknownGuessIsMaskedInsteadOfBecomingEvidence() {
        var b=block("甲错丙",List.of(issue(1,2,false,null,"猜测")));
        var result=EvidenceTextResolver.resolve(b).orElseThrow();
        assertEquals("甲□丙",result.value());assertTrue(result.hasUnresolved());assertFalse(result.hasCorrections());
    }
    @Test void invalidOrOverlappingSpansAreNotMislabelledAsConfirmed() {
        assertTrue(EvidenceTextResolver.resolve(block("甲乙丙",List.of(issue(0,2,true,"好",null),issue(1,3,true,"新",null)))).isEmpty());
        assertTrue(EvidenceTextResolver.resolve(block("甲乙",List.of(issue(-1,1,true,"新",null)))).isEmpty());
    }
    @Test void supplementaryCharactersCannotBeSplitByACorrection() {
        assertTrue(EvidenceTextResolver.resolve(block("甲𠮷乙",List.of(issue(1,2,true,"吉",null)))).isEmpty());
        assertEquals("甲吉乙",EvidenceTextResolver.resolve(block("甲𠮷乙",List.of(issue(1,3,true,"吉",null)))).orElseThrow().value());
    }
    @Test void nullConfirmationPreservesOriginalWhileEmptyMeansDeletion() {
        assertEquals("甲乙",EvidenceTextResolver.resolve(block("甲乙",List.of(issue(0,1,true,null,null)))).orElseThrow().value());
        assertEquals("乙",EvidenceTextResolver.resolve(block("甲乙",List.of(issue(0,1,true,"",null)))).orElseThrow().value());
    }
    @Test void originalScriptResolutionWinsOverSimplifiedDisplayReplacement() {
        var resolution=new DecisionModels.ReviewResolution("res","op",DecisionModels.Origin.MANUAL,null,null,null,
                "a".repeat(64),0,"b".repeat(64),"學","学","version",true,Instant.now(),1);
        var correction=new ContentIssue("id","suspected",0,1,0,1,"manual",true,"学",null,resolution);
        assertEquals("學",EvidenceTextResolver.resolve(block("错",List.of(correction))).orElseThrow().value());
    }
    @Test void onlyOneNormallyTerminatedCompletionIsAccepted() throws Exception {
        var valid=json.readTree("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"body\"}}]}");
        assertEquals("body",ModelCompletion.singleText(valid));
        for(String reason:List.of("length","content_filter","tool_calls","unknown","")) {
            var bad=valid.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)bad.at("/choices/0")).put("finish_reason",reason);
            assertThrows(OcrException.class,()->ModelCompletion.singleText(bad));
        }
    }
    @Test void missingReasonMultipleChoicesAndRefusalCannotBeCleanResults() throws Exception {
        for(String payload:List.of(
                "{\"choices\":[{\"message\":{\"content\":\"body\"}}]}",
                "{\"choices\":[{},{}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"body\",\"refusal\":\"declined\"}}]}",
                "{\"error\":{},\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"body\"}}]}")) {
            assertThrows(OcrException.class,()->ModelCompletion.singleText(json.readTree(payload)));
        }
    }
    @Test void laterRejectedHandwritingRegionPreservesEarlierText() throws Exception {
        var fixture=new HandwritingTranscribeServiceTest();var calls=new java.util.concurrent.atomic.AtomicInteger();
        String rejected="{\"choices\":[{\"finish_reason\":\"content_filter\",\"message\":{\"content\":\"{}\"}}]}";
        var service=fixture.service(request->fixture.response(200,calls.incrementAndGet()==1?fixture.envelope("先读到的文字","[]"):rejected));
        var result=service.transcribe(fixture.page(),"vertical",()->false);
        assertEquals(2,calls.get());assertEquals("先读到的文字",result.get(0).original());
        assertEquals(5,result.stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());
    }
}
