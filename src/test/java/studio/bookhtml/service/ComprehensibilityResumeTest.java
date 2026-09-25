package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.Path;
import java.io.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComprehensibilityResumeTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    final AutomaticComprehensibilityTest fixture=new AutomaticComprehensibilityTest();
    BookStore store;
    @BeforeEach void setup() throws Exception {
        store=new BookStore(TestConfigs.config(temp,"",""),json);store.createBookDirectory(book);
        store.writeBook(new Book(book,"fixture","fixture.pdf",2,Instant.now(),Instant.now(),0,0));
        store.writePage(book,Page.pending(1,600,800),false);store.writePage(book,Page.pending(2,600,800),false);
    }
    @AfterEach void close(){store.close();assertNull(UsageContext.current());assertNull(QwenExecutionScope.current());}
    ParagraphComprehensibilityService service(ParagraphComprehensibilityService.Transport sender) throws Exception {
        var config=new QwenAssistProperties();config.setApiKey("test-key");
        var result=new ParagraphComprehensibilityService(config,json,new TraditionalConverter(),sender);
        result.setResumeStore(new ComprehensibilityResumeStore(store,json));
        return result;
    }
    HttpResponse<InputStream> ok() throws Exception {return fixture.response(fixture.envelope("{\"findings\":[]}"));}
    HttpResponse<InputStream> unavailable(){var response=fixture.response("{}");when(response.statusCode()).thenReturn(429);return response;}
    List<Block> text(int size){return List.of(fixture.block("甲".repeat(size)));}
    @Test void aFreshServiceContinuesOnlyTheUnfinishedGroups() throws Exception {
        var firstCalls=new AtomicInteger();var input=text(3500);
        var first=service(r->firstCalls.incrementAndGet()==1?ok():unavailable()).check(book,1,input,()->false);
        assertFalse(first.complete());assertEquals(1,first.completed());
        var calls=new AtomicInteger();var second=service(r->{calls.incrementAndGet();return ok();}).check(book,1,input,()->false);
        assertTrue(second.complete());assertEquals(1,calls.get(),"completed first group survives a new service");
    }
    @Test void longPageContinuesBeyondTheFirstFourGroups() throws Exception {
        var calls=new AtomicInteger();var reader=service(r->{calls.incrementAndGet();return ok();});var input=text(12000);
        var first=reader.check(book,1,input,()->false);assertFalse(first.complete());assertEquals(4,calls.get());
        var second=reader.check(book,1,input,()->false);
        assertTrue(second.complete(),"a later attempt must reach groups five and six");assertEquals(6,calls.get());
        assertEquals(input.get(0).original(),second.blocks().get(0).original());
    }
    @Test void cancellationDoesNotDiscardAlreadyCompletedGroupEvidence() throws Exception {
        var calls=new AtomicInteger();var input=text(3500);
        assertThrows(CancelledException.class,()->service(r->{if(calls.incrementAndGet()==1)return ok();throw new CancelledException();}).check(book,1,input,()->false));
        calls.set(0);
        assertTrue(service(r->{calls.incrementAndGet();return ok();}).check(book,1,input,()->false).complete());
        assertEquals(1,calls.get());
    }
    @Test void changedTargetTextNeverReusesDifferentEvidence() throws Exception {
        var calls=new AtomicInteger();var reader=service(r->{calls.incrementAndGet();return ok();});
        reader.check(book,1,text(100),()->false);reader.check(book,1,text(101),()->false);
        assertEquals(2,calls.get());
    }

    @Test void originalAndUnconfirmedCandidateSurviveResumeWithoutBecomingResolved() throws Exception {
        var input=text(3500);var calls=new AtomicInteger();
        String finding="{\"findings\":[{\"blockId\":\"b:0\",\"start\":0,\"end\":1,\"quote\":\"甲\",\"inferredText\":\"乙\"}]}";
        service(r->calls.incrementAndGet()==1?fixture.response(fixture.envelope(finding)):unavailable()).check(book,1,input,()->false);
        var result=service(r->ok()).check(book,1,input,()->false);
        assertTrue(result.complete());assertEquals(1,result.reused());
        assertEquals(input.get(0).original(),result.blocks().get(0).original());
        var issue=result.blocks().get(0).issues().get(0);assertFalse(issue.resolved());assertNull(issue.replacement());assertEquals("乙",issue.inferredText());
    }
    @Test void partialAnnotationsAreNotPersistedAsCompletedGroups() throws Exception {
        var calls=new AtomicInteger();String invalid="{\"findings\":[{\"blockId\":\"foreign\",\"start\":0,\"end\":1,\"quote\":\"甲\"}]}";
        var first=service(r->{calls.incrementAndGet();return fixture.response(fixture.envelope(invalid));}).check(book,1,text(100),()->false);
        assertFalse(first.complete());assertEquals(0,first.completed());
        assertTrue(service(r->{calls.incrementAndGet();return ok();}).check(book,1,text(100),()->false).complete());
        assertEquals(2,calls.get());
    }
    @Test void targetRevisionOnlyDoesNotInvalidateTheSameSemanticInput() throws Exception {
        var calls=new AtomicInteger();var source=mock(BookContextService.class);
        when(source.current()).thenReturn(context(1,2));
        var first=service(r->{calls.incrementAndGet();return ok();});first.setBookContext(source);
        first.check(book,1,text(100),()->false);
        when(source.current()).thenReturn(context(9,2));
        var second=service(r->{calls.incrementAndGet();return ok();});second.setBookContext(source);
        var result=second.check(book,1,text(100),()->false);
        assertTrue(result.complete());assertEquals(1,result.reused());assertEquals(1,calls.get());
    }
    @Test void changedNeighbourEvidenceInvalidatesPriorReview() throws Exception {
        var calls=new AtomicInteger();var source=mock(BookContextService.class);when(source.current()).thenReturn(context(1,2));
        var first=service(r->{calls.incrementAndGet();return ok();});first.setBookContext(source);first.check(book,1,text(100),()->false);
        when(source.current()).thenReturn(context(1,3));
        var second=service(r->{calls.incrementAndGet();return ok();});second.setBookContext(source);second.check(book,1,text(100),()->false);
        assertEquals(2,calls.get());
    }
    String context(int target,int neighbour)throws Exception{
        return json.writeValueAsString(Map.of("version","book-context-v2-confirmed-evidence","bookId",book,"targetPage",1,
                "targetSourceRevision",target,"evidence",List.of(Map.of("page",2,"revision",neighbour,"text","邻页证据"))));
    }
    @Test void pageIdentityIsNeverSharedEvenForIdenticalText() throws Exception {
        var calls=new AtomicInteger();var first=service(r->{calls.incrementAndGet();return ok();});
        first.check(book,1,text(100),()->false);service(r->{calls.incrementAndGet();return ok();}).check(book,2,text(100),()->false);
        assertEquals(2,calls.get());
    }
    @Test void newModelOrEndpointNeverUsesOldResults() throws Exception {
        var calls=new AtomicInteger();service(r->{calls.incrementAndGet();return ok();}).check(book,1,text(100),()->false);
        var changed=new QwenAssistProperties();changed.setApiKey("test-key");changed.setModel("different-fixture-model");
        var second=new ParagraphComprehensibilityService(changed,json,new TraditionalConverter(),r->{calls.incrementAndGet();return ok();});
        second.setResumeStore(new ComprehensibilityResumeStore(store,json));second.check(book,1,text(100),()->false);
        assertEquals(2,calls.get());
        changed.setBaseUrl("https://other.example.invalid/v1");second.check(book,1,text(100),()->false);assertEquals(3,calls.get());
    }
    @Test void cachedEvidenceIsAccountedButConsumesNoAdditionalPhysicalBudget() throws Exception {
        var firstCalls=new AtomicInteger();service(r->firstCalls.incrementAndGet()==1?ok():unavailable()).check(book,1,text(3500),()->false);
        var config=new QwenAssistProperties();config.setApiKey("test-key");config.setMaxPhysicalCallsPerPageAttempt(1);
        var gate=new QwenRequestGate(config);var ledger=mock(UsageLedger.class);
        when(ledger.prepare("qwen",config.getModel())).thenReturn(UUID.randomUUID().toString());
        var sends=new AtomicInteger();var next=new ParagraphComprehensibilityService(config,json,new TraditionalConverter(),r->{sends.incrementAndGet();return ok();});
        next.setRequestGate(gate);next.setUsageLedger(ledger);next.setResumeStore(new ComprehensibilityResumeStore(store,json));
        var result=next.check(book,1,text(3500),()->false);
        assertTrue(result.complete());assertEquals(1,result.reused());assertEquals(1,sends.get());assertEquals(0,gate.inFlight());
        verify(ledger).cacheReused("qwen",config.getModel());verify(ledger).prepare("qwen",config.getModel());
    }
    @Test void failedReuseAccountingCannotFallThroughToPaidRecheck() throws Exception {
        service(r->ok()).check(book,1,text(100),()->false);
        var ledger=mock(UsageLedger.class);doThrow(new IOException("fixture")).when(ledger).cacheReused(anyString(),anyString());
        var next=service(r->{throw new AssertionError("no paid fallback");});next.setUsageLedger(ledger);
        var result=next.check(book,1,text(100),()->false);assertFalse(result.complete());assertEquals(0,result.completed());
    }
    @Test void contentOutsideFenceAndInvalidUnicodeCannotBeRecordedAsSuccess() throws Exception {
        for(String content:List.of("```json\n{\"findings\":[]}\n```\nextra", "{\"findings\":[{\"blockId\":\"b\",\"start\":0,\"end\":1,\"quote\":\"甲\",\"reason\":\"\\uD800\"}]}")){
            var result=service(r->fixture.response(fixture.envelope(content))).check(book,1,text(100),()->false);
            assertFalse(result.complete());assertEquals(0,result.completed());
        }
    }
    @Test void checkedGroupLimitDoesNotClaimCoverageOfArbitrarilyLongText() throws Exception {
        var calls=new AtomicInteger();var reader=service(r->{calls.incrementAndGet();return ok();});
        ParagraphComprehensibilityService.Result result=null;
        for(int i=0;i<8;i++)result=reader.check(book,1,text(65000),()->false);
        assertEquals(32,calls.get());assertEquals(32,result.planned());assertEquals(32,result.completed());
        assertFalse(result.complete());assertTrue(result.coverageLimited());
    }
    @Test void matchingExistingResolvedIssueIsNotRevertedByCachedSuggestion() throws Exception {
        String finding="{\"findings\":[{\"blockId\":\"b\",\"start\":0,\"end\":1,\"quote\":\"甲\",\"inferredText\":\"乙\"}]}";
        var first=service(r->fixture.response(fixture.envelope(finding))).check(book,1,text(100),()->false);var b=first.blocks().get(0);var issue=b.issues().get(0);
        var resolved=new ContentIssue(issue.id(),issue.kind(),issue.start(),issue.end(),issue.simplifiedStart(),issue.simplifiedEnd(),issue.reason(),true,"丙",issue.inferredText(),issue.resolution());
        var corrected=new Block(b.id(),b.type(),b.order(),b.bbox(),b.writingMode(),b.original(),b.simplified(),b.confidence(),true,false,b.headingLevel(),b.source(),b.sourceIds(),b.suggestion(),b.sourceRect(),List.of(resolved));
        var calls=new AtomicInteger();
        var next=service(r->{calls.incrementAndGet();return fixture.response(fixture.envelope(finding));}).check(book,1,List.of(corrected),()->false);
        assertEquals(1,calls.get(),"confirmed decisions change the evidence identity");
        assertEquals("丙",next.blocks().get(0).issues().get(0).replacement());assertTrue(next.blocks().get(0).issues().get(0).resolved());
    }
    @Test void malformedCacheStaysUntouchedAndAuthorizedCheckCanProceed() throws Exception {
        var resume=new ComprehensibilityResumeStore(store,json);Path file=resume.path(book,1);
        java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"damaged");
        var result=service(r->ok()).check(book,1,text(100),()->false);
        assertTrue(result.complete());assertFalse(result.resumeAvailable());assertEquals("damaged",java.nio.file.Files.readString(file));
    }
    @Test void duplicateBlockIdentityStopsBeforeSending() throws Exception {
        var reader=service(r->{throw new AssertionError("ambiguous input must not send");});
        assertThrows(OcrException.class,()->reader.check(book,1,List.of(fixture.block("甲"),fixture.block("乙")),()->false));
    }

    @Test void laterCachedGroupsRemainAvailableWhenAnEarlierGapFailsAgain() throws Exception {
        service(r->ok()).check(book,1,text(6000),()->false);
        var resume=new ComprehensibilityResumeStore(store,json);Path path=resume.path(book,1);var stored=resume.read(path,book,1);
        resume.open(book,1,stored.inputHash(),3).discard(0);
        var sends=new AtomicInteger();var next=service(r->{sends.incrementAndGet();return unavailable();}).check(book,1,text(6000),()->false);
        assertFalse(next.complete());assertEquals(2,next.completed());assertEquals(2,next.reused());assertEquals(1,sends.get());
    }
    @Test void canonicalContextPropertyOrderDoesNotCauseDuplicateRequests() throws Exception {
        var calls=new AtomicInteger();var ctx=mock(BookContextService.class);when(ctx.current()).thenReturn("{\"topic\":\"古籍\",\"evidence\":{\"a\":1,\"b\":2}}");
        var first=service(r->{calls.incrementAndGet();return ok();});first.setBookContext(ctx);first.check(book,1,text(100),()->false);
        when(ctx.current()).thenReturn("{\"evidence\":{\"b\":2,\"a\":1},\"topic\":\"古籍\"}");
        var next=service(r->{calls.incrementAndGet();return ok();});next.setBookContext(ctx);assertTrue(next.check(book,1,text(100),()->false).complete());
        assertEquals(1,calls.get());
    }
    @Test void supplementaryCharactersAtGroupBoundaryAreNeverSplit() throws Exception {
        var requests=new ArrayList<HttpRequest>();var first=service(r->{requests.add(r);return ok();});
        String text="甲".repeat(1999)+"𠮷"+"乙".repeat(100);
        var checked=first.check(book,1,List.of(fixture.block(text)),()->false);
        assertTrue(checked.complete());assertEquals(2,checked.planned());assertEquals(text,checked.blocks().get(0).original());
        assertTrue(service(r->{throw new AssertionError("all valid groups should resume");}).check(book,1,List.of(fixture.block(text)),()->false).complete());
    }
}
