package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Job;
import studio.bookhtml.domain.Page;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

@Repository
public class BookStore {
    private final Path booksRoot;
    private final ObjectMapper json;

    public BookStore(AppProperties properties, ObjectMapper json) throws IOException {
        this.booksRoot = properties.dataDir().toAbsolutePath().normalize().resolve("books");
        this.json = json;
        Files.createDirectories(booksRoot);
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
        Path dir = booksRoot.getParent() == null ? Path.of("tmp") : booksRoot.getParent().resolve("tmp");
        try { Files.createDirectories(dir); } catch (IOException ignored) { }
        return dir;
    }
    public Path pagePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".json"); }
    public Path originalPagePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".original.json"); }

    public synchronized void writeBook(Book book) throws IOException { atomic(bookDir(book.id()).resolve("book.json"), book); }
    public Book readBook(String id) { return read(bookDir(id).resolve("book.json"), Book.class, "未找到该书籍"); }
    public List<Book> listBooks() {
        if (!Files.isDirectory(booksRoot)) return List.of();
        try (Stream<Path> paths = Files.list(booksRoot)) {
            return paths.map(p -> p.resolve("book.json")).filter(Files::isRegularFile)
                .map(p -> read(p, Book.class, "书籍数据损坏"))
                .sorted(Comparator.comparing(Book::createdAt).reversed()).toList();
        } catch (IOException e) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "读取书籍列表失败"); }
    }
    public synchronized void writePage(String id, Page page, boolean preserveOriginal) throws IOException {
        Path original = originalPagePath(id, page.pageNumber());
        if (preserveOriginal && !Files.exists(original)) atomic(original, page);
        Page existing = null;
        Path target = pagePath(id, page.pageNumber());
        if (Files.exists(target)) { try { existing = json.readValue(target.toFile(), Page.class); } catch (Exception ignored) { existing = null; } }
        int existingRev = revisionOrZero(existing);
        int incomingRev = page.revision() == null ? -1 : page.revision();
        int newRev = (existing == null) ? (incomingRev < 0 ? 0 : incomingRev) : (incomingRev == existingRev + 1 ? incomingRev : existingRev + 1);
        // 阶段1：只归档可读版本（READY/FAILED），避免 PROCESSING 中间态污染历史；保留最近 5 个
        if (existing != null && ("READY".equals(existing.status()) || "FAILED".equals(existing.status()))) archiveHistory(id, existing);
        Page toWrite = new Page(page.pageNumber(), page.width(), page.height(), page.status(), page.provider(),
            page.blocks(), page.warnings(), page.reviewed(), page.error(), page.sourceRecords(), newRev);
        atomic(target, toWrite);
    }
    public Page readPage(String id, int page) {
        Path p = pagePath(id, page);
        if (!Files.exists(p)) return null;
        return read(p, Page.class, "页面数据损坏");
    }
    public Page readOriginalPage(String id,int page){Path p=originalPagePath(id,page);return Files.exists(p)?read(p,Page.class,"原始页面快照损坏"):null;}
    public synchronized void writeJob(String id, Job job) throws IOException { atomic(bookDir(id).resolve("job.json"), job); }
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
                                writePage(book.id(),new Page(readyHistory.pageNumber(),readyHistory.width(),readyHistory.height(),"READY",readyHistory.provider(),readyHistory.blocks()==null?List.of():readyHistory.blocks(),List.copyOf(merged),readyHistory.reviewed(),null,readyHistory.sourceRecords(),null),false);
                            } else {
                                writePage(book.id(),new Page(p.pageNumber(),p.width(),p.height(),"PENDING",p.provider(),List.of(),List.copyOf(warnings),p.reviewed(),null,p.sourceRecords(),null),false);
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
    public static Page withRevision(Page page, int revision) {
        if (page == null) return null;
        return new Page(page.pageNumber(), page.width(), page.height(), page.status(), page.provider(),
            page.blocks(), page.warnings(), page.reviewed(), page.error(), page.sourceRecords(), revision);
    }
    public Path historyDir(String id, int page) { return bookDir(id).resolve("pages").resolve("history").resolve(String.valueOf(page)); }
    public Path candidatePath(String id, int page) { return bookDir(id).resolve("pages").resolve(page + ".candidate.json"); }
    private static final java.util.regex.Pattern HISTORY_FILE = java.util.regex.Pattern.compile("^rev-(\\d+)\\.json$");
    private void archiveHistory(String id, Page existing) throws IOException {
        Path dir = historyDir(id, existing.pageNumber());
        Files.createDirectories(dir);
        Path target = dir.resolve("rev-" + revisionOrZero(existing) + ".json");
        if (!Files.exists(target)) atomic(target, existing);
        // R04：按数值保留最新 5 个有效历史版本；异常命名文件不参与排序、不被清理
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> revs = files.filter(p -> HISTORY_FILE.matcher(p.getFileName().toString()).matches())
                .sorted(Comparator.comparingInt(p -> Integer.parseInt(HISTORY_FILE.matcher(p.getFileName().toString()).replaceFirst("$1")))).toList();
            for (int i = 0; i + 5 < revs.size(); i++) Files.deleteIfExists(revs.get(i));
        } catch (IOException ignored) { }
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
    public synchronized Page revertPage(String id, int page, int revision) throws IOException {
        Page target = readRevision(id, page, revision);
        if (!"READY".equals(target.status()) && !"FAILED".equals(target.status()))
            throw new ApiException(HttpStatus.BAD_REQUEST, "仅可回退到可读版本");
        Page current = readPage(id, page);
        if (current != null && "PROCESSING".equals(current.status()))
            throw new ApiException(HttpStatus.CONFLICT, "本页正在识别，请等待完成或先取消任务再回退");
        List<String> warnings = new java.util.ArrayList<>(target.warnings()==null?List.of():target.warnings());
        warnings.add("已回退到版本 " + revision);
        Page next = new Page(target.pageNumber(), target.width(), target.height(), target.status(), target.provider(),
            target.blocks(), List.copyOf(warnings), target.reviewed(), null, target.sourceRecords(), null);
        writePage(id, next, false);
        return readPage(id, page);
    }
    public synchronized void writeCandidate(String id, Page candidate) throws IOException {
        atomic(candidatePath(id, candidate.pageNumber()), candidate);
    }
    public Page readCandidate(String id, int page) {
        Path p = candidatePath(id, page);
        return Files.exists(p) ? read(p, Page.class, "候选数据损坏") : null;
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
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            json.writeValue(tmp.toFile(), value);
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(tmp); }
    }
}
