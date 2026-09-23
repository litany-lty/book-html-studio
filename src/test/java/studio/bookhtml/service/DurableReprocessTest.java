package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.*;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DurableReprocessTest {
    @TempDir Path data;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final String bookId = UUID.randomUUID().toString();
    final Instant now = Instant.parse("2026-09-22T00:00:00Z");
    BookStore store;
    JobService jobs;
    PageProcessor processor;
    UUID reservation;

    @BeforeEach void setup() throws Exception {
        store = spy(new BookStore(TestConfigs.config(data, "", ""), json));
        store.createBookDirectory(bookId);
        store.writeBook(new Book(bookId, "合成书", "fixture.pdf", 1, now, now, 0, 0));
        store.writePage(bookId, result("原有可读正文").page(), false);
        startService(now);
    }
    @AfterEach void close() {
        if (jobs != null) jobs.close();
        if (store != null) store.close();
    }
    void startService(Instant instant) {
        processor = mock(PageProcessor.class);
        jobs = new JobService(store, mock(BookService.class), processor, Clock.fixed(instant, ZoneOffset.UTC));
        jobs.recover();
        reservation = UUID.randomUUID();
        jobs.reserveReading(reservation, bookId);
    }
    void restart(Instant instant) {
        jobs.releaseReading(reservation);
        jobs.close();
        startService(instant);
    }
    ProcessingResult result(String content) {
        Block b = new Block("b1", "text", 0, new double[]{.1,.1,.8,.1}, "horizontal-tb", content,
                content, .99, false, false, null, "paddle", List.of("b1"), null, null);
        return new ProcessingResult(new Page(1,600,800,"READY","paddle-aistudio",List.of(b),List.of(),false,null,List.of(b)), ProcessingResult.Category.TEXT);
    }
    PageReprocessRequest request(String id) {
        return new PageReprocessRequest(store.readPage(bookId,1).revision(), id, false,"paddle-aistudio",false);
    }
    Job submit(PageReprocessRequest r) {
        return jobs.requestReprocess(reservation,bookId,1,r,"paddle-aistudio","auto",false,false);
    }
    void succeeds() throws Exception {
        when(processor.processBaseline(eq(bookId),eq(1),anyString(),anyString(),anyBoolean(),any()))
                .thenReturn(result("新识别可读正文"));
    }
    static void await(BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) { assertTrue(System.nanoTime()<until,"worker failed to settle"); Thread.sleep(10); }
    }
    void settled() throws Exception { await(()->!jobs.readingJobActive(reservation,1)); }
    PageAttempt.Journal journal() { return store.readSidecar(store.pageAttemptsPath(bookId),PageAttempt.Journal.class); }

    @Test void replaySurvivesRestartAndNewReservationWithoutNewCall() throws Exception {
        succeeds(); var request=request("replay-once"); Job first=submit(request); settled();
        UUID attempt=journal().intents().get(bookId+":1").attemptId();
        assertEquals("COMPLETED",submit(request).status());
        restart(now.plusSeconds(60));
        assertEquals(first.id(),submit(request).id());
        assertEquals("COMPLETED",submit(request).status());
        assertEquals(attempt,journal().intents().get(bookId+":1").attemptId());
        verifyNoInteractions(processor);
    }

    @Test void changedRevisionWithSameIdConflictsAfterRestart() throws Exception {
        succeeds(); var original=request("immutable-operation"); submit(original); settled();
        restart(now.plusSeconds(60));
        var changed=new PageReprocessRequest(original.expectedRevision()+1,original.clientOperationId(),false,"paddle-aistudio",false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ApiException.class,()->submit(changed)).status());
        verifyNoInteractions(processor);
    }

    @Test void receiptIsOnDiskBeforeProcessorMayRun() throws Exception {
        var request=request("opaque/../client-operation");
        when(processor.processBaseline(eq(bookId),eq(1),anyString(),anyString(),anyBoolean(),any())).thenAnswer(inv->{
            var j=journal(); assertEquals(1,j.operations().size());
            assertEquals(j.intents().get(bookId+":1").attemptId(),j.operations().values().iterator().next().attemptId());
            return result("新识别可读正文");
        });
        submit(request); settled();
        assertEquals("SUCCEEDED",journal().operations().values().iterator().next().lifecycle());
        assertFalse(Files.readString(store.pageAttemptsPath(bookId)).contains(request.clientOperationId()));
        try(var paths = Files.list(store.bookDir(bookId))) {
            assertEquals(1,paths.filter(p->p.getFileName().toString().equals("page-attempts.json")).count());
        }
    }

    @Test void expiredIdIsNotSilentlyReusedAfterRestart() throws Exception {
        succeeds(); var request=request("expired"); submit(request); settled();
        restart(now.plus(Duration.ofDays(30)));
        assertEquals(HttpStatus.GONE,assertThrows(ApiException.class,()->submit(request)).status());
        verifyNoInteractions(processor);
    }

    @Test void previousReceiptSurvivesALaterAttemptOnTheSamePage() throws Exception {
        succeeds(); var first=request("first"); Job one=submit(first); settled();
        var second=request("second"); Job two=submit(second); settled();
        assertNotEquals(one.id(),two.id(),"different attempts have different public receipt IDs");
        assertEquals(2,journal().intents().get(bookId+":1").generation());
        restart(now.plusSeconds(60));
        assertEquals(one.id(),submit(first).id());
        assertEquals("COMPLETED",submit(first).status());
        assertEquals(2,journal().operations().size());
        verifyNoInteractions(processor);
    }

    @Test void queueRejectionLeavesDurableFailedReceiptAndNoCall() throws Exception {
        var r=request("queue-rejected");
        var field=JobService.class.getDeclaredField("worker"); field.setAccessible(true);
        ((ExecutorService)field.get(jobs)).shutdown();
        assertThrows(RejectedExecutionException.class,()->submit(r));
        assertEquals("FAILED",journal().operations().values().iterator().next().lifecycle());
        restart(now.plusSeconds(1));
        assertEquals("FAILED",submit(r).status());
        verifyNoInteractions(processor);
    }

    @Test void corruptOrFutureJournalNeverBecomesAnEmptyRegistry() throws Exception {
        for(String payload:List.of("{bad-json", "{\"intents\":{},\"intents\":{}}", "{\"schemaVersion\":99,\"intents\":{}}",
                "{}", "{\"schemaVersion\":2,\"intents\":{}}", "{\"intents\":{},\"operations\":null}")) {
            Files.writeString(store.pageAttemptsPath(bookId),payload);
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->submit(request("blocked"))).status());
        }
        verifyNoInteractions(processor);
    }

    @Test void fullReceiptRegistryFailsClosedButKnownReplayStillWorks() throws Exception {
        succeeds(); var r=request("known"); submit(r); settled();
        var before=journal(); var entries=new LinkedHashMap<>(before.operations());
        var receipt=entries.values().iterator().next();
        for(int n=0;entries.size()<PageAttempt.Journal.MAX_OPERATIONS;n++) entries.put(String.format("%064x",n),receipt);
        store.writeSidecar(store.pageAttemptsPath(bookId),new PageAttempt.Journal(before.intents(),entries));
        restart(now.plusSeconds(1));
        assertEquals("COMPLETED",submit(r).status());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,assertThrows(ApiException.class,()->submit(request("new-operation"))).status());
        verifyNoInteractions(processor);
    }

    @Test void unfinishedReceiptBecomesInterruptedWithoutInferringSuccessFromReadyPage() throws Exception {
        succeeds(); var r=request("crash-window"); submit(r); settled();
        var before=journal(); var receipt=before.operations().values().iterator().next();
        var interrupted=new ReprocessOperation(receipt.bookId(),receipt.fingerprint(),receipt.attemptId(),receipt.attemptSeq(),
                receipt.response(),receipt.acceptedAt(),receipt.expiresAt(),"RUNNING");
        store.writeSidecar(store.pageAttemptsPath(bookId), new PageAttempt.Journal(
                Map.of(bookId+":1",before.intents().get(bookId+":1").withLifecycle("RUNNING")),
                Map.of(before.operations().keySet().iterator().next(),interrupted)));
        // A readable legacy page without the matching commit marker is not publication proof.
        var legacy=json.valueToTree(store.readPage(bookId,1));
        ((com.fasterxml.jackson.databind.node.ObjectNode)legacy).remove("lastCommitId");
        json.writeValue(store.pagePath(bookId,1).toFile(),legacy);
        restart(now.plusSeconds(1));
        assertEquals("INTERRUPTED",submit(r).status());
        assertEquals("READY",store.readPage(bookId,1).status());
        verifyNoInteractions(processor);
    }
    @Test void receiptWriteFailureNeverDispatchesAndDoesNotEraseExistingRecords() throws Exception {
        succeeds(); var r=request("first-preserved"); submit(r); settled();
        Path path=store.pageAttemptsPath(bookId);
        byte[] before=Files.readAllBytes(path);
        doThrow(new java.io.IOException("fixture storage failure")).when(store).writeSidecar(eq(path),any());
        clearInvocations(processor);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ApiException.class,()->submit(request("not-dispatched"))).status());
        assertArrayEquals(before,Files.readAllBytes(path));
        assertFalse(jobs.readingJobActive(reservation,1));
        verifyNoInteractions(processor);
    }

    @Test void legacyIntentOnlyJournalKeepsAttemptSequenceButDoesNotInventReceipts() throws Exception {
        var legacy=PageAttempt.register(bookId,1,store.readPage(bookId,1).revision(),"b1",List.of("JOB_BASELINE"));
        json.writeValue(store.pageAttemptsPath(bookId).toFile(),Map.of("intents",Map.of(bookId+":1",legacy)));
        assertTrue(journal().operations().isEmpty());
        restart(now.plusSeconds(1));
        succeeds(); submit(request("first-durable-operation")); settled();
        assertEquals(2,journal().intents().get(bookId+":1").generation());
        assertEquals(1,journal().operations().size());
    }

}
