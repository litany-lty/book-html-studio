package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.api.PageUpdateRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JobPhase1SafetyTest {
    @TempDir Path temp;
    private ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}

    private static Block textBlock(String id,String text){return new Block(id,"text",0,new double[]{.1,.1,.1,.2},"vertical-rl",text,text,null,false,false,null,"paddle:R",List.of(id),null,null);}
    private static Page pageWithText(String text){Block b=textBlock("s",text);return new Page(1,600,800,"READY","paddle",List.of(b),List.of(),false,null,List.of(b));}
    private static Page emptyPage(){return new Page(1,600,800,"READY","paddle",List.of(),List.of(),false,null,List.of());}

    @Test void zeroResultIsRegression(){Page old=pageWithText("完整來源文字完整來源文字");assertTrue(JobService.isSignificantRegression(old,emptyPage()));assertTrue(JobService.isEmptyResult(emptyPage()));assertFalse(JobService.isEmptyResult(old));}
    @Test void emptyNewNeverBecomesReadyEmpty(){Page old=pageWithText("甲乙丙丁");Page merged=emptyPage();assertTrue(JobService.isSignificantRegression(old,merged)||JobService.isEmptyResult(merged));}
    @Test void recoveredTextIsNotEmptyWhenRawSourceContainsNoCharacters(){
        Block emptyRaw=new Block("raw","figure",0,new double[]{.1,.1,.8,.8},"horizontal-tb","","",null,true,false,null,"paddle",List.of("raw"),null,null);
        Block recovered=new Block("recovered","text",0,new double[]{.1,.1,.8,.8},"horizontal-tb","第一章\n第二章","第一章\n第二章",null,true,false,null,"qwen-toc-recovery",List.of("raw"),"目录区域恢复",null);
        Page page=new Page(4,600,800,"READY","paddle+qwen-toc-recovery",List.of(recovered),List.of(),false,null,List.of(emptyRaw));
        assertEquals(0,JobService.sourceChars(page));
        assertFalse(JobService.isEmptyResult(page));
    }
    @Test void emptyFigureInBothResultAndRawSourceRemainsEmpty(){
        Block empty=new Block("empty","figure",0,new double[]{.1,.1,.8,.8},"horizontal-tb","","",null,true,false,null,"paddle",List.of("empty"),null,null);
        Page page=new Page(7,600,800,"READY","paddle",List.of(empty),List.of(),false,null,List.of(empty));
        assertTrue(JobService.isEmptyResult(page));
    }

    @Test void failedKeepsOldReadyReadable()throws Exception{
        // T14：强制重做已 READY 页且处理失败——旧正文保留可读，错误可见，且确实经过 processor
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String id="33333333-3333-3333-3333-333333333333";
        store.createBookDirectory(id);
        Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);
        store.writeBook(book);
        store.writePage(id,pageWithText("甲乙丙丁戊己"),false);
        BookService books=mock(BookService.class);when(books.get(id)).thenReturn(book);
        PageProcessor processor=mock(PageProcessor.class);
        when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenThrow(new RuntimeException("boom"));
        JobService jobs=new JobService(store,books,processor);
        try{
            jobs.submit(id,new JobRequest("1","local","auto",false,true,false));
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline){String s=store.readJob(id).status();if(s.startsWith("COMPLETED")||"FAILED".equals(s))break;Thread.sleep(50);}
            Page after=store.readPage(id,1);
            assertEquals("READY",after.status());
            assertTrue(after.blocks().stream().anyMatch(b->"甲乙丙丁戊己".equals(b.original())));
            assertTrue(after.warnings().stream().anyMatch(w->w.contains("boom")||w.contains("处理失败")));
            verify(processor).process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any());
        }finally{jobs.close();}
    }

    @Test void zeroResultDoesNotOverwriteReady()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String id="44444444-4444-4444-4444-444444444444";
        store.createBookDirectory(id);
        Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);
        store.writeBook(book);
        store.writePage(id,pageWithText("完整來源文字完整來源文字完整"),false);
        BookService books=mock(BookService.class);when(books.get(id)).thenReturn(book);
        PageProcessor processor=mock(PageProcessor.class);
        when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenAnswer(inv->new ProcessingResult(emptyPage(),ProcessingResult.Category.TEXT));
        JobService jobs=new JobService(store,books,processor);
        try{
            jobs.submit(id,new JobRequest("1","local","auto",false,true,false));
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline){String s=store.readJob(id).status();if(s.startsWith("COMPLETED")||"FAILED".equals(s))break;Thread.sleep(50);}
            Page after=store.readPage(id,1);
            assertEquals("READY",after.status());
            assertFalse(JobService.isEmptyResult(after));
            assertNotNull(store.readCandidate(id,1));
        }finally{jobs.close();}
    }

    @Test void cancelThenRetryOldWorkerCannotOverwriteNewJob()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String id="55555555-5555-5555-5555-555555555555";
        store.createBookDirectory(id);
        Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);
        store.writeBook(book);
        store.writePage(id,Page.pending(1,600,800),false);
        BookService books=mock(BookService.class);when(books.get(id)).thenReturn(book);
        PageProcessor processor=mock(PageProcessor.class);
        CountDownLatch entered=new CountDownLatch(1);
        CountDownLatch releaseWorker=new CountDownLatch(1);
        when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenAnswer(inv->{
            entered.countDown();
            // Hold the physical worker until the assertion. An interruptible sleep may
            // end before submit() executes, in which case accepting a retry is correct.
            boolean interrupted=false;
            try {
                while(releaseWorker.getCount()>0){
                    try { assertTrue(releaseWorker.await(5,TimeUnit.SECONDS),"old worker must be explicitly released"); }
                    catch(InterruptedException ignored){ interrupted=true; }
                }
            } finally { if(interrupted)Thread.currentThread().interrupt(); }
            throw new CancelledException();
        });
        JobService jobs=new JobService(store,books,processor);
        try{
            Job first=jobs.submit(id,new JobRequest("1","local","auto",false,false,false));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Job cancelling=jobs.cancel(id);
            assertEquals("CANCELLING",cancelling.status());
            ApiException conflict=assertThrows(ApiException.class,
                    ()->jobs.submit(id,new JobRequest("1","local","auto",false,false,false)));
            assertEquals(HttpStatus.CONFLICT,conflict.status());
            assertEquals(first.id(),store.readJob(id).id());
            releaseWorker.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!"CANCELLED".equals(store.readJob(id).status()) && System.nanoTime()<deadline)Thread.sleep(10);
            assertEquals("CANCELLED",store.readJob(id).status());
            assertEquals(first.id(),store.readJob(id).id());
            reset(processor);
            Block done=textBlock("s","新任务结果");
            Page ready=new Page(1,600,800,"READY","local",List.of(done),List.of(),false,null,List.of(done));
            when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any()))
                    .thenReturn(new ProcessingResult(ready,ProcessingResult.Category.TEXT));
            Job second=null;
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            // The persistent terminal status may precede final admission/lease cleanup.
            while(second==null){
                try{second=jobs.submit(id,new JobRequest("1","local","auto",false,false,false));}
                catch(ApiException draining){
                    assertEquals(HttpStatus.CONFLICT,draining.status());
                    assertTrue(System.nanoTime()<deadline,"cancelled worker failed to drain");
                    Thread.sleep(10);
                }
            }
            assertNotEquals(first.id(),second.id());
            deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline){String status=store.readJob(id).status();if(status.startsWith("COMPLETED")||"FAILED".equals(status))break;Thread.sleep(10);}
            assertEquals("COMPLETED",store.readJob(id).status());
            assertEquals(second.id(),store.readJob(id).id());
            assertEquals("新任务结果",store.readPage(id,1).blocks().get(0).original());
        }finally{releaseWorker.countDown();jobs.close();}
    }

    @Test void manualSaveConflict409AndRevisionIncrement()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String id="66666666-6666-6666-6666-666666666666";
        store.createBookDirectory(id);
        store.writeBook(new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0));
        store.writePage(id,pageWithText("原文"),false);
        PdfService pdf=mock(PdfService.class);
        BookService service=new BookService(store,pdf,TestConfigs.config(temp,"",""));
        Page before=store.readPage(id,1);
        int rev=BookStore.revisionOrZero(before);
        Block b=textBlock("s","手工修改");
        Page saved=service.update(id,1,new PageUpdateRequest(List.of(b),true,rev));
        assertEquals(rev+1,BookStore.revisionOrZero(saved));
        assertEquals("manual",saved.provider());
        // 旧版本重试应 409
        try{service.update(id,1,new PageUpdateRequest(List.of(b),true,rev));fail("应返回 409");}
        catch(ApiException e){assertEquals(HttpStatus.CONFLICT,e.status());}
        // 回退到旧版本（携带当前版本，成功生成新 revision）
        List<Integer> revs=service.revisions(id,1);
        assertTrue(revs.contains(rev));
        Page reverted=service.revert(id,1,rev,rev+1);
        assertTrue(reverted.warnings().stream().anyMatch(w->w.contains("回退")));
    }

    @Test void recoverPreservesJobParamsAndDoesNotFakeReady()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String id="77777777-7777-7777-7777-777777777777";
        store.createBookDirectory(id);
        store.writeBook(new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0));
        store.writePage(id,new Page(1,600,800,"PROCESSING","paddle",List.of(),List.of(),false,null,List.of()),false);
        store.writeJob(id,new Job("j","RUNNING",0,1,1,null,List.of(),Instant.now(),List.of(1),"paddle","auto",true,false,true,"fp"));
        store.recoverInterruptedJobs();
        Job after=store.readJob(id);
        assertEquals("INTERRUPTED",after.status());
        assertEquals(List.of(1),after.pages());
        assertEquals("paddle",after.provider());
        assertEquals("j",after.id());
        assertEquals("PENDING",store.readPage(id,1).status());
    }
}
