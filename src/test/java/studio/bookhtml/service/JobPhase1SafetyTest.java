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
            jobs.submit(id,new JobRequest("1","local","auto",false,false,false));
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline){String s=store.readJob(id).status();if(s.startsWith("COMPLETED")||"FAILED".equals(s))break;Thread.sleep(50);}
            Page after=store.readPage(id,1);
            assertEquals("READY",after.status());
            assertTrue(after.blocks().stream().anyMatch(b->"甲乙丙丁戊己".equals(b.original())));
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
        when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenAnswer(inv->emptyPage());
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
        when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenAnswer(inv->{
            entered.countDown();
            try{Thread.sleep(30_000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}
            Block b=textBlock("s","新任务结果");
            return new Page(1,600,800,"READY","local",List.of(b),List.of(),false,null,List.of(b));
        });
        JobService jobs=new JobService(store,books,processor);
        try{
            Job first=jobs.submit(id,new JobRequest("1","local","auto",false,false,false));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Job cancelling=jobs.cancel(id);
            assertEquals("CANCELLING",cancelling.status());
            // 取消后立即重试应被拒绝（旧 worker 尚未退出）
            try{jobs.submit(id,new JobRequest("1","local","auto",false,false,false));fail("应返回 409");}
            catch(ApiException e){assertEquals(HttpStatus.CONFLICT,e.status());}
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline){if("CANCELLED".equals(store.readJob(id).status()))break;Thread.sleep(50);}
            assertEquals("CANCELLED",store.readJob(id).status());
            String cancelledId=store.readJob(id).id();
            assertEquals(first.id(),cancelledId);
            // 取消完成后可重试，且旧 worker 不再回写新 job：第二轮 mock 直接成功
            reset(processor);
            when(books.get(id)).thenReturn(book);
            Block done=textBlock("s","新任务结果");
            Page ready=new Page(1,600,800,"READY","local",List.of(done),List.of(),false,null,List.of(done));
            when(processor.process(eq(id),eq(1),anyString(),anyString(),anyBoolean(),anyBoolean(),any())).thenReturn(ready);
            Job second=jobs.submit(id,new JobRequest("1","local","auto",false,false,false));
            assertNotEquals(first.id(),second.id());
            deadline=System.currentTimeMillis()+5000;
            while(System.currentTimeMillis()<deadline){String s=store.readJob(id).status();if(s.startsWith("COMPLETED")||"FAILED".equals(s))break;Thread.sleep(50);}
            assertEquals(second.id(),store.readJob(id).id());
        }finally{jobs.close();}
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
