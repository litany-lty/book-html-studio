package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.domain.Block;
import studio.bookhtml.store.BookStore;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real coordinator, real clients and real ledger, with only the HTTP transport replaced. */
class QwenLedgerIntegrationTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    QwenAssistProperties config;
    QwenRequestGate gate;
    UsageLedger ledger;
    BookStore store;
    QwenAssistCoordinator coordinator;
    @BeforeEach void setup() throws Exception {
        config=new QwenAssistProperties();config.setEnabled(true);config.setApiKey("test-key");
        config.setModel("qwen-fixture");config.setBaseUrl("http://127.0.0.1:9/disabled/");config.setTimeoutSeconds(2);
        gate=new QwenRequestGate(config);
        var app=TestConfigs.config(data,"","");store=new BookStore(app,json);store.createBookDirectory(book);
        ledger=new UsageLedger(store,new SettingsService(app,new PaddleAiStudioProperties("",null,null,10,10,1),
                new QwenAssistProperties(),new DecisionProperties(),json),json);
    }
    @AfterEach void close() { if(coordinator!=null)coordinator.close();store.close();assertNull(UsageContext.current());assertNull(QwenExecutionScope.current());assertEquals(0,gate.inFlight()); }
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries() throws Exception {return (List<Map<String,Object>>)ledger.view(book,0,100).get("entries");}
    @SuppressWarnings("unchecked") Map<String,Object> totals() throws Exception {return (Map<String,Object>)ledger.view(book,0,100).get("totals");}
    HttpResponse<InputStream> envelope(Object content) throws Exception {
        byte[] body=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason","stop",
                "message",Map.of("content",json.writeValueAsString(content)))),"usage",Map.of("input_tokens",5,"output_tokens",2)));
        return QwenPhysicalCallTest.response(200,new ByteArrayInputStream(body));
    }
    HttpResponse<InputStream> reviewResponse(String id) throws Exception {return envelope(Map.of("chunkId",id,"findings",List.of()));}
    QwenTaskPlanner.ChunkTask task(String id,int start,int end) {
        return new QwenTaskPlanner.ChunkTask(id,"TEXT_REVIEW",List.of(new QwenTaskPlanner.OwnedRange("s1",start,end)),List.of(),start,"test","test");
    }
    Block text(String content){return new Block("s1","text",0,new double[]{.1,.1,.8,.8},"horizontal-tb",content,content,.99,false,false,null,"paddle",List.of("s1"),null,null);}
    QwenTextReviewClient review(QwenTextReviewClient.Transport transport) {
        var client=new QwenTextReviewClient(config,json,transport);client.setRequestGate(gate);client.setUsageLedger(ledger);return client;
    }
    BufferedImage dottedToc() {
        var image=new BufferedImage(800,1000,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,800,1000);g.setColor(Color.DARK_GRAY);
        for(int i=0;i<8;i++)for(int y=270;y<850;y+=12)g.fillRect(210+i*50,y,2,4);
        g.dispose();return image;
    }

    @Test void tocWholePageStructureAndConcurrentReviewsShareEightPhysicalSends() throws Exception {
        var calls=new AtomicInteger();var executionIds=ConcurrentHashMap.<UUID>newKeySet();
        QwenLayoutClient layout=new QwenLayoutClient(config,json,request->{
            calls.incrementAndGet();executionIds.add(QwenExecutionScope.current().executionId());
            return UsageContext.current().operation().equals("QWEN_STRUCTURE")
                    ? envelope(Map.of("sourceOrder",List.of("s1")))
                    : envelope(Map.of("blocks",List.of(Map.of("sourceId","s1","order",0,"type","text"))));
        });
        layout.setRequestGate(gate);layout.setUsageLedger(ledger);
        QwenTocRecoveryService toc=new QwenTocRecoveryService(config,json,request->{
            calls.incrementAndGet();executionIds.add(QwenExecutionScope.current().executionId());
            return QwenPhysicalCallTest.response(503,new ByteArrayInputStream(new byte[0]));
        });
        toc.setRequestGate(gate);toc.setUsageLedger(ledger);
        var review=review(request->{
            calls.incrementAndGet();executionIds.add(QwenExecutionScope.current().executionId());
            return reviewResponse(UsageContext.current().taskId());
        });
        coordinator=new QwenAssistCoordinator();coordinator.setRequestGate(gate);coordinator.setReviewClient(review);
        coordinator.setStructureClient(layout);coordinator.setConverter(new TraditionalConverter());
        List<QwenTaskPlanner.ChunkTask> chunks=new ArrayList<>();Map<String,byte[]> images=new HashMap<>();
        for(int i=0;i<10;i++){chunks.add(task("review-"+i,i*4,i*4+4));images.put("review-"+i,new byte[]{1});}
        images.put("__overview__",new byte[]{1});String original="甲乙丙丁".repeat(10);var sources=List.of(text(original));
        try(var ctx=UsageContext.open(book,1,"ENRICH_PAGE");var scope=QwenExecutionScope.open(book,1,gate,true)) {
            var image=dottedToc();
            try { var result=toc.recover(image,List.of(),()->false);assertTrue(result.attempted());assertFalse(result.recovered()); }
            finally {image.flush();}
            layout.assist(new byte[]{1},sources,"auto",()->false);
            var result=coordinator.coordinate(book,1,sources,Map.of("s1",original),
                    new QwenTaskPlanner.PlannedReview(chunks,List.of(),0,11),images,null,"auto",true,()->false);
            assertEquals(5,result.succeededChunks());assertEquals(5,result.failedChunks());
            assertEquals(original,result.blocks().get(0).original());
            assertEquals(0,QwenExecutionScope.current().budget().remaining());
        }
        assertEquals(8,calls.get());assertEquals(1,executionIds.size());
        assertEquals(8L,totals().get("requests"));assertEquals(1L,totals().get("failed"));
        assertEquals(7L,totals().get("success"));assertTrue(gate.maxObservedInFlight()<=3);
        assertEquals(1,entries().stream().map(e->e.get("executionId")).distinct().count());
        assertTrue(entries().stream().allMatch(e->e.get("operation").toString().matches("[A-Z][A-Z0-9_]{0,39}")));
        assertTrue(entries().stream().filter(e->e.get("operation").equals("QWEN_TEXT_REVIEW")).allMatch(e->e.get("taskHash")!=null));
    }
    @Test void auditFailureDoesNotCallReviewTransportAndDoesNotPoisonBudget() throws Exception {
        var calls=new AtomicInteger();var client=review(req->{calls.incrementAndGet();return reviewResponse("r1");});
        var path=store.bookDir(book).resolve("usage");Files.createDirectory(path);Files.writeString(path.resolve(UUID.randomUUID()+".json"),"{damaged");
        var budget=gate.newBudget();
        try(var ctx=UsageContext.open(book,1,"QWEN_TEXT_REVIEW","r1")) {
            assertThrows(OcrException.class,()->client.reviewChunk(task("r1",0,4),Map.of("s1","甲乙丙丁"),new byte[]{1},null,true,budget,()->false));
        }
        assertEquals(0,calls.get());assertEquals(8,budget.remaining());
    }
    @Test void retriesAreIndividuallyRecordedAndCacheReuseDoesNotSpendBudget() throws Exception {
        var calls=new AtomicInteger();var bodies=new ArrayList<InputStream>();
        var client=review(req->{
            if(calls.incrementAndGet()==1) {
                InputStream body=spy(new ByteArrayInputStream(new byte[0]));bodies.add(body);
                var response=QwenPhysicalCallTest.response(429,body);
                when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("0")),(a,b)->true));return response;
            }
            return reviewResponse("r1");
        });
        var budget=gate.newBudget();
        try(var ctx=UsageContext.open(book,1,"QWEN_TEXT_REVIEW","r1")) {
            var result=client.reviewChunk(task("r1",0,4),Map.of("s1","甲乙丙丁"),new byte[]{1},null,true,budget,()->false);
            assertNotNull(result);
            client.reviewChunk(task("r1",0,4),Map.of("s1","甲乙丙丁"),new byte[]{1},null,true,budget,()->false);
        }
        assertEquals(2,calls.get());assertEquals(6,budget.remaining());verify(bodies.get(0),atLeastOnce()).close();
        assertEquals(2L,totals().get("requests"));assertEquals(1L,totals().get("cacheHits"));assertEquals(1L,totals().get("failed"));
    }
    @Test void retryAfterLongDelayAndOverflowAreNotRetriedEarly() throws Exception {
        assertEquals(-1,QwenTextReviewClient.parseRetryAfterMillis("9223372036854775807"));
        assertEquals(-1,QwenTextReviewClient.parseRetryAfterMillis("9999999999999999999999999"));
        assertEquals(-1,QwenTextReviewClient.parseRetryAfterMillis("60"));
        assertEquals(0,QwenTextReviewClient.parseRetryAfterMillis("0"));
        assertEquals(-1,QwenTextReviewClient.parseRetryAfterMillis(ZonedDateTime.now(ZoneOffset.UTC).plusMinutes(2).format(DateTimeFormatter.RFC_1123_DATE_TIME)));
        var calls=new AtomicInteger();var client=review(req->{
            calls.incrementAndGet();var r=QwenPhysicalCallTest.response(429,new ByteArrayInputStream(new byte[0]));
            when(r.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("60")),(a,b)->true));return r;
        });
        try(var ctx=UsageContext.open(book,1,"QWEN_TEXT_REVIEW","r1")) {
            assertThrows(OcrException.class,()->client.reviewChunk(task("r1",0,4),Map.of("s1","甲乙丙丁"),new byte[]{1},null,true,gate.newBudget(),()->false));
        }
        assertEquals(1,calls.get());assertEquals(1L,totals().get("requests"));
    }
    @Test void retryAfterCannotOutliveTheSharedExecutionDeadline() throws Exception {
        config.setTimeoutSeconds(1);
        var calls=new AtomicInteger();
        var client=review(req->{
            calls.incrementAndGet();var r=QwenPhysicalCallTest.response(429,new ByteArrayInputStream(new byte[0]));
            when(r.headers()).thenReturn(HttpHeaders.of(Map.of("Retry-After",List.of("2")),(a,b)->true));return r;
        });
        try(var ctx=UsageContext.open(book,1,"QWEN_TEXT_REVIEW","r1")) {
            assertThrows(OcrException.class,()->client.reviewChunk(task("r1",0,4),Map.of("s1","甲乙丙丁"),new byte[]{1},null,true,gate.newBudget(),()->false));
        }
        assertEquals(1,calls.get());assertEquals(1L,totals().get("requests"));
        assertEquals(0,gate.inFlight());
    }
    @Test void fractionalOffsetsAreNotTruncatedIntoAValidFinding() throws Exception {
        var client=review(req->{throw new AssertionError("parse-only test");});
        String invalid=json.writeValueAsString(Map.of("chunkId","r1","findings",List.of(Map.of(
                "sourceId","s1","start",0.5,"end",2,"quote","甲乙","kind","suspected","candidateText","甲乙","reason","test"))));
        var result=client.parseAndValidate(task("r1",0,4),Map.of("s1:0:4","甲乙丙丁"),invalid);
        assertEquals(1,result.dropped());assertTrue(result.findings().isEmpty());
    }
    @Test void historicalGeneratedOperationSuffixIsAdaptedWithoutRewritingHistory() throws Exception {
        String id;
        try(var ctx=UsageContext.open(book,1,"QWEN_STRUCTURE")){id=ledger.start("qwen","qwen-fixture");}
        Path path=store.bookDir(book).resolve("usage").resolve(id+".json");
        var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Files.readAllBytes(path));node.put("operation","QWEN_STRUCTURE:1");
        Files.write(path,json.writeValueAsBytes(node));byte[] old=Files.readAllBytes(path);
        assertEquals("QWEN_STRUCTURE",entries().get(0).get("operation"));assertNotNull(entries().get(0).get("taskHash"));
        assertArrayEquals(old,Files.readAllBytes(path));
        try(var ctx=UsageContext.open(book,1,"QWEN_STRUCTURE")){ledger.succeeded(id);ledger.start("qwen","qwen-fixture");}
        assertEquals(2L,totals().get("requests"));
    }
    @Test void legacyAdapterCannotDiscardNewAttemptIdentityOrCoerceNumbers() throws Exception {
        String id;
        try(var ctx=UsageContext.open(book,1,"QWEN_STRUCTURE")){id=ledger.start("qwen","qwen-fixture");}
        Path path=store.bookDir(book).resolve("usage").resolve(id+".json");
        byte[] original=Files.readAllBytes(path);
        for(String sequence:List.of("1","-1","0","1.5","\"1\"","9223372036854775808")) {
            String modified=new String(original,java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\"operation\":\"QWEN_STRUCTURE\"","\"operation\":\"QWEN_STRUCTURE:1\"")
                    .replace("\"attemptSeq\":null","\"attemptSeq\":"+sequence);
            Files.writeString(path,modified);
            assertThrows(IOException.class,this::entries,"malformed mixed-version identity must remain visible");
            assertEquals(modified,Files.readString(path),"read does not rewrite history");
        }
        Files.write(path,original);
        assertEquals("QWEN_STRUCTURE",entries().get(0).get("operation"));
    }

    @Test void duplicateKeysAndUnrecognizedLegacyOperationsStillFailClosed() throws Exception {
        String id;
        try(var ctx=UsageContext.open(book,1,"QWEN_STRUCTURE")){id=ledger.start("qwen","qwen-fixture");}
        Path path=store.bookDir(book).resolve("usage").resolve(id+".json");
        String original=Files.readString(path);
        Files.writeString(path,original.replace("\"operation\":\"QWEN_STRUCTURE\"", "\"operation\":\"QWEN_STRUCTURE:999\""));
        assertThrows(IOException.class,this::entries);
        Files.writeString(path,original.substring(0,original.length()-1)+",\"status\":\"SUCCEEDED\"}");
        assertThrows(IOException.class,this::entries);
    }
}
