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

    public BookStore(AppProperties properties, ObjectMapper json) throws IOException {
        Path dataDir = properties.dataDir().toAbsolutePath().normalize();
        // A1-05：先取得单写者租约（失败即拒绝启动），再建目录结构
        this.lease = DataDirectoryLease.acquire(dataDir);
        this.dirLock = lease.monitor();
        this.booksRoot = lease.realPath().resolve("books");
        this.json = json;
        Files.createDirectories(booksRoot);
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

    public void writeBook(Book book) throws IOException { synchronized (dirLock) { checkInjected("book"); atomic(bookDir(book.id()).resolve("book.json"), book); } }
    public Book readBook(String id) { return read(bookDir(id).resolve("book.json"), Book.class, "未找到该书籍"); }
    /** Read-modify-write under the directory lease lock, so page-stat touches cannot undo a library edit. */
    public Book updateBook(String id, UnaryOperator<Book> change) throws IOException {
        synchronized (dirLock) {
            Path path = bookDir(id).resolve("book.json");
            Book updated = change.apply(read(path, Book.class, "未找到该书籍"));
            checkInjected("book");
            atomic(path, updated);
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
    public Page commitPage(String id, Page proposed, int expectedRevision, CommitActor actor, String expectedJobId, CommitOp op) throws IOException {
        synchronized (dirLock) {
            if (proposed == null) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少页面内容");
            Path target = pagePath(id, proposed.pageNumber());
            Page current = Files.exists(target) ? read(target, Page.class, "页面数据损坏") : null;
            if (current == null) throw new ApiException(HttpStatus.NOT_FOUND, "页码不存在");
            int currentRev = revisionOrZero(current);
            if (currentRev != expectedRevision)
                throw new PageConflictException(currentRev, "页面已被更新，请刷新后重试");
            checkCommitEligibility(id, current, proposed, currentRev, actor, expectedJobId, op);
            return persistNewRevision(id, current, proposed);
        }
    }

    /** A1-04：同一锁内的操作资格判断。任务登记、状态与页集合在此统一裁定。 */
    private void checkCommitEligibility(String id, Page current, Page proposed, int currentRev, CommitActor actor, String expectedJobId, CommitOp op) {
        switch (op) {
            case MANUAL_SAVE, MANUAL_REVERT -> {
                if ("PROCESSING".equals(current.status()))
                    throw new PageConflictException(currentRev, "本页正在识别，请等待完成或先取消任务再操作");
                Job job = readJob(id);
                if (job != null && List.of("QUEUED", "RUNNING", "CANCELLING").contains(job.status())
                        && job.pages() != null && job.pages().contains(current.pageNumber()))
                    throw new PageConflictException(currentRev, "本页正在识别，请等待完成或先取消任务再操作");
            }
            case JOB_START -> {
                if (!"PROCESSING".equals(proposed.status()))
                    throw new ApiException(HttpStatus.BAD_REQUEST, "任务开始提交必须为 PROCESSING 页");
                Job job = requireCurrentJob(id, currentRev, expectedJobId, current.pageNumber());
                if (!List.of("QUEUED", "RUNNING").contains(job.status()))
                    throw new PageConflictException(currentRev, "任务已不在可开始状态，本页不再写入");
                requirePageInJob(job, current, currentRev);
            }
            case JOB_COMPLETE -> {
                Job job = requireCurrentJob(id, currentRev, expectedJobId, current.pageNumber());
                if (!"RUNNING".equals(job.status()))
                    throw new PageConflictException(currentRev, "任务已结束或取消，本页结果不再写入");
                requirePageInJob(job, current, currentRev);
            }
            case JOB_RESTORE -> {
                Job job = requireCurrentJob(id, currentRev, expectedJobId, current.pageNumber());
                if (List.of("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED", "IDLE", "INTERRUPTED").contains(job.status()))
                    throw new PageConflictException(currentRev, "任务已终态，恢复写入不再执行");
                requirePageInJob(job, current, currentRev);
            }
            case SYSTEM_RECOVERY -> { }
        }
        if (actor == CommitActor.JOB && expectedJobId == null)
            throw new ApiException(HttpStatus.BAD_REQUEST, "任务提交缺少任务标识");
    }

    private Job requireCurrentJob(String id, int currentRev, String expectedJobId, int pageNumber) {
        if (expectedJobId == null) throw new ApiException(HttpStatus.BAD_REQUEST, "任务提交缺少任务标识");
        Job job = readJob(id);
        if (job == null)
            throw new PageConflictException(currentRev, "任务已被取代，本页不再写入");
        if (expectedJobId.startsWith("reading:") && job.id() != null && job.id().startsWith("reading:")) {
            String[] expParts = expectedJobId.split(":");
            String[] actParts = job.id().split(":");
            if (expParts.length > 1 && actParts.length > 1 && !expParts[1].equals(actParts[1]))
                throw new PageConflictException(currentRev, "任务已被取代，本页不再写入");
            return job;
        }
        if (!expectedJobId.equals(job.id()))
            throw new PageConflictException(currentRev, "任务已被取代，本页不再写入");
        return job;
    }

    private static void requirePageInJob(Job job, Page current, int currentRev) {
        if (job.id() != null && job.id().startsWith("reading:")) {
            if (job.pages() != null && !job.pages().isEmpty() && !job.pages().contains(current.pageNumber()))
                throw new PageConflictException(currentRev, "本页不属于当前任务，不再写入");
            return;
        }
        if (job.pages() == null || !job.pages().contains(current.pageNumber()))
            throw new PageConflictException(currentRev, "本页不属于当前任务，不再写入");
    }

    private Page persistNewRevision(String id, Page current, Page proposed) throws IOException {
        // 阶段1：只归档可读版本（READY/FAILED），避免 PROCESSING 中间态污染历史；R04 按数值保留最近 5 个
        if (current != null && ("READY".equals(current.status()) || "FAILED".equals(current.status()))) archiveHistory(id, current);
        int newRev = current == null
                ? (proposed.revision() == null || proposed.revision() < 0 ? 0 : proposed.revision())
                : revisionOrZero(current) + 1;
        Page toWrite = new Page(proposed.pageNumber(), proposed.width(), proposed.height(), proposed.status(), proposed.provider(),
            proposed.blocks(), proposed.warnings(), proposed.reviewed(), proposed.error(), proposed.sourceRecords(), newRev);
        atomic(pagePath(id, proposed.pageNumber()), toWrite);
        return toWrite;
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
            page.blocks(), page.warnings(), page.reviewed(), page.error(), page.sourceRecords(), revision);
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
        if (!Files.exists(target)) atomic(target, existing);
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
            return new IssueAcceptResult(persistNewRevision(id, current, proposed), false);
        }
    }
    public Page readCandidate(String id, int page) {
        Path p = candidatePath(id, page);
        return Files.exists(p) ? read(p, Page.class, "候选数据损坏") : null;
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
