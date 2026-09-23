package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.decision.DecisionModels;
import studio.bookhtml.decision.IssueBasis;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.ContentIssue;
import studio.bookhtml.domain.Job;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.PageAttempt;
import studio.bookhtml.domain.ReprocessOperation;

import studio.bookhtml.domain.PageHead;
import studio.bookhtml.domain.SourceChange;
import studio.bookhtml.service.BookIndexService;
import studio.bookhtml.service.HeadingText;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Repository
public class BookStore {
    private final Path booksRoot;
    private final ObjectMapper json;
    private final Object dirLock;
    private final DataDirectoryLease lease;
    private final SourceIdentityGuard sourceIdentity = new SourceIdentityGuard();
    private final PageCommitJournal commits;
    private final AttemptAuthority authority;
    private final SourceChangeJournal sourceJournal;
    private final PageHeadStore headStore;
    private BookIndexService indexService;
    private final CloudConsentStore consentStore;
    private final ReadingPolicyStore policyStore;
    private final OperationEpochStore epochStore;
    public record PageChange(String bookId, Page previous, Page committed, long sourceEpoch) {}
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> pageEpochs = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<java.util.function.Consumer<PageChange>> pageListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    public long pageEpoch(String id) {
        var epoch = pageEpochs.get(id);
        return epoch == null ? 0 : epoch.get();
    }
    public void addPageChangeListener(java.util.function.Consumer<PageChange> listener) {
        pageListeners.add(java.util.Objects.requireNonNull(listener));
    }

    public BookStore(AppProperties properties, ObjectMapper json) throws IOException {
        Path dataDir = properties.dataDir().toAbsolutePath().normalize();
        // A1-05：先取得单写者租约（失败即拒绝启动），再建目录结构
        this.lease = DataDirectoryLease.acquire(dataDir);
        this.dirLock = lease.monitor();
        this.booksRoot = lease.realPath().resolve("books");
        this.json = json;
        this.commits = new PageCommitJournal(json);
        this.authority = new AttemptAuthority(dirLock, sourceIdentity, commits, lease.revokedAttempts());
        this.sourceJournal = new SourceChangeJournal(json);
        this.headStore = new PageHeadStore(json);
        this.indexService = new BookIndexService(json);
        Path realDataDir = lease.realPath();
        this.consentStore = new CloudConsentStore(realDataDir, json);
        this.policyStore = new ReadingPolicyStore(realDataDir, json);
        this.epochStore = new OperationEpochStore(realDataDir, json);
        Files.createDirectories(booksRoot);
    }

    public SourceChangeJournal sourceJournal() { return sourceJournal; }
    public PageHeadStore headStore() { return headStore; }
    public BookIndexService indexService() { return indexService; }
    public void setIndexService(BookIndexService indexService) { this.indexService = indexService; }
    public CloudConsentStore consentStore() { return consentStore; }
    public ReadingPolicyStore policyStore() { return policyStore; }
    public OperationEpochStore epochStore() { return epochStore; }
    public ObjectMapper json() { return json; }
    public PageCommitJournal pageCommitJournal() { return commits; }
    public String pageContentHash(Page page) throws IOException { return commits.hash(page); }

