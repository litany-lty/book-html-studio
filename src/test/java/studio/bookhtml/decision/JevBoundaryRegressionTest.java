package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.service.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevBoundaryRegressionTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    BookStore store;UsageLedger usage;AppProperties config;SettingsService settings;
    @BeforeEach void setup() throws Exception {
        config=new AppProperties(temp,300,5000,2400,"tesseract","","q","https://dashscope.aliyuncs.com/api/v1",5,"","m","https://api.minimax.cn/v1",5,true);
        store=new BookStore(config,json);store.createBookDirectory(book);
        settings=new SettingsService(config,new PaddleAiStudioProperties("",null,null,5,5,1),new QwenAssistProperties(),new DecisionProperties(),json);
        usage=spy(new UsageLedger(store,settings,json));
    }
    @AfterEach void close(){Thread.interrupted();store.close();assertNull(UsageContext.current());}
    Map<String,JevDecisionClient.QuestionSpec> questions(){return Map.of("q",new JevDecisionClient.QuestionSpec("score","Rate evidence",Map.of()));}
    String valid(){return "{\"answers\":{\"q\":{\"type\":\"score\",\"score\":2.5}}}";}
    BoundedHttp.Response response(String text){return new BoundedHttp.Response(200,text.getBytes(StandardCharsets.UTF_8));}
    JevDecisionClient client(DecisionTransport transport){var c=new JevDecisionClient(json,transport);c.setUsageLedger(usage);return c;}
    JevDecisionClient.CallResult call(JevDecisionClient c,java.util.function.BooleanSupplier stop) throws Exception {
        try(var scope=UsageContext.open(book,1,"JEV_DECISION")){return c.callOnce("https://api.typesafe.ai/v1/systemone","fixture-key","fixture-model",Map.of(),questions(),Duration.ofSeconds(3).toNanos(),32768,65536,stop);}
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries() throws Exception{return (List<Map<String,Object>>)usage.view(book,0,100).get("entries");}
    @Test void cancellationBeforeAdmissionSendsNothing() throws Exception {
        var sent=new AtomicInteger();var c=client((r,n,m,s)->{sent.incrementAndGet();return response(valid());});
        var error=assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->true));
        assertEquals(JevDecisionClient.Kind.CANCELLED,error.kind());assertEquals(0,sent.get());assertTrue(entries().isEmpty());
    }
    @Test void cancellationDuringAuditIsRecheckedBeforeSend() throws Exception {
        var stop=new AtomicBoolean();var sent=new AtomicInteger();
        doAnswer(inv->{Object result=inv.callRealMethod();stop.set(true);return result;}).when(usage).start(anyString(),anyString());
        doAnswer(inv->{Object result=inv.callRealMethod();stop.set(true);return result;}).when(usage).prepare(anyString(),anyString());
        var c=client((r,n,m,s)->{sent.incrementAndGet();return response(valid());});
        var error=assertThrows(JevDecisionClient.JevCallException.class,()->call(c,stop::get));
        assertEquals(JevDecisionClient.Kind.CANCELLED,error.kind());assertEquals(0,sent.get());assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void transportFailureSettlesUnknownInsteadOfLeavingSendIntent() throws Exception {
        var c=client((r,n,m,s)->{throw new java.io.IOException("sensitive transport detail");});
        var error=assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->false));
        assertEquals(JevDecisionClient.Kind.NETWORK,error.kind());assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
        assertFalse(error.toString().contains("sensitive"));assertNull(error.getCause());
    }
    @Test void cancellationAfterBodyNeverProducesSuccessfulDecision() throws Exception {
        var stop=new AtomicBoolean();var c=client((r,n,m,s)->{stop.set(true);return response(valid());});
        var error=assertThrows(JevDecisionClient.JevCallException.class,()->call(c,stop::get));
        assertEquals(JevDecisionClient.Kind.CANCELLED,error.kind());assertNotEquals("SUCCEEDED",entries().get(0).get("status"));
    }
    @Test void trailingJsonIsNotASingleVerifiedResponse() throws Exception {
        var c=client((r,n,m,s)->response(valid()+" {}"));
        assertEquals(JevDecisionClient.Kind.PROTOCOL,assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->false)).kind());
    }
    @Test void fractionalTokenUsageIsNotRoundedDown() throws Exception {
        String body=valid().substring(0,valid().length()-1)+",\"usage\":{\"input_tokens\":1.5}}";
        assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,s)->response(body)),()->false));
    }
    @Test void overflowingTokenUsageIsNotSaturated() throws Exception {
        String body=valid().substring(0,valid().length()-1)+",\"usage\":{\"input_tokens\":9223372036854775808}}";
        assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,s)->response(body)),()->false));
    }
    @Test void malformedScoreConfidenceIsNotIgnored() throws Exception {
        String body=valid().replace("2.5","2.5,\"confidence\":\"high\"");
        assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,s)->response(body)),()->false));
    }
    @Test void malformedScoreDistributionIsNotIgnored() throws Exception {
        String body=valid().replace("2.5","2.5,\"probabilities\":[]");
        assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,s)->response(body)),()->false));
    }
    @Test void businessErrorCannotBeHiddenBesidePlausibleAnswers() throws Exception {
        String body=valid().substring(0,valid().length()-1)+",\"error\":\"rejected\"}";
        assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,s)->response(body)),()->false));
    }
    @Test void adapterEnforcesResponseLimitEvenWhenInjectedTransportDoesNot() throws Exception {
        var c=client((r,n,m,s)->response(valid()+" ".repeat(66000)));
        assertEquals(JevDecisionClient.Kind.TOO_LARGE,assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->false)).kind());
    }

    @Test void cancellationDuringSendingReceiptIsRechecked() throws Exception {
        var stop=new AtomicBoolean();var sends=new AtomicInteger();
        doAnswer(inv->{Object result=inv.callRealMethod();stop.set(true);return result;}).when(usage).sending(anyString());
        var c=client((r,n,m,x)->{sends.incrementAndGet();return response(valid());});
        assertEquals(JevDecisionClient.Kind.CANCELLED,assertThrows(JevDecisionClient.JevCallException.class,()->call(c,stop::get)).kind());
        assertEquals(0,sends.get());assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void timeSpentPersistingPreparationCannotRefreshNetworkDeadline() throws Exception {
        doAnswer(inv->{Object value=inv.callRealMethod();Thread.sleep(100);return value;}).when(usage).prepare(anyString(),anyString());
        var sends=new AtomicInteger();var c=client((r,n,m,x)->{sends.incrementAndGet();return response(valid());});
        try(var ctx=UsageContext.open(book,1,"JEV_DECISION")) {
            assertEquals(JevDecisionClient.Kind.TIMEOUT,assertThrows(JevDecisionClient.JevCallException.class,
                ()->c.callOnce("https://api.typesafe.ai/v1/systemone","fixture-key","fixture-model",Map.of(),questions(),
                        Duration.ofMillis(60).toNanos(),32768,65536,()->false)).kind());
        }
        assertEquals(0,sends.get());assertTrue(entries().isEmpty() || "NOT_SENT".equals(entries().get(0).get("status")));
    }
    @Test void malformedHeaderIsClassifiedWithoutCredentialInCause() throws Exception {
        var sends=new AtomicInteger();var c=client((r,n,m,x)->{sends.incrementAndGet();return response(valid());});
        var error=assertThrows(JevDecisionClient.JevCallException.class,
            ()->c.callOnce("https://api.typesafe.ai/v1/systemone","fixture\nSENSITIVE_TEST_HEADER","fixture-model",Map.of(),questions(),
                        Duration.ofSeconds(1).toNanos(),32768,65536,()->false));
        assertEquals(JevDecisionClient.Kind.PROTOCOL,error.kind());assertFalse(error.toString().contains("SENSITIVE_TEST_HEADER"));assertNull(error.getCause());
        assertEquals(0,sends.get());assertTrue(entries().isEmpty());
    }
    @Test void invalidLimitsDoNotReachLedgerOrNetwork() throws Exception {
        var sends=new AtomicInteger();var c=client((r,n,m,x)->{sends.incrementAndGet();return response(valid());});
        for(int limit:new int[]{0,-1,Integer.MAX_VALUE}) {
            assertEquals(JevDecisionClient.Kind.INVALID_REQUEST,assertThrows(JevDecisionClient.JevCallException.class,
                ()->c.callOnce("https://api.typesafe.ai/v1/systemone","fixture-key","fixture-model",Map.of(),questions(),1_000_000,32768,limit,()->false)).kind());
        }
        assertEquals(0,sends.get());assertTrue(entries().isEmpty());
    }
    @Test void frozenQuestionsCannotChangeWhileResponseIsInFlight() throws Exception {
        var mutable=new LinkedHashMap<>(questions());
        var c=client((r,n,m,x)->{mutable.clear();return response(valid());});
        try(var ctx=UsageContext.open(book,1,"JEV_DECISION")) {
            var result=c.callOnce("https://api.typesafe.ai/v1/systemone","fixture-key","fixture-model",Map.of(),mutable,
                    Duration.ofSeconds(1).toNanos(),32768,65536,()->false);
            assertEquals(2.5,result.scores().get("q").score());
        }
    }
    @Test void knownTokensRemainWhenBusinessValidationFails() throws Exception {
        String body=valid().replace("2.5","2.5,\"confidence\":\"invalid\"");
        body=body.substring(0,body.length()-1)+",\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}";
        String payload=body;assertThrows(JevDecisionClient.JevCallException.class,()->call(client((r,n,m,x)->response(payload)),()->false));
        var row=entries().get(0);assertEquals("FAILED",row.get("status"));assertEquals(7L,row.get("inputTokens"));assertEquals(3L,row.get("outputTokens"));
        var reload=new UsageLedger(store,settings,json);
        var rows=(List<Map<String,Object>>)reload.view(book,0,100).get("entries");assertEquals("FAILED",rows.get(0).get("status"));assertEquals(7L,rows.get(0).get("inputTokens"));
    }
    @Test void cancellationDuringFinalReceiptDoesNotReturnRecommendation() throws Exception {
        var stop=new AtomicBoolean();
        doAnswer(inv->{Object result=inv.callRealMethod();stop.set(true);return result;}).when(usage).succeeded(anyString());
        assertEquals(JevDecisionClient.Kind.CANCELLED,assertThrows(JevDecisionClient.JevCallException.class,
                ()->call(client((r,n,m,x)->response(valid())),stop::get)).kind());
        assertEquals("SUCCEEDED",entries().get(0).get("status"),"physical completion remains true, but the caller receives cancellation");
    }
    @Test void interruptedIoPreservesCancellationAndUnknownReceipt() throws Exception {
        var c=client((r,n,m,x)->{Thread.currentThread().interrupt();throw new java.io.IOException("private transport detail");});
        assertEquals(JevDecisionClient.Kind.CANCELLED,assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->false)).kind());
        assertTrue(Thread.currentThread().isInterrupted());Thread.interrupted();
        assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
    }
    @Test void failedPreparationAndOversizedSerializedRequestNeverSend() throws Exception {
        var sends=new AtomicInteger();var c=client((r,n,m,x)->{sends.incrementAndGet();return response(valid());});
        try(var ctx=UsageContext.open(book,1,"JEV_DECISION")) {
            assertEquals(JevDecisionClient.Kind.TOO_LARGE,assertThrows(JevDecisionClient.JevCallException.class,
                ()->c.callOnce("https://api.typesafe.ai/v1/systemone","fixture-key","fixture-model",Map.of("text","字".repeat(10000)),questions(),
                        Duration.ofSeconds(1).toNanos(),1024,65536,()->false)).kind());
        }
        doThrow(new java.io.IOException("private disk path")).when(usage).prepare(anyString(),anyString());
        assertEquals(JevDecisionClient.Kind.PROTOCOL,assertThrows(JevDecisionClient.JevCallException.class,()->call(c,()->false)).kind());
        assertEquals(0,sends.get());assertTrue(entries().isEmpty());
    }
}
