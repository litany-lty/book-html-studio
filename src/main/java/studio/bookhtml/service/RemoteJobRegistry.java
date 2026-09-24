package studio.bookhtml.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.store.DurableJson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Persistent registry for asynchronous remote OCR jobs (B05 / G04 / CONC-12).
 * Tracks remote handles, state transitions, polling count, crash recovery,
 * and maintains active remote debt slots across restart and poll intervals.
 */
@Component
public class RemoteJobRegistry {
    public static final int MAX_ACTIVE_REMOTE_JOBS = 3;
    public static final int DEFAULT_MAX_POLLS = 36;
    public static final Duration DEFAULT_JOB_TTL = Duration.ofMinutes(15);
    /**
     * B-01：活动记录超过该时长没有任何更新即视为陈旧（崩溃/中断残留），
     * 仅用于发现陈旧状态；只有从未提交的预约可释放。已发送或结果未知的任务继续占位，不能仅凭时间判定终止。
     */
    public static final Duration STALE_AFTER = Duration.ofMinutes(5);

    public static final String STATE_RESERVED = "RESERVED";
    public static final String STATE_SUBMITTING = "SUBMITTING";
    public static final String STATE_RUNNING = "RUNNING";
    public static final String STATE_TERMINAL_PROVEN = "TERMINAL_PROVEN";
    public static final String STATE_SUBMIT_UNKNOWN = "SUBMIT_UNKNOWN";
    public static final String STATE_REMOTE_UNKNOWN = "REMOTE_UNKNOWN";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RemoteJobRecord(
            String handleId,
            String bookId,
            int page,
            String provider,
            String accountScope,
            String remoteJobId,
            String physicalCallId,
            String state,
            int pollCount,
            int maxPolls,
            Instant nextPollAt,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            String inputFingerprint,
            String usageAttemptId,
            String failureReason
    ) {
        public boolean isActive() {
            return STATE_RESERVED.equals(state)
                    || STATE_SUBMITTING.equals(state)
                    || STATE_RUNNING.equals(state)
                    || STATE_SUBMIT_UNKNOWN.equals(state)
                    || STATE_REMOTE_UNKNOWN.equals(state);
        }

        public boolean isTerminal() {
            return STATE_TERMINAL_PROVEN.equals(state);
        }
    }

