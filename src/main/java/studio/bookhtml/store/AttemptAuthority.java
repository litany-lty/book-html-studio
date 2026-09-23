package studio.bookhtml.store;

import studio.bookhtml.api.ApiException;
import studio.bookhtml.domain.*;
import org.springframework.http.HttpStatus;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

/** Storage-owned authority. Every read/modify/write uses the same publication monitor. */
final class AttemptAuthority {
    static final Set<String> TERMINAL = Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED","INTERRUPTED","UNKNOWN");
    private final Object lock;
    private final SourceIdentityGuard sources;
    private final PageCommitJournal commits;
    private final Set<UUID> revoked;
    record Claim(PageAttempt attempt, String outcome) {}
    AttemptAuthority(Object lock, SourceIdentityGuard sources, PageCommitJournal commits, Set<UUID> revoked) {
        this.lock=lock; this.sources=sources; this.commits=commits; this.revoked=revoked;
    }
    PageAttempt.Journal read(BookStore store, String bookId) {
        PageAttempt.Journal journal = store.readSidecar(store.pageAttemptsPath(bookId),PageAttempt.Journal.class);
        if (journal == null) return PageAttempt.Journal.empty();
        for (var e : journal.intents().entrySet()) {
            PageAttempt a=e.getValue();
            if (!bookId.equals(a.bookId()) || !e.getKey().equals(a.key()) || a.pageNumber()<1
                    || a.attemptId()==null || a.runId()==null || a.generation()<1 || a.expectedRevision()<0)
                throw invalidJournal();
            if (a.startedAt()==null || a.updatedAt()==null || a.lifecycle()==null
                    || !(TERMINAL.contains(a.lifecycle()) || Set.of("RUNNING","BASELINE_PUBLISHED","DRAINING").contains(a.lifecycle()))
                    || a.binding()!=null && (a.expectedSourceHash()==null || !a.expectedSourceHash().matches("[0-9a-f]{64}")))
                throw invalidJournal();
        }
        for (var e : journal.operations().entrySet())
            if (!e.getKey().matches("[0-9a-f]{64}") || !bookId.equals(e.getValue().bookId())) throw invalidJournal();
        return journal;
    }
    private static ApiException invalidJournal() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"任务身份记录不可读，未派发云请求");
    }
    PageAttempt admit(BookStore store, String bookId, int page, int revision, String jobId, List<String> operations,
                      boolean overwrite, String operationKey, Function<PageAttempt,ReprocessOperation> receipt) throws IOException {
        SourceIdentityGuard.Snapshot source=sources.capture(store.pdf(bookId));
        synchronized(lock) {
            if (operations==null || operations.isEmpty() || !Set.of("JOB_BASELINE","JOB_COMPLETE","JOB_ENHANCEMENT","JOB_RESTORE").containsAll(operations))
                throw new IOException("invalid attempt operation plan");
            Book book=store.readBook(bookId);
            if (book.archived() || page<1 || page>book.totalPages()) throw new PageConflictException(revision,"书籍或页码不可处理");
            Page current=store.readPage(bookId,page);
            if (current==null || BookStore.revisionOrZero(current)!=revision) throw new PageConflictException(BookStore.revisionOrZero(current),"页面已被更新");
            Job job=store.readJob(bookId);
            if (!Objects.equals(jobId,job.id()) || !Set.of("RUNNING","QUEUED").contains(job.status()))
                throw new PageConflictException(revision,"任务身份或状态已变化");
            if (!jobId.startsWith("reading:") && (job.pages()==null || !job.pages().contains(page)))
                throw new PageConflictException(revision,"本页不属于该任务");
            if (humanEdited(current) && !overwrite)
                throw new PageConflictException(revision,"人工页面需要明确的覆盖授权");
            sources.verifyStable(store.pdf(bookId),source);
            var commitLog=commits.reconcile(store.bookDir(bookId),bookId,page,current);
            if (commitLog.stream().anyMatch(e->"UNKNOWN".equals(e.state()))) throw new IOException("unresolved publication");
            PageAttempt.Journal journal=read(store,bookId);
            PageAttempt previous=journal.intents().get(bookId+":"+page);
            if (previous!=null && !TERMINAL.contains(previous.lifecycle()))
                throw new PageConflictException(revision,"本页旧任务尚未结束");
            String hash=commits.hash(current);
            PageAttempt next=(previous==null ? PageAttempt.register(bookId,page,revision,hash,operations)
                    : previous.nextGeneration(revision,hash,operations)).bind(jobId,source.sha256(),overwrite);
            Map<String,PageAttempt> intents=new LinkedHashMap<>(journal.intents());
            Map<String,ReprocessOperation> receipts=new LinkedHashMap<>(journal.operations());
            intents.put(next.key(),next);
            if (operationKey!=null) {
                if (receipts.containsKey(operationKey)) throw new PageConflictException(revision,"操作已登记，请读取原回执");
                receipts.put(operationKey,Objects.requireNonNull(receipt.apply(next)));
            }
            store.writeSidecar(store.pageAttemptsPath(bookId),new PageAttempt.Journal(intents,receipts));
            return next;
        }
    }
    Claim claim(BookStore store, String bookId, int page, String identity, int revision) {
        PageAttempt a=read(store,bookId).intents().get(bookId+":"+page);
        String[] parts=identity==null ? new String[0] : identity.split(":",-1);
        if (a==null || a.binding()==null || parts.length!=4 || !parts[0].equals("attempt")
                || !a.attemptId().toString().equals(parts[1]) || !String.valueOf(a.generation()).equals(parts[2])
                || !Set.of("SUCCEEDED","PARTIAL","FAILED","BASELINE_PUBLISHED").contains(parts[3]))
            throw new PageConflictException(revision,"本页处理尝试已失效，旧结果不再发布");
        return new Claim(a,parts[3]);
    }
    void requireLive(BookStore store, Claim claim, Page current, CommitOp op, SourceIdentityGuard.Snapshot source,
                     List<PageCommitJournal.Entry> log) throws IOException {
        PageAttempt a=claim.attempt(); int revision=BookStore.revisionOrZero(current);
        if (revoked.contains(a.attemptId()) || !Set.of("RUNNING","BASELINE_PUBLISHED").contains(a.lifecycle())
                || !a.allowedCommitOps().contains(op.name()))
            throw new PageConflictException(revision,"任务已停止或不允许此提交操作");
        Job job=store.readJob(a.bookId());
        if (!a.binding().jobId().equals(job.id()) || !"RUNNING".equals(job.status()))
            throw new PageConflictException(revision,"所属任务已停止或被替代");
        if (store.readBook(a.bookId()).archived()) throw new PageConflictException(revision,"书籍已归档");
        sources.verifyStable(store.pdf(a.bookId()),source);
        if (!a.binding().pdfSha256().equals(source.sha256())) throw new PageConflictException(revision,"原稿内容已变化，旧结果不发布");
        if (humanEdited(current)
                && (op==CommitOp.JOB_ENHANCEMENT || !a.binding().overwriteAuthorized()))
            throw new PageConflictException(revision,"人工版本未授权覆盖");
        boolean initial=revision==a.expectedRevision() && a.expectedSourceHash().equals(commits.hash(current));
        boolean baseline=false;
        if (op==CommitOp.JOB_ENHANCEMENT) {
            for (var e : log) {
                if (a.attemptId().equals(e.attemptId()) && a.generation()==e.attemptSeq()
                        && "JOB_BASELINE".equals(e.operation()) && "COMMITTED".equals(e.state()) && commits.matches(e,current))
                    baseline=true;
            }
        }
        if (op==CommitOp.JOB_ENHANCEMENT ? !baseline : !initial)
            throw new PageConflictException(revision,"来源版本不属于当前处理尝试");
        if (op==CommitOp.JOB_RESTORE ? !claim.outcome().equals("FAILED")
                : claim.outcome().equals("FAILED") || op!=CommitOp.JOB_BASELINE && claim.outcome().equals("BASELINE_PUBLISHED"))
            throw new IOException("invalid publication outcome");
    }
    void finish(BookStore store, PageAttempt owner, String lifecycle) throws IOException {
        synchronized(lock) {
            var journal=read(store,owner.bookId()); var current=journal.intents().get(owner.key());
            if (!same(owner,current) || TERMINAL.contains(current.lifecycle())) return;
            if ("DRAINING".equals(current.lifecycle()) && !TERMINAL.contains(lifecycle)) return;
            Map<String,PageAttempt> intents=new LinkedHashMap<>(journal.intents());
            intents.put(owner.key(),current.withLifecycle(lifecycle));
            store.writeSidecar(store.pageAttemptsPath(owner.bookId()),journal.withIntents(intents));
            if (TERMINAL.contains(lifecycle)) revoked.remove(owner.attemptId());
        }
    }
    void revoke(BookStore store, PageAttempt owner) throws IOException {
        synchronized(lock) {
            var journal=read(store,owner.bookId()); var current=journal.intents().get(owner.key());
            if (!same(owner,current) || TERMINAL.contains(current.lifecycle())) return;
            revoked.add(owner.attemptId()); // Deny new writes even if the disk is failing.
            var intents=new LinkedHashMap<>(journal.intents());
            intents.put(owner.key(),current.withLifecycle("DRAINING"));
            store.writeSidecar(store.pageAttemptsPath(owner.bookId()),journal.withIntents(intents));
        }
    }
    private static boolean humanEdited(Page page) {
        if (page.reviewed() || "manual".equals(page.provider())) return true;
        return page.blocks()!=null && page.blocks().stream().filter(Objects::nonNull).anyMatch(block ->
                block.reviewed() || "manual".equals(block.source()) || block.issues()!=null
                && block.issues().stream().filter(Objects::nonNull).anyMatch(issue -> issue.resolved() || issue.resolution()!=null));
    }
    private static boolean same(PageAttempt a, PageAttempt b) {
        return b!=null && a.attemptId().equals(b.attemptId()) && a.generation()==b.generation();
    }
    String recoveredOutcome(BookStore store, PageAttempt attempt) {
        if (attempt.binding()==null) return "INTERRUPTED";
        if ("DRAINING".equals(attempt.lifecycle())) return "CANCELLED";
        try {
            Page page=store.readPage(attempt.bookId(),attempt.pageNumber());
            for (var e : commits.reconcile(store.bookDir(attempt.bookId()),attempt.bookId(),attempt.pageNumber(),page)) {
                if (attempt.attemptId().equals(e.attemptId()) && attempt.generation()==e.attemptSeq() && "UNKNOWN".equals(e.state()))
                    return "UNKNOWN";
                if (attempt.attemptId().equals(e.attemptId()) && attempt.generation()==e.attemptSeq()
                        && "COMMITTED".equals(e.state()) && commits.matches(e,page))
                    return "BASELINE_PUBLISHED".equals(e.outcome()) ? "INTERRUPTED" : e.outcome();
            }
            return "INTERRUPTED";
        } catch (Exception unknown) { return "UNKNOWN"; }
    }
}
