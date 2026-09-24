package studio.bookhtml.service;

import org.junit.jupiter.api.Test;
import studio.bookhtml.domain.ContentIssue;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Invalid model annotations must never be silently turned into a negative check. No external requests. */
class SelfcheckResultIntegrityTest {
    private final AutomaticComprehensibilityTest fixture=new AutomaticComprehensibilityTest();
    private String valid() { return "{\"blockId\":\"b\",\"start\":0,\"end\":1,\"quote\":\"甲\",\"reason\":\"需对照原稿\",\"inferredText\":\"乙\"}"; }
    private ParagraphComprehensibilityService.Result review(String data,String text) throws Exception {
        return fixture.service(r->fixture.response(fixture.envelope(data)))
                .check("book",1,List.of(fixture.block(text)),()->false);
    }
    @Test void allRejectedAnnotationsMeanIncompleteNotNoProblems() throws Exception {
        for(String finding:List.of("null","{}",
                valid().replace("\"b\"","\"foreign\""),valid().replace("\"start\":0","\"start\":0.5"),
                valid().replace("\"甲\"","\"不存在\""),valid().replace("\"reason\":\"需对照原稿\"","\"reason\":{}"),
                valid().replace("\"inferredText\":\"乙\"","\"inferredText\":[]"))) {
            var result=review("{\"findings\":["+finding+"]}","甲丙");
            assertFalse(result.complete(),finding);assertEquals(0,result.completed());
            assertTrue(result.blocks().get(0).issues().isEmpty());
        }
    }
    @Test void mixedResponseKeepsValidFindingsWithoutClaimingCompleteCoverage() throws Exception {
        var result=review("{\"findings\":["+valid()+",{}]}","甲丙");
        assertFalse(result.complete());assertEquals(1,result.planned());assertEquals(0,result.completed());
        assertEquals("甲丙",result.blocks().get(0).original());
        assertEquals(1,result.blocks().get(0).issues().size());
        ContentIssue issue=result.blocks().get(0).issues().get(0);
        assertEquals("乙",issue.inferredText());assertNull(issue.replacement());assertFalse(issue.resolved());
    }
    @Test void incompleteResponsesAreNotCachedAsSuccessfulReviews() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var service=fixture.service(r->fixture.response(fixture.envelope(calls.incrementAndGet()==1?"{\"findings\":[{}]}":"{\"findings\":[]}")));
        var input=List.of(fixture.block("甲丙"));
        assertFalse(service.check("book",1,input,()->false).complete());
        assertTrue(service.check("book",1,input,()->false).complete());
        assertTrue(service.check("book",1,input,()->false).complete());
        assertEquals(2,calls.get(),"a later explicit attempt can retry; only its valid response is cached");
    }
    @Test void nonStopTerminationIsNotAcceptedAsAnEmptyReview() throws Exception {
        for(String reason:List.of("length","content_filter","tool_calls","unknown")) {
            String envelope=fixture.json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason",reason,"message",Map.of("content","{\"findings\":[]}")))));
            var service=fixture.service(r->fixture.response(envelope));
            assertFalse(service.check("book",1,List.of(fixture.block("甲丙")),()->false).complete(),reason);
        }
    }
    @Test void partialSecondGroupKeepsFirstGroupEvidenceAndStopsExtraCalls() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var service=fixture.service(r->fixture.response(fixture.envelope(calls.incrementAndGet()==1?"{\"findings\":["+valid()+"]}":"{\"findings\":[{}]}")));
        var result=service.check("book",1,List.of(fixture.block("甲"+"丙".repeat(4500))),()->false);
        assertFalse(result.complete());assertEquals(3,result.planned());assertEquals(1,result.completed());
        assertEquals(2,calls.get(),"do not retry the page or send more groups after incomplete validation");
        assertEquals(1,result.blocks().get(0).issues().size());
        assertEquals(4501,result.blocks().get(0).original().length());
    }
    @Test void ambiguousQuoteIsExplicitlyIncomplete() throws Exception {
        var result=review("{\"findings\":["+valid().replace("\"start\":0","\"start\":90").replace("\"end\":1","\"end\":91")+"]}","甲丙甲");
        assertFalse(result.complete());assertTrue(result.blocks().get(0).issues().isEmpty());
    }
    @Test void aValidEmptyReviewStillCompletesAndDoesNotAddNoise() throws Exception {
        var result=review("{\"findings\":[]}","古文专名不可随意改写");
        assertTrue(result.complete());assertEquals(1,result.completed());assertTrue(result.blocks().get(0).issues().isEmpty());
    }
}