    public PageAttempt registerPageAttempt(String id, int page, int revision, String jobId,
            List<String> operations, boolean overwrite, String operationKey,
            java.util.function.Function<PageAttempt,ReprocessOperation> receipt) throws IOException {
        return authority.admit(this,id,page,revision,jobId,operations,overwrite,operationKey,receipt);
    }
    public PageAttempt pageAttempt(String id,int page) {
        synchronized(dirLock) { return authority.read(this,id).intents().get(id+":"+page); }
    }
    public void finishPageAttempt(PageAttempt owner, String lifecycle) throws IOException { authority.finish(this,owner,lifecycle); }
    public void revokePageAttempt(PageAttempt owner) throws IOException { authority.revoke(this,owner); }
    public String recoveredAttemptOutcome(PageAttempt owner) {
        synchronized (dirLock) { return authority.recoveredOutcome(this,owner); }
    }
    public void reconcilePageAttempts(String id) throws IOException {
        synchronized(dirLock) {
            var journal=authority.read(this,id);
            var intents=new java.util.LinkedHashMap<>(journal.intents());
            journal.intents().forEach((key,attempt)->{
                if (!AttemptAuthority.TERMINAL.contains(attempt.lifecycle()))
                    intents.put(key,attempt.withLifecycle(authority.recoveredOutcome(this,attempt)));
            });
            var reconciled=journal.withIntents(intents);
            var receipts=new java.util.LinkedHashMap<>(reconciled.operations());
            receipts.replaceAll((key,r)->r.terminal()?r:r.finish("INTERRUPTED",java.time.Instant.now()));
            var result=new PageAttempt.Journal(intents,receipts);
            if (!result.equals(journal)) writeSidecar(pageAttemptsPath(id),result);
        }
    }
    public void recoverPagePublications() {
        for (Book book:listBooks()) {
            Path directory=bookDir(book.id()).resolve("pages/commits");
            if (!Files.exists(directory,LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                DurableJson.rejectLinks(directory);
                try (var paths=Files.list(directory)) {
                    for (var iterator=paths.iterator();iterator.hasNext();) {
                        String name=iterator.next().getFileName().toString();
                        if (!name.matches("[1-9][0-9]*\\.json")) continue;
                        int page=Integer.parseInt(name.substring(0,name.length()-5));
                        if (page>book.totalPages()) throw new IOException("commit page out of range");
                        synchronized(dirLock) { commits.reconcile(bookDir(book.id()),book.id(),page,readPage(book.id(),page)); }
                    }
                }
                synchronized(dirLock) { sourceJournal.reconcile(bookDir(book.id()), book.id(), this); }
            } catch (Exception invalid) {
                System.getLogger(BookStore.class.getName()).log(System.Logger.Level.WARNING,
                        "Publication journal needs inspection; page content is preserved and affected writes fail closed");
            }
        }
    }

    public void verifyAttemptForDispatch(PageAttempt owner, CommitOp operation) throws IOException {
        var source=sourceIdentity.capture(pdf(owner.bookId()));
        synchronized (dirLock) {
            Page current=readPage(owner.bookId(),owner.pageNumber());
            if (current==null) throw new IOException("attempt page missing");
            var claim=authority.claim(this,owner.bookId(),owner.pageNumber(),owner.commitIdentity("SUCCEEDED"),revisionOrZero(current));
            var log=commits.reconcile(bookDir(owner.bookId()),owner.bookId(),owner.pageNumber(),current);
            authority.requireLive(this,claim,current,operation,source,log);
        }
    }

    /** 释放数据目录租约（Spring 销毁时调用；测试可显式调用验证释放语义）。 */
    @jakarta.annotation.PreDestroy
    public void close() {
        lease.close();
    }

    public Path createBookDirectory(String id) throws IOException {
        Path dir = bookDir(id); Files.createDirectories(dir.resolve("pages")); return dir;
    }
    public Path bookDir(String id) {
        try { UUID.fromString(id); } catch (RuntimeException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "书籍编号无效"); }
        Path result = booksRoot.resolve(id).normalize();
        if (!result.getParent().equals(booksRoot)) throw new ApiException(HttpStatus.BAD_REQUEST, "书籍编号无效");
        return result;
    }
    public Path pdf(String id) { return bookDir(id).resolve("source.pdf"); }
    public Path tmpDir() {
        return tmpRoot();
    }
    /** R05：临时文件按用途隔离，各清理器只能操作自己的根目录。 */
    public Path renderTmpDir() {
        return tmpRoot().resolve("render");
    }
    public Path exportTmpDir() {
        return tmpRoot().resolve("export");
    }
    public Path ocrTmpDir() {
        return tmpRoot().resolve("ocr");
    }
    private Path tmpRoot() {
        Path dir = booksRoot.getParent() == null ? Path.of("tmp") : booksRoot.getParent().resolve("tmp");
        try { Files.createDirectories(dir); } catch (IOException ignored) { }
        return dir;
    }
    public Path pagePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".json"); }
    public Path originalPagePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".original.json"); }

    public void writeBook(Book book) throws IOException { synchronized (dirLock) { checkInjected("book"); atomic(bookDir(book.id()).resolve("book.json"), book); notifyBookChanged(book.id()); } }
    public Book readBook(String id) { return read(bookDir(id).resolve("book.json"), Book.class, "未找到该书籍"); }
    /** Read-modify-write under the directory lease lock, so page-stat touches cannot undo a library edit. */
    public Book updateBook(String id, UnaryOperator<Book> change) throws IOException {
        synchronized (dirLock) {
            Path path = bookDir(id).resolve("book.json");
            Book updated = change.apply(read(path, Book.class, "未找到该书籍"));
            checkInjected("book");
            atomic(path, updated);
            notifyBookChanged(id);
            return updated;
        }
    }
    public List<Book> listBooks() {
        if (!Files.isDirectory(booksRoot)) return List.of();
        try (Stream<Path> paths = Files.list(booksRoot)) {
            return paths.map(p -> p.resolve("book.json")).filter(Files::isRegularFile)
                .map(p -> read(p, Book.class, "书籍数据损坏"))
                .sorted(Comparator.comparing(Book::createdAt).reversed()).toList();
        } catch (IOException e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取书籍列表失败"); }
    }
    public void writePage(String id, Page page, boolean preserveOriginal) throws IOException {
        synchronized (dirLock) {
            Path original = originalPagePath(id, page.pageNumber());
            if (preserveOriginal && !Files.exists(original)) atomic(original, page);
            Path target = pagePath(id, page.pageNumber());
            Page existing = null;
            if (Files.exists(target)) { try { existing = json.readValue(target.toFile(), Page.class); } catch (Exception ignored) { existing = null; } }
            persistNewRevision(id, existing, page);
        }
    }

    /**
     * R03/A1-04：条件提交——读取当前页、校验 expectedRevision、按操作类别校验
     * 任务状态与页集合资格、归档旧版本、写入新版本在同一目录锁内完成。
     * 成功返回此次真正提交的不可变结果；调用方不得再 readPage。
     */
    public Page commitPage(String id, Page proposed, int expectedRevision, CommitActor actor, String identity, CommitOp op) throws IOException {
        validateActor(actor,op);
        var source=actor==CommitActor.JOB ? sourceIdentity.capture(pdf(id)) : null;
        synchronized (dirLock) {
            if (proposed==null || proposed.pageNumber()<1) throw new ApiException(HttpStatus.BAD_REQUEST,"缺少页面内容");
            Path target=pagePath(id,proposed.pageNumber());
            Page current=Files.exists(target)?read(target,Page.class,"页面数据损坏"):null;
            if (current==null) throw new ApiException(HttpStatus.NOT_FOUND,"页码不存在");
            int revision=revisionOrZero(current);
            if (current.pageNumber()!=proposed.pageNumber()) throw new IOException("page identity mismatch");
            AttemptAuthority.Claim claim=null;
            if (actor==CommitActor.JOB) {
                claim=authority.claim(this,id,proposed.pageNumber(),identity,revision);
                var log=commits.reconcile(bookDir(id),id,proposed.pageNumber(),current);
                for (var e : log) {
                    if (claim.attempt().attemptId().equals(e.attemptId()) && claim.attempt().generation()==e.attemptSeq()
                            && op.name().equals(e.operation())) {
                        if (expectedRevision==e.expectedRevision() && claim.outcome().equals(e.outcome())
                                && "COMMITTED".equals(e.state()) && commits.matches(e,current)
                                && e.contentHash().equals(commits.hash(withRevision(proposed,e.publishedRevision()))))
                            return current; // Read-only replay: no new revision, history or event.
                        throw new PageConflictException(revision,"该尝试已提交或不能重放，不覆盖当前版本");
                    }
                }
                authority.requireLive(this,claim,current,op,source,log);
            } else checkCommitEligibility(id,current,proposed,revision,actor,identity,op);
            if (revision!=expectedRevision) throw new PageConflictException(revision,"页面已被更新，请刷新后重试");
            return persistNewRevision(id,current,proposed,op,claim);
        }
    }

    private static void validateActor(CommitActor actor, CommitOp op) {
        if (actor==null || op==null) throw new ApiException(HttpStatus.BAD_REQUEST,"缺少提交操作身份");
        CommitActor required=switch(op) {
            case MANUAL_SAVE -> CommitActor.MANUAL;
            case MANUAL_REVERT -> CommitActor.REVERT;
            case SYSTEM_RECOVERY -> CommitActor.SYSTEM;
            default -> CommitActor.JOB;
        };
        if (required!=actor) throw new ApiException(HttpStatus.BAD_REQUEST,"提交操作与操作者不匹配");
    }

    /** A1-04：同一锁内的操作资格判断。任务登记、状态与页集合在此统一裁定。 */
    private void checkCommitEligibility(String id, Page current, Page proposed, int revision,
                                        CommitActor actor, String identity, CommitOp op) {
        validateActor(actor,op);
        if (actor==CommitActor.JOB) throw new PageConflictException(revision,"任务必须经尝试身份验证后提交");
        if (op==CommitOp.SYSTEM_RECOVERY) return;
        if ("PROCESSING".equals(current.status())) throw new PageConflictException(revision,"本页正在识别，请先停止任务");
        Job job=readJob(id);
        if (job!=null && List.of("QUEUED","RUNNING","CANCELLING").contains(job.status())
                && job.pages()!=null && job.pages().contains(current.pageNumber()))
            throw new PageConflictException(revision,"本页正在识别，请先停止任务");
    }

    private Page persistNewRevision(String id, Page current, Page proposed) throws IOException {
        return persistNewRevision(id,current,proposed,CommitOp.SYSTEM_RECOVERY,null);
    }
    private Page persistNewRevision(String id, Page current, Page proposed, CommitOp operation,
                                    AttemptAuthority.Claim claim) throws IOException {
        checkInjected("atomic");
        int revision=current==null ? Math.max(0,revisionOrZero(proposed)) : Math.addExact(revisionOrZero(current),1);
        UUID commitId=current==null ? null : UUID.randomUUID();
        Page saved=new Page(proposed.pageNumber(),proposed.width(),proposed.height(),proposed.status(),proposed.provider(),
                proposed.blocks(),proposed.warnings(),proposed.reviewed(),proposed.error(),proposed.sourceRecords(),revision,commitId);
        var epoch=pageEpochs.computeIfAbsent(id,ignored->new java.util.concurrent.atomic.AtomicLong());
        long settled=Math.addExact(epoch.get(),2); epoch.set(settled-1);
        try {
            if (current==null) {
                atomic(pagePath(id,saved.pageNumber()),saved);
                try {
                    headStore.createOrUpdatePublication(bookDir(id), saved, commits.hash(saved), 0L,
                            HeadingText.pageTitle(saved), null, null, null, null);
                } catch (Exception ignored) {}
            } else publishRecorded(id,current,saved,operation,claim);
            PageChange event=new PageChange(id,current,saved,settled);
            for (var listener:pageListeners) {
                try { listener.accept(event); } catch (RuntimeException ignored) { /* Projection cannot undo publication. */ }
            }
            notifyBookChanged(id);
            return saved;
        } finally { epoch.set(settled); }
    }
    private void publishRecorded(String id, Page current, Page saved, CommitOp operation,
                                 AttemptAuthority.Claim claim) throws IOException {
        Path dir=bookDir(id); int page=saved.pageNumber();
        var before=commits.reconcile(dir,id,page,current);
        PageAttempt a=claim==null ? null : claim.attempt();
        String prevHash = commits.hash(current);
        String nextHash = commits.hash(saved);
        int prevRev = revisionOrZero(current);
        int nextRev = saved.revision();

        long sourceSeq = sourceJournal.nextSourceSeq(dir, id);
        var sourceChange = sourceJournal.prepare(dir, id, "PAGE", page, saved.lastCommitId(), null,
                prevRev, nextRev, prevHash, nextHash, operation.name());

        var entry=new PageCommitJournal.Entry(saved.lastCommitId(),id,page,a==null?null:a.attemptId(),a==null?0:a.generation(),
                operation.name(),claim==null?"SUCCEEDED":claim.outcome(),prevRev,nextRev,
                prevHash,nextHash,"PREPARED",java.time.Instant.now());
        var prepared=commits.append(before,entry);
        commitCheckpoint("commit-intent");
        commits.write(dir,page,prepared);
        try {
            commitCheckpoint("commit-prepared");
            if ("READY".equals(current.status()) || "FAILED".equals(current.status())) archiveHistory(id,current);
            commitCheckpoint("page-replace");
            DurableJson.write(pagePath(id,page),saved,json,64*1024*1024);
        } catch (IOException failure) {
            // A transport/filesystem exception is not proof that rename did not happen.
            boolean published=false;
            try {
                Page actual=read(pagePath(id,page),Page.class,"页面数据损坏");
                published=commits.matches(entry,actual);
                commits.reconcile(dir,id,page,actual);
            } catch (IOException | RuntimeException unknown) { /* Preserve PREPARED for conservative recovery. */ }
            if (!published) {
                sourceJournal.markNotPublished(dir, id, sourceSeq);
                throw failure;
            }
        }
        // Nothing below may turn a confirmed saved page into a misleading save failure.
        try {
            commitCheckpoint("page-published");
            commitCheckpoint("commit-completion");
            commits.write(dir,page,commits.mark(prepared,entry.commitId(),"COMMITTED"));
            sourceJournal.commit(dir, id, sourceSeq, page, saved.lastCommitId(), nextRev, nextHash);
            commitCheckpoint("commit-settled");
        } catch (IOException | RuntimeException failure) {
            System.getLogger(BookStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Page published; commit metadata will be reconciled from its exact identity");
        }
        try {
            headStore.createOrUpdatePublication(dir, saved, nextHash, sourceSeq,
                    HeadingText.pageTitle(saved), a == null ? null : a.attemptId(),
                    a == null ? null : a.generation(), claim == null ? "SUCCEEDED" : claim.outcome(),
                    a == null ? null : a.lifecycle());
        } catch (Exception ex) {
            System.getLogger(BookStore.class.getName()).log(System.Logger.Level.WARNING,
                    "Page published; head will be reconciled from page data");
        }
    }
    /** Test subclasses can interrupt a precise boundary; no HTTP/config switch exposes it. */
    protected void commitCheckpoint(String phase) throws IOException { checkInjected(phase); }

    /** U6：书籍变更监听（画像/统计等派生缓存失效用）。监听器只做轻量失效，不得阻塞。 */
    private final List<java.util.function.Consumer<String>> changeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addChangeListener(java.util.function.Consumer<String> listener) {
        if (listener != null) changeListeners.add(listener);
    }

    private void notifyBookChanged(String id) {
        for (java.util.function.Consumer<String> listener : changeListeners) {
            try {
                listener.accept(id);
            } catch (RuntimeException ignored) {}
        }
    }
    public Page readPage(String id, int page) {
        Path p = pagePath(id, page);
        if (!Files.exists(p)) return null;
        return read(p, Page.class, "页面数据损坏");
    }
    public Page readOriginalPage(String id,int page){Path p=originalPagePath(id,page);return Files.exists(p)?read(p,Page.class,"原始页面快照损坏"):null;}
    public void writeJob(String id, Job job) throws IOException { synchronized (dirLock) { atomic(bookDir(id).resolve("job.json"), job); } }
    public Job readJob(String id) {
        Path p = bookDir(id).resolve("job.json"); return Files.exists(p) ? read(p, Job.class, "任务数据损坏") : Job.idle();
    }
    public void recoverInterruptedJobs() {
        for (Book book : listBooks()) {
            Job j = readJob(book.id());
            if (List.of("QUEUED", "RUNNING", "CANCELLING").contains(j.status())) {
                try {
                    for(int n=1;n<=book.totalPages();n++){
                        Page p=readPage(book.id(),n);
                        if(p!=null&&"PROCESSING".equals(p.status())){
                            // 阶段1：PROCESSING 为中间态，不直接标 READY；优先回退到最近可读历史，否则 PENDING
                            Page readyHistory = latestReadyHistory(book.id(), n);
                            List<String>warnings=new java.util.ArrayList<>(p.warnings()==null?List.of():p.warnings());
                            warnings.add("应用重启导致本页处理中断");
                            if (readyHistory != null) {
                                List<String> merged = new java.util.ArrayList<>(readyHistory.warnings()==null?List.of():readyHistory.warnings());
                                merged.add("应用重启导致本页处理中断，已回退到上一个可读版本");
                                Page restored = new Page(readyHistory.pageNumber(),readyHistory.width(),readyHistory.height(),"READY",readyHistory.provider(),readyHistory.blocks()==null?List.of():readyHistory.blocks(),List.copyOf(merged),readyHistory.reviewed(),null,readyHistory.sourceRecords(),null);
                                // A1-04：启动恢复走专用恢复提交（启动期单线程，不与其他写入竞争）
                                Page current = readPage(book.id(), n);
                                commitPage(book.id(), restored, revisionOrZero(current), CommitActor.SYSTEM, null, CommitOp.SYSTEM_RECOVERY);
                            } else {
                                Page restored = new Page(p.pageNumber(),p.width(),p.height(),"PENDING",p.provider(),List.of(),List.copyOf(warnings),p.reviewed(),null,p.sourceRecords(),null);
                                Page current = readPage(book.id(), n);
                                commitPage(book.id(), restored, revisionOrZero(current), CommitActor.SYSTEM, null, CommitOp.SYSTEM_RECOVERY);
                            }
                        }
                    }
                    // 阶段1：恢复保留原任务参数，不擅自重新提交；沿用原 jobId
                    writeJob(book.id(), new Job(j.id(), "INTERRUPTED", j.completed(), j.total(), j.currentPage(), "应用重启导致任务中断", j.errors()==null?List.of():j.errors(), java.time.Instant.now(),
                        j.pages(), j.provider(), j.layout(), j.splitSpreads(), j.force(), j.assist(), j.fingerprint())); }
                catch (IOException ignored) { }
            }
        }
    }
    public static int revisionOrZero(Page page) { return page == null || page.revision() == null ? 0 : page.revision(); }

    /** JR-01：锁内 PDF 内容身份（SHA-256，与 PdfIdentity 一致；size/mtime 不用作身份）。 */
    static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) out.append(String.format("%02x", b));
            return out.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("PDF 身份计算失败", e);
        }
    }
    public static Page withRevision(Page page, int revision) {
        if (page == null) return null;
        return new Page(page.pageNumber(), page.width(), page.height(), page.status(), page.provider(),
            page.blocks(), page.warnings(), page.reviewed(), page.error(), page.sourceRecords(), revision, page.lastCommitId());
    }
    public Path historyDir(String id, int page) { return bookDir(id).resolve("pages").resolve("history").resolve(String.valueOf(page)); }
    public Path candidatePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".candidate.json"); }
    /** A1-C06 测试注入点：仅测试使用。设为阶段名则下一次对应落盘抛 IOException，用后必须清零。 */
    public static volatile String injectIoFailureAt = null;
    public static void failNextIoAt(String stage) { injectIoFailureAt = stage; }
    public static void clearIoFailure() { injectIoFailureAt = null; }
    private static void checkInjected(String stage) throws IOException {
        if (stage.equals(injectIoFailureAt)) throw new IOException("injected failure at " + stage);
    }
    private static final java.util.regex.Pattern HISTORY_FILE = java.util.regex.Pattern.compile("^rev-(\\d+)\\.json$");
    private void archiveHistory(String id, Page existing) throws IOException {
        checkInjected("history");
        Path dir = historyDir(id, existing.pageNumber());
        Files.createDirectories(dir);
        Path target = dir.resolve("rev-" + revisionOrZero(existing) + ".json");
        if (!Files.exists(target)) DurableJson.write(target, existing, json, 64*1024*1024);
        // R04/A1-C08：按数值保留最新 5 个有效历史版本；异常命名、非普通文件、
        // 超出 int 范围的版本号不参与排序、不被清理
        try (Stream<Path> files = Files.list(dir)) {
            List<RevisionFile> revs = files.filter(Files::isRegularFile)
                .map(p -> RevisionFile.parse(p)).filter(java.util.Objects::nonNull).toList();
            List<RevisionFile> effective = revs.stream()
                .filter(r -> r.number <= Integer.MAX_VALUE)
                .sorted(Comparator.comparingLong(r -> r.number)).toList();
            for (int i = 0; i + 5 < effective.size(); i++) Files.deleteIfExists(effective.get(i).path);
        } catch (IOException ignored) { }
    }
    private record RevisionFile(Path path, long number) {
        static RevisionFile parse(Path path) {
            java.util.regex.Matcher matcher = HISTORY_FILE.matcher(path.getFileName().toString());
            if (!matcher.matches()) return null;
            try {
                return new RevisionFile(path, Long.parseLong(matcher.group(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
    public List<Integer> listRevisions(String id, int page) {
        Path dir = historyDir(id, page);
        List<Integer> result = new java.util.ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.getFileName().toString().startsWith("rev-") && p.toString().endsWith(".json")).forEach(p -> {
                    try { result.add(Integer.parseInt(p.getFileName().toString().substring(4, p.getFileName().toString().length() - 5))); }
                    catch (NumberFormatException ignored) { }
                });
            } catch (IOException ignored) { }
        }
        Page current = readPage(id, page);
        if (current != null) result.add(revisionOrZero(current));
        return result.stream().distinct().sorted().toList();
    }
    public Page readRevision(String id, int page, int revision) {
        Page current = readPage(id, page);
        if (current != null && revisionOrZero(current) == revision) return current;
        Path p = historyDir(id, page).resolve("rev-" + revision + ".json");
        if (!Files.exists(p)) throw new ApiException(HttpStatus.NOT_FOUND, "未找到该版本");
        return read(p, Page.class, "版本数据损坏");
    }
    public Page revertPage(String id, int page, int targetRevision, int expectedRevision) throws IOException {
        synchronized (dirLock) {
            Page target = readRevision(id, page, targetRevision);
            if (!"READY".equals(target.status()) && !"FAILED".equals(target.status()))
                throw new ApiException(HttpStatus.BAD_REQUEST, "仅可回退到可读版本");
            List<String> warnings = new java.util.ArrayList<>(target.warnings()==null?List.of():target.warnings());
            warnings.add("已回退到版本 " + targetRevision);
            Page proposed = new Page(target.pageNumber(), target.width(), target.height(), target.status(), target.provider(),
                target.blocks(), List.copyOf(warnings), target.reviewed(), null, target.sourceRecords(), null);
            // R03/A1-04：回退同样走条件提交，成功生成更高的新 revision
            return commitPage(id, proposed, expectedRevision, CommitActor.REVERT, null, CommitOp.MANUAL_REVERT);
        }
    }
    public void writeCandidate(String id, Page candidate) throws IOException {
        synchronized (dirLock) {
            atomic(candidatePath(id, candidate.pageNumber()), candidate);
        }
    }

    /** J09：锁内构造单疑点人工变更。内部复用相同资格与持久化逻辑，不另写保存路径。 */
    public record IssueAcceptSpec(String blockId, String issueId, String expectedBasisHash,
                                  String candidateSetHash, String candidateId,
                                  String originalReplacement, String simplifiedReplacement,
                                  String converterVersion, String clientOperationId,
                                  String decisionId, String basisPdfSha256) {}

    public record IssueAcceptResult(Page committed, boolean idempotent) {}

    public IssueAcceptResult applyIssueResolution(String id, int pageNumber, int expectedRevision,
                                                 CommitActor actor, String expectedJobId, CommitOp op,
                                                 IssueAcceptSpec spec) throws IOException {
        synchronized (dirLock) {
            Path target = pagePath(id, pageNumber);
            Page current = Files.exists(target) ? read(target, Page.class, "页面数据损坏") : null;
            if (current == null) throw new ApiException(HttpStatus.NOT_FOUND, "页码不存在");
            Block block = current.blocks() == null ? null : current.blocks().stream()
                    .filter(b -> b != null && spec.blockId().equals(b.id())).findFirst().orElse(null);
            if (block == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在");
            ContentIssue issue = block.issues() == null ? null : block.issues().stream()
                    .filter(i -> i != null && spec.issueId().equals(i.id())).findFirst().orElse(null);
            if (issue == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的问题不存在");
            // 幂等先行：同 operationId 同候选同基线返回已应用结果，不再推进 revision；
            // 同 operationId 不同内容拒绝；其他人工编辑后无法安全重放时走版本冲突。
            DecisionModels.ReviewResolution existing = issue.resolution();
            if (existing != null && spec.clientOperationId().equals(existing.clientOperationId())) {
                if (spec.candidateId().equals(existing.candidateId())
                        && spec.candidateSetHash().equals(existing.candidateSetHash())
                        && spec.expectedBasisHash().equals(existing.basisIssueHash()))
                    return new IssueAcceptResult(current, true);
                throw new PageConflictException(revisionOrZero(current),
                        "相同操作已应用不同内容，拒绝重放，请核对后重试");
            }
            int currentRev = revisionOrZero(current);
            if (currentRev != expectedRevision)
                throw new PageConflictException(currentRev, "页面已被更新，请刷新后重试");
            // JR-01：锁内二次核对来源 PDF 身份，关闭“检查后更换 PDF”的 TOCTOU 窗口
            if (spec.basisPdfSha256() != null && !spec.basisPdfSha256().isBlank()) {
                try {
                    Path pdfPath = bookDir(id).resolve("source.pdf");
                    if (java.nio.file.Files.exists(pdfPath)) {
                        String currentPdf = sha256Hex(pdfPath);
                        if (!spec.basisPdfSha256().equals(currentPdf))
                            throw new PageConflictException(currentRev, "来源 PDF 内容已变化，旧建议不可接受");
                    }
                } catch (PageConflictException e) {
                    throw e;
                } catch (Exception e) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "来源 PDF 读取失败");
                }
            }
            String basis;
            try {
                basis = IssueBasis.basisHash(block, issue);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.CONFLICT, "问题基线无效：" + e.getMessage());
            }
            if (!basis.equals(spec.expectedBasisHash()))
                throw new PageConflictException(currentRev, "问题基线已变化，请刷新后重试");
            if (spec.originalReplacement() == null || spec.originalReplacement().isBlank())
                throw new ApiException(HttpStatus.BAD_REQUEST, "候选正文为空");
            java.time.Instant now = java.time.Instant.now();
            DecisionModels.ReviewResolution resolution = new DecisionModels.ReviewResolution(
                    UUID.randomUUID().toString(), spec.clientOperationId(),
                    DecisionModels.Origin.JEV_ASSISTED, spec.decisionId(), spec.candidateId(),
                    spec.candidateSetHash(), spec.basisPdfSha256(), currentRev, basis,
                    spec.originalReplacement(), spec.simplifiedReplacement(), spec.converterVersion(),
                    true, now, currentRev + 1);
            ContentIssue confirmed = new ContentIssue(issue.id(), issue.kind(), issue.start(),
                    issue.end(), issue.simplifiedStart(), issue.simplifiedEnd(), issue.reason(),
                    true, spec.originalReplacement(), issue.inferredText(), resolution);
            List<ContentIssue> issues = new java.util.ArrayList<>(block.issues().size());
            for (ContentIssue currentIssue : block.issues())
                issues.add(currentIssue != null && spec.issueId().equals(currentIssue.id())
                        ? confirmed : currentIssue);
            Block changed = new Block(block.id(), block.type(), block.order(), block.bbox(),
                    block.writingMode(), block.original(), block.simplified(), block.confidence(),
                    block.uncertain(), block.reviewed(), block.headingLevel(), block.source(),
                    block.sourceIds(), block.suggestion(), block.sourceRect(), List.copyOf(issues));
            List<Block> blocks = new java.util.ArrayList<>(current.blocks().size());
            for (Block currentBlock : current.blocks())
                blocks.add(currentBlock != null && spec.blockId().equals(currentBlock.id())
                        ? changed : currentBlock);
            Page proposed = new Page(current.pageNumber(), current.width(), current.height(),
                    current.status(), current.provider(), List.copyOf(blocks), current.warnings(),
                    current.reviewed(), null, current.sourceRecords(), null);
            checkCommitEligibility(id, current, proposed, currentRev, actor, expectedJobId, op);
            return new IssueAcceptResult(persistNewRevision(id, current, proposed, op, null), false);
        }
    }
    public Page readCandidate(String id, int page) {
        Path p = candidatePath(id, page);
        return Files.exists(p) ? read(p, Page.class, "候选数据损坏") : null;
    }
    /**
     * U3：展示层 sidecar（画像 / 人工覆盖）的原子读写辅助，复用目录单写锁。
     * 存的是派生索引与人工选择，不是原文；删 sidecar 可重建（人工覆盖需保留）。
     */
    public Path layoutProfilePath(String id) { return bookDir(id).resolve("layout-profile.json"); }
    public Path presentationOverridesPath(String id) { return bookDir(id).resolve("presentation-overrides.json"); }
    /** U4：页面 attempt 恢复意图（IN_PROGRESS → 终态；重启对照，不重发云请求）。 */
    public Path pageAttemptsPath(String id) { return bookDir(id).resolve("page-attempts.json"); }
    private static final int MAX_ATTEMPT_BYTES = 16 * 1024 * 1024;
    private static final java.util.concurrent.atomic.AtomicBoolean DIRECTORY_FORCE_WARNING = new java.util.concurrent.atomic.AtomicBoolean();

    public <T> T readSidecar(Path path, Class<T> type) {
        if (type == studio.bookhtml.domain.PageAttempt.Journal.class) {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try {
                if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())
                        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
                byte[] bytes;
                try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = in.readNBytes(MAX_ATTEMPT_BYTES + 1);
                }
                if (bytes.length > MAX_ATTEMPT_BYTES) throw new IOException();
                try (var parser = json.getFactory().createParser(bytes)) {
                    parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                    com.fasterxml.jackson.databind.JsonNode tree = json.readTree(parser);
                    if (tree == null || !tree.isObject() || parser.nextToken() != null
                            || !tree.path("intents").isObject()
                            || tree.has("operations") && !tree.path("operations").isObject()) throw new IOException();
                    var version = tree.get("schemaVersion");
                    if (version != null && (!version.isIntegralNumber() || !version.canConvertToInt()
                            || version.intValue() < 1 || version.intValue() > 3)) throw new IOException();
                    if (version != null && version.intValue() >= 2 && !tree.path("operations").isObject())
                        throw new IOException();
                    for (var attempt : tree.path("intents")) {
                        requireInteger(attempt,"pageNumber",1,Integer.MAX_VALUE);
                        requireInteger(attempt,"generation",1,Long.MAX_VALUE);
                        requireInteger(attempt,"expectedRevision",0,Integer.MAX_VALUE);
                    }
                    for (var receipt : tree.path("operations")) requireInteger(receipt,"attemptSeq",1,Long.MAX_VALUE);
                    return json.treeToValue(tree, type);
                }
            } catch (Exception invalid) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "任务身份记录不可读，未派发云请求");
            }
        }
        return Files.exists(path) ? read(path, type, "展示索引数据损坏") : null;
    }
    private static void requireInteger(com.fasterxml.jackson.databind.JsonNode node, String field, long min, long max) throws IOException {
        var value=node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<min || value.longValue()>max)
            throw new IOException("invalid journal integer");
    }
    public <T> void writeSidecar(Path path, T value) throws IOException {
        synchronized (dirLock) {
            if (value instanceof studio.bookhtml.domain.PageAttempt.Journal) durableAttemptWrite(path, value);
            else atomic(path, value);
        }
    }

    /** A receipt and attempt share one forced atomic replacement, never two-file admission. */
    private void durableAttemptWrite(Path target, Object value) throws IOException {
        checkInjected("atomic");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(target.getParent()))
            throw new IOException("unsafe attempt journal");
        byte[] bytes = json.writeValueAsBytes(value);
        if (bytes.length > MAX_ATTEMPT_BYTES) throw new IOException("attempt journal capacity exceeded");
        Path temp = Files.createTempFile(target.getParent(), "attempt-", ".tmp");
        // Cancellation must not abort its own durable finalizer with ClosedByInterruptException.
        boolean interrupted = Thread.interrupted();
        try {
            try (var file = java.nio.channels.FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Some platforms cannot force a directory. A completed rename is not reported as failed.
            try (var directory = java.nio.channels.FileChannel.open(target.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            } catch (IOException | UnsupportedOperationException unsupported) {
                if (DIRECTORY_FORCE_WARNING.compareAndSet(false, true))
                    System.getLogger(BookStore.class.getName()).log(System.Logger.Level.WARNING,
                            "Directory fsync unavailable; power-loss durability of directory entries is platform dependent");
            }
        } finally {
            try { Files.deleteIfExists(temp); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    /** 首次成功结果缺失原始快照时补留（与旧 writePage preserveOriginal 语义一致）。 */
    public void preserveOriginal(String id, Page page) throws IOException {
        synchronized (dirLock) {
            checkInjected("original");
            Path original = originalPagePath(id, page.pageNumber());
            if (!Files.exists(original)) atomic(original, page);
        }
    }
    private Page latestReadyHistory(String id, int page) {
        List<Integer> revs = listRevisions(id, page);
        for (int i = revs.size() - 1; i >= 0; i--) {
            int rev = revs.get(i);
            Page current = readPage(id, page);
            if (current != null && revisionOrZero(current) == rev) continue; // 当前 PROCESSING 跳过
            try {
                Page h = readRevision(id, page, rev);
                if (h != null && "READY".equals(h.status())) return h;
            } catch (Exception ignored) { }
        }
        return null;
    }
    private <T> T read(Path path, Class<T> type, String message) {
        try { return json.readValue(path.toFile(), type); }
        catch (IOException e) { throw new ApiException(Files.exists(path) ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.NOT_FOUND, message); }
    }
    private void atomic(Path target, Object value) throws IOException {
        checkInjected("atomic");
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            json.writeValue(tmp.toFile(), value);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
