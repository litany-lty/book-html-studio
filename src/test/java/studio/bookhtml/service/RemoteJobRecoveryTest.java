package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import studio.bookhtml.api.ApiException;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RemoteJobRecoveryTest {
    @TempDir Path dataDir;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private RemoteJobRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new RemoteJobRegistry(dataDir, json);
    }

    @Test
    void activeRemoteJobsEnforceCapOfThree() throws Exception {
        // CONC-12: Cannot create jobs exceeding remote cap
        var r1 = registry.register("book-1", 1, "paddle-aistudio", "acc-1", "fp-1", "u-1");
        var r2 = registry.register("book-1", 2, "paddle-aistudio", "acc-1", "fp-2", "u-2");
        var r3 = registry.register("book-1", 3, "paddle-aistudio", "acc-1", "fp-3", "u-3");

        assertEquals(3, registry.activeJobCount());

        ApiException ex = assertThrows(ApiException.class, () ->
                registry.register("book-1", 4, "paddle-aistudio", "acc-1", "fp-4", "u-4"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.status());
        assertTrue(ex.getMessage().contains("活跃远端 OCR 任务已达上限"));

        // Release one job to terminal state
        registry.markTerminal(r1.handleId(), "TERMINAL_PROVEN", null);
        assertEquals(2, registry.activeJobCount());

        // Now 4th registration succeeds
        var r4 = registry.register("book-1", 4, "paddle-aistudio", "acc-1", "fp-4", "u-4");
        assertNotNull(r4);
        assertEquals(3, registry.activeJobCount());
    }

    @Test
    void crashRecoveryRestoresRunningJobsWithoutReSubmitting() throws Exception {
        // Register and mark running with remoteTaskId
        var r = registry.register("book-crash", 1, "paddle-aistudio", "acc-1", "fp-crash-1", "u-crash-1");
        registry.markSubmitting(r.handleId(), "call-1");
        registry.markRunning(r.handleId(), "paddle-remote-task-888");

        // Simulate server crash and restart: create a new RemoteJobRegistry pointing to same dataDir
        RemoteJobRegistry recoveredRegistry = new RemoteJobRegistry(dataDir, json);

        assertEquals(1, recoveredRegistry.activeJobCount());
        var recovered = recoveredRegistry.get(r.handleId());
        assertNotNull(recovered);
        assertEquals("RUNNING", recovered.state());
        assertEquals("paddle-remote-task-888", recovered.remoteJobId());

        // CONC-13: Same fingerprint query returns existing active record instead of submitting new
        var existing = recoveredRegistry.findByFingerprint("fp-crash-1");
        assertNotNull(existing);
        assertEquals(r.handleId(), existing.handleId(), "已有在途远端任务时按指纹复用，绝不触发第二次提交");
    }

    @Test
    void submitUnknownWithoutJobIdDoesNotAutomaticallyResubmit() throws Exception {
        // CONC-13: SUBMIT_UNKNOWN without remote jobId
        var r = registry.register("book-unk", 1, "paddle-aistudio", "acc-1", "fp-unk-1", "u-unk-1");
        registry.markSubmitting(r.handleId(), "call-unk");
        registry.markSubmitUnknown(r.handleId(), "网络超时，远端结果不明");

        // Restart
        RemoteJobRegistry recoveredRegistry = new RemoteJobRegistry(dataDir, json);
        var recovered = recoveredRegistry.get(r.handleId());
        assertNotNull(recovered);
        assertEquals("SUBMIT_UNKNOWN", recovered.state());
        assertNull(recovered.remoteJobId());

        // When queried again for same input fingerprint, returns existing SUBMIT_UNKNOWN record
        var existing = recoveredRegistry.findByFingerprint("fp-unk-1");
        assertNotNull(existing);
        assertEquals("SUBMIT_UNKNOWN", existing.state());
        assertNull(existing.remoteJobId(), "未知无 jobId 不自动重提，保持未知债务");
    }
}
