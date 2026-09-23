package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import studio.bookhtml.api.*;
import studio.bookhtml.config.QwenAssistProperties;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Same behavioral contract through all three public entry adapters; no real OCR. */
class UnifiedPageEngineTest {
    enum Mode { BATCH, WINDOW, RETRY }
    @TempDir Path data;
    BookStore store; JobService jobs; PageProcessor processor; ProcessingProgressService progress;
    String book; UUID reservation;
    @BeforeEach void setup() throws Exception {
        store=spy(new BookStore(TestConfigs.config(data,"",""),new ObjectMapper().findAndRegisterModules()));
        book=UUID.randomUUID().toString(); reservation=UUID.randomUUID();
        store.createBookDirectory(book);
        Book metadata=new Book(book,"fixture","fixture.pdf",1,Instant.now(),Instant.now(),0,0);
        store.writeBook(metadata); store.writePage(book,Page.pending(1,600,800),false);
        BookService books=mock(BookService.class); when(books.get(book)).thenReturn(metadata);
        processor=mock(PageProcessor.class); progress=new ProcessingProgressService();
        jobs=new JobService(store,books,processor); jobs.setProgress(progress);
        jobs.setQwenExecutionDependencies(new QwenRequestGate(new QwenAssistProperties()),null);
    }
    @AfterEach void close() { jobs.close(); store.close(); assertNull(QwenExecutionScope.current()); }
    ProcessingResult text(String value) {
        Block block=new Block("s1","text",0,new double[]{.1,.1,.8,.2},"horizontal-tb",value,value,.98,
                false,false,null,"paddle",List.of("s1"),null,null,List.of());
        return new ProcessingResult(new Page(1,600,800,"READY","paddle-aistudio",List.of(block),List.of(),false,null,List.of(block)),ProcessingResult.Category.TEXT);
    }
    void launch(Mode mode,boolean assist) {
        JobRequest request=new JobRequest("1","paddle-aistudio","auto",false,false,assist);
        if(mode==Mode.BATCH) jobs.submit(book,request);
        else {
            jobs.reserveReading(reservation,book);
            if(mode==Mode.WINDOW) jobs.submitReserved(reservation,book,request);
            else jobs.requestReprocess(reservation,book,1,new PageReprocessRequest(store.readPage(book,1).revision(),
                    "explicit-retry",false,"paddle-aistudio",assist),"paddle-aistudio","auto",false,assist);
        }
    }
    static void await(BooleanSupplier predicate) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!predicate.getAsBoolean() && System.nanoTime()<end) Thread.sleep(5);
        assertTrue(predicate.getAsBoolean(),"bounded state transition was not observed");
    }
    void done(Mode mode) throws Exception {
        if(mode==Mode.BATCH) await(()->Set.of("COMPLETED","COMPLETED_WITH_ERRORS","FAILED","CANCELLED").contains(store.readJob(book).status()));
        else await(()->!jobs.readingJobActive(reservation,1));
    }
    void outcome(String expected) {
        assertEquals(expected,store.pageAttempt(book,1).lifecycle());
        assertEquals(expected,progress.latest(book,1).lifecycle());
        assertEquals(store.readPage(book,1).revision().intValue(),progress.latest(book,1).publishedRevision());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void readableBaselinePrecedesEnhancementWithOneIdentityAndBudget(Mode mode) throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<QwenExecutionScope.Value> first=new AtomicReference<>();
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenAnswer(inv->{
            first.set(QwenExecutionScope.current()); assertNotNull(first.get());
            assertTrue(first.get().budget().reserve(2)); return text("基线内容仍可阅读");
        });
        when(processor.enrichBaseline(eq(book),eq(1),any(),anyString(),anyString(),any())).thenAnswer(inv->{
            assertSame(first.get(),QwenExecutionScope.current()); assertEquals(6,first.get().budget().remaining());
            entered.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS));
            return new PageProcessor.EnrichResult(text("增强内容继续阅读").page().blocks(),"paddle-aistudio+qwen",List.of());
        });
        try {
            launch(mode,true); assertTrue(entered.await(3,TimeUnit.SECONDS));
            Page baseline=store.readPage(book,1);
            assertEquals("READY",baseline.status()); assertEquals(1,baseline.revision());
            assertEquals("基线内容仍可阅读",baseline.blocks().get(0).original());
            assertEquals(1,progress.latest(book,1).publishedRevision());
            assertEquals(store.pageAttempt(book,1).attemptId(),first.get().executionId());
            assertEquals(store.pageAttempt(book,1).generation(),first.get().attemptSeq());
        } finally { release.countDown(); }
        done(mode); outcome("SUCCEEDED");
        assertEquals(2,store.readPage(book,1).revision());
        assertEquals("基线内容仍可阅读",store.readPage(book,1).sourceRecords().get(0).original());
        verify(processor,never()).process(anyString(),anyInt(),anyString(),anyString(),anyBoolean(),anyBoolean(),any());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void enhancementFailureKeepsBaselineAndSettlesPartial(Mode mode) throws Exception {
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("已经提交的原始内容"));
        when(processor.enrichBaseline(eq(book),eq(1),any(),anyString(),anyString(),any())).thenThrow(new IOException("synthetic failure"));
        launch(mode,true); done(mode); outcome("PARTIAL");
        assertEquals("已经提交的原始内容",store.readPage(book,1).blocks().get(0).original());
        assertEquals(1,store.readPage(book,1).revision());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void partialExtractionDoesNotStartOptionalEnhancement(Mode mode) throws Exception {
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any()))
                .thenReturn(new ProcessingResult(text("只恢复这一段").page(),ProcessingResult.Category.TEXT_PARTIAL));
        launch(mode,true); done(mode); outcome("PARTIAL");
        verify(processor,never()).enrichBaseline(anyString(),anyInt(),any(),anyString(),anyString(),any());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void cancelledEnhancementKeepsExactPublishedBytes(Mode mode) throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("取消后仍可阅读"));
        when(processor.enrichBaseline(eq(book),eq(1),any(),anyString(),anyString(),any())).thenAnswer(inv->{
            entered.countDown(); release.await(5,TimeUnit.SECONDS); throw new CancelledException();
        });
        try {
            launch(mode,true); assertTrue(entered.await(3,TimeUnit.SECONDS));
            byte[] before=Files.readAllBytes(store.pagePath(book,1));
            if(mode==Mode.BATCH)jobs.cancel(book); else jobs.cancelReadingPage(reservation,1);
            release.countDown(); done(mode); outcome("CANCELLED");
            assertArrayEquals(before,Files.readAllBytes(store.pagePath(book,1)));
        } finally { release.countDown(); }
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void journalSettlementFailureDoesNotBecomeAFalseFailureOrSuccess(Mode mode) throws Exception {
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("已存正文无需重复调用"));
        doThrow(new IOException("synthetic finalizer failure")).when(store).finishPageAttempt(any(),eq("SUCCEEDED"));
        launch(mode,false); done(mode);
        assertEquals("UNKNOWN",progress.latest(book,1).lifecycle());
        assertEquals("RUNNING",store.pageAttempt(book,1).lifecycle(),"cleanup must not overwrite uncertainty with fallback FAILED");
        verify(store,times(1)).finishPageAttempt(any(),anyString());
        assertEquals("已存正文无需重复调用",store.readPage(book,1).blocks().get(0).original());
        store.recoverPagePublications(); store.reconcilePageAttempts(book);
        assertEquals("SUCCEEDED",store.pageAttempt(book,1).lifecycle(),"recovery proves publication by commit ID, never by resending");
        verify(processor,times(1)).processBaseline(anyString(),anyInt(),anyString(),anyString(),anyBoolean(),any());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void failedGroupsStayPartialEvenWhenTheHelperClaimsComplete(Mode mode) throws Exception {
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("分块保真正文"));
        when(processor.enrichBaseline(eq(book),eq(1),any(),anyString(),anyString(),any())).thenAnswer(inv->{
            UUID id=store.pageAttempt(book,1).attemptId();
            progress.plan(book,1,id,"REVIEW_CHUNK",2);
            progress.unitDone(book,1,id,"one","SUCCEEDED"); progress.unitDone(book,1,id,"two","FAILED");
            return new PageProcessor.EnrichResult(text("分块保真正文").page().blocks(),"paddle-aistudio+qwen",List.of(),true);
        });
        launch(mode,true); done(mode); outcome("PARTIAL");
        assertEquals(2,store.readPage(book,1).revision());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void confirmedBlankHasNoOptionalCloudCalls(Mode mode) throws Exception {
        Page blank=new Page(1,600,800,"READY","paddle-aistudio+blank",List.of(),List.of("[BLANK_EVIDENCE_V2] fixture"),false,null,List.of());
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any()))
                .thenReturn(new ProcessingResult(blank,ProcessingResult.Category.BLANK_CONFIRMED));
        launch(mode,true); done(mode); outcome("SUCCEEDED");
        verify(processor,never()).enrichBaseline(anyString(),anyInt(),any(),anyString(),anyString(),any());
    }

    @ParameterizedTest @EnumSource(Mode.class)
    void progressReducerFailureCannotSkipDurableSettlement(Mode mode) throws Exception {
        progress=spy(progress); jobs.setProgress(progress);
        doThrow(new IllegalStateException("fixture projection fault")).when(progress)
                .unitDone(eq(book),eq(1),any(),anyString(),anyString());
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("已发布正文保留"));
        launch(mode,false); done(mode); outcome("PARTIAL");
        verify(store,times(1)).finishPageAttempt(any(),anyString());
        assertEquals("已发布正文保留",store.readPage(book,1).blocks().get(0).original());
    }
    @ParameterizedTest @EnumSource(Mode.class)
    void candidateArchiveRuntimeFailureDoesNotCauseASecondFinalization(Mode mode) throws Exception {
        byte[] original=Files.readAllBytes(store.pagePath(book,1));
        when(processor.processBaseline(eq(book),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(text("未能发布的候选"));
        doThrow(new IOException("fixture publication fault")).when(store)
                .commitPage(eq(book),any(),anyInt(),eq(CommitActor.JOB),anyString(),eq(CommitOp.JOB_BASELINE));
        doThrow(new IllegalStateException("fixture candidate fault")).when(store).writeCandidate(eq(book),any());
        launch(mode,false); done(mode); outcome("FAILED");
        verify(store,times(1)).finishPageAttempt(any(),anyString());
        assertArrayEquals(original,Files.readAllBytes(store.pagePath(book,1)));
    }
}
