package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private void checkLimits(Path dir, byte[] bytes, String name, boolean isNewFile) throws IOException {
        if (bytes.length > MAX_FILE_BYTES)
            throw new IOException("决策文件过大，拒绝写入：" + name);
        // JR-11：只有新增文件受条目上限约束；已有任务终结/预算结算等覆盖写必须能落盘
        if (!isNewFile) return;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            int count = 0;
            for (Path ignored : entries) {
                if (++count >= MAX_FILES_PER_DIR) throw new IOException("决策目录条目超限：" + dir);
            }
        }
    }

    /**
     * JR-11：外来 ID 只允许安全叶名（字母数字/下划线/连字符/点），拒绝斜杠、
     * 反斜杠与点路径；受控根目录之外的读写一律拒绝。
     */
    static void checkLeaf(String name) {
        if (name == null || name.isBlank() || name.length() > 128
                || !name.matches("[A-Za-z0-9][A-Za-z0-9_\\-.]*")
                || name.contains(".."))
            throw new IllegalArgumentException("非法标识：" + name);
    }

    private void atomicWrite(Path dir, String name, Object value) throws IOException {
        checkLeaf(name.replaceAll("\\.json$", "").replaceAll("\\.tmp-.*$", ""));
        synchronized (lock(dirKey(dir))) {
            byte[] bytes = json.writeValueAsBytes(value);
            Path target = dir.resolve(name);
            checkLimits(dir, bytes, name, !Files.exists(target));
            Path tmp = dir.resolve(name + ".tmp-" + System.nanoTime());
            Files.write(tmp, bytes);
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    // JR-11：不支持原子移动的文件系统必须明确拒绝，不安静降级为半写
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                    throw new IOException("文件系统不支持原子替换，拒绝写入：" + name, unsupported);
                }
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
            // JR-11：先做大小上限校验并有界读取，不把超大文件读进内存
            if (Files.size(file) > MAX_FILE_BYTES) throw new IOException("文件过大");
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
        checkLeaf(snapshotHash);
        return readIsolated(decisionsDir(bookId).resolve("snapshots").resolve(snapshotHash + ".json"),
                DecisionModels.DecisionSnapshot.class);
    }

    public void saveCandidateSet(String bookId, DecisionModels.CandidateSet set) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "candidates"), set.candidateSetHash() + ".json", set);
    }

    public Optional<DecisionModels.CandidateSet> loadCandidateSet(String bookId, String candidateSetHash) throws IOException {
        checkLeaf(candidateSetHash);
        return readIsolated(decisionsDir(bookId).resolve("candidates").resolve(candidateSetHash + ".json"),
                DecisionModels.CandidateSet.class);
    }

    public void saveResult(String bookId, DecisionModels.DecisionEvidence evidence) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "results"), evidence.decisionId() + ".json", evidence);
    }

    public Optional<DecisionModels.DecisionEvidence> loadResult(String bookId, String decisionId) throws IOException {
        checkLeaf(decisionId);
        return readIsolated(decisionsDir(bookId).resolve("results").resolve(decisionId + ".json"),
                DecisionModels.DecisionEvidence.class);
    }

    public void saveJob(String bookId, DecisionJob job) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "jobs"), job.jobId() + ".json", job);
    }

    public Optional<DecisionJob> loadJob(String bookId, String jobId) throws IOException {
        checkLeaf(jobId);
        return readIsolated(decisionsDir(bookId).resolve("jobs").resolve(jobId + ".json"), DecisionJob.class);
    }

    /** 预算账本：发送前原子预留的持久依据；未知费用保留预留，绝不记 0。 */
    public record BudgetState(long reservedMinor, long reportedMinor, long releasedMinor,
                              Instant updatedAt) {
        public BudgetState {
            if (reservedMinor < 0 || reportedMinor < 0 || releasedMinor < 0)
                throw new IllegalArgumentException("预算为负");
            if (updatedAt == null) throw new IllegalArgumentException("时间为空");
        }
    }

    /**
     * JR-05：以 physicalAttemptId 为键的预留/结算台账。
     * RESERVED → SEND_INTENT → RESPONSE_RECEIVED/SENT_UNKNOWN
     *   → SETTLED_REPORTED/RETAINED_UNKNOWN；可证明未发送才 RELEASED_NOT_SENT。
     */
    public enum AttemptState {
        RESERVED, SEND_INTENT, RESPONSE_RECEIVED, SENT_UNKNOWN,
        SETTLED_REPORTED, RETAINED_UNKNOWN, RELEASED_NOT_SENT
    }

    public record AttemptLedger(String physicalAttemptId, String purpose, long reservedMinor,
                                AttemptState state, Long reportedMinor, Instant updatedAt) {
        public AttemptLedger {
            if (physicalAttemptId == null || physicalAttemptId.isBlank())
                throw new IllegalArgumentException("attemptId 为空");
            if (purpose == null || purpose.isBlank())
                throw new IllegalArgumentException("purpose 为空");
            if (reservedMinor < 0 || (reportedMinor != null && reportedMinor < 0))
                throw new IllegalArgumentException("预算为负");
            if (state == null || updatedAt == null)
                throw new IllegalArgumentException("状态/时间为空");
        }
    }

    public synchronized void saveAttempt(String bookId, AttemptLedger attempt) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "attempts"), attempt.physicalAttemptId() + ".json", attempt);
    }

    public synchronized Optional<AttemptLedger> loadAttempt(String bookId, String physicalAttemptId)
            throws IOException {
        checkLeaf(physicalAttemptId);
        return readIsolated(
                decisionsDir(bookId).resolve("attempts").resolve(physicalAttemptId + ".json"),
                AttemptLedger.class);
    }

    /**
     * JR-05：限额判断用“已实报累计 + 未结算预留”。账本损坏/不可读抛
     * BudgetUnavailableException（禁止新外呼），与“账本不存在（首用）”区分。
     */
    public synchronized long[] budgetTotals(String bookId) throws IOException {
        Path dir = decisionsDir(bookId).resolve("attempts");
        if (!Files.exists(dir)) return new long[]{0, 0};
        long reserved = 0, reported = 0;
        boolean corrupt = false;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : entries) {
                Optional<AttemptLedger> attempt;
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    if (bytes.length > MAX_FILE_BYTES) throw new IOException("文件过大");
                    attempt = Optional.of(json.readValue(bytes, AttemptLedger.class));
                } catch (Exception e) {
                    corrupt = true;
                    continue;
                }
                if (attempt.isEmpty()) {
                    corrupt = true;
                    continue;
                }
                AttemptLedger ledger = attempt.get();
                switch (ledger.state()) {
                    case RESERVED, SEND_INTENT, RESPONSE_RECEIVED, SENT_UNKNOWN, RETAINED_UNKNOWN ->
                            reserved += ledger.reservedMinor();
                    case SETTLED_REPORTED -> reported += ledger.reportedMinor() == null
                            ? 0 : ledger.reportedMinor();
                    case RELEASED_NOT_SENT -> {
                    }
                }
            }
        }
        if (corrupt) throw new BudgetUnavailableException("预算账本损坏，禁止新外呼");
        return new long[]{reserved, reported};
    }

    public static final class BudgetUnavailableException extends IOException {
        public BudgetUnavailableException(String message) {
            super(message);
        }
    }

    public synchronized void saveBudgetState(String bookId, BudgetState state) throws IOException {
        ensureSchema(bookId);
        atomicWrite(subdir(bookId, "budgets"), bookId + ".json", state);
    }

    public synchronized Optional<BudgetState> loadBudgetState(String bookId) throws IOException {
        return readIsolated(decisionsDir(bookId).resolve("budgets").resolve(bookId + ".json"),
                BudgetState.class);
    }

    /** 原子查找/登记准入键：调用方在付费生成候选前合并重复请求；返回最新的一条。 */
    public DecisionJob findByAdmission(String bookId, String admissionKey) throws IOException {
        Path dir = decisionsDir(bookId).resolve("jobs");
        if (!Files.exists(dir)) return null;
        List<DecisionJob> matches = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : entries) {
                Optional<DecisionJob> job = readIsolated(file, DecisionJob.class);
                if (job.isPresent() && admissionKey.equals(job.get().admissionKey())) matches.add(job.get());
            }
        }
        matches.sort(Comparator.comparing(DecisionJob::createdAt).reversed());
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** 按请求内容 hash 查找已有完成证据（零费用复用），只读小文件目录。 */
    public DecisionModels.DecisionEvidence findEvidenceByRequestHash(String bookId, String requestHash)
            throws IOException {
        Path dir = decisionsDir(bookId).resolve("results");
        if (!Files.exists(dir)) return null;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : entries) files.add(p);
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path file : files) {
            Optional<DecisionModels.DecisionEvidence> evidence = readIsolated(file,
                    DecisionModels.DecisionEvidence.class);
            if (evidence.isPresent() && requestHash.equals(evidence.get().requestHash())
                    && evidence.get().executionStatus() == DecisionModels.ExecutionStatus.SUCCEEDED)
                return evidence.get();
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
            Instant createdAt, Instant updatedAt, Instant deadlineAt, boolean allowFreshVision,
            DecisionTargetIdentity target) {
        public DecisionJob {
            if (jobId == null || jobId.isBlank()) throw new IllegalArgumentException("jobId 为空");
            if (state == null || state.isBlank()) throw new IllegalArgumentException("state 为空");
            if (admissionKey == null || admissionKey.isBlank()) throw new IllegalArgumentException("admissionKey 为空");
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
            if (createdAt == null || updatedAt == null || deadlineAt == null)
                throw new IllegalArgumentException("时间为空");
            // target 可空：旧作业无该字段时读为 null，按遗留关闭处理，不复用、不接受
        }

        public DecisionJob(
                String jobId, String state, int stateVersion, String progressStage,
                String admissionKey, String requestHash,
                String bookId, int sourcePageNumber, String blockId, String issueId,
                String snapshotHash, String candidateSetHash, String decisionId,
                String clientOperationId, String verdict, List<String> reasonCodes,
                long reservedCostMinor, String costStatus, String cancellationState,
                Instant createdAt, Instant updatedAt, Instant deadlineAt, boolean allowFreshVision) {
            this(jobId, state, stateVersion, progressStage, admissionKey, requestHash,
                    bookId, sourcePageNumber, blockId, issueId, snapshotHash, candidateSetHash,
                    decisionId, clientOperationId, verdict, reasonCodes, reservedCostMinor,
                    costStatus, cancellationState, createdAt, updatedAt, deadlineAt,
                    allowFreshVision, null);
        }
    }

    /**
     * JR-11：有界可重建索引（原文件保持权威）。admission/request hash 到最新作业/证据，
     * 查询走索引；缺失或损坏时扫描重建。
     */
    public record JobIndex(Map<String, String> admissionToJob, Map<String, String> requestToDecision,
                           Instant updatedAt) {
        public JobIndex {
            admissionToJob = admissionToJob == null ? Map.of() : Map.copyOf(admissionToJob);
            requestToDecision = requestToDecision == null ? Map.of() : Map.copyOf(requestToDecision);
            if (updatedAt == null) throw new IllegalArgumentException("时间为空");
        }
    }

    public synchronized JobIndex repairIndexes(String bookId) throws IOException {
        ensureSchema(bookId);
        Map<String, String> admissionToJob = new LinkedHashMap<>();
        Map<String, String> requestToDecision = new LinkedHashMap<>();
        for (DecisionJob job : listJobs(bookId)) {
            admissionToJob.putIfAbsent(job.admissionKey(), job.jobId());
            if (job.decisionId() != null) {
                try {
                    Optional<DecisionModels.DecisionEvidence> evidence =
                            loadResult(bookId, job.decisionId());
                    if (evidence.isPresent()
                            && evidence.get().executionStatus()
                            == DecisionModels.ExecutionStatus.SUCCEEDED)
                        requestToDecision.putIfAbsent(evidence.get().requestHash(),
                                evidence.get().decisionId());
                } catch (IOException ignored) {
                }
            }
        }
        // 同一 admission 保留最新：listJobs 按创建时间升序，后写覆盖
        Map<String, String> latest = new LinkedHashMap<>();
        for (DecisionJob job : listJobs(bookId)) latest.put(job.admissionKey(), job.jobId());
        JobIndex index = new JobIndex(latest, requestToDecision, Instant.now());
        atomicWrite(decisionsDir(bookId), "index.json", index);
        return index;
    }

    public synchronized Optional<String> indexLookupAdmission(String bookId, String admissionKey) {
        try {
            Optional<JobIndex> index = readIsolated(decisionsDir(bookId).resolve("index.json"),
                    JobIndex.class);
            if (index.isPresent() && index.get().admissionToJob().containsKey(admissionKey))
                return Optional.of(index.get().admissionToJob().get(admissionKey));
        } catch (IOException ignored) {
        }
        return Optional.empty();
    }

    /** 列出本书全部决策作业（重启恢复与审计用；状态查询走单文件，不用它）。 */
    public List<DecisionJob> listJobs(String bookId) throws IOException {
        Path dir = decisionsDir(bookId).resolve("jobs");
        List<DecisionJob> jobs = new ArrayList<>();
        if (!Files.exists(dir)) return jobs;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : entries)
                readIsolated(file, DecisionJob.class).ifPresent(jobs::add);
        }
        jobs.sort(Comparator.comparing(DecisionJob::createdAt));
        return jobs;
    }
}
