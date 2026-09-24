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
    private final BookStore store;private final BookService books;
    private final ExecutorService worker = new ThreadPoolExecutor(8, 8, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(24), r -> { Thread t = new Thread(r, "book-html-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.AbortPolicy());
    private final PageProcessingService pageEngine;
    private Running active;
    private boolean closing;
    private final Map<String, Running> activeReserved = new ConcurrentHashMap<>();
    private static String reservedKey(String bookId, int pageNumber) { return bookId + ":" + pageNumber; }
    // U2：页面级 attempt 登记（调度用；最终写入权限仍以 BookStore 锁内校验为准）。
    private final Map<String, PageAttempt> pageAttempts = new ConcurrentHashMap<>();
    private final java.time.Clock operationClock;
    private static final java.time.Duration OPERATION_RETENTION = java.time.Duration.ofDays(30);
    public record PageTaskDescriptor(String bookId, int pageNumber, int expectedRevision, boolean force) {}
    private record PendingOperation(String key, String fingerprint, boolean overwrite) {}
    private UUID readingReservation;
    private String readingReservationBookId;
    private final Map<String, UUID> readingReservations = new ConcurrentHashMap<>();
    private final Map<UUID, String> readingReservationBooks = new ConcurrentHashMap<>();
    private PageWorkScheduler scheduler;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setScheduler(PageWorkScheduler scheduler) { this.scheduler = scheduler; }
    private SettingsService settings;
    private ProcessingProgressService progress;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setQwenExecutionDependencies(QwenRequestGate gate, ReadingPriority priority) {
        this.pageEngine.setResources(gate,priority);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public JobService(BookStore store,BookService books,PageProcessor processor){
        this(store, books, processor, java.time.Clock.systemUTC());
    }
    JobService(BookStore store, BookService books, PageProcessor processor, java.time.Clock clock) {
        this.store=store; this.books=books; this.operationClock=Objects.requireNonNull(clock);
        this.pageEngine=new PageProcessingService(store,processor);
    }
    @org.springframework.beans.factory.annotation.Autowired public void setSettings(SettingsService settings){this.settings=settings;}
    private CloudConsentService consentService;
    private studio.bookhtml.store.OperationEpochStore epochStore;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setCloudConsentService(CloudConsentService consentService) { this.consentService = consentService; }
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setOperationEpochStore(studio.bookhtml.store.OperationEpochStore epochStore) { this.epochStore = epochStore; }
    /** U4：阶段事件聚合（测试可注入；缺省关闭，不影响正式保存）。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false) public void setProgress(ProcessingProgressService progress){this.progress=progress;this.pageEngine.setProgress(progress);}
    @PostConstruct void recover(){store.recoverPagePublications();store.recoverInterruptedJobs();reconcileAttemptIntents();}
    @PreDestroy void close() {
        RuntimeException shutdownFailure = null;
        synchronized (this) {
            closing = true;
            if (active != null) {
                Running running = active;
                markCancelled(running);
                interruptOnce(running);
                if (!running.started) {
                    try {
                        Job queued = store.readJob(running.bookId);
                        if (queued != null) write(running.bookId, statusJob(queued, "CANCELLED",
                                queued.completed(), queued.total(), queued.currentPage(), null, queued.errors()));
                    } catch (RuntimeException failure) {
                        shutdownFailure = new IllegalStateException("排队任务关闭状态保存失败，请检查恢复日志");
                    } finally {
                        active = null;
                        if (running.lease != null) running.lease.close();
                    }
                }
            }
            cancelAllReserved();
            worker.shutdown(); // Every owned future was cancelled above; do not interrupt durable finalizers again.
        }
        // Never hold the admission monitor while joining: worker finalizers need it to
        // settle journals and release their real ownership before BookStore is closed.
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (!worker.isTerminated()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new IllegalStateException("任务线程未在关闭期限内结束；不能确认数据目录可交接");
                try {
                    if (worker.awaitTermination(remaining, TimeUnit.NANOSECONDS)) break;
                } catch (InterruptedException cancellation) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
        if (scheduler != null) {
            try { scheduler.close(); } catch (Exception ignored) {}
        }
        if (shutdownFailure != null) throw shutdownFailure;
    }

    private void requireOpen() {
        if (closing) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "应用正在关闭，不接受新识别任务");
    }
    public synchronized void reserveReading(UUID reservation,String bookId){
        requireOpen();
        if(readingReservations.containsKey(bookId) || (active!=null && active.bookId.equals(bookId)))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务或阅读窗口正在运行");
        if(store.readBook(bookId).archived())throw new ApiException(HttpStatus.CONFLICT,"本书已归档，请先恢复后再识别");
        Job current=store.readJob(bookId);
        if(current!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(current.status()))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消");
        readingReservation=Objects.requireNonNull(reservation);
        readingReservationBookId=bookId;
        readingReservations.put(bookId, reservation);
        readingReservationBooks.put(reservation, bookId);
        Job readingJob = new Job("reading:" + reservation, "RUNNING", 0, 0, null, null, List.of(), Instant.now(),
                List.of(), "paddle-aistudio", "auto", false, false, false, "reading:" + reservation);
        write(bookId, readingJob);
    }
    public synchronized void releaseReading(UUID reservation){
        String bookId = readingReservationBooks.remove(reservation);
        if (bookId != null) {
            readingReservations.remove(bookId);
            if (Objects.equals(readingReservation, reservation)) {
                readingReservation = null;
                readingReservationBookId = null;
            }
            cancelReservedForBook(bookId);
            try {
                Job cur = store.readJob(bookId);
                if (cur != null && ("reading:" + reservation).equals(cur.id())) {
                    write(bookId, Job.idle());
                }
            } catch (Exception ignored) {}
        } else if(Objects.equals(readingReservation,reservation)){
            readingReservation=null;
            String b = readingReservationBookId;
            readingReservationBookId=null;
            cancelAllReserved();
            if (b != null) {
                try {
                    Job cur = store.readJob(b);
                    if (cur != null && ("reading:" + reservation).equals(cur.id())) {
                        write(b, Job.idle());
                    }
                } catch (Exception ignored) {}
            }
        }
    }
    public synchronized boolean readingJobActive(UUID reservation){
        if (reservation == null) return false;
        String b = readingReservationBooks.get(reservation);
        if (b == null && Objects.equals(readingReservation, reservation)) b = readingReservationBookId;
        if (b == null) return false;
        for (Running r : activeReserved.values()) {
            if (r.bookId.equals(b)) return true;
        }
        return false;
    }
    public synchronized boolean readingJobActive(UUID reservation, int pageNumber){
        if (reservation == null) return false;
        String b = readingReservationBooks.get(reservation);
        if (b == null && Objects.equals(readingReservation, reservation)) b = readingReservationBookId;
        if (b == null) return false;
        Running r = activeReserved.get(reservedKey(b, pageNumber));
        return r != null && r.bookId.equals(b);
    }
    public synchronized void cancelReadingPage(UUID reservation, int pageNumber) {
        if (reservation == null) return;
        String b = readingReservationBooks.get(reservation);
        if (b == null && Objects.equals(readingReservation, reservation)) b = readingReservationBookId;
        if (b == null) return;
        Running running = activeReserved.get(reservedKey(b, pageNumber));
        if (running != null) {
            if (scheduler != null) {
                scheduler.cancel(b, pageNumber);
            }
            // U2：只标记取消并中断，不提前从登记表删除。物理槽与登记在 worker
            // 收尾（finally）时释放；取消后仍可恢复旧可读版本（mayRestore）。
            markCancelled(running);
            interruptOnce(running);
            if (!running.started) releaseReserved(running, "CANCELLED");
        }
    }
    private synchronized void cancelReservedForBook(String bookId) {
        for (Running running : new HashSet<>(activeReserved.values())) {
            if (running.bookId.equals(bookId)) {
                markCancelled(running);
                interruptOnce(running);
            }
        }
        for (Running running : new HashSet<>(activeReserved.values())) {
            if (running.bookId.equals(bookId) && !running.started) releaseReserved(running, "CANCELLED");
        }
    }
    private synchronized void cancelAllReserved() {
        for (Running running : new HashSet<>(activeReserved.values())) {
            markCancelled(running);
            interruptOnce(running);
        }
        for (Running running : new HashSet<>(activeReserved.values())) {
            if (!running.started) releaseReserved(running, "CANCELLED");
        }
    }
    /** Serialize library archiving with both batch admission and reading-window reservation. */
    public synchronized Book updateLibrary(String bookId,String title,Boolean archived){
        if(Boolean.TRUE.equals(archived)
                && ((active!=null&&active.bookId.equals(bookId)) || readingReservations.containsKey(bookId) || bookId.equals(readingReservationBookId)))
            throw new ApiException(HttpStatus.CONFLICT,"本书正在识别，请等待任务完成或停止随读后归档");
        return books.updateLibrary(bookId,title,archived);
    }
    public synchronized Job submitReserved(UUID reservation, String bookId, JobRequest request) {
        return submitReserved(reservation, bookId, request, null);
    }

    private Job submitReserved(UUID reservation, String bookId, JobRequest request, PendingOperation operation) {
        requireOpen();
        UUID expected = readingReservations.get(bookId);
        if (expected == null && Objects.equals(readingReservation, reservation) && Objects.equals(readingReservationBookId, bookId)) {
            expected = reservation;
        }
        if (!Objects.equals(expected, reservation))
            throw new ApiException(HttpStatus.CONFLICT, "阅读窗口预约已失效");
        if (active != null && active.bookId.equals(bookId)) throw new ApiException(HttpStatus.CONFLICT, "已有识别任务正在运行");
        Book book = store.readBook(bookId);
        if (book.archived()) throw new ApiException(HttpStatus.CONFLICT, "本书已归档，请先恢复后再识别");
        String provider = request.provider() == null ? (settings == null ? "paddle-aistudio" : settings.state().defaultProvider()) : request.provider();
        if (settings != null && !List.of("paddle-aistudio", "ppocr").contains(provider))
            throw new ApiException(HttpStatus.BAD_REQUEST, "新任务仅支持 AI Studio 与 PP-OCRv6 通道");
        String layout = request.layout() == null ? "auto" : request.layout();
        List<Integer> pages = PageRanges.parse(request.pages(), book.totalPages());
        String fingerprint = requestFingerprint(bookId, pages.toString(), provider, layout,
                String.valueOf(request.splitSpreads()), String.valueOf(request.force()), String.valueOf(request.assistEnabled()));
        for (int page : pages) {
            Running existing = activeReserved.get(reservedKey(bookId, page));
            if (existing != null) {
                if (!existing.cancelled && existing.fingerprint.equals(fingerprint)) return existing.job;
                throw new ApiException(HttpStatus.CONFLICT, "已有识别任务正在运行或正在取消");
            }
        }
        SettingsService.Lease lease = settings == null ? null : settings.beginWork();
        Running running = new Running(bookId, fingerprint, lease);
        String expectedJobId = "reading:" + reservation + ":" + (pages.isEmpty() ? "0" : pages.get(0)) + ":" + UUID.randomUUID();
        Job queued = new Job(expectedJobId, "RUNNING", 0, pages.size(), pages.isEmpty() ? null : pages.get(0),
                null, List.of(), Instant.now(), List.copyOf(pages), provider, layout,
                request.splitSpreads(), request.force(), request.assistEnabled(), fingerprint);
        running.job = queued;
        try {
            for (int page : pages) {
                Page published = store.readPage(bookId, page);
                PageAttempt attempt = registerAttempt(bookId, page, BookStore.revisionOrZero(published),
                        List.of("JOB_BASELINE", "JOB_ENHANCEMENT", "JOB_COMPLETE", "JOB_RESTORE"), "reading:"+reservation, operation!=null && operation.overwrite(), operation, queued);
                running.attempts.put(page, attempt);
                activeReserved.put(reservedKey(bookId, page), running);
                if (progress != null) progress.begin(attempt, BookStore.revisionOrZero(published),
                        published != null && "READY".equals(published.status()));
            }
            running.future = worker.submit(() -> runReserved(running, reservation, expectedJobId, pages, provider,
                    layout, request.splitSpreads(), request.force(), request.assistEnabled()));
            return queued;
        } catch (RuntimeException rejected) {
            releaseReserved(running, "FAILED");
            throw rejected;
        }
    }

    private synchronized void releaseReserved(Running running, String fallbackLifecycle) {
        for (var entry : running.attempts.entrySet()) {
            int page = entry.getKey();
            PageAttempt attempt = entry.getValue();
            completeIntent(running, page, fallbackLifecycle);
            activeReserved.remove(reservedKey(running.bookId, page), running);
        }
        if (running.lease != null) running.lease.close();
    }

    private static String requestFingerprint(String... values) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                String part = value == null ? "-1:" : value.length() + ":" + value;
                digest.update(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /**
     * U2：安全重新处理准入。保持当前可读 Page 不变（不在此处写 PENDING），
     * 经版本/人工保护/活动 attempt/授权校验后创建独立 attempt。
     */
    public synchronized Job requestReprocess(UUID reservation, String bookId, int pageNumber,
                                             studio.bookhtml.api.PageReprocessRequest request,
                                             String provider, String layout,
                                             boolean splitSpreads, boolean assist) {
        requireOpen();
        if (readingReservation == null || !readingReservation.equals(reservation)
                || !Objects.equals(readingReservationBookId, bookId))
            throw new ApiException(HttpStatus.CONFLICT, "阅读窗口预约已失效");
        Book book = store.readBook(bookId);
        if (book.archived()) throw new ApiException(HttpStatus.CONFLICT, "本书已归档，请先恢复后再识别");
        if (pageNumber < 1 || pageNumber > book.totalPages())
            throw new ApiException(HttpStatus.BAD_REQUEST, "页码超出书籍范围");
        if (request == null || request.clientOperationId() == null || request.clientOperationId().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 clientOperationId");
        if (request.clientOperationId().length() > 200 || request.expectedRevision() == null || request.expectedRevision() < 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "操作 ID 或期望版本无效");
        // Book scope is stable across browser reservations and process restarts.
        // Do not persist raw client IDs or use them as filesystem paths.
        String operationKey = requestFingerprint(bookId, request.clientOperationId());
        String fingerprint = requestFingerprint(bookId, String.valueOf(pageNumber), provider, layout,
                String.valueOf(splitSpreads), String.valueOf(assist), String.valueOf(request.expectedRevision()),
                String.valueOf(request.explicitOverwriteAuthorization()), request.provider(), String.valueOf(request.assist()));
        if (consentService != null) {
            consentService.validateAuthorization(null, bookId, provider == null ? "paddle-aistudio" : provider,
                    request.assistEnabled(), false);
        }
        if (epochStore != null) {
            ReprocessOperation epochKnown = epochStore.findOperation(bookId, operationKey, request.operationEpoch(), operationClock.instant());
            if (epochKnown != null) {
                if (!epochKnown.fingerprint().equals(fingerprint))
                    throw new ApiException(HttpStatus.CONFLICT, "相同操作 ID 但参数不一致，已拒绝");
                if (!operationClock.instant().isBefore(epochKnown.expiresAt()))
                    throw new ApiException(HttpStatus.GONE, "操作记录已过期，请确认后使用新操作 ID；未重新派发");
                return epochKnown.response();
            }
        }
        PageAttempt.Journal journal = readJournal(bookId);
        ReprocessOperation known = journal.operations().get(operationKey);
        if (known != null) {
            if (!known.fingerprint().equals(fingerprint))
                throw new ApiException(HttpStatus.CONFLICT, "相同操作 ID 但参数不一致，已拒绝");
            if (!operationClock.instant().isBefore(known.expiresAt()))
                throw new ApiException(HttpStatus.GONE, "操作记录已过期，请确认后使用新操作 ID；未重新派发");
            return known.response();
        }
        // Never evict old IDs and silently interpret them as permission to charge again.
        if (journal.operations().size() >= PageAttempt.Journal.MAX_OPERATIONS)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "本书操作回执容量已满，需归档维护；重启不会清空幂等保护");
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
        Running existing = activeReserved.get(reservedKey(bookId, pageNumber));
        if (existing != null) throw new ApiException(HttpStatus.CONFLICT, "本页已有识别任务正在运行或正在取消");
        String channel = provider == null ? "paddle-aistudio" : provider;
        JobRequest jobRequest = new JobRequest(String.valueOf(pageNumber), channel, layout,
                splitSpreads, true, assist);
        return submitReserved(reservation, bookId, jobRequest, new PendingOperation(operationKey, fingerprint, request.explicitOverwriteAuthorization()));
    }

    private PageAttempt registerAttempt(String bookId, int pageNumber, int expectedRevision,
            List<String> allowedOps, String ownerJobId, boolean overwrite, PendingOperation operation, Job response) {
        try {
            Instant accepted=operationClock.instant();
            PageAttempt attempt=store.registerPageAttempt(bookId,pageNumber,expectedRevision,ownerJobId,allowedOps,
                    overwrite,operation==null?null:operation.key(),
                    owner->new ReprocessOperation(bookId,operation.fingerprint(),owner.attemptId(),owner.generation(),
                            response,accepted,accepted.plus(OPERATION_RETENTION),"RUNNING"));
            pageAttempts.put(attempt.key(),attempt);
            if (epochStore != null && operation != null) {
                try {
                    epochStore.recordOperation(bookId, operation.key(),
                            new ReprocessOperation(bookId, operation.fingerprint(), attempt.attemptId(), attempt.generation(),
                                    response, accepted, accepted.plus(OPERATION_RETENTION), "RUNNING"), null);
                } catch (Exception ignored) {}
            }
            return attempt;
        } catch (IOException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"任务身份记录保存失败，未派发云请求");
        }
    }

    private void completeIntent(Running running, int pageNumber, String lifecycle) {
        PageAttempt owner=running.attempts.get(pageNumber);
        if (owner==null) return;
        try {
            if(running.settledPages.add(pageNumber)) pageEngine.finish(owner,lifecycle,"ATTEMPT_EXITED");
            PageAttempt saved=store.pageAttempt(running.bookId,pageNumber);
            if (saved!=null) pageAttempts.computeIfPresent(owner.key(),(key,current)->
                    current.attemptId().equals(saved.attemptId()) ? saved : current);
        } catch (Exception failure) {
            if (progress!=null) progress.finish(running.bookId,pageNumber,owner.attemptId(),
                    "UNKNOWN","ATTEMPT_JOURNAL_WRITE_FAILED",false);
        }
    }

    // Called under the admission monitor. Repeated stop/close is idempotent at
    // the interruption boundary as well as at the durable cancellation fence.
    private void interruptOnce(Running running) {
        if (running.interruptionRequested) return;
        running.interruptionRequested = true;
        if (running.future != null) running.future.cancel(true);
        else if (running.thread != null) running.thread.interrupt();
    }

    private void markCancelled(Running running) {
        running.cancelled=true;
        for (PageAttempt attempt:running.attempts.values()) {
            try { store.revokePageAttempt(attempt); }
            catch (Exception failure) {
                System.getLogger(JobService.class.getName()).log(System.Logger.Level.WARNING,
                        "Cancellation fence persistence failed; publication remains denied in this process");
            }
        }
    }

    private PageAttempt.Journal readJournal(String bookId) {
        try {
            PageAttempt.Journal journal =
                    store.readSidecar(store.pageAttemptsPath(bookId), PageAttempt.Journal.class);
            if (journal == null) return PageAttempt.Journal.empty();
            for (var entry : journal.intents().entrySet()) {
                PageAttempt a = entry.getValue();
                if (!bookId.equals(a.bookId()) || !entry.getKey().equals(a.key()) || a.pageNumber() < 1
                        || a.attemptId() == null || a.runId() == null || a.generation() < 1
                        || a.expectedRevision() < 0 || a.startedAt() == null || a.updatedAt() == null
                        || a.lifecycle() == null) throw new IllegalStateException("invalid attempt identity");
            }
            for (var entry : journal.operations().entrySet()) {
                if (!entry.getKey().matches("[0-9a-f]{64}") || !bookId.equals(entry.getValue().bookId()))
                    throw new IllegalStateException("invalid operation ownership");
            }
            return journal;
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "任务身份记录不可读，未派发云请求");
        }
    }

    /**
     * U4：重启对照意图与当前页。内容已一致补终态；尚未发布保留现有可读页标
     * INTERRUPTED；无法证明所有权不覆盖；绝不重新发送云请求。
     */
    public synchronized void reconcileAttemptIntents() {
        if (active!=null || !activeReserved.isEmpty()) return;
        for (Book book:store.listBooks()) {
            try { store.reconcilePageAttempts(book.id()); }
            catch (Exception invalid) {
                System.getLogger(JobService.class.getName()).log(System.Logger.Level.WARNING,
                        "Attempt recovery incomplete; no cloud request has been resubmitted");
            }
        }
    }

    /** U2：测试可见的 attempt 登记快照（调度用，不代表最终写入权限）。 */
    Map<String, PageAttempt> attemptSnapshot() {
        return Map.copyOf(pageAttempts);
    }

    /** U2：本书本页的登记是否仍归属该 attempt（不判断取消，供恢复路径使用）。 */
    private boolean ownsAttempt(Running running, UUID reservation) {
        UUID expected = readingReservations.get(running.bookId);
        if (expected == null && Objects.equals(readingReservation, reservation)) expected = reservation;
        if (!Objects.equals(expected, reservation)) return false;
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
    private synchronized boolean mayPublish(Running running, UUID reservation, String expectedJobId) {
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
        requireOpen();
        if(readingReservations.containsKey(bookId) || (readingReservation!=null && bookId.equals(readingReservationBookId)))throw new ApiException(HttpStatus.CONFLICT,"阅读窗口正在运行，请先停止随读处理");
        SettingsService.Lease lease=settings==null?null:settings.beginWork();boolean transferred=false;try{Book book=books.get(bookId);if(book.archived())throw new ApiException(HttpStatus.CONFLICT,"本书已归档，请先恢复后再识别");String provider=request.provider()==null?(settings==null?"paddle-aistudio":settings.state().defaultProvider()):request.provider();if(settings!=null&&!List.of("paddle-aistudio","ppocr",HandwritingTranscribeService.PROVIDER_ID).contains(provider))throw new ApiException(HttpStatus.BAD_REQUEST,"新任务仅支持 AI Studio、PP-OCRv6 与手写/影印稿转写通道");String layout=request.layout()==null?"auto":request.layout();List<Integer> pages=PageRanges.parse(request.pages(),book.totalPages());String fingerprint=bookId+"|"+pages+"|"+provider+"|"+layout+"|"+request.splitSpreads()+"|"+request.force()+"|"+request.assistEnabled();
        if(active!=null){if(active.bookId.equals(bookId)&&!active.cancelled&&active.fingerprint.equals(fingerprint))return store.readJob(bookId);throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消");}
        Job current = null;
        try { current = store.readJob(bookId); } catch (Exception ignored) { }
        if(current!=null&&List.of("QUEUED","RUNNING","CANCELLING").contains(current.status()))throw new ApiException(HttpStatus.CONFLICT,"已有识别任务正在运行或正在取消，请稍后再试");
        if (consentService != null) {
            consentService.validateAuthorization(null, bookId, provider, request.assistEnabled(), false);
        }
        Job queued=new Job(UUID.randomUUID().toString(),"QUEUED",0,pages.size(),null,null,List.of(),Instant.now(),
            List.copyOf(pages),provider,layout,request.splitSpreads(),request.force(),request.assistEnabled(),fingerprint);
        Map<Integer,Integer> authorizedRevisions=new HashMap<>();
        for(int page:pages) {
            Page authorized=store.readPage(bookId,page);
            if(authorized!=null) authorizedRevisions.put(page,BookStore.revisionOrZero(authorized));
        }
        try {
            var batch = studio.bookhtml.domain.DurableBatchAdmission.create(bookId, queued.id(), pages,
                    authorizedRevisions, fingerprint, 1L, null);
            java.nio.file.Path bdir = store.bookDir(bookId).resolve("batches");
            java.nio.file.Files.createDirectories(bdir);
            studio.bookhtml.store.DurableJson.write(bdir.resolve(queued.id() + ".json"), batch, store.json(), 512 * 1024);
        } catch (Exception ignored) {}
        write(bookId,queued);Running running=new Running(bookId,fingerprint,lease);
        running.overwriteRevisions=Map.copyOf(authorizedRevisions);active=running;
        try{running.future=worker.submit(()->run(running,queued,pages,provider,layout,request.splitSpreads(),request.force(),request.assistEnabled()));}
        catch(RuntimeException e){active=null;
            try{write(bookId,statusJob(queued,"FAILED",0,pages.size(),null,"任务无法启动",List.of("任务无法启动")));}
            catch(RuntimeException ignored){}throw e;}
        transferred=true;return queued;
        }finally{if(!transferred&&lease!=null)lease.close();}}
    public Job get(String bookId){books.get(bookId);return store.readJob(bookId);}
    // 运行中取消先写 CANCELLING，由 worker 收尾时写 CANCELLED；排队未启动可直接取消。
    public synchronized Job cancel(String bookId){books.get(bookId);Job job=store.readJob(bookId);
        if(readingReservations.containsKey(bookId) || bookId.equals(readingReservationBookId)){cancelReservedForBook(bookId);}
        if(active==null||!active.bookId.equals(bookId)||!List.of("QUEUED","RUNNING","CANCELLING").contains(job.status()))return job;
        if("CANCELLING".equals(job.status()))return job;
        Running running=active;markCancelled(running);
        if (scheduler != null) {
            scheduler.cancelBook(bookId);
        }
        if(!running.started){running.future.cancel(false);Job cancelled=statusJob(job,"CANCELLED",job.completed(),job.total(),job.currentPage(),null,job.errors());write(bookId,cancelled);active=null;if(running.lease!=null)running.lease.close();return cancelled;}
        Job cancelling=statusJob(job,"CANCELLING",job.completed(),job.total(),job.currentPage(),null,job.errors());write(bookId,cancelling);interruptOnce(running);return cancelling;}
    private void run(Running running,Job initial,List<Integer> pages,String provider,String layout,boolean split,boolean force,boolean assist) {
        synchronized(this) {
            if(active!=running || running.cancelled)return;
            running.started=true; running.thread=Thread.currentThread();
        }
        int completed=0; List<String> errors=new ArrayList<>();
        try {
            writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",0,pages.size(),null,null,List.of()));
            for(int page:pages) {
                if(running.cancelled || Thread.currentThread().isInterrupted())throw new CancelledException();
                if(!stillCurrent(running,initial.id()))return;
                writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),page,null,List.copyOf(errors)));
                Page old=store.readPage(running.bookId,page);
                if(old==null)errors.add("第 "+page+" 页数据缺失，已跳过");
                else if(force && !Objects.equals(running.overwriteRevisions.get(page),BookStore.revisionOrZero(old)))
                    errors.add("第 "+page+" 页在批量确认后已更新，未覆盖；请重新确认该页");
                else if(!"READY".equals(old.status()) || force) {
                    try {
                        PageAttempt attempt;
                        synchronized(this) {
                            if(running.cancelled)throw new CancelledException();
                            attempt=registerAttempt(running.bookId,page,force?running.overwriteRevisions.get(page):BookStore.revisionOrZero(old),
                                    List.of("JOB_BASELINE","JOB_ENHANCEMENT","JOB_RESTORE"),initial.id(),force,null,null);
                            running.attempts.put(page,attempt);
                        }
                        PageProcessingService.Result result=executePage(running,new PageProcessingService.Request(attempt,
                                provider,layout,split,force,assist,()->running.cancelled||Thread.currentThread().isInterrupted(),
                                ()->stillCurrent(running,initial.id())));
                        if(!"SUCCEEDED".equals(result.lifecycle()))
                            errors.add("第 "+page+" 页"+outcomeText(result.messageCode()));
                        if("CANCELLED".equals(result.lifecycle()))throw new CancelledException();
                    } catch(CancelledException cancelled) { throw cancelled; }
                    catch(Exception failure) { errors.add("第 "+page+" 页无法完成，保留已有版本"); }
                    finally {
                        completeIntent(running,page,running.cancelled?"CANCELLED":"FAILED");
                        running.attempts.remove(page);
                    }
                }
                completed++;
                writeIfCurrent(running,initial.id(),statusJob(initial,"RUNNING",completed,pages.size(),page,null,List.copyOf(errors)));
            }
            if(running.cancelled || Thread.currentThread().isInterrupted())throw new CancelledException();
            writeIfCurrent(running,initial.id(),statusJob(initial,errors.isEmpty()?"COMPLETED":"COMPLETED_WITH_ERRORS",
                    completed,pages.size(),null,null,List.copyOf(errors)));
        } catch(CancelledException | CancellationException cancelled) {
            if(stillCurrent(running,initial.id())) {
                Job current=store.readJob(running.bookId);
                writeIfCurrent(running,initial.id(),statusJob(initial,"CANCELLED",current.completed(),pages.size(),current.currentPage(),null,current.errors()));
            }
        } catch(Exception failure) {
            writeIfCurrent(running,initial.id(),statusJob(initial,"FAILED",completed,pages.size(),null,"任务执行失败",List.copyOf(errors)));
        } finally {
            synchronized(this) { if(active==running)active=null; if(running.lease!=null)running.lease.close(); }
        }
    }
    private PageProcessingService.Result executePage(Running running,PageProcessingService.Request request) {
        try {
            if (scheduler != null) {
                PageWorkScheduler.Priority p;
                if (active == running) {
                    p = PageWorkScheduler.Priority.P3;
                } else {
                    int center = running.job != null && running.job.currentPage() != null ? running.job.currentPage() : -1;
                    if (request.page() == center) {
                        p = PageWorkScheduler.Priority.P0;
                    } else if (request.force()) {
                        p = PageWorkScheduler.Priority.P1;
                    } else {
                        p = PageWorkScheduler.Priority.P2;
                    }
                }
                CompletableFuture<PageProcessingService.Result> future = scheduler.schedule(request, p);
                try {
                    return future.get();
                } catch (InterruptedException ie) {
                    scheduler.cancel(request.book(), request.page());
                    for(;;) {
                        try { future.get(); break; }
                        catch(InterruptedException repeated) { /* Preserve ownership until physical cleanup. */ }
                        catch(ExecutionException | CancellationException done) { break; }
                    }
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                } catch (ExecutionException ee) {
                    if (ee.getCause() instanceof CancelledException ce) throw ce;
                    if (ee.getCause() instanceof RuntimeException re) throw re;
                    throw new RuntimeException(ee.getCause());
                }
            }
            return pageEngine.execute(request);
        }
        finally { running.settledPages.add(request.attempt().pageNumber()); }
    }
    private void runReserved(Running running,UUID reservation,String expectedJobId,List<Integer> pages,
                             String provider,String layout,boolean split,boolean force,boolean assist) {
        try {
            synchronized(this) {
                UUID expected = readingReservations.get(running.bookId);
                if (expected == null && Objects.equals(readingReservation, reservation)) expected = reservation;
                if(!Objects.equals(expected,reservation) || running.cancelled)return;
                running.started=true; running.thread=Thread.currentThread();
            }
            for(int page:pages) {
                if(running.cancelled || Thread.currentThread().isInterrupted())throw new CancelledException();
                PageAttempt attempt=running.attempts.get(page);
                if(attempt==null)continue;
                PageProcessingService.Result result=executePage(running,new PageProcessingService.Request(attempt,
                        provider,layout,split,force,assist,()->running.cancelled||Thread.currentThread().isInterrupted(),
                        ()->mayPublish(running,reservation,expectedJobId)));
                if("CANCELLED".equals(result.lifecycle()))throw new CancelledException();
            }
        } catch(CancelledException | CancellationException cancelled) {
            // The page engine has settled the executed attempt; queued attempts settle below.
        } finally { releaseReserved(running,running.cancelled?"CANCELLED":"INTERRUPTED"); }
    }
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
    private static final class Running{Map<Integer,Integer> overwriteRevisions=Map.of();final Set<Integer> settledPages=ConcurrentHashMap.newKeySet(); final Map<Integer, PageAttempt> attempts = new ConcurrentHashMap<>(); Job job;final String bookId,fingerprint;final SettingsService.Lease lease;volatile boolean cancelled;boolean started;boolean interruptionRequested;Thread thread;Future<?> future;Running(String bookId,String fingerprint,SettingsService.Lease lease){this.bookId=bookId;this.fingerprint=fingerprint;this.lease=lease;}}

    /** P1：把内部结果码翻成用户能懂的话，不在界面暴露 OCR_RECOVERY_PARTIAL 这类内部标识。 */
    private static String outcomeText(String messageCode){
        String code=messageCode==null?"":messageCode;
        if(code.contains("OCR_RECOVERY_PARTIAL"))return "已识别主要文字，但未能逐字验证，建议对照原稿核对";
        if(code.contains("PROCESSING_FAILED"))return "本次处理未完成，已保留原有版本";
        if(code.contains("BLANK"))return "疑似空白页或仅含插图，已保留原稿";
        return "结果不完整，已保留原稿与已有内容";
    }
}
