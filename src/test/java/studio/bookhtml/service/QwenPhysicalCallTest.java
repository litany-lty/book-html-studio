package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real private ledger + controlled streams. No provider connections or real credentials. */
class QwenPhysicalCallTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String book=UUID.randomUUID().toString();
    QwenAssistProperties config;
    QwenRequestGate gate;
    UsageLedger ledger;
    BookStore store;
    @BeforeEach void setup() throws Exception {
        config=new QwenAssistProperties();
        gate=new QwenRequestGate(config);
        var app=TestConfigs.config(data,"","");
        store=new BookStore(app,json);store.createBookDirectory(book);
        var settings=new SettingsService(app,new PaddleAiStudioProperties("",null,null,10,10,1),
                config,new DecisionProperties(),json);
        ledger=spy(new UsageLedger(store,settings,json));
    }
    @AfterEach void close() {
        store.close();assertNull(UsageContext.current());assertNull(QwenExecutionScope.current());
        assertEquals(0,gate.inFlight());
    }
    UsageContext.Scope context(){return UsageContext.open(book,1,"QWEN_TEXT_REVIEW","chunk-1");}
    QwenPhysicalCall open(QwenRequestGate.Budget budget,java.util.function.BooleanSupplier cancelled) throws Exception {
        return QwenPhysicalCall.open(gate,ledger,"qwen-fixture",budget,true,1,cancelled,true);
    }
    HttpRequest request(){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:9/disabled"))
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();}
    @SuppressWarnings("unchecked")
    static HttpResponse<InputStream> response(int status,InputStream stream) {
        HttpResponse<InputStream> r=mock(HttpResponse.class);
        when(r.statusCode()).thenReturn(status);when(r.body()).thenReturn(stream);
        when(r.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));return r;
    }
    @SuppressWarnings("unchecked") Map<String,Object> totals() throws Exception{return (Map<String,Object>)ledger.view(book,0,100).get("totals");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> entries() throws Exception{return (List<Map<String,Object>>)ledger.view(book,0,100).get("entries");}

    @Test void intentAndCorrelationAreDurableBeforeSend() throws Exception {
        try(var ctx=context();var scope=QwenExecutionScope.open(book,1,gate,true)) {
            var execution=QwenExecutionScope.current();
            try(var call=open(execution.budget(),()->false)) {
                var r=call.send(request(), req->{
                    var e=entries().get(0);
                    assertEquals("SENT_UNKNOWN",e.get("status"));
                    assertEquals(execution.executionId().toString(),e.get("executionId"));
                    assertEquals("QWEN_TEXT_REVIEW",e.get("operation"));
                    assertTrue(e.get("taskHash").toString().matches("[0-9a-f]{64}"));
                    assertFalse(json.writeValueAsString(e).contains("chunk-1"));
                    return response(200,new ByteArrayInputStream("{}".getBytes()));
                });
                assertEquals("{}",new String(call.read(r,1024)));
                call.succeeded();
            }
            assertEquals(7,execution.budget().remaining());
        }
        assertEquals(1L,totals().get("success"));
    }
    @Test void missingRuntimeDependenciesRejectBeforeSending() {
        try(var ctx=context()) {
            assertThrows(OcrException.class,()->QwenPhysicalCall.open(null,ledger,"qwen-fixture",null,true,1,()->false,true));
            assertThrows(OcrException.class,()->QwenPhysicalCall.open(gate,null,"qwen-fixture",null,true,1,()->false,true));
        }
    }
    @Test void queueRejectionDoesNotCreateReceiptOrDebitBudget() throws Exception {
        config.setMaxConcurrentRequests(1);config.setMaxQueuedChunks(0);gate.refresh();
        var budget=gate.newBudget();
        try(var ctx=context();var hold=gate.acquire(true,Duration.ZERO)) {
            assertThrows(OcrException.class,()->open(budget,()->false));
            assertEquals(8,budget.remaining());assertEquals(0L,totals().get("requests"));
        }
    }
    @Test void failedLedgerWriteNeverSendsAndRefundsOnlyUnsentReservation() throws Exception {
        doThrow(new IOException("synthetic disk failure")).when(ledger).prepare("qwen","qwen-fixture");
        var budget=gate.newBudget();
        try(var ctx=context()) { assertThrows(IOException.class,()->open(budget,()->false)); }
        assertEquals(8,budget.remaining());assertEquals(0L,totals().get("requests"));
    }
    @Test void cancelAfterReceiptButBeforeSendIsNotAPhysicalRequest() throws Exception {
        var stop=new AtomicBoolean();var budget=gate.newBudget();var sends=new AtomicInteger();
        try(var ctx=context();var call=open(budget,stop::get)) {
            stop.set(true);
            assertThrows(CancelledException.class,()->call.send(request(),req->{sends.incrementAndGet();return null;}));
        }
        assertEquals(0,sends.get());assertEquals(8,budget.remaining());
        assertEquals(0L,totals().get("requests"));assertEquals(1L,totals().get("notSent"));
        assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void unknownSendOutcomeIsNotRefundedOrRetried() throws Exception {
        var budget=gate.newBudget();var sends=new AtomicInteger();
        try(var ctx=context();var call=open(budget,()->false)) {
            assertThrows(IOException.class,()->call.send(request(),req->{sends.incrementAndGet();throw new IOException("synthetic timeout");}));
        }
        assertEquals(1,sends.get());assertEquals(7,budget.remaining());
        assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
        assertEquals(1L,totals().get("pending"));
    }
    @Test void aSuccessfulHeaderWithStalledBodyIsBoundedAndUnknown() throws Exception {
        BlockingBody body=new BlockingBody();var budget=gate.newBudget();long start=System.nanoTime();
        try(var ctx=context();var call=open(budget,()->false)) {
            var r=call.send(request(),req->response(200,body));
            assertThrows(OcrException.class,()->call.read(r,1024));
        }
        assertTrue(body.closed.get());assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(4));
        assertEquals(7,budget.remaining());assertEquals("OUTCOME_UNKNOWN",entries().get(0).get("status"));
    }
    @Test void oversizedBodyClosesStreamAndDoesNotDeclareSuccess() throws Exception {
        var body=spy(new ByteArrayInputStream(new byte[50]));
        try(var ctx=context();var call=open(gate.newBudget(),()->false)) {
            var r=call.send(request(),req->response(200,body));
            assertTrue(assertThrows(OcrException.class,()->call.read(r,10)).getMessage().contains("过大"));
        }
        verify(body,atLeastOnce()).close();assertEquals(0L,totals().get("success"));
    }
    @Test void tokenUsageSurvivesSemanticFailureWithoutBecomingSuccess() throws Exception {
        try(var ctx=context();var call=open(gate.newBudget(),()->false)) {
            call.read(call.send(request(),req->response(200,new ByteArrayInputStream("{}".getBytes()))),1024);
            call.captureUsage(json.readTree("{\"usage\":{\"input_tokens\":9,\"output_tokens\":2}}"));
        }
        assertEquals("FAILED",entries().get(0).get("status"));
        assertEquals(9L,entries().get(0).get("inputTokens"));
    }
    @Test void preflightValidationRejectsBadOperationsBeforeWriting() throws Exception {
        try(var ctx=UsageContext.open(book,1,"QWEN_TEXT_REVIEW:bad")) {
            assertThrows(IOException.class,()->ledger.start("qwen","qwen-fixture"));
        }
        assertFalse(Files.exists(store.bookDir(book).resolve("usage")));
        try(var ctx=context()){ledger.succeeded(ledger.start("qwen","qwen-fixture"));}
        assertEquals(1L,totals().get("success"));
    }
    @Test void taskScopeCannotCrossBookOrBeReplacedByANewBudget() throws Exception {
        var budget=gate.newBudget();
        try(var ctx=context();var scope=QwenExecutionScope.open(book,1,gate,true)) {
            assertThrows(IllegalArgumentException.class,()->open(budget,()->false));
            try(var other=UsageContext.open(UUID.randomUUID().toString(),1,"QWEN_STRUCTURE")) {
                assertThrows(IOException.class,()->ledger.start("qwen","qwen-fixture"));
            }
        }
        assertEquals(8,budget.remaining());assertEquals(0L,totals().get("requests"));
    }
    @Test void reservationCloseIsIdempotentAndSentReservationsNeverRefund() {
        var budget=gate.newBudget();var claim=budget.claim();assertNotNull(claim);
        claim.close();claim.close();assertEquals(8,budget.remaining());
        assertThrows(IllegalStateException.class,claim::markSent);
        var sent=budget.claim();sent.markSent();sent.close();sent.close();assertEquals(7,budget.remaining());
    }
    @Test void finalLedgerStatesCannotBeResurrectedByLateFailures() throws Exception {
        try(var ctx=context()) {
            String id=ledger.start("qwen","qwen-fixture");ledger.succeeded(id);
            assertThrows(IOException.class,()->ledger.failed(id));
            String unsent=ledger.start("qwen","qwen-fixture");ledger.notSent(unsent);
            assertThrows(IOException.class,()->ledger.usage(unsent,1L,1L));
        }
        assertEquals(1L,totals().get("success"));assertEquals(1L,totals().get("notSent"));
    }
    @Test void preparedIntentDoesNotCountAsSentAndSendingWriteFailureStopsTransport() throws Exception {
        var budget=gate.newBudget();var sends=new AtomicInteger();
        try(var ctx=context();var call=open(budget,()->false)) {
            assertEquals("PREPARED",entries().get(0).get("status"));
            assertEquals(0L,totals().get("requests"));assertEquals(1L,totals().get("prepared"));
            doThrow(new IOException("synthetic mark failure")).when(ledger).sending(anyString());
            assertThrows(IOException.class,()->call.send(request(),req->{sends.incrementAndGet();return null;}));
        }
        assertEquals(0,sends.get());assertEquals(8,budget.remaining());
        assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    @Test void expiredSharedBudgetCannotAcquireAFreshDeadline() throws Exception {
        var budget=gate.newBudget();long original=budget.deadline(1);
        assertEquals(original,budget.deadline(600),"a later helper cannot extend the execution");
        var field=budget.getClass().getDeclaredField("deadlineNanos");field.setAccessible(true);
        field.set(budget,System.nanoTime()-TimeUnit.SECONDS.toNanos(1));
        try(var ctx=context()) { assertThrows(OcrException.class,()->open(budget,()->false)); }
        assertEquals(8,budget.remaining());assertEquals(0L,totals().get("requests"));
    }
    @Test void durableAttemptIdentityIsSharedAcrossNestedProcessingHelpers() throws Exception {
        var attempt=studio.bookhtml.domain.PageAttempt.register(book,1,0,"test",List.of("JOB_BASELINE"));
        try(var ctx=context();var outer=QwenExecutionScope.open(attempt,gate,true)) {
            var value=QwenExecutionScope.current();
            try(var helper=QwenExecutionScope.open(book,1,gate,false);var call=open(value.budget(),()->false)) {
                call.read(call.send(request(),req->response(200,new ByteArrayInputStream("{}".getBytes()))),1024);
                call.succeeded();
            }
            assertSame(value,QwenExecutionScope.current());
            assertEquals(attempt.attemptId().toString(),entries().get(0).get("executionId"));
            assertEquals(attempt.generation(),entries().get(0).get("attemptSeq"));
        }
    }
    @Test void cancellationWhileAuditingIsRecheckedBeforeActualSend() throws Exception {
        var cancelled=new AtomicBoolean();var sends=new AtomicInteger();var budget=gate.newBudget();
        doAnswer(inv->{ Object result=inv.callRealMethod();cancelled.set(true);return result; })
                .when(ledger).sending(anyString());
        try(var ctx=context();var call=open(budget,cancelled::get)) {
            assertThrows(CancelledException.class,()->call.send(request(),req->{sends.incrementAndGet();return null;}));
        }
        assertEquals(0,sends.get());assertEquals(8,budget.remaining());
        assertEquals("NOT_SENT",entries().get(0).get("status"));
    }
    static final class BlockingBody extends InputStream {
        final AtomicBoolean closed=new AtomicBoolean();final CountDownLatch stop=new CountDownLatch(1);
        @Override public int read() throws IOException {
            try { if(!stop.await(5,TimeUnit.SECONDS)) throw new IOException("test stream not closed"); }
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("test interrupted");}
            return -1;
        }
        @Override public void close(){closed.set(true);stop.countDown();}
    }
}
