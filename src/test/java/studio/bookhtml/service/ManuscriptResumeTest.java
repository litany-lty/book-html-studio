package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Only synthetic pixels, isolated BookStore and controlled transport; no cloud calls. */
class ManuscriptResumeTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    QwenAssistProperties config;BookStore store;UsageLedger ledger;QwenRequestGate gate;
    @BeforeEach void setup() throws Exception {
        config=new QwenAssistProperties();config.setApiKey("test-key");config.setTimeoutSeconds(60);
        gate=new QwenRequestGate(config);var app=TestConfigs.config(data,"","");
        store=new BookStore(app,json);store.createBookDirectory(book);
        store.writeBook(new Book(book,"Synthetic manuscript","fixture.pdf",2,Instant.now(),Instant.now(),0,0));
        store.writePage(book,Page.pending(1,600,800),false);store.writePage(book,Page.pending(2,600,800),false);
        var settings=new SettingsService(app,new PaddleAiStudioProperties("",null,null,10,10,1),config,new DecisionProperties(),json);
        ledger=new UsageLedger(store,settings,json);
    }
    @AfterEach void close() {store.close();assertNull(UsageContext.current());assertNull(QwenExecutionScope.current());assertEquals(0,gate.inFlight());}
    BufferedImage image() {return ScanRecoveryTest.manuscript(true,true);}
    HandwritingTranscribeService service(HandwritingTranscribeService.Transport transport) throws Exception {
        var service=new HandwritingTranscribeService(config,json,transport);service.setUsageLedger(ledger);service.setRequestGate(gate);
        service.setResumeStore(new ManuscriptResumeStore(store,json));
        return service;
    }
    HttpResponse<InputStream> response(int status,String text) throws Exception {
        String payload=status==200?json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",
                json.writeValueAsString(Map.of("text",text,"findings",List.of()))))))):"{}";
        return QwenPhysicalCallTest.response(status,new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)));
    }
    List<Block> run(HandwritingTranscribeService s,BufferedImage image,BooleanSupplier cancel) throws Exception {
        try(var context=UsageContext.open(book,1,"OCR_PAGE")){return s.transcribe(image,"vertical",cancel);}
    }
    List<Block> partial(BufferedImage image) throws Exception {
        var calls=new AtomicInteger();var result=run(service(req->calls.incrementAndGet()==1?response(200,"前次已辨认文字"):response(429,"")),image,()->false);
        assertEquals(2,calls.get());assertEquals(5,result.stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());return result;
    }
    @Test void explicitRetryReusesValidatedFirstRegionInsteadOfSpendingAllSixAgain() throws Exception {
        var image=image();partial(image);var calls=new AtomicInteger();
        var result=run(service(req->{calls.incrementAndGet();return response(200,"本次新恢复文字");}),image,()->false);
        assertEquals(5,calls.get(),"only five missing regions may invoke the model");
        assertEquals("前次已辨认文字",result.get(0).original());assertEquals(6,result.size());
    }
    @Test void newServiceAndStoreHandleRecoverTheSameIncompleteRegions() throws Exception {
        var image=image();partial(image);store.close();store=new BookStore(TestConfigs.config(data,"",""),json);
        var calls=new AtomicInteger();var result=run(service(req->{calls.incrementAndGet();return response(200,"余下内容");}),image,()->false);
        assertEquals(5,calls.get());assertEquals("前次已辨认文字",result.get(0).original());
    }
    @Test void cancelledLaterRegionDoesNotDiscardDurableEarlierSuccess() throws Exception {
        var image=image();var calls=new AtomicInteger();
        assertThrows(CancelledException.class,()->run(service(req->{if(calls.incrementAndGet()==2)throw new CancelledException();return response(200,"取消前已完成");}),image,()->false));
        calls.set(0);var result=run(service(req->{calls.incrementAndGet();return response(200,"继续缺失部分");}),image,()->false);
        assertEquals(5,calls.get());assertEquals("取消前已完成",result.get(0).original());
    }
    @Test void anotherImageNeverReusesTranscriptionFromOldPixels() throws Exception {
        var image=image();partial(image);image.setRGB(0,0,image.getRGB(0,0)^0x00ffffff);var calls=new AtomicInteger();
        run(service(req->{calls.incrementAndGet();return response(200,"新图内容");}),image,()->false);
        assertEquals(6,calls.get());
    }

    @SuppressWarnings("unchecked") Map<String,Object> totals()throws Exception{return (Map<String,Object>)ledger.view(book,0,100).get("totals");}
    Path resumeFile(){return store.bookDir(book).resolve("manuscript-resume/1.json");}
    Page published(List<Block> sources)throws Exception {
        return store.commitPage(book,new Page(1,600,800,"READY",HandwritingTranscribeService.SOURCE,sources,List.of(),false,null,sources),
                BookStore.revisionOrZero(store.readPage(book,1)),CommitActor.MANUAL,null,CommitOp.MANUAL_SAVE);
    }
    @Test void completeTranscriptionNotYetPublishedSurvivesWithoutAnyNewSend()throws Exception {
        var image=image();var first=run(service(req->response(200,"已完成但尚未发布")),image,()->false);
        var replay=run(service(req->{throw new AssertionError("completed evidence must not be sent twice");}),image,()->false);
        assertEquals(json.readTree(json.writeValueAsBytes(first)),json.readTree(json.writeValueAsBytes(replay)));assertEquals(6L,totals().get("requests"));assertEquals(6L,totals().get("cacheHits"));
    }
    @Test void normallyPublishedCompletePageIsReprocessedFreshWhenUserExplicitlyRetries()throws Exception {
        var image=image();published(run(service(req->response(200,"上一轮完整结果")),image,()->false));var calls=new AtomicInteger();
        var next=run(service(req->{calls.incrementAndGet();return response(200,"用户要求重新识别");}),image,()->false);
        assertEquals(6,calls.get());assertEquals("用户要求重新识别",next.get(0).original());
    }
    @Test void publishedPartialPageResumesRatherThanRecognizingAlreadyRecoveredRegion()throws Exception {
        var image=image();published(partial(image));byte[] before=Files.readAllBytes(store.pagePath(book,1));var calls=new AtomicInteger();
        var next=run(service(req->{calls.incrementAndGet();return response(200,"继续恢复的文字");}),image,()->false);
        assertEquals(5,calls.get());assertEquals("前次已辨认文字",next.get(0).original());
        assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)),"resume evidence never publishes over a page");
    }
    @Test void resumedRegionsConsumeNoNewPhysicalBudgetOrTokenUsage()throws Exception {
        var image=image();partial(image);config.setMaxPhysicalCallsPerPageAttempt(5);var calls=new AtomicInteger();
        try(var context=UsageContext.open(book,1,"OCR_PAGE");var scope=QwenExecutionScope.open(book,1,gate,true)) {
            var result=service(req->{calls.incrementAndGet();return response(200,"缺失区域");}).transcribeDetailed(image,"vertical",()->false);
            assertEquals(1,result.reusedRegions());assertEquals(5,calls.get());assertEquals(0,QwenExecutionScope.current().budget().remaining());
            assertEquals(0,result.blocks().stream().filter(b->"ocr-region-unresolved".equals(b.source())).count());
        }
        assertEquals(7L,totals().get("requests"));assertEquals(1L,totals().get("cacheHits"));
    }
    @Test void changedModelDoesNotReuseOldContract()throws Exception {
        var image=image();partial(image);config.setModel("different-fixture-model");var calls=new AtomicInteger();
        run(service(req->{calls.incrementAndGet();return response(200,"新模型");}),image,()->false);assertEquals(6,calls.get());
    }
    @Test void changedDestinationDoesNotReuseOldContract()throws Exception {
        var image=image();partial(image);config.setBaseUrl("https://different.invalid/v1");var calls=new AtomicInteger();
        run(service(req->{calls.incrementAndGet();return response(200,"新通道");}),image,()->false);assertEquals(6,calls.get());
    }
    @Test void changedReadingOrientationDoesNotReuseOldRegions()throws Exception {
        var image=image();partial(image);var calls=new AtomicInteger();
        try(var context=UsageContext.open(book,1,"OCR_PAGE")) {
            var next=service(req->{calls.incrementAndGet();return response(200,"横排内容");}).transcribe(image,"horizontal",()->false);
            assertEquals(6,calls.get());assertTrue(next.stream().allMatch(b->"horizontal-tb".equals(b.writingMode())));
        }
    }
    @Test void pageIdentityPreventsCrossPageEvidenceReuse()throws Exception {
        var image=image();partial(image);var calls=new AtomicInteger();
        try(var context=UsageContext.open(book,2,"OCR_PAGE")) {
            service(req->{calls.incrementAndGet();return response(200,"另页内容");}).transcribe(image,"vertical",()->false);
        }
        assertEquals(6,calls.get());
    }
    @Test void missingTextRemainsUnresolvedAndIsNotCachedAsCompleted()throws Exception {
        var image=image();var calls=new AtomicInteger();
        run(service(req->response(200,calls.incrementAndGet()==2?"":"其他区域已辨认")),image,()->false);
        calls.set(0);var result=run(service(req->{calls.incrementAndGet();return response(200,"新辨认的缺失区域");}),image,()->false);
        assertEquals(1,calls.get());assertEquals("新辨认的缺失区域",result.get(1).original());assertEquals(5L,totals().get("cacheHits"));
    }
    @Test void corruptedOptionalResumeDoesNotBecomeTextOrGetSilentlyOverwritten()throws Exception {
        var image=image();partial(image);byte[] damaged="{broken".getBytes(StandardCharsets.UTF_8);Files.write(resumeFile(),damaged);var calls=new AtomicInteger();
        var result=run(service(req->{calls.incrementAndGet();return response(200,"重新识别所得");}),image,()->false);
        assertEquals(6,calls.get());assertEquals("重新识别所得",result.get(0).original());assertArrayEquals(damaged,Files.readAllBytes(resumeFile()));
    }
    @Test void evidenceStringsAndCoordinatesAreNotMutatedWhenCallerEditsReturnedBlocks()throws Exception {
        var image=image();var blocks=partial(image);blocks.get(0).bbox()[0]=.777;
        var next=run(service(req->response(200,"余下内容")),image,()->false);
        assertNotEquals(.777,next.get(0).bbox()[0]);assertTrue(next.get(0).uncertain());assertFalse(next.get(0).reviewed());
    }
    @Test void corruptCacheRecordDoesNotContainOrExposeCredentials()throws Exception {
        var image=image();partial(image);String raw=Files.readString(resumeFile());
        assertFalse(raw.contains(config.getApiKey()));assertFalse(raw.contains(config.getBaseUrl()));assertFalse(raw.contains("data:image"));
        assertTrue(raw.contains("integrityHash"));assertTrue(raw.contains("sourceHash"));
    }
    @Test void cancellingBeforeAResumedRunDoesNotSendOrRewriteItsCheckpoint()throws Exception {
        var image=image();partial(image);byte[] before=Files.readAllBytes(resumeFile());
        assertThrows(CancelledException.class,()->run(service(req->{throw new AssertionError("must not send");}),image,()->true));
        assertArrayEquals(before,Files.readAllBytes(resumeFile()));
    }

    @Test void malformedFindingIsNotCachedAsACompletedRegion()throws Exception {
        var image=image();var calls=new AtomicInteger();
        run(service(req->{
            if(calls.incrementAndGet()!=1)return response(200,"已核验的邻区文字");
            String content=json.writeValueAsString(Map.of("text","甲乙丙","findings",List.of(Map.of("index",1.5,"char","乙","verdict","mismatch"))));
            String envelope=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
            return QwenPhysicalCallTest.response(200,new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)));
        }),image,()->false);
        calls.set(0);var next=run(service(req->{calls.incrementAndGet();return response(200,"重新核验这一区域");}),image,()->false);
        assertEquals(1,calls.get());assertEquals("重新核验这一区域",next.get(0).original());
    }
    @Test void reuseAuditFailureDoesNotFallThroughToAnotherPaidSend()throws Exception {
        var image=image();partial(image);var calls=new AtomicInteger();ledger=spy(ledger);
        doThrow(new IOException("synthetic audit unavailable")).when(ledger).cacheReused(anyString(),anyString());
        assertThrows(OcrException.class,()->run(service(req->{calls.incrementAndGet();return response(200,"must not send");}),image,()->false));
        assertEquals(0,calls.get());
    }
    @Test void invalidUnicodeOrContentOutsideFenceCannotBeSettledAsSuccessfulTranscription()throws Exception {
        for(String content:List.of("```json\n{\"text\":\"甲乙\",\"findings\":[]}\n```trailing",
                "{\"text\":\""+(char)92+"ud800\",\"findings\":[]}")) {
            String envelope=json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content",content)))));
            assertThrows(OcrException.class,()->run(service(req->QwenPhysicalCallTest.response(200,new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)))),image(),()->false));
        }
        assertEquals(0L,totals().get("success"));
    }
}
