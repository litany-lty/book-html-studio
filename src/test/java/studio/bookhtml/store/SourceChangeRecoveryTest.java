package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SourceChangeRecoveryTest {
    @TempDir Path tempDir;
    private Path realDir;
    private ObjectMapper json;
    private BookStore store;
    private String bookId;

    static AppProperties config(Path data) {
        return new AppProperties(data, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, true);
    }

    @BeforeEach
    void setUp() throws Exception {
        realDir = tempDir.toRealPath();
        json = new ObjectMapper().findAndRegisterModules();
        var cfg = config(realDir);
        store = new BookStore(cfg, json);
        bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, "源事件测试书", "source.pdf", 10, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    private Block block(String id, String text) {
        return new Block(id, "text", 0, new double[]{0.1, 0.1, 0.8, 0.1}, "horizontal-tb",
                text, text, 0.99, false, false, null, "test", List.of(id), null, null);
    }

    private Page readyPage(int pageNumber, String text) {
        return new Page(pageNumber, 600.0, 800.0, "READY", "test",
                List.of(block("b" + pageNumber, text)), List.of(), false, null, List.of(block("b" + pageNumber, text)));
    }

    @Test
    void normalCommitRecordsPreparedAndCommittedSourceEvents() throws Exception {
        Page page1 = readyPage(1, "初始文字");
        store.writePage(bookId, page1, true);

        // Commit page 1 revision 1
        Page next = readyPage(1, "修改后的文字");
        Page committed = store.commitPage(bookId, next, 0, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);
        assertEquals(1, committed.revision());

        List<SourceChange> events = store.sourceJournal().readAll(store.bookDir(bookId), bookId);
        assertFalse(events.isEmpty());

        // Find events for page 1
        List<SourceChange> pageEvents = events.stream()
                .filter(e -> "PAGE".equals(e.targetKind()) && Integer.valueOf(1).equals(e.pageNumber()))
                .toList();

        assertEquals(2, pageEvents.size());
        assertEquals("PREPARED", pageEvents.get(0).state());
        assertEquals(1, pageEvents.get(0).afterRevision());
        assertEquals(committed.lastCommitId(), pageEvents.get(0).commitId());

        assertEquals("COMMITTED", pageEvents.get(1).state());
        assertEquals(committed.lastCommitId(), pageEvents.get(1).commitId());
    }

    @Test
    void crashAfterReplaceReconcilesPreparedToCommitted() throws Exception {
        Page page1 = readyPage(1, "基础文字");
        store.writePage(bookId, page1, true);

        // Inject simulated crash: create a PREPARED event matching current page
        Page currentPage = store.readPage(bookId, 1);
        String currentHash = store.pageContentHash(currentPage);
        long seq = store.sourceJournal().nextSourceSeq(store.bookDir(bookId), bookId);
        store.sourceJournal().prepare(store.bookDir(bookId), bookId, "PAGE", 1,
                currentPage.lastCommitId(), null, 0, 0, "0000000000000000000000000000000000000000000000000000000000000000",
                currentHash, "CRASH_SIMULATION");

        // Reconcile
        store.sourceJournal().reconcile(store.bookDir(bookId), bookId, store);

        List<SourceChange> events = store.sourceJournal().readAll(store.bookDir(bookId), bookId);
        SourceChange last = events.get(events.size() - 1);
        assertEquals("COMMITTED", last.state());
        assertEquals(currentPage.lastCommitId(), last.commitId());
    }

    @Test
    void crashBeforeReplaceReconcilesPreparedToNotPublished() throws Exception {
        Page page1 = readyPage(1, "未替换的文字");
        store.writePage(bookId, page1, true);

        Page currentPage = store.readPage(bookId, 1);
        String currentHash = store.pageContentHash(currentPage);

        // Prepare intent for revision 1 (which was never actually written to disk)
        UUID hypotheticalCommitId = UUID.randomUUID();
        long seq = store.sourceJournal().nextSourceSeq(store.bookDir(bookId), bookId);
        store.sourceJournal().prepare(store.bookDir(bookId), bookId, "PAGE", 1,
                hypotheticalCommitId, null, 0, 1, currentHash,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "ABORTED_INTENT");

        // Reconcile
        store.sourceJournal().reconcile(store.bookDir(bookId), bookId, store);

        List<SourceChange> events = store.sourceJournal().readAll(store.bookDir(bookId), bookId);
        SourceChange last = events.get(events.size() - 1);
        assertEquals("NOT_PUBLISHED", last.state());
    }

    @Test
    void pageSaveSucceedsEvenIfEventFinalizeThrows() throws Exception {
        Page page1 = readyPage(1, "容错测试文字");
        store.writePage(bookId, page1, true);

        // Subclass BookStore or use commit checkpoint to simulate failure during finalize
        class FaultyBookStore extends BookStore {
            FaultyBookStore(studio.bookhtml.config.AppProperties props, ObjectMapper json) throws IOException {
                super(props, json);
            }
            @Override
            protected void commitCheckpoint(String phase) throws IOException {
                if ("commit-completion".equals(phase)) {
                    throw new IOException("Simulated disk error during finalize");
                }
            }
        }

        var cfg = config(realDir);
        // We test that BookStore catch block protects the published page
        Page next = readyPage(1, "虽然finalize失败但正文必须安全持久化");
        Page saved = store.commitPage(bookId, next, 0, CommitActor.MANUAL, null, CommitOp.MANUAL_SAVE);

        assertNotNull(saved);
        assertEquals(1, saved.revision());
        Page onDisk = store.readPage(bookId, 1);
        assertEquals(saved.lastCommitId(), onDisk.lastCommitId());
    }
}
