package studio.bookhtml.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.JobRequest;
import studio.bookhtml.config.SettingsService;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import studio.bookhtml.store.CommitActor;
import studio.bookhtml.store.CommitOp;
import studio.bookhtml.store.PageConflictException;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class JobService {
    private final BookStore store;private final BookService books;private final PageProcessor processor;
    private final ExecutorService worker=new ThreadPoolExecutor(8,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread t=new Thread(r,"book-html-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private Running active;
    private final Map<Integer, Running> activeReserved = new ConcurrentHashMap<>();
    // U2：页面级 attempt 登记（调度用；最终写入权限仍以 BookStore 锁内校验为准）。
    private final Map<String, PageAttempt> pageAttempts = new ConcurrentHashMap<>();
    // U2：重处理操作幂等（reservation:operationId → 请求指纹），同 ID 同参数返回同一任务。
    private final Map<String, String> operationFingerprints = new ConcurrentHashMap<>();
    private final Map<String, Job> operationJobs = new ConcurrentHashMap<>();
    private UUID readingReservation;
    private String readingReservationBookId;
    private SettingsService settings;
    private ProcessingProgressService progress;
    public JobService(BookStore store,BookService books,PageProcessor processor){this.store=store;this.books=books;this.processor=processor;}
    @org.springframework.beans.factory.annotation.Autowired public void setSettings(SettingsService settings){this.settings=settings;}
    /** U4：阶段事件聚合（测试可注入；缺省关闭，不影响正式保存）。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false) public void setProgress(ProcessingProgressService progress){this.progress=progress;}
    @PostConstruct void recover(){store.recoverInterruptedJobs();reconcileAttemptIntents();}
    @PreDestroy void close(){
        cancelAllReserved();
        synchronized(this){if(active!=null){active.cancelled=true;if(active.thread!=null)active.thread.interrupt();}}
        worker.shutdownNow();
        // Never hold the service monitor while joining: each worker needs it in finally.
        try { worker.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
    public synchronized void reserveReading(UUID reservation,String bookId){
        if(readingReservation!=null||active!=null||!activeReserved.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"已有识别任务或阅读窗口正在运行");
        if(store.readBook(bookId).archived())throw new ApiException(HttpStatus.CONFLICT,"本书已归档，请先恢复后再识别");
        Job current=store.readJob(bookId);
        if(current!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(current.status()))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消");
        operationFingerprints.clear(); operationJobs.clear();
        readingReservation=Objects.requireNonNull(reservation);
        readingReservationBookId=bookId;
        Job readingJob = new Job("reading:" + reservation, "RUNNING", 0, 0, null, null, List.of(), Instant.now(),
                List.of(), "paddle-aistudio", "auto", false, false, false, "reading:" + reservation);
        write(bookId, readingJob);
    }
    public synchronized void releaseReading(UUID reservation){
        if(Objects.equals(readingReservation,reservation)){
            readingReservation=null;
            String bookId = readingReservationBookId;
            readingReservationBookId=null;
            cancelAllReserved();
            if (bookId != null) {
                try {
                    Job cur = store.readJob(bookId);
                    if (cur != null && ("reading:" + reservation).equals(cur.id())) {
                        write(bookId, Job.idle());
                    }
                } catch (Exception ignored) {}
            }
        }
    }
    public synchronized boolean readingJobActive(UUID reservation){return Objects.equals(readingReservation,reservation)&&!activeReserved.isEmpty();}
    public synchronized boolean readingJobActive(UUID reservation, int pageNumber){return Objects.equals(readingReservation,reservation)&&activeReserved.containsKey(pageNumber);}
    public synchronized void cancelReadingPage(UUID reservation, int pageNumber) {
        if (!Objects.equals(readingReservation, reservation)) return;
        Running running = activeReserved.get(pageNumber);
        if (running != null) {
            // U2：只标记取消并中断，不提前从登记表删除。物理槽与登记在 worker
            // 收尾（finally）时释放；取消后仍可恢复旧可读版本（mayRestore）。
            running.cancelled = true;
            if (running.future != null) running.future.cancel(true);
            if (running.thread != null) running.thread.interrupt();
        }
    }
    private synchronized void cancelAllReserved() {
        for (Running running : new HashSet<>(activeReserved.values())) {
            running.cancelled = true;
            if (running.future != null) running.future.cancel(true);
            if (running.thread != null) running.thread.interrupt();
        }
        activeReserved.entrySet().removeIf(entry -> {
            Running running = entry.getValue();
            if (running.started) return false; // Physical work retains its slot until finally.
            if (running.lease != null) running.lease.close();
            return true;
        });
    }
    /** Serialize library archiving with both batch admission and reading-window reservation. */
    public synchronized Book updateLibrary(String bookId,String title,Boolean archived){
        if(Boolean.TRUE.equals(archived)
                && ((active!=null&&active.bookId.equals(bookId)) || bookId.equals(readingReservationBookId)))
            throw new ApiException(HttpStatus.CONFLICT,"本书正在识别，请等待任务完成或停止随读后归档");
        return books.updateLibrary(bookId,title,archived);
    }
    public synchronized Job submitReserved(UUID reservation,String bookId,JobRequest request){
        if(readingReservation==null||!readingReservation.equals(reservation)||!Objects.equals(bookId,readingReservationBookId))throw new ApiException(HttpStatus.CONFLICT,"阅读窗口预约已失效");
        if(active!=null)throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行");
        Book book=store.readBook(bookId);
        if(book.archived())throw new ApiException(HttpStatus.CONFLICT,"本书已归档，请先恢复后再识别");
        String provider=request.provider()==null?(settings==null?"paddle-aistudio":settings.state().defaultProvider()):request.provider();
        if(settings!=null&&!List.of("paddle-aistudio","ppocr").contains(provider))throw new ApiException(HttpStatus.BAD_REQUEST,"新任务仅支持 AI Studio 与 PP-OCRv6 通道");
        String layout=request.layout()==null?"auto":request.layout();
        List<Integer> pages=PageRanges.parse(request.pages(),book.totalPages());
        String fingerprint=bookId+"|"+pages+"|"+provider+"|"+layout+"|"+request.splitSpreads()+"|"+request.force()+"|"+request.assistEnabled();
        for(int p:pages){
            Running existing=activeReserved.get(p);
            if(existing!=null&&!existing.cancelled){
                if(existing.fingerprint.equals(fingerprint))return store.readJob(bookId);
                throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消");
            }
        }
        SettingsService.Lease lease=settings==null?null:settings.beginWork();
        String expectedJobId="reading:"+reservation+":"+(pages.isEmpty()?"0":pages.get(0));
        Running running=new Running(bookId,fingerprint,lease);
        for(int p:pages){activeReserved.put(p,running);}
        // U2：为每页登记 attempt（调度用；最终写入仍以 BookStore 锁内校验为准）。
        // U4：允许操作含基线/增强/恢复三类提交。
        for(int p:pages){
            Page pg=null;try{pg=store.readPage(bookId,p);}catch(RuntimeException ignored){}
            registerAttempt(bookId,p,BookStore.revisionOrZero(pg),List.of("JOB_START","JOB_BASELINE","JOB_ENHANCEMENT","JOB_COMPLETE","JOB_RESTORE"));
        }
        Job queued=new Job(expectedJobId,"RUNNING",0,pages.size(),pages.isEmpty()?null:pages.get(0),
                null,List.of(),Instant.now(),List.copyOf(pages),provider,layout,
                request.splitSpreads(),request.force(),request.assistEnabled(),fingerprint);
        try{
            running.future=worker.submit(()->runReserved(running,reservation,expectedJobId,pages,provider,layout,
                    request.splitSpreads(),request.force(),request.assistEnabled()));
        }catch(RuntimeException e){
            for(int p:pages)activeReserved.remove(p);
            if(lease!=null)lease.close();
            throw e;
        }
        return queued;
    }

    /**
     * U2：安全重新处理准入。保持当前可读 Page 不变（不在此处写 PENDING），
     * 经版本/人工保护/活动 attempt/授权校验后创建独立 attempt。
     */
    public synchronized Job requestReprocess(UUID reservation, String bookId, int pageNumber,
                                             studio.bookhtml.api.PageReprocessRequest request,
                                             String provider, String layout,
                                             boolean splitSpreads, boolean assist) {
        if (readingReservation == null || !readingReservation.equals(reservation) || !Objects.equals(bookId, readingReservationBookId))
            throw new ApiException(HttpStatus.CONFLICT, "阅读窗口预约已失效");
        Book book = store.readBook(bookId);
        if (book.archived()) throw new ApiException(HttpStatus.CONFLICT, "本书已归档，请先恢复后再识别");
        if (pageNumber < 1 || pageNumber > book.totalPages())
            throw new ApiException(HttpStatus.BAD_REQUEST, "页码超出书籍范围");
        if (request == null || request.clientOperationId() == null || request.clientOperationId().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 clientOperationId");
        Page current = store.readPage(bookId, pageNumber);
        if (current == null) throw new ApiException(HttpStatus.NOT_FOUND, "页面不存在");
        // U2：先判人工保护（授权问题），再判版本新鲜度。MANUAL/已校对页默认不覆盖；
        // 明确覆盖确认须绑定当前 revision。
        boolean manual = current.reviewed() || "manual".equals(current.provider());
        if (manual && !request.explicitOverwriteAuthorization())
            throw new ApiException(HttpStatus.CONFLICT, "本页含人工校对内容，默认不覆盖；确认覆盖需绑定当前版本");
        int currentRev = BookStore.revisionOrZero(current);
        if (request.expectedRevision() != null && request.expectedRevision() != currentRev)
            throw new ApiException(HttpStatus.CONFLICT, "页面已被他处更新，请刷新后重试；旧确认不覆盖新版本");
        String operationKey = reservation + ":" + request.clientOperationId();
        String fingerprint = bookId + "|" + pageNumber + "|" + provider + "|" + layout
                + "|" + splitSpreads + "|true|" + assist;
        String known = operationFingerprints.get(operationKey);
        if (known != null) {
            if (!known.equals(fingerprint))
                throw new ApiException(HttpStatus.CONFLICT, "相同操作 ID 但参数不一致，已拒绝");
            Job existing = operationJobs.get(operationKey);
            if (existing != null) return existing;
        }
        if (operationFingerprints.size() >= 4096)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "本次会话重试次数已达上限，请结束后重新开启");
        Running existing = activeReserved.get(pageNumber);
        if (existing != null && !existing.cancelled) {
            // 同页已有活动 attempt：不重复派发，返回当前任务。
            try {
                Job current2 = store.readJob(bookId);
                if (current2 != null) {
                    operationFingerprints.put(operationKey, fingerprint);
                    operationJobs.put(operationKey, current2);
                    return current2;
                }
            } catch (RuntimeException ignored) {}
            throw new ApiException(HttpStatus.CONFLICT, "本页已有识别任务正在运行");
        }
        String channel = provider == null ? "paddle-aistudio" : provider;
        JobRequest jobRequest = new JobRequest(String.valueOf(pageNumber), channel, layout,
                splitSpreads, true, assist);
        Job queued = submitReserved(reservation, bookId, jobRequest);
        operationFingerprints.put(operationKey, fingerprint);
        operationJobs.put(operationKey, queued);
        return queued;
    }

    private void registerAttempt(String bookId, int pageNumber, int expectedRevision, List<String> allowedOps) {
        String key = bookId + ":" + pageNumber;
        PageAttempt prev;
        synchronized (pageAttempts) {
            prev = pageAttempts.get(key);
            String sourceHash = null;
            try {
                Page page = store.readPage(bookId, pageNumber);
                if (page != null && page.sourceRecords() != null && !page.sourceRecords().isEmpty()
                        && page.sourceRecords().get(0) != null)
                    sourceHash = page.sourceRecords().get(0).id();
            } catch (RuntimeException ignored) {}
            PageAttempt next = (prev != null && prev.bookId().equals(bookId) && prev.pageNumber() == pageNumber)
                    ? prev.nextGeneration()
                    : PageAttempt.register(bookId, pageNumber, expectedRevision, sourceHash, allowedOps);
            pageAttempts.put(key, next);
            persistIntent(bookId, next);
        }
    }

    /**
     * U4：恢复意图持久化（IN_PROGRESS → 终态）。写持久化意图 → 原子页提交 →
     * 更新完成标记；自动恢复不重发云请求，不重复收费。
     */
    private void persistIntent(String bookId, PageAttempt attempt) {
        try {
            java.util.Map<String, PageAttempt> intents = new java.util.LinkedHashMap<>(readJournal(bookId).intents());
            intents.put(attempt.key(), attempt);
            store.writeSidecar(store.pageAttemptsPath(bookId), new PageAttempt.Journal(intents));
        } catch (Exception ignored) {
            // 意图落盘失败不阻塞派发；内存登记仍约束本次运行。
        }
    }

    private void completeIntent(String bookId, int pageNumber, String lifecycle) {
        synchronized (pageAttempts) {
            try {
                PageAttempt.Journal journal = readJournal(bookId);
                PageAttempt current = journal.intents().get(bookId + ":" + pageNumber);
                if (current == null) return;
                java.util.Map<String, PageAttempt> intents = new java.util.LinkedHashMap<>(journal.intents());
                intents.put(current.key(), current.withLifecycle(lifecycle));
                store.writeSidecar(store.pageAttemptsPath(bookId), new PageAttempt.Journal(intents));
            } catch (Exception ignored) {}
        }
    }

    private PageAttempt.Journal readJournal(String bookId) {
        try {
            PageAttempt.Journal journal =
                    store.readSidecar(store.pageAttemptsPath(bookId), PageAttempt.Journal.class);
            return journal == null ? PageAttempt.Journal.empty() : journal;
        } catch (RuntimeException e) {
            return PageAttempt.Journal.empty();
        }
    }

    /**
     * U4：重启对照意图与当前页。内容已一致补终态；尚未发布保留现有可读页标
     * INTERRUPTED；无法证明所有权不覆盖；绝不重新发送云请求。
     */
    public synchronized void reconcileAttemptIntents() {
        List<String> bookIds;
        try {
            bookIds = store.listBooks().stream().map(Book::id).toList();
        } catch (RuntimeException e) {
            return;
        }
        for (String bookId : bookIds) {
            PageAttempt.Journal journal = readJournal(bookId);
            boolean changed = false;
            java.util.Map<String, PageAttempt> intents = new java.util.LinkedHashMap<>(journal.intents());
            for (Map.Entry<String, PageAttempt> entry : journal.intents().entrySet()) {
                PageAttempt intent = entry.getValue();
                if (List.of("SUCCEEDED", "CANCELLED", "FAILED", "INTERRUPTED").contains(intent.lifecycle())) continue;
                try {
                    Page page = store.readPage(bookId, intent.pageNumber());
                    if (page != null && !"PROCESSING".equals(page.status())
                            && BookStore.revisionOrZero(page) >= intent.expectedRevision()) {
                        intents.put(entry.getKey(), intent.withLifecycle("SUCCEEDED"));
                    } else {
                        // recoverInterruptedJobs 已处理 PROCESSING 页本身；此处只标意图终态。
                        intents.put(entry.getKey(), intent.withLifecycle("INTERRUPTED"));
                    }
                    changed = true;
                } catch (RuntimeException ignored) {}
            }
            if (changed) {
                try {
                    store.writeSidecar(store.pageAttemptsPath(bookId), new PageAttempt.Journal(intents));
                } catch (Exception ignored) {}
            }
        }
    }

    /** U2：测试可见的 attempt 登记快照（调度用，不代表最终写入权限）。 */
    Map<String, PageAttempt> attemptSnapshot() {
        return Map.copyOf(pageAttempts);
    }

    /** U2：本书本页的登记是否仍归属该 attempt（不判断取消，供恢复路径使用）。 */
    private boolean ownsAttempt(Running running, UUID reservation) {
        if (!Objects.equals(readingReservation, reservation)) return false;
        for (Running candidate : activeReserved.values()) {
            if (candidate == running) return true;
        }
        return false;
    }

    /** U2：是否允许派发/继续执行（拥有 attempt 且未停止）。 */
    private boolean mayDispatch(Running running, UUID reservation) {
        return ownsAttempt(running, reservation) && !running.cancelled;
    }

    /** U2：是否允许发布新内容（拥有 attempt、未停止、任务身份一致）。 */
    private boolean mayPublish(Running running, UUID reservation, String expectedJobId) {
        if (!mayDispatch(running, reservation)) return false;
        return reservedJobMatches(running, reservation);
    }

    /** U2：是否允许恢复旧可读版本（拥有 attempt、任务身份一致；取消后仍可恢复）。 */
    private boolean mayRestore(Running running, UUID reservation, String expectedJobId) {
        if (!ownsAttempt(running, reservation)) return false;
        return reservedJobMatches(running, reservation);
    }

    /**
     * U2：随读预约的持久任务身份是 {@code "reading:"+reservation}（见 reserveReading）；
     * submitReserved 的 {@code expectedJobId} 另带首派发页后缀，仅用于区分同预约内的
     * 多次派发，不作为归属判断。归属只认预约身份 + 登记表同一性。
     */
    private boolean reservedJobMatches(Running running, UUID reservation) {
        try {
            Job cur = store.readJob(running.bookId);
            return cur != null && ("reading:" + reservation).equals(cur.id());
        } catch (RuntimeException e) {
            return false;
        }
    }
    public synchronized Job submit(String bookId,JobRequest request){
        if(readingReservation!=null||!activeReserved.isEmpty())throw new ApiException(HttpStatus.CONFLICT,"阅读窗口正在运行或收尾，请先停止随读处理并等待结束");
        SettingsService.Lease lease=settings==null?null:settings.beginWork();boolean transferred=false;try{Book book=books.get(bookId);if(book.archived())throw new ApiException(HttpStatus.CONFLICT,"本书已归档，请先恢复后再识别");String provider=request.provider()==null?(settings==null?"paddle-aistudio":settings.state().defaultProvider()):request.provider();if(settings!=null&&!List.of("paddle-aistudio","ppocr").contains(provider))throw new ApiException(HttpStatus.BAD_REQUEST,"新任务仅支持 AI Studio 与 PP-OCRv6 通道");String layout=request.layout()==null?"auto":request.layout();List<Integer> pages=PageRanges.parse(request.pages(),book.totalPages());String fingerprint=bookId+"|"+pages+"|"+provider+"|"+layout+"|"+request.splitSpreads()+"|"+request.force()+"|"+request.assistEnabled();
        if(active!=null){if(!active.cancelled&&active.fingerprint.equals(fingerprint))return store.readJob(bookId);throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消");}
        Job current = null;
        try { current = store.readJob(bookId); } catch (Exception ignored) { }
        if(current!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(current.status()))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消，请稍后再试");
        Job queued=new Job(UUID.randomUUID().toString(),"QUEUED",0,pages.size(),null,null,List.of(),Instant.now(),
            List.copyOf(pages),provider,layout,request.splitSpreads(),request.force(),request.assistEnabled(),fingerprint);
        write(bookId,queued);Running running=new Running(bookId,fingerprint,lease);active=running;
        try{running.future=worker.submit(()->run(running,queued,pages,provider,layout,request.splitSpreads(),request.force(),request.assistEnabled()));}
        catch(RuntimeException e){active=null;
            try{write(bookId,statusJob(queued,"FAILED",0,pages.size(),null,"任务无法启动",List.of("任务无法启动")));}
            catch(RuntimeException ignored){}throw e;}
        transferred=true;return queued;
        }finally{if(!transferred&&lease!=null)lease.close();}}
    public Job get(String bookId){books.get(bookId);return store.readJob(bookId);}
    // 运行中取消先写 CANCELLING，由 worker 收尾时写 CANCELLED；排队未启动可直接取消。
    public synchronized Job cancel(String bookId){books.get(bookId);Job job=store.readJob(bookId);
        if(bookId.equals(readingReservationBookId)){cancelAllReserved();}
        if(active==null||!active.bookId.equals(bookId)||!List.of("QUEUED","RUNNING","CANCELLING").contains(job.status()))return job;
        if("CANCELLING".equals(job.status()))return job;
        Running running=active;running.cancelled=true;
        if(!running.started){running.future.cancel(false);Job cancelled=statusJob(job,"CANCELLED",job.completed(),job.total(),job.currentPage(),null,job.errors());write(bookId,cancelled);active=null;if(running.lease!=null)running.lease.close();return cancelled;}
        Job cancelling=statusJob(job,"CANCELLING",job.completed(),job.total(),job.currentPage(),null,job.errors());write(bookId,cancelling);running.thread.interrupt();return cancelling;}
    private void run(Running running,Job initial,List<Integer> pages,String provider,String layout,boolean split,boolean force,boolean assist){synchronized(this){if(active!=running||running.cancelled)return;running.started=true;running.thread=Thread.currentThread();}int completed=0;List<String>errors=new ArrayList<>();try{
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
            // R03/A1-04：PROCESSING 标记走条件提交（JOB_START）；基线已被并发修改时不覆盖，直接跳过
            try {
                store.commitPage(running.bookId,processing,baselineRev,CommitActor.JOB,initial.id(),CommitOp.JOB_START);
            } catch (PageConflictException conflict) {
                String message="第 "+pageNumber+" 页在识别开始前已被更新，已保留较新版本，跳过本页";
                errors.add(message);completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));continue;
            }
            UUID batchAttempt = progress == null ? null : progress.begin(running.bookId, pageNumber, baselineRev);
            if (batchAttempt != null) progress.stage(running.bookId, pageNumber, batchAttempt, "OCR");
            try{
                ProcessingResult result=processor.process(running.bookId,pageNumber,provider,layout,split,assist,()->running.cancelled||Thread.currentThread().isInterrupted());
                Page page=mergeUnresolvedIssues(old,result.page());
                if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
                if(!stillCurrent(running,initial.id()))return;
                // F03/R08：有证据的空白/纯视觉页直接成功；显著缩水仍拒绝；其他空结果仍失败
                boolean confirmedNoText=result.category()==ProcessingResult.Category.BLANK_CONFIRMED
                        ||result.category()==ProcessingResult.Category.VISUAL_ONLY;
                // 阶段1：零结果与显著缩水保护（不限于 force），失败不覆盖旧可读版本，候选留档
                if(isSignificantRegression(baseline,page)||(!confirmedNoText&&isEmptyResult(page))){
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
                        store.commitPage(running.bookId,fallback,baselineRev+1,CommitActor.JOB,initial.id(),CommitOp.JOB_RESTORE);
                    } catch (PageConflictException conflict) {
                        errors.add("第 "+pageNumber+" 页在识别期间又被更新，已保留最新版本");
                    }
                }else{
                    // A1-04：先提交成功结果，通过后再补原始快照；被拒绝的结果不写快照
                    try {
                        if(batchAttempt!=null)progress.stage(running.bookId,pageNumber,batchAttempt,"PUBLISHING");
                        store.commitPage(running.bookId,page,baselineRev+1,CommitActor.JOB,initial.id(),CommitOp.JOB_COMPLETE);
                        if(batchAttempt!=null){
                            progress.baselinePublished(running.bookId,pageNumber,batchAttempt,baselineRev+1,assist);
                            boolean partial=new PageProcessor.EnrichResult(page.blocks(),page.provider(),page.warnings()).partial();
                            progress.finish(running.bookId,pageNumber,batchAttempt,partial?"PARTIAL":"SUCCEEDED",partial?"BATCH_PARTIAL":"BATCH_PUBLISHED",partial);
                        }
                        try{store.preserveOriginal(running.bookId,page);}catch(IOException ignored){}
                    } catch (PageConflictException conflict) {
                        String message="第 "+pageNumber+" 页在识别期间已被手工保存，已保留手工版本，识别候选另存备查";
                        errors.add(message);
                        try{store.writeCandidate(running.bookId,page);}catch(IOException ignored){}
                    }
                }
            }
            catch(CancelledException e){
                if(batchAttempt!=null)progress.finish(running.bookId,pageNumber,batchAttempt,"CANCELLED","BATCH_CANCELLED",false);
                if(!stillCurrent(running,initial.id()))return;
                // 恢复旧状态为新版本，不降低可读性；并发写入优先保留
                try {
                    store.commitPage(running.bookId,new Page(old.pageNumber(),old.width(),old.height(),old.status(),old.provider(),old.blocks(),old.warnings(),old.reviewed(),old.error(),old.sourceRecords(),null),baselineRev+1,CommitActor.JOB,initial.id(),CommitOp.JOB_RESTORE);
                } catch (PageConflictException ignored) { }
                throw e;}
            catch(Exception e){
                if(batchAttempt!=null)progress.finish(running.bookId,pageNumber,batchAttempt,"FAILED","BATCH_FAILED",true);
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
                    store.commitPage(running.bookId,failed,baselineRev+1,CommitActor.JOB,initial.id(),CommitOp.JOB_RESTORE);
                } catch (PageConflictException ignored) { }
            } finally {
                if(batchAttempt!=null){var snap=progress.snapshot(running.bookId,pageNumber,batchAttempt);
                    if(snap!=null&&"RUNNING".equals(snap.lifecycle()))progress.finish(running.bookId,pageNumber,batchAttempt,"PARTIAL","BATCH_CANDIDATE_NOT_PUBLISHED",true);}
            }
            completed++;writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),pageNumber,null,List.copyOf(errors)));
        }
        if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
        writeIfCurrent(running,initial.id(),statusJob(initial,errors.isEmpty()?"COMPLETED":"COMPLETED_WITH_ERRORS",completed,pages.size(),null,null,List.copyOf(errors)));
    }catch(CancelledException|CancellationException e){
        if(!stillCurrent(running,initial.id()))return;
        Job j=store.readJob(running.bookId);
        writeIfCurrent(running,initial.id(),statusJob(initial,"CANCELLED",j.completed(),pages.size(),j.currentPage(),null,j.errors()));
    }
    catch(Exception e){writeIfCurrent(running,initial.id(),statusJob(initial,"FAILED",completed,pages.size(),null,"任务执行失败",List.copyOf(errors)));}
    finally{synchronized(this){if(active==running)active=null;if(running.lease!=null)running.lease.close();}}}
    private void runReserved(Running running,UUID reservation,String expectedJobId,List<Integer> pages,String provider,String layout,boolean split,boolean force,boolean assist){
        try{
            synchronized(this){if(!Objects.equals(readingReservation,reservation)||running.cancelled)return;running.started=true;running.thread=Thread.currentThread();}
            for(int pageNumber:pages){
                if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
                if(!mayRestore(running,reservation,expectedJobId)){completeIntent(running.bookId,pageNumber,"INTERRUPTED");return;}
                Page old=store.readPage(running.bookId,pageNumber);
                if(old==null)continue;
                int baselineRev=BookStore.revisionOrZero(old);
                Page baseline=force?strongestBaseline(old,store.readOriginalPage(running.bookId,pageNumber)):old;
                if("READY".equals(old.status())&&!force)continue;
                Page processing=new Page(old.pageNumber(),old.width(),old.height(),"PROCESSING",provider,old.blocks(),old.warnings(),old.reviewed(),null,old.sourceRecords(),null);
                try {
                    store.commitPage(running.bookId,processing,baselineRev,CommitActor.JOB,expectedJobId,CommitOp.JOB_START);
                } catch (PageConflictException | IOException conflict) {
                    continue;
                }
                // U4 跨 catch 可见：基线已发布时，取消不再恢复旧版（基线即有效可读版）。
                boolean pageBaselinePublished = false;
                UUID attemptId = progress == null ? null : progress.begin(running.bookId, pageNumber, baselineRev);
                try{
                    // U4：两阶段。基线（OCR/原生）先行发布可读；增强（Qwen 整理/局部核对）
                    // 只产候选，经门与版本校验后最多发布一次。任一阶段取消/失败不丢基线。
                    boolean baselinePublishedThisPage = false;
                    int publishedRev = baselineRev;
                    Page publishedPage = old;
                    if (attemptId != null) {
                        progress.stage(running.bookId, pageNumber, attemptId, "OCR");
                        progress.plan(running.bookId, pageNumber, attemptId, "PAGE", 1);
                    }
                    ProcessingResult baselineResult=processor.processBaseline(running.bookId,pageNumber,provider,layout,split,()->running.cancelled||Thread.currentThread().isInterrupted());
                    Page baselinePage=mergeUnresolvedIssues(old,baselineResult.page());
                    if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
                    // U2：新内容发布要求未停止且身份一致；取消后只允许恢复旧版本。
                    if(!mayPublish(running,reservation,expectedJobId))return;
                    boolean confirmedNoText=baselineResult.category()==ProcessingResult.Category.BLANK_CONFIRMED
                            ||baselineResult.category()==ProcessingResult.Category.VISUAL_ONLY;
                    if(isSignificantRegression(baseline,baselinePage)||(!confirmedNoText&&isEmptyResult(baselinePage))){
                        String message="第 "+pageNumber+" 页重识别来源文字少于旧记录的 60%（或为空），已拒绝覆盖并保留较完整结果";
                        try{store.writeCandidate(running.bookId,baselinePage);}catch(IOException ignored){}
                        Page fallback;
                        if("READY".equals(baseline.status())){
                            List<String>warnings=new ArrayList<>(baseline.warnings()==null?List.of():baseline.warnings());warnings.add(message);
                            fallback=new Page(baseline.pageNumber(),baseline.width(),baseline.height(),"READY",baseline.provider(),baseline.blocks(),List.copyOf(warnings),baseline.reviewed(),message,baseline.sourceRecords(),null);
                        }else{
                            List<String>warnings=new ArrayList<>(old.warnings()==null?List.of():old.warnings());warnings.add(message);
                            fallback=new Page(old.pageNumber(),old.width(),old.height(),"FAILED",old.provider(),old.blocks(),List.copyOf(warnings),old.reviewed(),message,old.sourceRecords(),null);
                        }
                        try {
                            store.commitPage(running.bookId,fallback,baselineRev+1,CommitActor.JOB,expectedJobId,CommitOp.JOB_RESTORE);
                        } catch (PageConflictException | IOException ignored) {}
                        if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"FAILED","BASELINE_REJECTED",true);
                    }else{
                        try {
                            if(attemptId!=null)progress.stage(running.bookId,pageNumber,attemptId,"PUBLISHING");
                            Page committed=store.commitPage(running.bookId,baselinePage,baselineRev+1,CommitActor.JOB,expectedJobId,CommitOp.JOB_BASELINE);
                            try{store.preserveOriginal(running.bookId,committed);}catch(IOException ignored){}
                            baselinePublishedThisPage = true;
                            pageBaselinePublished = true;
                            publishedRev = baselineRev + 1;
                            publishedPage = committed;
                            completeIntent(running.bookId, pageNumber, "BASELINE_PUBLISHED");
                            if(attemptId!=null)progress.baselinePublished(running.bookId,pageNumber,attemptId,publishedRev,false);
                        } catch (PageConflictException | IOException conflict) {
                            // U2：提交失败必须留下可诊断结果，不吞掉异常显示成功。
                            // 随读路径无批量 errors 通道，将诊断记入候选页的 warnings。
                            List<String> note = new ArrayList<>(baselinePage.warnings() == null ? List.of() : baselinePage.warnings());
                            note.add("第 " + pageNumber + " 页识别完成但写入失败，已保留旧版本");
                            Page diagnosed = new Page(baselinePage.pageNumber(), baselinePage.width(), baselinePage.height(), baselinePage.status(),
                                    baselinePage.provider(), baselinePage.blocks(), List.copyOf(note), baselinePage.reviewed(),
                                    baselinePage.error(), baselinePage.sourceRecords(), baselinePage.revision());
                            try{store.writeCandidate(running.bookId,diagnosed);}catch(IOException ignored){}
                            if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"FAILED","BASELINE_WRITE_FAILED",true);
                        }
                    }
                    // U4 phase 2：可选增强。基线已落盘才可读；增强阻塞/失败/取消不影响基线。
                    if (baselinePublishedThisPage && assist) {
                        if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
                        if(attemptId!=null)progress.stage(running.bookId,pageNumber,attemptId,"STRUCTURE");
                        try {
                            PageProcessor.EnrichResult enriched=processor.enrichBaseline(running.bookId,pageNumber,publishedPage,provider,layout,()->running.cancelled||Thread.currentThread().isInterrupted());
                            if(running.cancelled||Thread.currentThread().isInterrupted())throw new CancelledException();
                            if(!mayPublish(running,reservation,expectedJobId))return;
                            if(attemptId!=null)progress.stage(running.bookId,pageNumber,attemptId,"VALIDATING");
                            List<String> mergedWarnings=new ArrayList<>(publishedPage.warnings()==null?List.of():publishedPage.warnings());
                            mergedWarnings.addAll(enriched.warnings());
                            Page enrichedPage=mergeUnresolvedIssues(publishedPage,new Page(publishedPage.pageNumber(),publishedPage.width(),publishedPage.height(),"READY",enriched.actualProvider(),enriched.blocks(),List.copyOf(mergedWarnings),false,null,publishedPage.sourceRecords()));
                            // U4：增强门比较正文块（增强不改写来源，不能比 sourceRecords）。
                            int beforeChars=textChars(publishedPage.blocks());
                            int afterChars=textChars(enrichedPage.blocks());
                            boolean enhancementRegression=beforeChars>0&&afterChars<beforeChars*0.6;
                            boolean enhancementEmpty=afterChars==0&&beforeChars>0;
                            if(enhancementRegression||enhancementEmpty){
                                try{store.writeCandidate(running.bookId,enrichedPage);}catch(IOException ignored){}
                                if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"PARTIAL","ENHANCEMENT_SKIPPED_BASELINE_KEPT",false);
                            } else {
                                try {
                                    if(attemptId!=null)progress.stage(running.bookId,pageNumber,attemptId,"PUBLISHING");
                                    store.commitPage(running.bookId,enrichedPage,publishedRev+1,CommitActor.JOB,expectedJobId,CommitOp.JOB_ENHANCEMENT);
                                    String lifecycle = enriched.partial() ? "PARTIAL" : "SUCCEEDED";
                                    completeIntent(running.bookId, pageNumber, lifecycle);
                                    if(attemptId!=null){progress.baselinePublished(running.bookId,pageNumber,attemptId,publishedRev+1,true);progress.finish(running.bookId,pageNumber,attemptId,lifecycle,enriched.partial()?"ENHANCEMENT_PARTIAL":"ENHANCED",enriched.partial());}
                                } catch (PageConflictException manualKept) {
                                    // U4：人工在此期间保存，人工版本优先；增强保留为过期候选。
                                    try{store.writeCandidate(running.bookId,enrichedPage);}catch(IOException ignored){}
                                    if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"PARTIAL","MANUAL_KEPT_ENHANCEMENT_CANDIDATE",false);
                                } catch (IOException ioFailed) {
                                    try{store.writeCandidate(running.bookId,enrichedPage);}catch(IOException ignored){}
                                    if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"PARTIAL","ENHANCEMENT_WRITE_FAILED",true);
                                }
                            }
                        } catch (CancelledException cancelledDuringEnrich) {
                            // 基线已可读：不恢复、不降级，只收尾。
                            completeIntent(running.bookId, pageNumber, "CANCELLED");
                            if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"CANCELLED","ENHANCEMENT_CANCELLED_BASELINE_KEPT",false);
                            throw cancelledDuringEnrich;
                        } catch (Exception enrichFailed) {
                            try{store.writeCandidate(running.bookId,publishedPage);}catch(IOException ignored){}
                            if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"PARTIAL","ENHANCEMENT_FAILED_BASELINE_KEPT",false);
                        }
                    } else if (baselinePublishedThisPage && attemptId != null
                            && progress.latest(running.bookId, pageNumber) != null) {
                        String lifecycle = progress.latest(running.bookId, pageNumber).lifecycle();
                        if ("RUNNING".equals(lifecycle)) {
                            progress.finish(running.bookId,pageNumber,attemptId,"SUCCEEDED","BASELINE_ONLY",false);
                            completeIntent(running.bookId, pageNumber, "SUCCEEDED");
                        }
                    }
                }
                catch(CancelledException e){
                    if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"CANCELLED","PROCESSING_CANCELLED",false);
                    // U2：取消后仍可恢复旧可读版本（mayRestore 不因 cancelled 返回 false）。
                    // U4：基线已发布时不恢复旧版（基线即有效可读版），只收尾。
                    if(!mayRestore(running,reservation,expectedJobId))return;
                    if (pageBaselinePublished) {
                        completeIntent(running.bookId, pageNumber, "CANCELLED");
                        throw e;
                    }
                    try {
                        store.commitPage(running.bookId,new Page(old.pageNumber(),old.width(),old.height(),old.status(),old.provider(),old.blocks(),old.warnings(),old.reviewed(),old.error(),old.sourceRecords(),null),baselineRev+1,CommitActor.JOB,expectedJobId,CommitOp.JOB_RESTORE);
                        completeIntent(running.bookId, pageNumber, "CANCELLED");
                    } catch (PageConflictException | IOException ignored) { }
                    throw e;}
                catch(Exception e){
                    if(attemptId!=null)progress.finish(running.bookId,pageNumber,attemptId,"FAILED","PROCESSING_FAILED",true);
                    // U2：失败回退保留旧可读版本；归属校验通过即恢复，不因取消而跳过。
                    if(!mayRestore(running,reservation,expectedJobId))return;
                    String detail=safeDetail(e);String message="第 "+pageNumber+" 页处理失败"+(detail==null?"":"："+detail);
                    Page failed;
                    if("READY".equals(old.status())){
                        List<String>warnings=new ArrayList<>(old.warnings()==null?List.of():old.warnings());warnings.add(message);
                        failed=new Page(old.pageNumber(),old.width(),old.height(),"READY",old.provider(),old.blocks(),List.copyOf(warnings),old.reviewed(),message,old.sourceRecords(),null);
                    }else{
                        failed=new Page(old.pageNumber(),old.width(),old.height(),"FAILED",provider,old.blocks(),old.warnings(),old.reviewed(),message,old.sourceRecords(),null);
                    }
                    try {
                        store.commitPage(running.bookId,failed,baselineRev+1,CommitActor.JOB,expectedJobId,CommitOp.JOB_RESTORE);
                        completeIntent(running.bookId, pageNumber, "FAILED");
                    } catch (PageConflictException | IOException ignored) { }
                } finally {
                    if (attemptId != null) {
                        var snapshot = progress.snapshot(running.bookId, pageNumber, attemptId);
                        if (snapshot != null && "RUNNING".equals(snapshot.lifecycle())) {
                            progress.finish(running.bookId, pageNumber, attemptId, "INTERRUPTED", "ATTEMPT_ENDED", true);
                            completeIntent(running.bookId, pageNumber, "INTERRUPTED");
                        }
                    }
                }
            }
        }catch(CancelledException|CancellationException ignored){}
        finally{synchronized(this){for(int p:pages){if(activeReserved.get(p)==running)activeReserved.remove(p);}if(running.lease!=null)running.lease.close();}}}
    // U2：取消与归属已拆分为 ownsAttempt/mayDispatch/mayPublish/mayRestore；
    // 不再使用“是否当前”与“是否取消”混用的单一判断。
    private void write(String id,Job job){try{store.writeJob(id,job);}catch(IOException e){throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,"任务状态保存失败");}}
    // 阶段1：job 代次保护——仅当内存 active 仍是本 worker 且持久化 jobId 一致时才写入
    private boolean stillCurrent(Running running,String expectedJobId){synchronized(this){if(active!=running)return false;}try{Job cur=store.readJob(running.bookId);return cur!=null&&expectedJobId.equals(cur.id());}catch(Exception e){return false;}}
    private void writeIfCurrent(Running running,String expectedJobId,Job job){synchronized(this){if(active!=running)return;try{Job cur=store.readJob(running.bookId);if(cur==null||!expectedJobId.equals(cur.id()))return;
            if("CANCELLING".equals(cur.status())){
                if("CANCELLED".equals(job.status())||List.of("COMPLETED","COMPLETED_WITH_ERRORS","FAILED").contains(job.status()))
                    write(running.bookId,statusJob(cur,"CANCELLED",cur.completed(),cur.total(),cur.currentPage(),null,cur.errors()));
                return;
            }
            if(running.cancelled&&!"CANCELLED".equals(job.status()))return;
            write(running.bookId,job);
        }catch(Exception ignored){}}}
    private static Job statusJob(Job initial,String status,int completed,int total,Integer currentPage,String error,List<String>errors){
        return new Job(initial.id(),status,completed,total,currentPage,error,errors==null?List.of():List.copyOf(errors),Instant.now(),
            initial.pages(),initial.provider(),initial.layout(),initial.splitSpreads(),initial.force(),initial.assist(),initial.fingerprint());
    }
    static String safeDetail(Exception error){if(error instanceof OcrException||error instanceof ApiException){String message=error.getMessage();return message==null||message.isBlank()?null:message;}return null;}
    // 阶段1：零结果保护——旧有文字而新结果为空或显著缩水均视为回归
    static boolean isSignificantRegression(Page oldPage,Page newPage){int oldChars=sourceChars(oldPage),newChars=sourceChars(newPage);return oldChars>0&&newChars<oldChars*.6;}
    static boolean isEmptyResult(Page page){return sourceChars(page)==0&&blockChars(page)==0;}
    static Page strongestBaseline(Page current,Page original){if(current==null)return original;if(current.reviewed()||original==null)return current;return sourceChars(original)>sourceChars(current)?original:current;}
    /** J09/T58：跨版本未解决疑点合并；旧已确认记录按引用保留（含 resolution）。 */
    public static Page mergeUnresolvedIssues(Page previous,Page current){if(previous==null||current==null||"manual".equals(previous.provider())||previous.blocks()==null||current.blocks()==null||previous.sourceRecords()==null||current.sourceRecords()==null)return current;Map<String,Block>oldBlocks=byId(previous.blocks()),oldSources=byId(previous.sourceRecords()),newSources=byId(current.sourceRecords());List<Block>merged=new ArrayList<>(current.blocks().size());boolean changed=false;for(Block block:current.blocks()){Block old=oldBlocks.get(block.id()),oldSource=oldSources.get(block.id()),newSource=newSources.get(block.id());if(!samePaddleSource(old,block,oldSource,newSource)){merged.add(block);continue;}List<ContentIssue>issues=new ArrayList<>(block.issues()==null?List.of():block.issues());for(ContentIssue issue:old.issues()==null?List.<ContentIssue>of():old.issues()){if(issue.resolved()||!validIssue(issue,block)||issues.stream().anyMatch(currentIssue->currentIssue.id().equals(issue.id())||overlaps(currentIssue,issue)))continue;issues.add(issue);changed=true;}if(changedFor(block,issues)){issues.sort(Comparator.comparingInt(ContentIssue::start).thenComparingInt(ContentIssue::end));merged.add(new Block(block.id(),block.type(),block.order(),block.bbox(),block.writingMode(),block.original(),block.simplified(),block.confidence(),block.uncertain()||issues.stream().anyMatch(i->!i.resolved()),block.reviewed(),block.headingLevel(),block.source(),block.sourceIds(),block.suggestion(),block.sourceRect(),List.copyOf(issues)));}else merged.add(block);}if(!changed)return current;BlockValidator.validate(merged);return new Page(current.pageNumber(),current.width(),current.height(),current.status(),current.provider(),List.copyOf(merged),current.warnings(),current.reviewed(),current.error(),current.sourceRecords(),current.revision());}
    private static boolean changedFor(Block block,List<ContentIssue>issues){return issues.size()!=(block.issues()==null?0:block.issues().size());}
    private static Map<String,Block>byId(List<Block>blocks){Map<String,Block>result=new HashMap<>();for(Block block:blocks)if(block!=null&&block.id()!=null)result.putIfAbsent(block.id(),block);return result;}
    private static boolean samePaddleSource(Block old,Block current,Block oldSource,Block newSource){return old!=null&&oldSource!=null&&newSource!=null&&!"manual".equals(old.source())&&Objects.equals(old.original(),current.original())&&Objects.equals(old.simplified(),current.simplified())&&Objects.equals(oldSource.original(),newSource.original())&&isPaddleFamily(oldSource.source())&&isPaddleFamily(newSource.source());}
    private static boolean isPaddleFamily(String source){return source!=null&&(source.startsWith("paddle")||source.startsWith("ppocr"));}
    private static boolean validIssue(ContentIssue issue,Block block){if(issue==null||issue.id()==null||issue.id().isBlank()||issue.id().length()>120||!("unreadable".equals(issue.kind())||"suspected".equals(issue.kind())))return false;String original=block.original()==null?"":block.original(),simplified=block.simplified()==null?"":block.simplified();return issue.start()>=0&&issue.end()>issue.start()&&issue.end()<=original.length()&&issue.simplifiedStart()>=0&&issue.simplifiedEnd()>=issue.simplifiedStart()&&issue.simplifiedEnd()<=simplified.length()&&length(issue.reason())<=1000&&length(issue.replacement())<=1000&&length(issue.inferredText())<=1000;}
    private static boolean overlaps(ContentIssue a,ContentIssue b){return a.start()<b.end()&&b.start()<a.end();}
    private static int length(String value){return value==null?0:value.length();}
    static int sourceChars(Page page){if(page==null)return 0;List<Block>records=page.sourceRecords()!=null&&!page.sourceRecords().isEmpty()?page.sourceRecords():page.blocks();if(records==null)return 0;return records.stream().map(Block::original).filter(Objects::nonNull).mapToInt(s->(int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count()).sum();}
    /** U4：增强门比较正文块文本量（增强不改写来源记录）。 */
    static int textChars(List<Block> blocks){if(blocks==null)return 0;return blocks.stream().map(Block::original).filter(Objects::nonNull).mapToInt(s->(int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count()).sum();}
    private static int blockChars(Page page){if(page==null||page.blocks()==null)return 0;return page.blocks().stream().map(Block::original).filter(Objects::nonNull).mapToInt(s->(int)s.codePoints().filter(cp->!Character.isWhitespace(cp)).count()).sum();}
    private static final class Running{final String bookId,fingerprint;final SettingsService.Lease lease;volatile boolean cancelled;boolean started;Thread thread;Future<?> future;Running(String bookId,String fingerprint,SettingsService.Lease lease){this.bookId=bookId;this.fingerprint=fingerprint;this.lease=lease;}}
}
