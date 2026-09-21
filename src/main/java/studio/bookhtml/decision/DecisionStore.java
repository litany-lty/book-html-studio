package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * J01：决策 sidecar 存储。路径一律经 BookStore.bookDir 构造，不直接拼用户或模型返回的路径。
 * 同一文件临时写入后原子替换；多 JSON 各自原子写入不是跨文件事务——候选快照/结果先持久化，
 * 才产生可接受的推荐；确认事实随 Page 同次提交，sidecar 确认索引只是可重建派生。
 */
@Service
public class DecisionStore {
    static final String SCHEMA_VERSION = "decision-sidecar-v1";
    static final long MAX_FILE_BYTES = 256 * 1024;
    static final int MAX_FILES_PER_DIR = 1024;

    private final BookStore books;
    private final ObjectMapper json;
    private final ConcurrentHashMap<String, Object> bookLocks = new ConcurrentHashMap<>();

    public DecisionStore(BookStore books, ObjectMapper json) {
        this.books = books;
        this.json = json;
    }

    private Object lock(String bookId) {
        return bookLocks.computeIfAbsent(bookId, k -> new Object());
    }

    public Path decisionsDir(String bookId) {
        return books.bookDir(bookId).resolve("decisions");
    }

    private Path subdir(String bookId, String name) throws IOException {
        Path dir = decisionsDir(bookId).resolve(name);
        Files.createDirectories(dir);
        return dir;
    }

    private void checkLimits(Path dir, byte[] bytes, String name) throws IOException {
        if (bytes.length > MAX_FILE_BYTES)
            throw new IOException("决策文件过大，拒绝写入：" + name);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            int count = 0;
            for (Path ignored : entries) {
                if (++count >= MAX_FILES_PER_DIR) throw new IOException("决策目录条目超限：" + dir);
            }
        }
    }

    private void atomicWrite(Path dir, String name, Object value) throws IOException {
        synchronized (lock(dirKey(dir))) {
            byte[] bytes = json.writeValueAsBytes(value);
            checkLimits(dir, bytes, name);
            Path tmp = dir.resolve(name + ".tmp-" + System.nanoTime());
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                throw e;
            }
        }
    }

    private String dirKey(Path dir) {
        return dir.toAbsolutePath().toString();
    }

    /** 损坏文件隔离：改名保留，返回空，不抛弃其他数据。 */
    private <T> Optional<T> readIsolated(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_FILE_BYTES) throw new IOException("文件过大");
            return Optional.of(json.readValue(bytes, type));
        } catch (Exception e) {
            Path bad = file.resolveSibling(
                    file.getFileName() + ".corrupt-" + Instant.now().toEpochMilli());
            try { Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING); } catch (IOException ignored) {}
            return Optional.empty();
        }
    }

    public void ensureSchema(String bookId) throws IOException {
        Path dir = decisionsDir(bookId);
        Files.createDirectories(dir);
        Path schema = dir.resolve("schema.json");
        if (!Files.exists(schema)) atomicWrite(dir, "schema.json", SCHEMA_VERSION);
    }

    public void saveSnapshot(String bookId, DecisionModels.DecisionSnapshot snapshot) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "snapshots"), snapshot.snapshotHash() + ".json", snapshot);
    }

    public Optional<DecisionModels.DecisionSnapshot> loadSnapshot(String bookId, String snapshotHash) throws IOException {
        return readIsolated(decisionsDir(bookId).resolve("snapshots").resolve(snapshotHash + ".json"),
                DecisionModels.DecisionSnapshot.class);
    }

    public void saveCandidateSet(String bookId, DecisionModels.CandidateSet set) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "candidates"), set.candidateSetHash() + ".json", set);
    }

    public Optional<DecisionModels.CandidateSet> loadCandidateSet(String bookId, String candidateSetHash) throws IOException {
        return readIsolated(decisionsDir(bookId).resolve("candidates").resolve(candidateSetHash + ".json"),
                DecisionModels.CandidateSet.class);
    }

    public void saveResult(String bookId, DecisionModels.DecisionEvidence evidence) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "results"), evidence.decisionId() + ".json", evidence);
    }

    public Optional<DecisionModels.DecisionEvidence> loadResult(String bookId, String decisionId) throws IOException {
        return readIsolated(decisionsDir(bookId).resolve("results").resolve(decisionId + ".json"),
                DecisionModels.DecisionEvidence.class);
    }

    public void saveJob(String bookId, DecisionJob job) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "jobs"), job.jobId() + ".json", job);
    }

    public Optional<DecisionJob> loadJob(String bookId, String jobId) throws IOException {
        return readIsolated(decisionsDir(bookId).resolve("jobs").resolve(jobId + ".json"), DecisionJob.class);
    }

    /** 原子查找/登记准入键：调用方在付费生成候选前合并重复请求。 */
    public DecisionJob findByAdmission(String bookId, String admissionKey) throws IOException {
        Path dir = decisionsDir(bookId).resolve("jobs");
        if (!Files.exists(dir)) return null;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : entries) files.add(p);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path file : files) {
            Optional<DecisionJob> job = readIsolated(file, DecisionJob.class);
            if (job.isPresent() && admissionKey.equals(job.get().admissionKey())) return job.get();
        }
        return null;
    }

    /** 独立作业记录：不复用/覆盖单书 OCR 的 job.json；只读查询不触发补算。 */
    public record DecisionJob(
            String jobId, String state, int stateVersion, String progressStage,
            String admissionKey, String requestHash,
            String bookId, int sourcePageNumber, String blockId, String issueId,
            String snapshotHash, String candidateSetHash, String decisionId,
            String clientOperationId, String verdict, List<String> reasonCodes,
            long reservedCostMinor, String costStatus, String cancellationState,
            Instant createdAt, Instant updatedAt, Instant deadlineAt) {
        public DecisionJob {
            if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId 为空");
            if (state == null || state.isBlank()) throw new IllegalArgumentException("state 为空");
            if (admissionKey == null || admissionKey.isBlank()) throw new IllegalArgumentException("admissionKey 为空");
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            if (createdAt == null || updatedAt == null || deadlineAt == null)
                throw new IllegalArgumentException("时间为空");
        }
    }
}
