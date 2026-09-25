package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.domain.Block;
import java.nio.file.Path;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LegacyModelBoundaryTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    BookStore store;UsageLedger usage;AppProperties config;
    @BeforeEach void setup() throws Exception {
        config=TestConfigs.config(temp,"fixture-qwen","fixture-minimax");store=new BookStore(config,json);store.createBookDirectory(book);
        var settings=new SettingsService(config,new PaddleAiStudioProperties("",null,null,5,5,1),new QwenAssistProperties(),new DecisionProperties(),json);
        usage=spy(new UsageLedger(store,settings,json));
    }
    @AfterEach void close(){Thread.interrupted();store.close();assertNull(UsageContext.current());}
    @SuppressWarnings("unchecked") static HttpResponse<String> response(int status,String text){
        HttpResponse<String> response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(text);return response;
    }
    static Block line(){return new Block("line","text",0,new double[]{.1,.1,.2,.2},"horizontal-tb","原文","原文",.99,false,false,null,"qwen",List.of("line"),null,null);}
    String ocr(String finish) throws Exception {return json.writeValueAsString(Map.of("output",Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content",List.of(Map.of("ocr_result",Map.of("words_info",List.of(Map.of("text","文字","location",List.of(10,10,30,10,30,30,10,30))))))))))));}
    String mini(String finish) throws Exception {return json.writeValueAsString(Map.of("choices",List.of(Map.of("finish_reason",finish,"message",Map.of("content","{\"blocks\":[{\"type\":\"text\",\"order\":0,\"sourceIds\":[\"line\"]}]}")))));}
    @Test void qwenMustNotSendWhenSharedPoolCannotAdmit() throws Exception {
        var resources=new ProviderResourceRegistry();resources.configure("qwen",1,0,0);var sent=new AtomicInteger();
        var client=new QwenOcrClient(config,json,r->{sent.incrementAndGet();return response(200,ocr("stop"));});client.setResourceRegistry(resources);
        try(var held=resources.acquire("qwen",true,Duration.ZERO,()->false)) {
            assertThrows(OcrException.class,()->client.recognize(new byte[]{1},100,100,"horizontal",()->false));
        }
        assertEquals(0,sent.get());
    }
    @Test void miniMaxMustNotSendWithoutAResourcePermit() throws Exception {
        var resources=new ProviderResourceRegistry();resources.configure("minimax",1,0,0);var sent=new AtomicInteger();
        var client=new MiniMaxVisionClient(config,json,r->{sent.incrementAndGet();return response(200,mini("stop"));});client.setResourceRegistry(resources);
        try(var held=resources.acquire("minimax",true,Duration.ZERO,()->false)) {
            assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));
        }
        assertEquals(0,sent.get());
    }
    @Test void filteredQwenOutputCannotBecomeTranscribedText() throws Exception {
        var client=new QwenOcrClient(config,json,r->null);
        assertThrows(OcrException.class,()->client.parse(ocr("content_filter"),100,100,"horizontal"));
    }
    @Test void multipleQwenChoicesAreNotSilentlyReducedToOne() throws Exception {
        var value=json.readTree(ocr("stop"));var choices=(com.fasterxml.jackson.databind.node.ArrayNode)value.at("/output/choices");choices.add(choices.get(0).deepCopy());
        var client=new QwenOcrClient(config,json,r->null);assertThrows(OcrException.class,()->client.parse(json.writeValueAsString(value),100,100,"horizontal"));
    }
    @Test void malformedCoordinateTokensAreNotSilentlyDiscarded() throws Exception {
        var value=json.readTree(ocr("stop"));((com.fasterxml.jackson.databind.node.ArrayNode)value.at("/output/choices/0/message/content/0/ocr_result/words_info/0/location")).insert(0,"ignored-noise");
        var client=new QwenOcrClient(config,json,r->null);assertThrows(OcrException.class,()->client.parse(json.writeValueAsString(value),100,100,"horizontal"));
    }
    @Test void filteredMiniMaxOutputCannotBeSuccessfulStructure() throws Exception {
        var client=new MiniMaxVisionClient(config,json,r->response(200,mini("content_filter")));
        assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));
    }
    @Test void miniMaxBusinessFailureMustNotBePersistedAsSuccess() throws Exception {
        var client=new MiniMaxVisionClient(config,json,r->response(200,"{\"error\":{\"message\":\"synthetic failure\"}}"));client.setUsageLedger(usage);
        try(var ctx=UsageContext.open(book,1,"MINIMAX_ASSIST")) {
            assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));
        }
        var rows=(List<Map<String,Object>>)usage.view(book,0,50).get("entries");assertEquals(1,rows.size(),"an actual MiniMax call must not disappear from its ledger");assertEquals("FAILED",rows.get(0).get("status"));
    }
    @Test void interruptedQwenTransportMustRemainCancellation() {
        var client=new QwenOcrClient(config,json,r->{throw new InterruptedException();});
        assertThrows(CancelledException.class,()->client.recognize(new byte[]{1},100,100,"horizontal",()->false));
        assertTrue(Thread.currentThread().isInterrupted());
    }
    @Test void miniMaxAccountingFailureStopsSendRatherThanFailingOpen() throws Exception {
        var sent=new AtomicInteger();doThrow(new java.io.IOException("synthetic disk failure")).when(usage).start(anyString(),anyString());
        doThrow(new java.io.IOException("synthetic disk failure")).when(usage).prepare(anyString(),anyString());
        var client=new MiniMaxVisionClient(config,json,r->{sent.incrementAndGet();return response(200,mini("stop"));});client.setUsageLedger(usage);
        try(var ctx=UsageContext.open(book,1,"MINIMAX_ASSIST")) {assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));}
        assertEquals(0,sent.get());
    }
    @Test void ordinaryOcrDoesNotHideThreePhysicalRetriesBehindOneInvocation() throws Exception {
        var sent=new AtomicInteger();var client=new QwenOcrClient(config,json,r->{sent.incrementAndGet();return response(429,"");});
        assertThrows(OcrException.class,()->client.recognize(new byte[]{1},100,100,"horizontal",()->false));assertEquals(1,sent.get());
    }

    PhysicalCallService gateway(ManagedTransport transport) throws Exception {
        var consent=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());consent.init();
        var service=spy(new PhysicalCallService(new ProviderResourceRegistry(),new AttemptCallBudgetStore(),new DelayedCallQueue(),json,usage,consent));
        doReturn(transport).when(service).synchronousTransport();return service;
    }
    @Test void managedQwenAndMiniMaxUseOneSharedAuditedLifecycle() throws Exception {
        var sent=new AtomicInteger();
        ManagedTransport transport=(request,nanos,max,stop)->{
            assertTrue(nanos>0 && nanos<=java.util.concurrent.TimeUnit.SECONDS.toNanos(5));sent.incrementAndGet();
            try{return new BoundedHttp.Response(200,(request.uri().getHost().contains("minimax")?mini("stop"):ocr("stop")).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            catch(Exception failure){throw new java.io.IOException("fixture");}
        };
        try(var service=gateway(transport);var usageScope=UsageContext.open(book,1,"OCR_PAGE");var execution=QwenExecutionScope.open(book,1,null,true)) {
            var qwen=new QwenOcrClient(config,json,service);var mini=new MiniMaxVisionClient(config,json,service);
            assertEquals("文字",qwen.recognize(new byte[]{1},100,100,"horizontal",()->false).get(0).original());
            assertEquals("原文",mini.assist(new byte[]{1},List.of(line()),"horizontal",()->false).get(0).original());
            var rows=(List<Map<String,Object>>)usage.view(book,0,50).get("entries");
            assertEquals(2,rows.size());assertTrue(rows.stream().allMatch(row->"SUCCEEDED".equals(row.get("status"))));
            assertEquals(Set.of("qwen","minimax"),rows.stream().map(row->(String)row.get("provider")).collect(java.util.stream.Collectors.toSet()));
            assertEquals(4,QwenExecutionScope.current().ocrBudget().remaining());assertEquals(7,QwenExecutionScope.current().budget().remaining());
            assertEquals(0,service.resources().inFlight("qwen"));assertEquals(0,service.resources().inFlight("minimax"));
            assertEquals(2,sent.get());
        }
    }
    @Test void managedAdaptersRequirePageExecutionIdentity() throws Exception {
        var sends=new AtomicInteger();try(var service=gateway((r,n,m,c)->{sends.incrementAndGet();return null;})) {
            var qwen=new QwenOcrClient(config,json,service);var mini=new MiniMaxVisionClient(config,json,service);
            assertThrows(OcrException.class,()->qwen.recognize(new byte[]{1},100,100,"horizontal",()->false));
            assertThrows(OcrException.class,()->mini.assist(new byte[]{1},List.of(line()),"horizontal",()->false));
            assertEquals(0,sends.get());
        }
    }
    @Test void qwenRegionsShareFivePhysicalOcrCallsInsteadOfRenewingBudget() throws Exception {
        var sends=new AtomicInteger();try(var service=gateway((r,n,m,c)->{sends.incrementAndGet();try{return new BoundedHttp.Response(200,ocr("stop").getBytes());}catch(Exception e){throw new java.io.IOException();}});
                var usageScope=UsageContext.open(book,1,"OCR_PAGE");var execution=QwenExecutionScope.open(book,1,null,true)) {
            var client=new QwenOcrClient(config,json,service);
            for(int i=0;i<5;i++)client.recognize(new byte[]{1},100,100,"horizontal",()->false);
            assertThrows(OcrException.class,()->client.recognize(new byte[]{1},100,100,"horizontal",()->false));
            assertEquals(5,sends.get());assertEquals(0,QwenExecutionScope.current().ocrBudget().remaining());
            assertEquals(8,QwenExecutionScope.current().budget().remaining());
        }
    }
    @Test void miniMaxSharesExistingEnhancementBudgetWithQwen() throws Exception {
        var sends=new AtomicInteger();try(var service=gateway((r,n,m,c)->{sends.incrementAndGet();try{return new BoundedHttp.Response(200,mini("stop").getBytes());}catch(Exception e){throw new java.io.IOException();}});
                var usageScope=UsageContext.open(book,1,"ENRICH_PAGE");var execution=QwenExecutionScope.open(book,1,null,true)) {
            assertTrue(QwenExecutionScope.current().budget().reserve(7));
            var client=new MiniMaxVisionClient(config,json,service);client.assist(new byte[]{1},List.of(line()),"horizontal",()->false);
            assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));assertEquals(1,sends.get());
        }
    }
    @Test void boundedCropDeadlineIncludesWaitingForAResourceSlot() throws Exception {
        var sends=new AtomicInteger();try(var service=gateway((r,n,m,c)->{fail("wrong transport");return null;});
                var usageScope=UsageContext.open(book,1,"QWEN_CROP_OCR");var execution=QwenExecutionScope.open(book,1,null,true)) {
            service.resources().configure("qwen",1,0,24);
            try(var held=service.resources().acquire("qwen",true,Duration.ZERO,()->false)) {
                var client=new QwenOcrClient(config,json,service);long start=System.nanoTime();
                assertThrows(OcrException.class,()->client.recognizeBounded(new byte[]{1},100,100,"horizontal",java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(70),4096,()->false,
                        (r,n,m,c)->{sends.incrementAndGet();return null;}));
                assertTrue(System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(1));assertEquals(0,sends.get());
            }
        }
    }
    @Test void permissionRevokedDuringReceiptWriteStopsBothAdaptersBeforeTransport() throws Exception {
        var sends=new AtomicInteger();var consent=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());consent.init();
        var grant=consent.findActiveConsent(null,book);
        try(var service=new PhysicalCallService(new ProviderResourceRegistry(),new AttemptCallBudgetStore(),new DelayedCallQueue(),json,usage,consent);
                var usageScope=UsageContext.open(book,1,"QWEN_CROP_OCR");var execution=QwenExecutionScope.open(book,1,null,true)) {
            doAnswer(inv->{var result=inv.callRealMethod();consent.revokeConsent(null,grant.consentId(),consent.getReadingPolicy(null).policyRevision(),"test-revoke");return result;}).when(usage).sending(anyString());
            var client=new QwenOcrClient(config,json,service);
            assertThrows(OcrException.class,()->client.recognizeBounded(new byte[]{1},100,100,"horizontal",java.util.concurrent.TimeUnit.SECONDS.toNanos(1),4096,()->false,
                    (r,n,m,c)->{sends.incrementAndGet();return null;}));
            assertEquals(0,sends.get());assertEquals(8,QwenExecutionScope.current().budget().remaining());
            var rows=(List<Map<String,Object>>)usage.view(book,0,50).get("entries");assertEquals("NOT_SENT",rows.get(0).get("status"));
        }
    }
    @Test void minimaxLedgerSurvivesReloadAndRetainsReportedUsageOnBadOutput() throws Exception {
        var payload=json.readTree(mini("content_filter"));((com.fasterxml.jackson.databind.node.ObjectNode)payload).putObject("usage").put("input_tokens",7).put("output_tokens",3);
        try(var service=gateway((r,n,m,c)->new BoundedHttp.Response(200,payload.toString().getBytes()));
                var usageScope=UsageContext.open(book,1,"ENRICH_PAGE");var execution=QwenExecutionScope.open(book,1,null,true)) {
            var client=new MiniMaxVisionClient(config,json,service);assertThrows(OcrException.class,()->client.assist(new byte[]{1},List.of(line()),"horizontal",()->false));
        }
        var settings=new SettingsService(config,new PaddleAiStudioProperties("",null,null,5,5,1),new QwenAssistProperties(),new DecisionProperties(),json);
        var reload=new UsageLedger(store,settings,json);var rows=(List<Map<String,Object>>)reload.view(book,0,50).get("entries");
        assertEquals(1,rows.size());assertEquals("FAILED",rows.get(0).get("status"));assertEquals(7L,rows.get(0).get("inputTokens"));
    }
    @Test void configuredMiniMaxHostIsExactHttpsNotAnArbitraryDomainSuffix() {
        var policy=new OutboundDestinationPolicy();
        assertTrue(policy.isAllowed("https://api.minimax.cn/v1/chat/completions"));
        assertFalse(policy.isAllowed("https://other.minimax.cn/v1/chat/completions"));
        assertFalse(policy.isAllowed("http://api.minimax.cn/v1/chat/completions"));
        assertFalse(policy.isAllowed("https://api.minimax.cn.evil.invalid/v1/chat/completions"));
    }
    @Test void malformedJsonAndNumericCoordinatesNeverSilentlyBecomeValidText() throws Exception {
        var client=new QwenOcrClient(config,json,r->null);
        assertThrows(OcrException.class,()->client.parse(ocr("stop")+" {}",100,100,"horizontal"));
        var payload=json.readTree(ocr("stop"));((com.fasterxml.jackson.databind.node.ObjectNode)payload.at("/output/choices/0/message/content/0/ocr_result/words_info/0")).put("text",123);
        assertThrows(OcrException.class,()->client.parse(payload.toString(),100,100,"horizontal"));
        assertThrows(OcrException.class,()->QwenOcrClient.normalize(new double[]{10,10,-30,40,0},100,100));
    }
    @Test void anExplicitEmptyOcrResultIsNotAGenericMalformedResponse() throws Exception {
        var payload=json.readTree(ocr("stop"));((com.fasterxml.jackson.databind.node.ArrayNode)payload.at("/output/choices/0/message/content/0/ocr_result/words_info")).removeAll();
        var client=new QwenOcrClient(config,json,r->response(200,payload.toString()));
        assertThrows(OcrNoTextException.class,()->client.recognize(new byte[]{1},100,100,"horizontal",()->false));
    }
}
