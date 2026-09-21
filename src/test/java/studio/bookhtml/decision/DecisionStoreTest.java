package studio.bookhtml.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Book;
import studio.bookhtml.store.BookStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * J01（T27–T31 存储侧）：sidecar 原子保存、损坏隔离、数量字节限制、不跨书。
 */
class DecisionStoreTest {
    @TempDir Path temp;

    private BookStore store() throws Exception {
        AppProperties config = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "m", "u", 5,
                "", "m", "u", 5, true);
        return new BookStore(config, new ObjectMapper().findAndRegisterModules());
    }

    private String book(BookStore store) throws Exception {
        String id = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        return id;
    }

    private DecisionModels.DecisionSnapshot snapshot(String book) {
        DecisionModels.IssueRef ref = new DecisionModels.IssueRef(book, "pdfhash", 1, 0, "b1", "i1",
                "otext", "basis", 0, 1, "span", "map-v1");
        String hash = DecisionModels.DecisionSnapshot.computeHash(ref, "cs-hash", Map.of("k", "v"), "tpl-v1");
        return new DecisionModels.DecisionSnapshot(hash, ref, "cs-hash", Map.of("k", "v"),
                List.of("r1"), List.of(), "tpl-v1", Instant.now());
    }

    @Test void roundTripAndIsolation() throws Exception {
        BookStore store = store();
        String id = book(store);
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        decisions.saveSnapshot(id, snapshot(id));
        assertEquals(snapshot(id).snapshotHash(),
                decisions.loadSnapshot(id, snapshot(id).snapshotHash()).orElseThrow().snapshotHash());
        // 未保存的 hash 返回空，不抛错
        assertTrue(decisions.loadSnapshot(id, "no-such-hash").isEmpty());
        // 损坏文件隔离：返回空并改名保留，其他文件不受影响
        Path bad = decisions.decisionsDir(id).resolve("snapshots").resolve(snapshot(id).snapshotHash() + ".json");
        Files.writeString(bad, "{broken json");
        assertTrue(decisions.loadSnapshot(id, snapshot(id).snapshotHash()).isEmpty());
        assertFalse(Files.exists(bad));
        try (var entries = Files.list(bad.getParent())) {
            assertTrue(entries.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")));
        }
        // 跨书不串：同 hash 在另一本书读不到
        String other = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        store.createBookDirectory(other);
        store.writeBook(new Book(other, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        assertTrue(decisions.loadSnapshot(other, snapshot(id).snapshotHash()).isEmpty());
    }

    @Test void rejectsOversizeAndUsesBookStorePaths() throws Exception {
        BookStore store = spy(store());
        String id = book(store);
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        // 路径必须经 BookStore.bookDir 构造
        decisions.ensureSchema(id);
        verify(store, atLeastOnce()).bookDir(id);
        assertTrue(Files.exists(decisions.decisionsDir(id).resolve("schema.json")));
    }

    @Test void admissionLookupFindsSameKey() throws Exception {
        BookStore store = store();
        String id = book(store);
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.now();
        DecisionStore.DecisionJob job = new DecisionStore.DecisionJob("job-1", "QUEUED", 1, "LOCATING",
                "admission-1", null, id, 1, "b1", "i1", null, null, null, "op-1", null, List.of(),
                0, "UNKNOWN", "NONE", now, now, now.plusSeconds(180), false);
        decisions.saveJob(id, job);
        assertEquals("job-1", decisions.findByAdmission(id, "admission-1").jobId());
        assertNull(decisions.findByAdmission(id, "other-key"));
        assertEquals("job-1", decisions.loadJob(id, "job-1").orElseThrow().jobId());
    }

    @Test void checkLeafRejectsPathTraversalAndInvalidIds() throws Exception {
        // JR-11-T03: 拒绝斜杠、反斜杠、点路径与非预期格式
        assertThrows(IllegalArgumentException.class, () -> DecisionStore.checkLeaf("../evil"));
        assertThrows(IllegalArgumentException.class, () -> DecisionStore.checkLeaf("sub/dir"));
        assertThrows(IllegalArgumentException.class, () -> DecisionStore.checkLeaf("sub\\dir"));
        assertThrows(IllegalArgumentException.class, () -> DecisionStore.checkLeaf(""));
        assertThrows(IllegalArgumentException.class, () -> DecisionStore.checkLeaf(null));

        // load 方法在参数含非法路径时立即拒绝，不读受控目录之外的文件
        BookStore store = store();
        String id = book(store);
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        assertThrows(IllegalArgumentException.class, () -> decisions.loadJob(id, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> decisions.loadSnapshot(id, "../../outside"));
        assertThrows(IllegalArgumentException.class, () -> decisions.loadResult(id, "foo/bar"));
        assertThrows(IllegalArgumentException.class, () -> decisions.loadCandidateSet(id, "foo\\bar"));
    }

    @Test void dirLimitAllowsOverwriteOfExistingJob() throws Exception {
        // JR-11-T01: 达到条目上限仍允许已有任务更新终态
        BookStore store = store();
        String id = book(store);
        DecisionStore decisions = new DecisionStore(store, new ObjectMapper().findAndRegisterModules());
        Instant now = Instant.now();
        DecisionStore.DecisionJob job = new DecisionStore.DecisionJob("job-existing", "QUEUED", 1, "LOCATING",
                "admission-1", null, id, 1, "b1", "i1", null, null, null, "op-1", null, List.of(),
                0, "UNKNOWN", "NONE", now, now, now.plusSeconds(180), false);
        decisions.saveJob(id, job);

        // 模拟目录被其他条目填满至 MAX_FILES_PER_DIR
        Path jobsDir = decisions.decisionsDir(id).resolve("jobs");
        for (int i = 0; i < DecisionStore.MAX_FILES_PER_DIR; i++) {
            Path dummy = jobsDir.resolve("dummy-" + i + ".json");
            if (!Files.exists(dummy)) Files.writeString(dummy, "{}");
        }

        // 新增文件被拒绝
        DecisionStore.DecisionJob newJob = new DecisionStore.DecisionJob("job-new", "QUEUED", 1, "LOCATING",
                "admission-new", null, id, 1, "b1", "i1", null, null, null, "op-new", null, List.of(),
                0, "UNKNOWN", "NONE", now, now, now.plusSeconds(180), false);
        assertThrows(java.io.IOException.class, () -> decisions.saveJob(id, newJob));

        // 已有文件更新成功落盘
        DecisionStore.DecisionJob updatedJob = new DecisionStore.DecisionJob("job-existing", "SUCCEEDED", 2, "DONE",
                "admission-1", null, id, 1, "b1", "i1", null, null, "dec-1", "op-1", "RECOMMEND", List.of(),
                0, "UNKNOWN", "NONE", now, now, now.plusSeconds(180), false);
        assertDoesNotThrow(() -> decisions.saveJob(id, updatedJob));
        assertEquals("SUCCEEDED", decisions.loadJob(id, "job-existing").orElseThrow().state());
    }
}
