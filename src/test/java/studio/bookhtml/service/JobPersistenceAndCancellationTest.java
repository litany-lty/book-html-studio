package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
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

class JobPersistenceAndCancellationTest {
    @TempDir Path temp;
    private ObjectMapper mapper(){return new ObjectMapper().findAndRegisterModules();}
    @Test void restartMarksRunningJobInterruptedAndUnlocksPage()throws Exception{BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());String id="11111111-1111-1111-1111-111111111111";store.createBookDirectory(id);store.writeBook(new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0));store.writePage(id,new Page(1,600,800,"PROCESSING","qwen",List.of(),List.of(),false,null,List.of()),false);store.writeJob(id,new Job("j","RUNNING",0,1,1,null,List.of(),Instant.now()));store.recoverInterruptedJobs();assertEquals("INTERRUPTED",store.readJob(id).status());Page page=store.readPage(id,1);assertEquals("PENDING",page.status());assertTrue(page.warnings().get(0).contains("中断"));}
    @Test void cancellationInterruptsWorkerAndPersistsCancelled()throws Exception{BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());String id="22222222-2222-2222-2222-222222222222";store.createBookDirectory(id);Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);store.writeBook(book);store.writePage(id,Page.pending(1,600,800),false);BookService books=mock(BookService.class);when(books.get(id)).thenReturn(book);PageProcessor processor=mock(PageProcessor.class);CountDownLatch entered=new CountDownLatch(1);when(processor.processBaseline(eq(id),eq(1),anyString(),anyString(),anyBoolean(),any())).thenAnswer(inv->{entered.countDown();try{Thread.sleep(30_000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CancelledException();}throw new AssertionError();});JobService jobs=new JobService(store,books,processor);try{jobs.submit(id,new JobRequest("1","local","auto",false,false,false));assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(1,store.readJob(id).currentPage());Job cancelling=jobs.cancel(id);assertEquals("CANCELLING",cancelling.status());long deadline=System.currentTimeMillis()+3000;String status="";while(System.currentTimeMillis()<deadline){status=store.readJob(id).status();if("CANCELLED".equals(status))break;Thread.sleep(50);}assertEquals("CANCELLED",store.readJob(id).status());}finally{jobs.close();}}
    @Test void cancelledWorkerMustExitBeforeAnotherBookStartsAndCannotFinishLastPage()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());
        String first="33333333-3333-3333-3333-333333333333",second="44444444-4444-4444-4444-444444444444";
        BookService books=mock(BookService.class);
        for(String id:List.of(first,second)){store.createBookDirectory(id);Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);store.writeBook(book);store.writePage(id,Page.pending(1,600,800),false);when(books.get(id)).thenReturn(book);}
        PageProcessor processor=mock(PageProcessor.class);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(processor.processBaseline(eq(first),eq(1),anyString(),anyString(),anyBoolean(),any())).thenAnswer(inv->{
            entered.countDown();boolean done=false;while(!done){try{done=release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){/* 模拟暂时无法响应中断的处理器 */}}
            return new ProcessingResult(pageWithText("识别结果",false),ProcessingResult.Category.TEXT);
        });
        when(processor.processBaseline(eq(second),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(
                new ProcessingResult(pageWithText("第二本识别结果",false),ProcessingResult.Category.TEXT));
        JobService jobs=new JobService(store,books,processor);JobRequest request=new JobRequest("1","local","auto",false,false,false);
        try{
            jobs.submit(first,request);assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("CANCELLING",jobs.cancel(first).status());
            ApiException conflict=assertThrows(ApiException.class,()->jobs.submit(second,request));
            assertEquals(HttpStatus.CONFLICT,conflict.status());
            release.countDown();
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline&&!"CANCELLED".equals(store.readJob(first).status()))Thread.sleep(20);
            assertEquals("CANCELLED",store.readJob(first).status());
            assertEquals("PENDING",store.readPage(first,1).status());
            long freeDeadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<freeDeadline){try{jobs.submit(second,request);break;}catch(ApiException e){assertEquals(HttpStatus.CONFLICT,e.status());Thread.sleep(20);}}
            assertNotEquals("IDLE",store.readJob(second).status());
            long doneDeadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<doneDeadline&&!"COMPLETED".equals(store.readJob(second).status()))Thread.sleep(20);
            assertEquals("COMPLETED",store.readJob(second).status());
        }finally{release.countDown();jobs.close();store.close();}
    }
    @Test void queuedCancellationIsTerminalWithoutRunningWorker()throws Exception{
        BookStore store=new BookStore(TestConfigs.config(temp,"",""),mapper());String id="55555555-5555-5555-5555-555555555555";
        store.createBookDirectory(id);Book book=new Book(id,"t","t.pdf",1,Instant.now(),Instant.now(),0,0);store.writeBook(book);store.writePage(id,Page.pending(1,600,800),false);
        BookService books=mock(BookService.class);when(books.get(id)).thenReturn(book);PageProcessor processor=mock(PageProcessor.class);
        JobService jobs=new JobService(store,books,processor);JobRequest request=new JobRequest("1","local","auto",false,false,false);
        try{
            synchronized(jobs){assertEquals("QUEUED",jobs.submit(id,request).status());assertEquals("CANCELLED",jobs.cancel(id).status());}
            Thread.sleep(50);
            assertEquals("CANCELLED",store.readJob(id).status());verifyNoInteractions(processor);
            when(processor.processBaseline(eq(id),eq(1),anyString(),anyString(),anyBoolean(),any())).thenReturn(
                    new ProcessingResult(pageWithText("再次识别结果",false),ProcessingResult.Category.TEXT));
            assertNotEquals("CANCELLED",jobs.submit(id,request).status());
            long deadline=System.currentTimeMillis()+3000;
            while(System.currentTimeMillis()<deadline&&!"COMPLETED".equals(store.readJob(id).status()))Thread.sleep(20);
            assertEquals("COMPLETED",store.readJob(id).status());
        }finally{jobs.close();store.close();}
    }
    @Test void controlledOcrMessageIsPersistedWithoutUnknownExceptionDetails(){assertEquals("Qwen OCR 请求频率受限，请稍后重试",JobService.safeDetail(new OcrException("Qwen OCR 请求频率受限，请稍后重试")));assertNull(JobService.safeDetail(new RuntimeException("sensitive-body")));}
    @Test void forceRejectsLargeSourceRegressionAndPrefersUnreviewedOriginalSnapshot(){Page current=pageWithText("短頁",false),original=pageWithText("完整來源文字完整來源文字",false),reviewed=pageWithText("人工校對",true),regressed=pageWithText("少",false);assertSame(original,JobService.strongestBaseline(current,original));assertSame(reviewed,JobService.strongestBaseline(reviewed,original));assertTrue(JobService.isSignificantRegression(original,regressed));assertFalse(JobService.isSignificantRegression(original,pageWithText("完整來源文字完整",false)));}
    @Test void retryKeepsOldUnresolvedIssueWhenCurrentIssueDoesNotOverlap(){Page old=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("old",0,1,false))),current=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("new",2,3,false)));Page merged=JobService.mergeUnresolvedIssues(old,current);assertEquals(List.of("old","new"),merged.blocks().get(0).issues().stream().map(ContentIssue::id).toList());assertSame(current.sourceRecords(),merged.sourceRecords());}
    @Test void retryPrefersCurrentIssueOnOverlap(){Page old=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("old",0,2,false))),current=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("new",1,3,false)));Page merged=JobService.mergeUnresolvedIssues(old,current);assertEquals(List.of("new"),merged.blocks().get(0).issues().stream().map(ContentIssue::id).toList());}
    @Test void retryDoesNotMoveIssueAcrossChangedDisplaySimplifiedOrPaddleSource(){Page old=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("old",0,1,false)));Page changedDisplay=paddlePage("paddle+qwen-assist","戊乙丙丁","戊乙丙丁",List.of());Page changedSimplified=paddlePageWithBlockSource("paddle+qwen-assist","甲乙丙丁","简体已变","甲乙丙丁","paddle:R",List.of());Page changedSource=paddlePage("paddle+qwen-assist","甲乙丙丁","來源已變",List.of());assertTrue(JobService.mergeUnresolvedIssues(old,changedDisplay).blocks().get(0).issues().isEmpty());assertTrue(JobService.mergeUnresolvedIssues(old,changedSimplified).blocks().get(0).issues().isEmpty());assertTrue(JobService.mergeUnresolvedIssues(old,changedSource).blocks().get(0).issues().isEmpty());}
    @Test void retryDoesNotRestoreManualOrResolvedIssue(){Page current=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of());Page manual=paddlePage("manual","甲乙丙丁","甲乙丙丁",List.of(issue("manual",0,1,false)));Page manualBlock=paddlePageWithBlockSource("paddle+qwen-assist","甲乙丙丁","甲乙丙丁","甲乙丙丁","manual",List.of(issue("manual-block",0,1,false)));Page resolved=paddlePage("paddle+qwen-assist","甲乙丙丁","甲乙丙丁",List.of(issue("resolved",0,1,true)));assertTrue(JobService.mergeUnresolvedIssues(manual,current).blocks().get(0).issues().isEmpty());assertTrue(JobService.mergeUnresolvedIssues(manualBlock,current).blocks().get(0).issues().isEmpty());assertTrue(JobService.mergeUnresolvedIssues(resolved,current).blocks().get(0).issues().isEmpty());}
    private static Page pageWithText(String text,boolean reviewed){Block source=new Block("s","text",0,new double[]{.1,.1,.1,.2},"vertical-rl",text,text,null,true,reviewed,null,"qwen",List.of("s"),null,null);return new Page(1,600,800,"READY","qwen",List.of(source),List.of(),reviewed,null,List.of(source));}
    private static Page paddlePage(String provider,String displayText,String sourceText,List<ContentIssue>issues){return paddlePageWithBlockSource(provider,displayText,displayText,sourceText,"paddle:R",issues);}
    private static Page paddlePageWithBlockSource(String provider,String displayText,String simplifiedText,String sourceText,String blockSource,List<ContentIssue>issues){Block block=new Block("paddle-line","text",0,new double[]{.1,.1,.1,.2},"vertical-rl",displayText,simplifiedText,null,!issues.isEmpty(),false,null,blockSource,List.of("paddle-line"),null,null,issues);Block source=new Block("paddle-line","text",0,new double[]{.1,.1,.1,.2},"vertical-rl",sourceText,sourceText,null,true,false,null,"paddle:R",List.of("paddle-line"),null,null);return new Page(1,600,800,"READY",provider,List.of(block),List.of(),false,null,List.of(source));}
    private static ContentIssue issue(String id,int start,int end,boolean resolved){return new ContentIssue(id,"suspected",start,end,start,end,"图像核对",resolved,resolved?"已确认":null,null);}
}
