package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.config.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FinalGapRegressionTest {
    @TempDir Path data;
    final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final String id=UUID.randomUUID().toString();
    BookStore store; JobService jobs;
    @BeforeEach void setup() throws Exception {
        store=new BookStore(TestConfigs.config(data,"",""),json);
        store.createBookDirectory(id);
        store.writeBook(new Book(id,"合成验收书","fixture.pdf",2,Instant.now(),Instant.now(),0,0));
        store.writePage(id,page(1,"第一页原字"),false);
        store.writePage(id,page(2,"第二页原字"),false);
    }
    @AfterEach void close() { if(jobs!=null) jobs.close(); store.close(); }
    Page page(int n,String text) {
        Block b=new Block("s"+n,"text",0,new double[]{.1,.1,.8,.1},"horizontal-tb",text,text,
                .99,false,false,null,"paddle",List.of("s"+n),null,null);
        return new Page(n,600,800,"READY","paddle",List.of(b),List.of(),false,null,List.of(b));
    }
    UsageLedger ledger() throws Exception {
        var app=TestConfigs.config(data,"","");
        var settings=new SettingsService(app,new PaddleAiStudioProperties("",null,null,10,10,1),
                new QwenAssistProperties(),new DecisionProperties(),json);
        return new UsageLedger(store,settings,json);
    }
    @Test void orphanStagingDoesNotEraseUnknownOrBlockNewRecords() throws Exception {
        var ledger=ledger();
        try(var scope=UsageContext.open(id,1,"QWEN_TEXT_REVIEW")) {
            String unknown=ledger.start("qwen","qwen-test");
            Path directory=store.bookDir(id).resolve("usage");
            byte[] before=Files.readAllBytes(directory.resolve(unknown+".json"));
            Files.writeString(directory.resolve("entry-123456.tmp"),"incomplete staged bytes");
            String fresh=ledger.prepare("qwen","qwen-test");
            assertNotEquals(unknown,fresh);
            assertArrayEquals(before,Files.readAllBytes(directory.resolve(unknown+".json")));
            try(var recovered=Files.list(store.bookDir(id).resolve("usage-recovery"))) {
                assertEquals(1,recovered.count());
            }
            assertFalse(Files.exists(directory.resolve("entry-123456.tmp")));
            Files.writeString(store.bookDir(id).resolve("usage-staging/entry-999.tmp"),"partial new staging");
            assertDoesNotThrow(()->ledger.view(id,0,10));
            Files.writeString(directory.resolve("unexpected.json"),"{}");
            assertThrows(java.io.IOException.class,()->ledger.prepare("qwen","qwen-test"));
        }
    }
    @Test void frozenContextRemainsIdenticalWhenNeighborChangesButNextAttemptRefreshes() throws Exception {
        var contexts=new BookContextService(store,json);
        var gate=new QwenRequestGate(new QwenAssistProperties());
        String first;
        try(var usage=UsageContext.open(id,1,"QWEN_TEXT_REVIEW");var scope=QwenExecutionScope.open(id,1,gate,true)) {
            first=contexts.current();
            assertTrue(first.contains("第二页原字"));
            String hash=QwenExecutionScope.current().context().hash();
            store.writePage(id,page(2,"第二页新证据"),false);
            assertEquals(first,contexts.current());
            var executor=Executors.newSingleThreadExecutor();
            var execution=QwenExecutionScope.current();
            try {
                assertEquals(first,executor.submit(()-> {
                    try(var child=QwenExecutionScope.attach(execution);var u=UsageContext.open(id,1,"QWEN_TEXT_REVIEW")) {
                        return contexts.current();
                    }
                }).get(3,TimeUnit.SECONDS));
            } finally { executor.shutdownNow(); }
            assertEquals(hash,execution.context().hash());
        }
        try(var usage=UsageContext.open(id,1,"QWEN_TEXT_REVIEW");var next=QwenExecutionScope.open(id,1,gate,true)) {
            assertNotEquals(first,contexts.current()); assertTrue(contexts.current().contains("第二页新证据"));
        }
    }
    @Test void persistedPartialAndUnknownRemainVisibleWithoutInventingProgress() throws Exception {
        var attempt=PageAttempt.register(id,1,0,"source",List.of("JOB_BASELINE"));
        for(String state:List.of("PARTIAL","UNKNOWN","INTERRUPTED")) {
            store.writeSidecar(store.pageAttemptsPath(id),new PageAttempt.Journal(Map.of(id+":1",attempt.withLifecycle(state))));
            var projection=new ProcessingProgressService(); projection.setStore(store);
            var snapshot=projection.latest(id,1);
            assertEquals(state,snapshot.lifecycle()); assertEquals("RECOVERED",snapshot.stage());
            assertEquals(attempt.attemptId(),snapshot.attemptId()); assertTrue(snapshot.canRead());
            assertFalse(snapshot.canStop());
            if(state.equals("UNKNOWN")) assertFalse(snapshot.canRetry());
        }
    }
    @Test void batchCannotReauthorizeAPageChangedAfterInitialConfirmation() throws Exception {
        var processor=mock(PageProcessor.class);
        var books=mock(BookService.class);when(books.get(id)).thenReturn(store.readBook(id));
        jobs=new JobService(store,books,processor);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(processor.processBaseline(eq(id),eq(1),anyString(),anyString(),anyBoolean(),any())).thenAnswer(call->{
            entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));
            return new ProcessingResult(page(1,"第一页新版内容"),ProcessingResult.Category.TEXT);
        });
        try {
            jobs.submit(id,new studio.bookhtml.api.JobRequest("1-2","local","auto",false,true,false));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            // Fault injection of a queued-page revision change; ordinary editing may additionally refuse the batch lock.
            store.writePage(id,page(2,"排队后新保存内容"),false);
            byte[] changed=Files.readAllBytes(store.pagePath(id,2));
            release.countDown();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!store.readJob(id).status().startsWith("COMPLETED")) {
                assertTrue(System.nanoTime()<until);Thread.sleep(10);
            }
            assertArrayEquals(changed,Files.readAllBytes(store.pagePath(id,2)));
            verify(processor,never()).processBaseline(eq(id),eq(2),anyString(),anyString(),anyBoolean(),any());
            assertTrue(store.readJob(id).errors().stream().anyMatch(s->s.contains("确认后已更新")));
        } finally { release.countDown(); }
    }
    @Test void conditionalProgressChangesOnRealEventsAndAcceptsUnchangedState() throws Exception {
        var books=mock(BookService.class);when(books.page(id,1)).thenReturn(store.readPage(id,1));
        var progress=new ProcessingProgressService();
        var controller=new studio.bookhtml.api.ReaderController(store,books,progress);
        var first=controller.conditionalProgress(id,1,null);
        assertEquals(200,first.getStatusCode().value());
        assertEquals(304,controller.conditionalProgress(id,1,first.getHeaders().getETag()).getStatusCode().value());
        var attempt=progress.begin(id,1,0);progress.stage(id,1,attempt,"OCR");
        var changed=controller.conditionalProgress(id,1,first.getHeaders().getETag());
        assertEquals(200,changed.getStatusCode().value());
        assertNotEquals(first.getHeaders().getETag(),changed.getHeaders().getETag());
        assertEquals(304,controller.conditionalProgress(id,1,"W/"+changed.getHeaders().getETag()).getStatusCode().value());
    }
}
