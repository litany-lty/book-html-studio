package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhysicalBoundaryRegressionTest {
    @TempDir Path temp;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    BookStore store; UsageLedger ledger; PhysicalCallService calls;
    ProviderResourceRegistry resources; AttemptCallBudgetStore budgets; DelayedCallQueue delayed;
    @BeforeEach void setup() throws Exception {
        var config=TestConfigs.config(temp,"","");store=new BookStore(config,json);store.createBookDirectory(book);
        var settings=new SettingsService(config,new PaddleAiStudioProperties("",null,null,10,10,1),new QwenAssistProperties(),new DecisionProperties(),json);
        ledger=spy(new UsageLedger(store,settings,json));resources=new ProviderResourceRegistry();
        budgets=new AttemptCallBudgetStore();delayed=new DelayedCallQueue();calls=new PhysicalCallService(resources,budgets,delayed,json);
        calls.setUsageLedger(ledger);
    }
    @AfterEach void close() {calls.close();store.close();assertNull(UsageContext.current());}
    PhysicalCallCommand command() {return PhysicalCallCommand.builder().bookId(book).page(1).provider("qwen").model("fixture")
        .kind(PhysicalCallCommand.ExecutionKind.ASSIST_TEXT_REVIEW).purpose("QWEN_TEXT_REVIEW").budgetRootId("fixture-budget")
        .deadlineNanos(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)).build();}
    HttpRequest request() {return HttpRequest.newBuilder(URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions")).build();}
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries() throws Exception {return (List<Map<String,Object>>)ledger.view(book,0,100).get("entries");}
    @Test void qwenAdaptersAndRegistryMustShareOneProcessPool() throws Exception {
        try(var context=new AnnotationConfigApplicationContext()) {
            context.registerBean(QwenAssistProperties.class,QwenAssistProperties::new);
            context.registerBean(ProviderResourceRegistry.class,()->resources);
            context.registerBean(PhysicalCallService.class,()->calls);
            context.register(QwenRequestGate.class);context.refresh();
            QwenRequestGate gate=context.getBean(QwenRequestGate.class);
            try(var first=gate.acquire(true,Duration.ZERO);var second=gate.acquire(false,Duration.ZERO);var third=gate.acquire(false,Duration.ZERO)) {
                assertEquals(3,resources.inFlight("qwen"),"real auxiliary requests must be visible to diagnostics and other adapters");
                var overflow=resources.acquire("qwen",true,Duration.ZERO,()->false);
                try {assertNull(overflow,"separate adapters cannot mint another three permits");} finally {if(overflow!=null)overflow.close();}
            }
        }
    }
    @Test void cancelledQueuedCallerDoesNotWaitForAnUnrelatedRequestToFinish() throws Exception {
        var holds=new ArrayList<ProviderResourceRegistry.Permit>();for(int i=0;i<3;i++)holds.add(resources.acquire("qwen",true,Duration.ZERO,()->false));
        var stop=new AtomicBoolean();var pool=Executors.newSingleThreadExecutor();
        try {
            var future=pool.submit(()->{assertThrows(CancelledException.class,()->resources.acquire("qwen",true,Duration.ofSeconds(5),stop::get));});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(resources.queued("qwen")==0 && System.nanoTime()<end)Thread.sleep(5);
            assertEquals(1,resources.queued("qwen"));stop.set(true);
            future.get(800,TimeUnit.MILLISECONDS);
            assertEquals(0,resources.queued("qwen"));
        } finally {stop.set(true);pool.shutdownNow();holds.forEach(ProviderResourceRegistry.Permit::close);pool.awaitTermination(2,TimeUnit.SECONDS);}
    }
    @Test void factoryCancellationMustBeCheckedBeforePhysicalSend() throws Exception {
        var stop=new AtomicBoolean();var sent=new AtomicInteger();
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            assertThrows(CancelledException.class,()->calls.execute(command(),()->{stop.set(true);return request();},
                (r,d,m,c)->{sent.incrementAndGet();return new BoundedHttp.Response(200,"{}".getBytes());},stop::get));
        }
        assertEquals(0,sent.get());assertEquals("NOT_SENT",entries().get(0).get("status"));
        assertEquals(8,budgets.remaining("fixture-budget",8));
    }
    @Test void ledgerFlushCancellationMustBeCheckedAgainBeforeTransport() throws Exception {
        var stop=new AtomicBoolean();var sent=new AtomicInteger();
        doAnswer(inv->{var result=inv.callRealMethod();stop.set(true);return result;}).when(ledger).sending(anyString());
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            assertThrows(CancelledException.class,()->calls.execute(command(),this::request,
                (r,d,m,c)->{sent.incrementAndGet();return new BoundedHttp.Response(200,"{}".getBytes());},stop::get));
        }
        assertEquals(0,sent.get());assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void noRequestMustSettlePreparedReceiptAsNotSent() throws Exception {
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            assertInstanceOf(CallOutcome.NotSent.class,calls.execute(command(),()->null,(r,d,m,c)->{fail("unexpected transport");return null;},()->false));
        }
        assertEquals("NOT_SENT",entries().get(0).get("status"));assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void invalidResponseMustNotBecomeLedgerSuccess() throws Exception {
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            assertInstanceOf(CallOutcome.Failed.class,calls.execute(command(),this::request,
                (r,d,m,c)->new BoundedHttp.Response(200,"not-json".getBytes()),()->false));
        }
        assertEquals("FAILED",entries().get(0).get("status"));
    }
    @Test void commandCannotBorrowAnotherBooksUsageContext() throws Exception {
        try(var context=UsageContext.open(UUID.randomUUID().toString(),1,"QWEN_TEXT_REVIEW")) {
            assertInstanceOf(CallOutcome.NotSent.class,calls.execute(command(),this::request,
                (r,d,m,c)->{fail("mismatched accounting scope sent");return null;},()->false));
        }
        assertTrue(entries().isEmpty());
    }
    @Test void transportDetailsMustNotLeakThroughOutcome() throws Exception {
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var result=calls.execute(command(),this::request,(r,d,m,c)->{throw new java.io.IOException("SENSITIVE_TRANSPORT_DETAIL");},()->false);
            assertInstanceOf(CallOutcome.OutcomeUnknown.class,result);
            assertFalse(result.toString().contains("SENSITIVE_TRANSPORT_DETAIL"));
        }
    }

    private CloudConsentService managedGateway() throws Exception {
        var consent=new CloudConsentService(store.consentStore(),store.policyStore(),store.epochStore());consent.init();
        calls=new PhysicalCallService(resources,budgets,delayed,json,ledger,consent);return consent;
    }
    @Test void actualQwenFacadeUsesSharedManagedLifecycleAndVisiblePool() throws Exception {
        managedGateway();var gateway=spy(calls);var gate=new QwenRequestGate(new QwenAssistProperties(),resources,gateway);
        for(String operation:List.of("QWEN_ASSIST","QWEN_STRUCTURE","QWEN_TEXT_REVIEW","QWEN_TOC_RECOVERY","HANDWRITING_TRANSCRIBE","COMPREHENSIBILITY")) {
            try(var usage=UsageContext.open(book,1,operation);var scope=QwenExecutionScope.open(book,1,gate,true);
                var call=QwenPhysicalCall.open(gate,ledger,"fixture",null,true,5,()->false,true)) {
                assertEquals(1,resources.inFlight("qwen"));
                var r=call.send(request(),req->{
                    var current=entries().stream().filter(e->operation.equals(e.get("operation"))).findFirst().orElseThrow();
                    assertEquals("SENT_UNKNOWN",current.get("status"));
                    return QwenPhysicalCallTest.response(200,new java.io.ByteArrayInputStream("{}".getBytes()));});
                call.read(r,1024);call.succeeded();
            }
            assertEquals(0,resources.inFlight("qwen"));
        }
        verify(gateway,times(6)).openQwen(eq(gate),eq(ledger),eq("fixture"),any(),eq(true),anyLong(),any());
        assertEquals(6,entries().size());assertTrue(entries().stream().allMatch(e->"SUCCEEDED".equals(e.get("status"))));
    }
    @Test void revocationBetweenPreparationAndSendPreventsExternalRequest() throws Exception {
        var consent=managedGateway();var grant=consent.findActiveConsent(null,book);
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources,calls);var sent=new AtomicInteger();var budget=gate.newBudget();
        try(var usage=UsageContext.open(book,1,"COMPREHENSIBILITY");var call=QwenPhysicalCall.open(gate,ledger,"fixture",budget,true,5,()->false,true)) {
            consent.revokeConsent(null,grant.consentId(),consent.getReadingPolicy(null).policyRevision(),"revoke-fixture");
            assertThrows(OcrException.class,()->call.send(request(),req->{sent.incrementAndGet();return null;}));
        }
        assertEquals(0,sent.get());assertEquals(8,budget.remaining());assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void sourceScopeAndDestinationAreStillValidatedByInjectedGateway() throws Exception {
        managedGateway();var gate=new QwenRequestGate(new QwenAssistProperties(),resources,calls);
        assertThrows(OcrException.class,()->QwenPhysicalCall.open(gate,ledger,"fixture",null,true,5,()->false,true));
        try(var usage=UsageContext.open(book,1,"COMPREHENSIBILITY");var call=QwenPhysicalCall.open(gate,ledger,"fixture",null,true,5,()->false,true)) {
            HttpRequest local=HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/private")).build();
            assertThrows(OcrException.class,()->call.send(local,req->{fail("unapproved endpoint");return null;}));
        }
        assertEquals(0,resources.inFlight("qwen"));assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void mixedAdmissionReconfigurationDrainsWithoutReplacingOldOwners() throws Exception {
        var config=new QwenAssistProperties();var gate=new QwenRequestGate(config,resources);
        var one=gate.acquire(false,Duration.ZERO);var two=resources.acquire("qwen",false,Duration.ZERO,()->false);var three=gate.acquire(true,Duration.ZERO);
        config.setMaxConcurrentRequests(1);gate.refresh();
        try {
            assertEquals(3,gate.inFlight());assertEquals(3,resources.inFlight("qwen"));
            assertNull(resources.acquire("qwen",true,Duration.ZERO,()->false));
            one.close();two.close();assertNull(gate.acquire(true,Duration.ZERO));
        }finally{one.close();two.close();three.close();}
        assertEquals(0,resources.inFlight("qwen"));
        try(var last=gate.acquire(true,Duration.ZERO)){assertNotNull(last);assertNull(resources.acquire("qwen",true,Duration.ZERO,()->false));}
    }
    @Test void diagnosticsReportTheSameSharedCountsWithoutAccountOrKeyMaterial() throws Exception {
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources);var diagnostics=new studio.bookhtml.api.DiagnosticsController(null,store);
        diagnostics.setProviderRegistry(resources);
        try(var one=gate.acquire(false,Duration.ZERO);var two=resources.acquire("qwen",true,Duration.ZERO,()->false)) {
            var providers=(Map<?,?>)diagnostics.diagnostics().get("providerResources");var qwen=(Map<?,?>)providers.get("qwen");
            assertEquals(2,providers.get("qwenInFlight"));assertEquals(2,qwen.get("inFlight"));assertEquals(1,qwen.get("backgroundInFlight"));
            assertEquals("PROCESS_WIDE",providers.get("scope"));assertFalse(providers.toString().contains("apiKey"));
        }
    }
    @Test void receiptWriteFailureDoesNotSpendBudgetOrLeavePermit() throws Exception {
        doThrow(new java.io.IOException("SENSITIVE_WRITE_DETAIL")).when(ledger).prepare(anyString(),any());var sent=new AtomicInteger();
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var outcome=calls.execute(command(),this::request,(r,d,m,c)->{sent.incrementAndGet();return null;},()->false);
            assertInstanceOf(CallOutcome.NotSent.class,outcome);assertFalse(outcome.toString().contains("SENSITIVE_WRITE_DETAIL"));
        }
        assertEquals(0,sent.get());assertEquals(8,budgets.remaining("fixture-budget",8));assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void validatorFailureRetainsReportedUsageButNotSuccess() throws Exception {
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var outcome=calls.execute(command(),this::request,
                (r,d,m,c)->new BoundedHttp.Response(200,"{\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}".getBytes()),()->false,
                response->{throw new IllegalArgumentException("SENSITIVE_VALIDATOR_DETAIL");});
            assertInstanceOf(CallOutcome.Failed.class,outcome);assertFalse(outcome.toString().contains("SENSITIVE_VALIDATOR_DETAIL"));
        }
        assertEquals("FAILED",entries().get(0).get("status"));assertEquals(7L,entries().get(0).get("inputTokens"));
        assertEquals(7,budgets.remaining("fixture-budget",8));
    }
    @Test void buffered429HonorsServerDelayAndReturnsWithoutHoldingPermit() throws Exception {
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var outcome=calls.execute(command(),this::request,(r,d,m,c)->new BoundedHttp.Response(429,new byte[0],"1"),()->false);
            var retry=assertInstanceOf(CallOutcome.RetryEligible.class,outcome);assertEquals(1,retry.retryAfterSeconds());
        }
        assertEquals(0,resources.inFlight("qwen"));assertEquals("FAILED",entries().get(0).get("status"));assertEquals(7,budgets.remaining("fixture-budget",8));
    }
    @Test void enormousRetryAfterNeverTurnsIntoAnEarlyAutomaticRetry() throws Exception {
        for(String retry:List.of("99999","99999999999999999999999999999999","AMBIGUOUS_RETRY_AFTER")) {
            try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
                var outcome=calls.execute(command(),this::request,(r,d,m,c)->new BoundedHttp.Response(429,new byte[0],retry),()->false);
                assertInstanceOf(CallOutcome.Failed.class,outcome);
            }
        }
        assertEquals(3,entries().size());assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void completeNonSuccessHeaderCannotBeMarkedAsSuccess() throws Exception {
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources);
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW");var call=QwenPhysicalCall.open(gate,ledger,"fixture",null,true,5,()->false,true)) {
            call.read(call.send(request(),r->QwenPhysicalCallTest.response(500,new java.io.ByteArrayInputStream("{}".getBytes()))),1024);
            assertThrows(IllegalStateException.class,call::succeeded);
        }
        assertEquals("FAILED",entries().get(0).get("status"));
    }
    @Test void readingAnotherResponseCannotCompleteTheOwnedCall() throws Exception {
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources);
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW");var call=QwenPhysicalCall.open(gate,ledger,"fixture",null,true,5,()->false,true)) {
            call.send(request(),r->QwenPhysicalCallTest.response(200,new java.io.ByteArrayInputStream("{}".getBytes())));
            assertThrows(IllegalArgumentException.class,()->call.read(QwenPhysicalCallTest.response(200,new java.io.ByteArrayInputStream("{}".getBytes())),1024));
            assertThrows(IllegalStateException.class,call::succeeded);
        }
        assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
    }
    @Test void factoryFailureWithTypedExceptionCannotExposeTransportDetails() throws Exception {
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var outcome=calls.execute(command(),()->{throw new OcrException("SENSITIVE_FACTORY_DETAIL");},(r,d,m,c)->null,()->false);
            assertInstanceOf(CallOutcome.NotSent.class,outcome);assertFalse(outcome.toString().contains("SENSITIVE_FACTORY_DETAIL"));
        }
        assertEquals("NOT_SENT",entries().get(0).get("status"));
    }

    @Test void configurationCannotBypassTheProcessCeilingViaAnotherAdapter() throws Exception {
        resources.configure("qwen",999,998,9999);
        var state=resources.pool("qwen").snapshot();
        assertEquals(3,state.get("limit"));assertEquals(2,state.get("backgroundLimit"));assertEquals(24,state.get("queueLimit"));
    }
    @Test void transportPreservesRetryAfterAndClosesResponseBody() throws Exception {
        var body=spy(new java.io.ByteArrayInputStream("limited".getBytes()));var response=QwenPhysicalCallTest.response(429,body);
        when(response.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of("Retry-After",List.of("17")),(n,v)->true));
        try(var http=new BoundedHttp(1,Duration.ofSeconds(1))) {
            var buffered=http.consumeResponse(response,System.nanoTime()+TimeUnit.SECONDS.toNanos(2),1024,()->false);
            assertEquals("17",buffered.retryAfter());verify(body,atLeastOnce()).close();
        }
    }
    @Test void authorityRevokedDuringLedgerFlushIsRecheckedByStreamingAdapter() throws Exception {
        var consent=managedGateway();var grant=consent.findActiveConsent(null,book);
        doAnswer(inv->{var result=inv.callRealMethod();
            consent.revokeConsent(null,grant.consentId(),consent.getReadingPolicy(null).policyRevision(),"during-durable-flush");return result;
        }).when(ledger).sending(anyString());
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources,calls);var sent=new AtomicInteger();var budget=gate.newBudget();
        try(var usage=UsageContext.open(book,1,"COMPREHENSIBILITY");var call=QwenPhysicalCall.open(gate,ledger,"fixture",budget,true,5,()->false,true)) {
            assertThrows(OcrException.class,()->call.send(request(),r->{sent.incrementAndGet();return null;}));
        }
        assertEquals(0,sent.get());assertEquals(8,budget.remaining());assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void bufferedRequestGetsOnlyRemainingTimeAfterFactoryWork() throws Exception {
        var cmd=command();
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var result=calls.execute(cmd,()->{try{Thread.sleep(30);}catch(InterruptedException e){Thread.currentThread().interrupt();}return request();},
                (request,nanos,max,stop)->{
                    assertTrue(nanos>0 && nanos<TimeUnit.SECONDS.toNanos(5));
                    assertTrue(Math.abs((cmd.deadlineNanos()-System.nanoTime())-nanos)<TimeUnit.MILLISECONDS.toNanos(50));
                    assertTrue(request.timeout().orElseThrow().toNanos()<TimeUnit.SECONDS.toNanos(5));
                    return new BoundedHttp.Response(200,"{}".getBytes());
                },()->false);
            assertInstanceOf(CallOutcome.Succeeded.class,result);
        }
    }

    @Test void productionBufferedGatewayRequiresAnExplicitBusinessValidator() throws Exception {
        managedGateway();var sent=new AtomicInteger();
        try(var usage=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            var outcome=calls.execute(command(),this::request,(r,d,m,c)->{sent.incrementAndGet();return new BoundedHttp.Response(200,"{}".getBytes());},()->false);
            assertInstanceOf(CallOutcome.NotSent.class,outcome);
        }
        assertEquals(0,sent.get());assertTrue(entries().isEmpty());
    }

    @Test void interruptedFactoryMustRemainCancellationAndPreserveTheInterrupt() throws Exception {
        try {
            try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
                assertThrows(CancelledException.class,()->calls.execute(command(),()->{throw new InterruptedException("fixture");},
                    (r,d,m,c)->{fail("factory cancelled before send");return null;},()->false));
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {Thread.interrupted();}
        assertEquals("NOT_SENT",entries().get(0).get("status"));
        assertEquals(8,budgets.remaining("fixture-budget",8));assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void boundedTransportCancellationMustNotBecomeAnOrdinaryFailedResult() throws Exception {
        try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
            assertThrows(CancelledException.class,()->calls.execute(command(),this::request,
                (r,d,m,c)->{throw new BoundedHttp.BoundedHttpException(BoundedHttp.Kind.CANCELLED,"fixture");},()->false));
        }
        assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
        assertEquals(7,budgets.remaining("fixture-budget",8));assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void interruptedValidatorPreservesCancellationButNeverRefundsAnActualSend() throws Exception {
        try {
            try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW")) {
                assertThrows(CancelledException.class,()->calls.execute(command(),this::request,
                    (r,d,m,c)->new BoundedHttp.Response(200,"{}".getBytes()),()->false,
                    response->{throw new InterruptedException("fixture");}));
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {Thread.interrupted();}
        assertEquals("FAILED",entries().get(0).get("status"));
        assertEquals(7,budgets.remaining("fixture-budget",8));assertEquals(0,resources.inFlight("qwen"));
    }
    @Test void preexistingInterruptDoesNotPreventDurableUnsentSettlement() throws Exception {
        var gate=new QwenRequestGate(new QwenAssistProperties(),resources);var budget=gate.newBudget();
        try {
            try(var context=UsageContext.open(book,1,"QWEN_TEXT_REVIEW");
                var call=QwenPhysicalCall.open(gate,ledger,"fixture",budget,true,5,()->false,true)) {
                Thread.currentThread().interrupt();
            }
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
        assertEquals("NOT_SENT",entries().get(0).get("status"));
        assertEquals(8,budget.remaining());assertEquals(0,resources.inFlight("qwen"));
        assertTrue(ledger.auditIntegrity(book).healthy());
    }

}
