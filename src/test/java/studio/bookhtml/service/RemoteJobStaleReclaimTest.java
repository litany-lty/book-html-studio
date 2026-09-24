package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B：远端并发上限不再把页面判死。
 * - 陈旧活动记录（崩溃/中断残留）必须被回收，避免"已达上限"永久化（本次事故 387 页的主因）。
 * - 命中上限时按剩余期限有界排队，而不是立即失败。
 */
class RemoteJobStaleReclaimTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    /** 把磁盘上的活动记录改成陈旧（updatedAt/expiresAt/createdAt 都在过去）。 */
    private void makeStale(String handleId) throws Exception {
        Path file = dataDir.resolve("remote-jobs").resolve(handleId + ".json");
        com.fasterxml.jackson.databind.node.ObjectNode node =
                (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(Files.readString(file));
        String past = Instant.now().minusSeconds(3600).toString();
        node.put("updatedAt", past);
        node.put("expiresAt", past);
        node.put("createdAt", past);
        Files.writeString(file, json.writeValueAsString(node));
    }

    @Test
    void staleActiveRecordsAreReclaimedOnRecovery() throws Exception {
        RemoteJobRegistry registry = new RemoteJobRegistry(dataDir, json);
        var r1 = registry.register("book-1", 1, "paddle-aistudio", "acc", "fp-1", "u-1");
        var r2 = registry.register("book-1", 2, "paddle-aistudio", "acc", "fp-2", "u-2");
        var r3 = registry.register("book-1", 3, "paddle-aistudio", "acc", "fp-3", "u-3");
        assertEquals(3, registry.activeJobCount());
        makeStale(r2.handleId());
        makeStale(r3.handleId());

        // 重启（崩溃恢复）后：陈旧记录不得继续占用并发名额
        RemoteJobRegistry recovered = new RemoteJobRegistry(dataDir, json);
        assertEquals(1, recovered.activeJobCount(), "陈旧活动记录必须被回收");
        assertNotNull(recovered.get(r1.handleId()));
        assertNull(recovered.get(r2.handleId()));

        // 名额已还：新的页面可以正常登记，而不是被"已达上限"判死
        var r4 = recovered.register("book-1", 4, "paddle-aistudio", "acc", "fp-4", "u-4");
        assertNotNull(r4);
        assertEquals(2, recovered.activeJobCount());
    }

    @Test
    void staleRecordsDoNotBlockRegistrationWithoutRestart() throws Exception {
        RemoteJobRegistry registry = new RemoteJobRegistry(dataDir, json);
        var r1 = registry.register("book-1", 1, "paddle-aistudio", "acc", "fp-1", "u-1");
        registry.register("book-1", 2, "paddle-aistudio", "acc", "fp-2", "u-2");
        registry.register("book-1", 3, "paddle-aistudio", "acc", "fp-3", "u-3");
        makeStale(r1.handleId());
        // 同进程内再次登记：计数前会先回收陈旧记录
        registry.recover();
        assertTrue(registry.activeJobCount() < 3);
        var next = registry.register("book-1", 9, "paddle-aistudio", "acc", "fp-9", "u-9");
        assertNotNull(next);
    }

    @Test
    void registerWaitingQueuesUntilSlotFrees() throws Exception {
        RemoteJobRegistry registry = new RemoteJobRegistry(dataDir, json);
        var first = registry.register("book-1", 1, "paddle-aistudio", "acc", "fp-1", "u-1");
        registry.register("book-1", 2, "paddle-aistudio", "acc", "fp-2", "u-2");
        registry.register("book-1", 3, "paddle-aistudio", "acc", "fp-3", "u-3");

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(400);
                registry.markTerminal(first.handleId(), RemoteJobRegistry.STATE_TERMINAL_PROVEN, "test-release");
            } catch (Exception ignored) { }
        });
        releaser.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        var queued = registry.registerWaiting("book-1", 4, "paddle-aistudio", "acc", "fp-4", "u-4",
                deadline, () -> false);
        releaser.join();
        assertNotNull(queued, "名额释放后排队请求应当成功，而不是把整页判为失败");
    }

    @Test
    void registerWaitingTimesOutWithReadableError() throws Exception {
        RemoteJobRegistry registry = new RemoteJobRegistry(dataDir, json);
        registry.register("book-1", 1, "paddle-aistudio", "acc", "fp-1", "u-1");
        registry.register("book-1", 2, "paddle-aistudio", "acc", "fp-2", "u-2");
        registry.register("book-1", 3, "paddle-aistudio", "acc", "fp-3", "u-3");
        long deadline = System.nanoTime() + 300_000_000L;
        ApiException error = assertThrows(ApiException.class, () ->
                registry.registerWaiting("book-1", 4, "paddle-aistudio", "acc", "fp-4", "u-4",
                        deadline, () -> false));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.status());
        assertTrue(error.getMessage().contains("等待超时"));
    }

    @Test
    void registerWaitingHonoursCancellation() throws Exception {
        RemoteJobRegistry registry = new RemoteJobRegistry(dataDir, json);
        registry.register("book-1", 1, "paddle-aistudio", "acc", "fp-1", "u-1");
        registry.register("book-1", 2, "paddle-aistudio", "acc", "fp-2", "u-2");
        registry.register("book-1", 3, "paddle-aistudio", "acc", "fp-3", "u-3");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ignored) { }
            cancelled.set(true);
        });
        canceller.start();
        long deadline = System.nanoTime() + 10_000_000_000L;
        assertThrows(CancelledException.class, () ->
                registry.registerWaiting("book-1", 4, "paddle-aistudio", "acc", "fp-4", "u-4",
                        deadline, cancelled::get));
        canceller.join();
    }

    @Test void remoteUnknownCannotBeReclaimedByElapsedTimeAlone()throws Exception {
        var registry=new RemoteJobRegistry(dataDir,json);
        var record=registry.register("book-1",1,"paddle-aistudio","account","fingerprint","usage");
        registry.markSubmitting(record.handleId(),"physical");registry.markRemoteUnknown(record.handleId(),"transport-unknown");
        makeStale(record.handleId());var recovered=new RemoteJobRegistry(dataDir,json);
        assertEquals(1,recovered.activeJobCount());assertFalse(recovered.get(record.handleId()).isTerminal());
    }
    @Test void exactResumeWorksAtCapacityButCannotCrossBookOrAccount()throws Exception {
        var registry=new RemoteJobRegistry(dataDir,json);
        var record=registry.register("book-1",1,"paddle-aistudio","account","fingerprint","usage");
        registry.register("book-1",2,"paddle-aistudio","account","second","usage2");registry.register("book-1",3,"paddle-aistudio","account","third","usage3");
        assertEquals(record.handleId(),registry.register("book-1",1,"paddle-aistudio","account","fingerprint","usage").handleId());
        assertThrows(ApiException.class,()->registry.register("book-2",1,"paddle-aistudio","account","fingerprint","usage"));
        assertThrows(ApiException.class,()->registry.register("book-1",1,"paddle-aistudio","other-account","fingerprint","usage"));
    }
}