    private final Path jobsDir;
    private final ObjectMapper json;
    private final Object lock = new Object();
    private final Map<String, RemoteJobRecord> activeRecords = new ConcurrentHashMap<>();
    private boolean recoveryBlocked;
    private final Set<String> quarantine=new TreeSet<>();
    private static final int MAX_RECORD_BYTES=128*1024,MAX_RECOVERY_RECORDS=10000;
    private Path recoveryFence(){return jobsDir.resolve("recovery-quarantine.json");}
    public boolean recoveryBlocked(){synchronized(lock){return recoveryBlocked;}}
    public int quarantinedRecordCount(){synchronized(lock){return quarantine.size();}}
    private static boolean canonicalUuid(String id) {
        try{return id!=null&&UUID.fromString(id).toString().equals(id);}catch(IllegalArgumentException e){return false;}
    }
    private static boolean text(String value,int max){return value!=null&&!value.isBlank()&&value.length()<=max;}
    private com.fasterxml.jackson.databind.JsonNode boundedJson(Path path,int max)throws IOException {
        DurableJson.rejectLinks(path);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("remote record unavailable");
        byte[] data;try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(max+1);}
        if(data.length>max)throw new IOException("remote record size exceeded");
        try(var parser=json.getFactory().createParser(data)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            com.fasterxml.jackson.databind.JsonNode value=json.readTree(parser);
            if(value==null||!value.isObject()||parser.nextToken()!=null)throw new IOException("invalid remote record JSON");
            return value;
        }
    }
    private RemoteJobRecord readRecord(Path path)throws IOException {
        var tree=boundedJson(path,MAX_RECORD_BYTES);
        for(String field:List.of("page","pollCount","maxPolls")) {
            var value=tree.get(field);
            if(value==null||!value.isIntegralNumber()||!value.canConvertToInt())throw new IOException("invalid remote counter");
        }
        RemoteJobRecord r=json.treeToValue(tree,RemoteJobRecord.class);
        if(r==null||!canonicalUuid(r.handleId())||!path.getFileName().toString().equals(r.handleId()+".json")
                ||!text(r.bookId(),200)||r.page()<1||!text(r.provider(),100)||!text(r.accountScope(),256)
                ||r.state()==null||!Set.of(STATE_RESERVED,STATE_SUBMITTING,STATE_RUNNING,STATE_TERMINAL_PROVEN,STATE_SUBMIT_UNKNOWN,STATE_REMOTE_UNKNOWN).contains(r.state())
                ||r.pollCount()<0||r.maxPolls()<1||r.maxPolls()>10000||r.createdAt()==null||r.updatedAt()==null
                ||STATE_RESERVED.equals(r.state())&&(r.physicalCallId()!=null||r.remoteJobId()!=null)
                ||STATE_SUBMITTING.equals(r.state())&&!text(r.physicalCallId(),512)
                ||STATE_RUNNING.equals(r.state())&&!text(r.remoteJobId(),2048))throw new IOException("remote job identity invalid");
        return r;
    }
    private void checkNewAdmission() {
        if(recoveryBlocked)throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "远端任务回执无法验证，已暂停新提交；已知任务可续查。请恢复损坏回执后重新核验，不能删除记录以释放名额");
    }

    @Autowired
    public RemoteJobRegistry(AppProperties properties, ObjectMapper json) {
        this(properties.dataDir().toAbsolutePath().normalize(), json);
    }

    public RemoteJobRegistry(Path dataDir, ObjectMapper json) {
        Path resolved = Objects.requireNonNull(dataDir, "dataDir").toAbsolutePath().normalize();
        try {
            Files.createDirectories(resolved);
            resolved = resolved.toRealPath();
        } catch (IOException ignored) {}
        this.jobsDir = resolved.resolve("remote-jobs");
        this.json = Objects.requireNonNull(json, "json");
        recover();
    }

    private Path jobPath(String handleId) {
        if(!canonicalUuid(handleId))throw new IllegalArgumentException("invalid remote handle");
        return jobsDir.resolve(handleId + ".json");
    }

    public void recover() {
        synchronized(lock) {
            Map<String,RemoteJobRecord> restored=new HashMap<>();
            Set<String> suspect=new TreeSet<>(quarantine);
            boolean directoryFailure=false;
            Set<String> seen=new HashSet<>();
            try {
                DurableJson.rejectLinks(jobsDir);
                if(Files.exists(recoveryFence(),LinkOption.NOFOLLOW_LINKS)) {
                    var fence=boundedJson(recoveryFence(),512*1024);
                    if(!fence.path("schemaVersion").isIntegralNumber()||fence.path("schemaVersion").asInt()!=1
                            ||!fence.path("files").isArray()||fence.path("files").size()>MAX_RECOVERY_RECORDS)
                        throw new IOException("quarantine marker invalid");
                    for(var f:fence.path("files")) {
                        if(!f.isTextual()||f.asText().length()>255||f.asText().contains("/")||f.asText().contains("\\"))
                            throw new IOException("quarantine filename invalid");
                        suspect.add(f.asText());
                    }
                }
                if(Files.exists(jobsDir,LinkOption.NOFOLLOW_LINKS)) {
                    List<Path> files;
                    try(var stream=Files.list(jobsDir)){files=stream.filter(p->p.getFileName().toString().endsWith(".json")&&!p.equals(recoveryFence()))
                            .sorted().limit(MAX_RECOVERY_RECORDS+1L).toList();}
                    if(files.size()>MAX_RECOVERY_RECORDS)throw new IOException("remote recovery capacity exceeded");
                    long bytes=0;
                    for(Path file:files) {
                        String name=file.getFileName().toString();
                        seen.add(name);
                        try {
                            bytes=Math.addExact(bytes,Files.size(file));if(bytes>32L*1024*1024)throw new IOException("remote recovery byte budget");
                            RemoteJobRecord record=readRecord(file);
                            if(record.isActive())restored.put(record.handleId(),record);
                            suspect.remove(name); // Only a validated record clears its own quarantine, never deletion.
                        }catch(Exception damaged){suspect.add(name);}
                    }
                }
            }catch(Exception inaccessible){directoryFailure=true;}
            // During same-process reconciliation retain unresolved in-memory debt as well.
            for(var prior:activeRecords.values()) {
                String name=prior.handleId()+".json";
                if(!seen.contains(name))suspect.add(name);
                if(suspect.contains(name)||directoryFailure)restored.putIfAbsent(prior.handleId(),prior);
            }
            quarantine.clear();quarantine.addAll(suspect);
            recoveryBlocked=directoryFailure||!quarantine.isEmpty();
            if(!directoryFailure) {
                try {
                    if(recoveryBlocked)DurableJson.write(recoveryFence(),Map.of("schemaVersion",1,"files",List.copyOf(quarantine)),json,512*1024);
                    else Files.deleteIfExists(recoveryFence());
                }catch(IOException failedFence){recoveryBlocked=true;}
            }
            activeRecords.clear();activeRecords.putAll(restored);
            if(recoveryBlocked)System.getLogger(RemoteJobRegistry.class.getName()).log(System.Logger.Level.WARNING,
                    "远端任务恢复未完成；保留未知债务，暂停新提交（未输出回执内容）");
            purgeStale(Instant.now());
        }
    }

    /**
     * B-01：回收陈旧的活动远端任务记录。
     * 判定条件（满足其一）：已超过 expiresAt；或超过 {@link #STALE_AFTER} 没有任何状态更新。
     * 回收写入终态并把名额还给后续页面，不再让一次性并发饱和演变成整批页面永久失败。
     */
    int purgeStale(Instant now) {
        synchronized(lock) {
            if(recoveryBlocked)return 0; // Corruption cannot be overwritten by reclaiming a stale in-memory reservation.
            int reclaimed=0;
            for(RemoteJobRecord record:new ArrayList<>(activeRecords.values())) {
                if(record==null||!record.isActive()||!isStale(record,now))continue;
                try {
                    if(STATE_RESERVED.equals(record.state())&&record.physicalCallId()==null&&record.remoteJobId()==null) {
                        markTerminal(record.handleId(),STATE_TERMINAL_PROVEN,"expired-before-submission");reclaimed++;
                    } else if(!STATE_REMOTE_UNKNOWN.equals(record.state())&&!STATE_SUBMIT_UNKNOWN.equals(record.state())) {
                        markRemoteUnknown(record.handleId(),"stale-outcome-unconfirmed");
                    }
                } catch(IOException unavailable) {
                    // Storage failure cannot prove termination or free an unconfirmed remote slot.
                }
            }
            return reclaimed;
        }
    }

    static boolean isStale(RemoteJobRecord record, Instant now) {
        if (record == null || !record.isActive() || now == null) return false;
        if (record.expiresAt() != null && !record.expiresAt().isAfter(now)) return true;
        Instant updated = record.updatedAt() != null ? record.updatedAt() : record.createdAt();
        return updated != null && !updated.plus(STALE_AFTER).isAfter(now);
    }

    /**
     * B-02：命中并发上限时按剩余期限有界等待，而不是把页面判为永久失败。
     * 等待期间尊重取消；超时抛出可读错误，由调用方记录为该页失败原因。
     */
    public RemoteJobRecord registerWaiting(String bookId, int page, String provider, String accountScope,
                                           String inputFingerprint, String usageAttemptId,
                                           long deadlineNanos, BooleanSupplier cancelled) throws IOException {
        long backoffMs = 200L;
        String lastMessage = null;
        while (true) {
            if (cancelled != null && cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancelledException();
            try {
                return register(bookId, page, provider, accountScope, inputFingerprint, usageAttemptId);
            } catch (ApiException busy) {
                if (busy.status() != HttpStatus.TOO_MANY_REQUESTS) throw busy;
                lastMessage = busy.getMessage();
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "远端 OCR 并发名额长时间被占用，等待超时（" + (lastMessage == null ? "已达并发上限" : lastMessage) + "）");
            }
            long sleepMs = Math.max(1L, Math.min(backoffMs, remainingNanos / 1_000_000L));
            try {
                Thread.sleep(Math.min(sleepMs, 1_000L));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancelledException();
            }
            backoffMs = Math.min(backoffMs * 2, 2_000L);
        }
    }

    public RemoteJobRecord register(String bookId, int page, String provider, String accountScope,
                                    String inputFingerprint, String usageAttemptId) throws IOException {
        synchronized (lock) {
            // B-01：计数前先回收陈旧记录，避免崩溃残留造成持续"已达上限"。
            purgeStale(Instant.now());
            // Resume the exact same book/page/account handle even when all remote slots are occupied.
            if(inputFingerprint!=null&&!inputFingerprint.isBlank()) {
                for(RemoteJobRecord existing:activeRecords.values()) {
                    if(existing.isActive()&&Objects.equals(existing.inputFingerprint(),inputFingerprint)
                            &&Objects.equals(existing.bookId(),bookId)&&existing.page()==page
                            &&Objects.equals(existing.provider(),provider)&&Objects.equals(existing.accountScope(),accountScope))return existing;
                }
            }
            checkNewAdmission();
            long activeCount=activeRecords.values().stream().filter(RemoteJobRecord::isActive).count();
            if(activeCount>=MAX_ACTIVE_REMOTE_JOBS)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "活跃远端 OCR 任务已达上限（"+MAX_ACTIVE_REMOTE_JOBS+"）；已知任务可续查，未知结果不能当作已结束");

            String handleId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            RemoteJobRecord record = new RemoteJobRecord(
                    handleId, bookId, page, provider, accountScope, null, null,
                    STATE_RESERVED, 0, DEFAULT_MAX_POLLS, now, now, now,
                    now.plus(DEFAULT_JOB_TTL), inputFingerprint, usageAttemptId, null
            );
            persist(record);
            activeRecords.put(handleId, record);
            return record;
        }
    }

    public RemoteJobRecord markSubmitting(String handleId, String physicalCallId) throws IOException {
        synchronized (lock) {
            checkNewAdmission();
            if(!text(physicalCallId,512))throw new IOException("missing physical call identity");
            RemoteJobRecord current = getRequired(handleId);
            if (!STATE_RESERVED.equals(current.state()) || current.remoteJobId()!=null || current.physicalCallId()!=null)
                throw new IOException("已有提交可能已送达，不能重新发送；请先核对远端任务状态");
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), physicalCallId,
                    STATE_SUBMITTING, current.pollCount(), current.maxPolls(), current.nextPollAt(),
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markRunning(String handleId, String remoteJobId) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), remoteJobId, current.physicalCallId(),
                    STATE_RUNNING, current.pollCount(), current.maxPolls(), current.nextPollAt(),
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord recordPoll(String handleId, Instant nextPollAt) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    current.state(), current.pollCount() + 1, current.maxPolls(), nextPollAt,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), null
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markTerminal(String handleId, String terminalState, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_TERMINAL_PROVEN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.remove(handleId);
            return updated;
        }
    }

    public RemoteJobRecord markSubmitUnknown(String handleId, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_SUBMIT_UNKNOWN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord markRemoteUnknown(String handleId, String reason) throws IOException {
        synchronized (lock) {
            RemoteJobRecord current = getRequired(handleId);
            RemoteJobRecord updated = new RemoteJobRecord(
                    current.handleId(), current.bookId(), current.page(), current.provider(),
                    current.accountScope(), current.remoteJobId(), current.physicalCallId(),
                    STATE_REMOTE_UNKNOWN, current.pollCount(), current.maxPolls(), null,
                    current.createdAt(), Instant.now(), current.expiresAt(), current.inputFingerprint(),
                    current.usageAttemptId(), reason
            );
            persist(updated);
            activeRecords.put(handleId, updated);
            return updated;
        }
    }

    public RemoteJobRecord get(String handleId) {
        return activeRecords.get(handleId);
    }

    public RemoteJobRecord findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return null;
        for (RemoteJobRecord record : activeRecords.values()) {
            if (Objects.equals(record.inputFingerprint(), fingerprint)) {
                return record;
            }
        }
        return null;
    }

    public List<RemoteJobRecord> findActiveJobs() {
        return List.copyOf(activeRecords.values());
    }

    public int activeJobCount() {
        return (int) activeRecords.values().stream().filter(RemoteJobRecord::isActive).count();
    }

    private RemoteJobRecord getRequired(String handleId) {
        RemoteJobRecord record = activeRecords.get(handleId);
        if (record == null) throw new NoSuchElementException("remote job not found: " + handleId);
        return record;
    }

    private void persist(RemoteJobRecord record) throws IOException {
        Files.createDirectories(jobsDir);
        Path target = jobPath(record.handleId());
        DurableJson.write(target, record, json, 128 * 1024);
    }
}
