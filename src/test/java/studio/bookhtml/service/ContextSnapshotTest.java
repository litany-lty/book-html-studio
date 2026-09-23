package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.decision.DecisionStore;
import studio.bookhtml.domain.ContextSnapshot;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContextSnapshotTest {

    @TempDir
    Path tempDir;

    private BookStore store;
    private DecisionStore decisionStore;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        AppProperties app = TestConfigs.config(tempDir, "", "");
        store = new BookStore(app, mapper);
        decisionStore = new DecisionStore(store, mapper);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void createsValidContextSnapshotWithCorrectCodePointsAndBytes() {
        String bookId = UUID.randomUUID().toString();
        // 包含常规汉字与代理对字符（如 "𠮷" / \uD842\uDFB7，占 2 个 UTF-16 code units，1 个 Unicode code point，4 个 UTF-8 字节）
        String fullBlockText = "天地玄黄𠮷宇宙洪荒";
        // 提取 "黄𠮷宇"：
        // "天地玄" -> len 3
        // "黄" -> index 3..4 (1 code unit)
        // "𠮷" -> index 4..6 (2 code units, \uD842\uDFB7)
        // "宇" -> index 6..7 (1 code unit)
        int start = 3;
        int end = 7; // "黄𠮷宇"
        String targetText = fullBlockText.substring(start, end);
        assertEquals("黄𠮷宇", targetText);
        assertEquals(4, targetText.length()); // 4 个 UTF-16 code units
        assertEquals(3, targetText.codePointCount(0, targetText.length())); // 3 个 Unicode 码点

        ContextSnapshot snapshot = ContextSnapshot.create(
                bookId, 1, 0, "parent-plan-001", "review-sub-001", 100L,
                "block-1", start, end, fullBlockText, "周围文本上下文", 1024);

        assertNotNull(snapshot);
        assertNotNull(snapshot.snapshotId());
        assertEquals(bookId, snapshot.bookId());
        assertEquals(1, snapshot.pageNumber());
        assertEquals(0, snapshot.pageRevision());
        assertEquals("parent-plan-001", snapshot.parentPlanHash());
        assertEquals("review-sub-001", snapshot.reviewPlanHash());
        assertEquals(100L, snapshot.eventSeq());
        assertEquals("block-1", snapshot.blockId());
        assertEquals(start, snapshot.startUtf16());
        assertEquals(end, snapshot.endUtf16());
        assertEquals("黄𠮷宇", snapshot.targetText());
        assertEquals(3, snapshot.codePointCount());
        assertEquals(targetText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, snapshot.byteLength());
        assertEquals(1024, snapshot.maxByteBudget());
        assertNotNull(snapshot.contextHash());
        assertFalse(snapshot.contextHash().isBlank());
    }

    @Test
    void rejectsWhenUtf16RangeSplitsSurrogatePair() {
        String bookId = UUID.randomUUID().toString();
        // "𠮷" 位于索引 0..2
        String text = "\uD842\uDFB7文本";

        // 尝试从代理对中间 (1) 切割
        IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                ContextSnapshot.create(bookId, 1, 0, "p", "r", 1L, "b1", 1, 3, text, "", 1024));
        assertTrue(ex1.getMessage().contains("代理对"));

        // 尝试在代理对中间 (1) 结束
        IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                ContextSnapshot.create(bookId, 1, 0, "p", "r", 1L, "b1", 0, 1, text, "", 1024));
        assertTrue(ex2.getMessage().contains("代理对"));
    }

    @Test
    void rejectsWhenByteBudgetExceeded() {
        String bookId = UUID.randomUUID().toString();
        String text = "这是一段很长的文本用来测试字节预算上限";
        // 预算仅给 10 字节，而 4 个汉字已有 12 字节
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                ContextSnapshot.create(bookId, 1, 0, "p", "r", 1L, "b1", 0, 4, text, "", 10));
        assertTrue(ex.getMessage().contains("字节预算"));
    }

    @Test
    void hashSensitivityToPlansAndText() {
        String bookId = UUID.randomUUID().toString();
        String text = "精益求精";

        ContextSnapshot base = ContextSnapshot.create(
                bookId, 1, 0, "parent-A", "review-1", 10L, "b1", 0, 2, text, "ctx", 1024);

        ContextSnapshot diffParent = ContextSnapshot.create(
                bookId, 1, 0, "parent-B", "review-1", 10L, "b1", 0, 2, text, "ctx", 1024);

        ContextSnapshot diffReview = ContextSnapshot.create(
                bookId, 1, 0, "parent-A", "review-2", 10L, "b1", 0, 2, text, "ctx", 1024);

        ContextSnapshot diffText = ContextSnapshot.create(
                bookId, 1, 0, "parent-A", "review-1", 10L, "b1", 2, 4, text, "ctx", 1024);

        assertNotEquals(base.contextHash(), diffParent.contextHash());
        assertNotEquals(base.contextHash(), diffReview.contextHash());
        assertNotEquals(base.contextHash(), diffText.contextHash());
    }

    @Test
    void persistsAndLoadsFromDecisionStore() throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);

        ContextSnapshot snapshot = ContextSnapshot.create(
                bookId, 1, 2, "parent-hash-xyz", "review-hash-123", 55L,
                "block-9", 0, 3, "古今通塞", "前置上下文", 2048);

        decisionStore.saveContextSnapshot(bookId, snapshot);

        Optional<ContextSnapshot> loaded = decisionStore.loadContextSnapshot(bookId, snapshot.snapshotId());
        assertTrue(loaded.isPresent());

        ContextSnapshot recovered = loaded.get();
        assertEquals(snapshot.snapshotId(), recovered.snapshotId());
        assertEquals(snapshot.bookId(), recovered.bookId());
        assertEquals(snapshot.pageNumber(), recovered.pageNumber());
        assertEquals(snapshot.pageRevision(), recovered.pageRevision());
        assertEquals(snapshot.parentPlanHash(), recovered.parentPlanHash());
        assertEquals(snapshot.reviewPlanHash(), recovered.reviewPlanHash());
        assertEquals(snapshot.eventSeq(), recovered.eventSeq());
        assertEquals(snapshot.blockId(), recovered.blockId());
        assertEquals(snapshot.startUtf16(), recovered.startUtf16());
        assertEquals(snapshot.endUtf16(), recovered.endUtf16());
        assertEquals(snapshot.targetText(), recovered.targetText());
        assertEquals(snapshot.codePointCount(), recovered.codePointCount());
        assertEquals(snapshot.byteLength(), recovered.byteLength());
        assertEquals(snapshot.contextHash(), recovered.contextHash());
    }
}
