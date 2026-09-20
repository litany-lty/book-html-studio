package studio.bookhtml.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.PageConflictException;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class JobService {
    private final BookStore store;private final BookService books;private final PageProcessor processor;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"book-html-worker");t.setDaemon(true);return t;});
    private Running active;
    public JobService(BookStore store,BookService books,PageProcessor processor){this.store=store;this.books=books;this.processor=processor;}
    @PostConstruct void recover(){store.recoverInterruptedJobs();}
    @PreDestroy void close(){worker.shutdownNow();}
    public synchronized Job submit(String bookId,JobRequest request){Book book=books.get(bookId);String provider=request.providerOrDefault();String layout=request.layout()==null?"auto":request.layout();List<Integer> pages=PageRanges.parse(request.pages(),book.totalPages());String fingerprint=bookId+"|"+pages+"|"+provider+"|"+layout+"|"+request.splitSpreads()+"|"+request.force()+"|"+request.assistEnabled();
        if(active!=null&&!active.future.isDone()){if(active.fingerprint.equals(fingerprint))return store.readJob(bookId);throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行");}
        Job current = null;
        try { current = store.readJob(bookId); } catch (Exception ignored) { }
        if(current!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(current.status()))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消，请稍后再试");
        Job queued=new Job(UUID.randomUUID().toString(),"QUEUED",0,pages.size(),null,null,List.of(),Instant.now(),
            List.copyOf(pages),provider,layout,request.splitSpreads(),request.force(),request.assistEnabled(),fingerprint);
        write(bookId,queued);Running running=new Running(bookId,fingerprint);active=running;running.future=worker.submit(()->run(running,queued,pages,provider,layout,request.splitSpreads(),request.force(),request.assistEnabled()));return queued;}
    public Job get(String bookId){books.get(bookId);return store.readJob(bookId);}
    // 阶段1：取消写入 CANCELLING，等待 worker 实际退出后才写 CANCELLED；旧任务不再直接覆盖为 CANCELLED
    public synchronized Job cancel(String bookId){books.get(bookId);Job job=store.readJob(bookId);if(active!=null&&active.bookId.equals(bookId)&&!active.future.isDone()){active.cancelled=true;active.future.cancel(true);Job cancelling=statusJob(job,"CANCELLING",job.completed(),job.total(),job.currentPage(),null,job.errors());write(bookId,cancelling);return cancelling;}return job;}
    private void run(Running running,Job initial,List<Integer> pages,String provider,String layout,boolean split,boolean force,boolean assist){int completed=0;List<String>errors=new ArrayList<>();try{
        writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",0,pages.size(),null,null,List.of()));
        for(int pageNumber:pages){
            if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
            if(!stillCurrent(running,initial.id()))return; // 已被新任务取代，旧 worker 不再回写
            writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));
            Page old=store.readPage(running.bookId,pageNumber);
            if(old==null){String message="第 "+pageNumber+" 页数据缺失，已跳过";errors.add(message);completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));continue;}
            int baselineRev=BookStore.revisionOrZero(old);
            Page baseline=force?strongestBaseline(old,store.readOriginalPage(running.bookId,pageNumber)):old;
            if("READY".equals(old.status())&&!force){completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));continue;}
            Page processing=new Page(old.pageNumber(),old.width(),old.height(),"PROCESSING",provider,old.blocks(),old.warnings(),old.reviewed(),null,old.sourceRecords(),null);
            // R03：PROCESSING 标记同样走条件提交；基线已被并发修改时不覆盖，直接跳过
            try {
                store.commitPage(running.bookId,processing,baselineRev,CommitActor.JOB,initial.id());
            } catch (PageConflictException conflict) {
                String message="第 "+pageNumber+" 页在识别开始前已被更新，已保留较新版本，跳过本页";
                errors.add(message);completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));continue;
            }
            try{
                Page rawNew=processor.process(running.bookId,pageNumber,provider,layout,split,assist,()->running.cancelled||Thread.currentThread().isInterrupted());
                Page page=mergeUnresolvedIssues(old,rawNew);
                if(!stillCurrent(running,initial.id()))return;
                // 阶段1：零结果与显著缩水保护（不限于 force），失败不覆盖旧可读版本，候选留档
                if(isSignificantRegression(baseline,page)||isEmptyResult(page)){
                    String message="第 "+pageNumber+" 页重识别来源文字少于旧记录的 60%（或为空），已拒绝覆盖并保留较完整结果";
                    errors.add(message);
                    try{store.writeCandidate(running.bookId,page);}catch(IOException ignored){}
                    Page fallback;
                    if("READY".equals(baseline.status())){
                        List<String>warnings=new ArrayList<>(baseline.warnings()==null?List.of():baseline.warnings());warnings.add(message);
                        fallback=new Page(baseline.pageNumber(),baseline.width(),baseline.height(),"READY",baseline.provider(),baseline.blocks(),List.copyOf(warnings),baseline.reviewed(),message,baseline.sourceRecords(),null);
                    }else{
                        List<String>warnings=new ArrayList<>(old.warnings()==null?List.of():old.warnings());warnings.add(message);
                        fallback=new Page(old.pageNumber(),old.width(),old.height(),"FAILED",old.provider(),old.blocks(),List.copyOf(warnings),old.reviewed(),message,old.sourceRecords(),null);
                    }
                    try {
                        store.commitPage(running.bookId,fallback,baselineRev+1,CommitActor.JOB,initial.id());
                    } catch (PageConflictException conflict) {
                        errors.add("第 "+pageNumber+" 页在识别期间又被更新，已保留最新版本");
                    }
                }else{
                    try{store.preserveOriginal(running.bookId,page);}catch(IOException ignored){}
                    try {
                        store.commitPage(running.bookId,page,baselineRev+1,CommitActor.JOB,initial.id());
                    } catch (PageConflictException conflict) {
                        String message="第 "+pageNumber+" 页在识别期间已被手工保存，已保留手工版本，识别候选另存备查";
                        errors.add(message);
                        try{store.writeCandidate(running.bookId,page);}catch(IOException ignored){}
                    }
                }
            }
            catch(CancelledException e){
                if(!stillCurrent(running,initial.id()))return;
                // 恢复旧状态为新版本，不降低可读性；并发写入优先保留
                try {
                    store.commitPage(running.bookId,new Page(old.pageNumber(),old.width(),old.height(),old.status(),old.provider(),old.blocks(),old.warnings(),old.reviewed(),old.error(),old.sourceRecords(),null),baselineRev+1,CommitActor.JOB,initial.id());
                } catch (PageConflictException ignored) { }
                throw e;}
            catch(Exception e){
                if(!stillCurrent(running,initial.id()))return;
                String detail=safeDetail(e);String message="第 "+pageNumber+" 页处理失败"+(detail==null?"":"："+detail);errors.add(message);
                // 阶段1：普通失败不降低已有有效页的可读状态——旧 READY 保持 READY，仅追加警告；并发写入优先保留
                Page failed;
                if("READY".equals(old.status())){
                    List<String>warnings=new ArrayList<>(old.warnings()==null?List.of():old.warnings());warnings.add(message);
                    failed=new Page(old.pageNumber(),old.width(),old.height(),"READY",old.provider(),old.blocks(),List.copyOf(warnings),old.reviewed(),message,old.sourceRecords(),null);
                }else{
                    failed=new Page(old.pageNumber(),old.width(),old.height(),"FAILED",provider,old.blocks(),old.warnings(),old.reviewed(),message,old.sourceRecords(),null);
                }
                try {
                    store.commitPage(running.bookId,failed,baselineRev+1,CommitActor.JOB,initial.id());
                } catch (PageConflictException ignored) { }
            }
            completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));
        }
        writeIfCurrent(running,initial.id(),statusJob(initial,errors.isEmpty()?"COMPLETED":"COMPLETED_WITH_ERRORS",completed,pages.size(),null,null,List.copyOf(errors)));
    }catch(CancelledException|CancellationException e){
        if(!stillCurrent(running,initial.id()))return;
        Job j=store.readJob(running.bookId);
        writeIfCurrent(running,initial.id(),statusJob(initial,"CANCELLED",j.completed(),pages.size(),j.currentPage(),null,j.errors()));
    }
    catch(Exception e){writeIfCurrent(running,initial.id(),statusJob(initial,"FAILED",completed,pages.size(),null,"任务执行失败",List.copyOf(errors)));}
    finally{synchronized(this){if(active==running)active=null;}}}
    private void write(String id,Job job){try{store.writeJob(id,job);}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"任务状态保存失败");}}
    // 阶段1：job 代次保护——仅当内存 active 仍是本 worker 且持久化 jobId 一致时才写入
    private boolean stillCurrent(Running running,String expectedJobId){synchronized(this){if(active!=running)return false;}try{Job cur=store.readJob(running.bookId);return cur!=null&&expectedJobId.equals(cur.id());}catch(Exception e){return false;}}
    private void writeIfCurrent(Running running,String expectedJobId,Job job){synchronized(this){if(active!=running)return;}try{Job cur=store.readJob(running.bookId);if(cur==null||!expectedJobId.equals(cur.id()))return;write(running.bookId,job);}catch(Exception ignored){}}
    private static Job statusJob(Job initial,String status,int completed,int total,Integer currentPage,String error,List<String>errors){
        return new Job(initial.id(),status,completed,total,currentPage,error,errors==null?List.of():List.copyOf(errors),Instant.now(),
            initial.pages(),initial.provider(),initial.layout(),initial.splitSpreads(),initial.force(),initial.assist(),initial.fingerprint());
    }
    static String safeDetail(Exception error){if(error instanceof OcrException||error instanceof ApiException){String message=error.getMessage();return message==null||message.isBlank()?null:message;}return null;}
    // 阶段1：零结果保护——旧有文字而新结果为空或显著缩水均视为回归
    static boolean isSignificantRegression(Page oldPage,Page newPage){int oldChars=sourceChars(oldPage),newChars=sourceChars(newPage);return oldChars>0&&newChars<oldChars*.6;}
    static boolean isEmptyResult(Page page){return sourceChars(page)==0&&blockChars(page)==0;}
    static Page strongestBaseline(Page current,Page original){if(current==null)return original;if(current.reviewed()||original==null)return current;return sourceChars(original)>sourceChars(current)?original:current;}
    static Page mergeUnresolvedIssues(Page previous,Page current){if(previous==null||current==null||"manual".equals(previous.provider())||previous.blocks()==null||current.blocks()==null||previous.sourceRecords()==null||current.sourceRecords()==null)return current;Map<String,Block>oldBlocks=byId(previous.blocks()),oldSources=byId(previous.sourceRecords()),newSources=byId(current.sourceRecords());List<Block>merged=new ArrayList<>(current.blocks().size());boolean changed=false;for(Block block:current.blocks()){Block old=oldBlocks.get(block.id()),oldSource=oldSources.get(block.id()),newSource=newSources.get(block.id());if(!samePaddleSource(old,block,oldSource,newSource)){merged.add(block);continue;}List<ContentIssue>issues=new ArrayList<>(block.issues()==null?List.of():block.issues());for(ContentIssue issue:old.issues()==null?List.<ContentIssue>of():old.issues()){if(issue.resolved()||!validIssue(issue,block)||issues.stream().anyMatch(currentIssue->currentIssue.id().equals(issue.id())||overlaps(currentIssue,issue)))continue;issues.add(issue);changed=true;}if(changedFor(block,issues)){issues.sort(Comparator.comparingInt(ContentIssue::start).thenComparingInt(ContentIssue::end));merged.add(new Block(block.id(),block.type(),block.order(),block.bbox(),block.writingMode(),block.original(),block.simplified(),block.confidence(),block.uncertain()||issues.stream().anyMatch(i->!i.resolved()),block.reviewed(),block.headingLevel(),block.source(),block.sourceIds(),block.suggestion(),block.sourceRect(),List.copyOf(issues)));}else merged.add(block);}if(!changed)return current;BlockValidator.validate(merged);return new Page(current.pageNumber(),current.width(),current.height(),current.status(),current.provider(),List.copyOf(merged),current.warnings(),current.reviewed(),current.error(),current.sourceRecords(),current.revision());}
    private static boolean changedFor(Block block,List<ContentIssue>issues){return issues.size()!=(block.issues()==null?0:block.issues().size());}
    private static Map<String,Block>byId(List<Block>blocks){Map<String,Block>result=new HashMap<>();for(Block block:blocks)if(block!=null&&block.id()!=null)result.putIfAbsent(block.id(),block);return result;}
    private static boolean samePaddleSource(Block old,Block current,Block oldSource,Block newSource){return old!=null&&oldSource!=null&&newSource!=null&&!"manual".equals(old.source())&&Objects.equals(old.original(),current.original())&&Objects.equals(old.simplified(),current.simplified())&&Objects.equals(oldSource.original(),newSource.original())&&isPaddleFamily(oldSource.source())&&isPaddleFamily(newSource.source());}
    private static boolean isPaddleFamily(String source){return source!=null&&(source.startsWith("paddle")||source.startsWith("ppocr"));}
    private static boolean validIssue(ContentIssue issue,Block block){if(issue==null||issue.id()==null||issue.id().isBlank()||issue.id().length()>120||!("unreadable".equals(issue.kind())||"suspected".equals(issue.kind())))return false;String original=block.original()==null?"":block.original(),simplified=block.simplified()==null?"":block.simplified();return issue.start()>=0&&issue.end()>issue.start()&&issue.end()<=original.length()&&issue.simplifiedStart()>=0&&issue.simplifiedEnd()>=issue.simplifiedStart()&&issue.simplifiedEnd()<=simplified.length()&&length(issue.reason())<=1000&&length(issue.replacement())<=1000&&length(issue.inferredText())<=1000;}
    private static boolean overlaps(ContentIssue a,ContentIssue b){return a.start()<b.end()&&b.start()<a.end();}
    private static int length(String value){return value==null?0:value.length();}
    static int sourceChars(Page page){if(page==null)return 0;List<Block>records=page.sourceRecords()!=null&&!page.sourceRecords().isEmpty()?page.sourceRecords():page.blocks();if(records==null)return 0;return records.stream().map(Block::original).filter(Objects::nonNull).mapToInt(s->(int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count()).sum();}
    private static int blockChars(Page page){if(page==null||page.blocks()==null)return 0;return page.blocks().stream().map(Block::original).filter(Objects::nonNull).mapToInt(s->(int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count()).sum();}
    private static final class Running{final String bookId,fingerprint;volatile boolean cancelled;Future<?> future;Running(String bookId,String fingerprint){this.bookId=bookId;this.fingerprint=fingerprint;}}
}
