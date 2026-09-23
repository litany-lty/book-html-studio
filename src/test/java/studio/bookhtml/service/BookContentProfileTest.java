package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.config.AppProperties;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.BookContentProfile;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BookContentProfileTest {

    @TempDir
    Path tempDir;

    private BookStore store;
    private ObjectMapper mapper;
    private TraditionalConverter converter;
    private BookContentProfileService profileService;

    @BeforeEach
    void setUp() throws Exception {
        mapper = new ObjectMapper().findAndRegisterModules();
        AppProperties app = TestConfigs.config(tempDir, "", "");
        store = new BookStore(app, mapper);
        converter = new TraditionalConverter();
        profileService = new BookContentProfileService(store, mapper, converter);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private String createTestBook(String title, int totalPages) throws Exception {
        String bookId = UUID.randomUUID().toString();
        store.createBookDirectory(bookId);
        Book book = new Book(bookId, title, "book.pdf", totalPages, Instant.now(), Instant.now(), 0, 0);
        store.writeBook(book);
        return bookId;
    }

    private void writePage(String bookId, int pageNum, String originalText, String writingMode) throws Exception {
        Block block = new Block("b" + pageNum, "text", 0, new double[]{0, 0, 1, 1},
                writingMode != null ? writingMode : "horizontal-tb",
                originalText, originalText, 0.95, false, false, null, "test", List.of("s1"), null, null);
        Page page = new Page(pageNum, 800, 1200, "READY", "test", List.of(block), List.of(), false, null, List.of(block));
        store.writePage(bookId, page, false);
    }

    @Test
    void generatesProfileAndDetectsScriptAndWritingMode() throws Exception {
        String bookId = createTestBook("中国古代历法", 5);

        // 写包含繁体字、竖排版、章节标题的页面
        writePage(bookId, 1, "第一章 曆法原委\n觀象授時之本，在於日月運行。", "vertical-rl");
        writePage(bookId, 2, "太陽過宮與太陰交食，歷代皆有記載。", "vertical-rl");
        writePage(bookId, 3, "第二章 歲差推步\n歲實消長，天行有常。", "vertical-rl");

        BookContentProfile profile = profileService.profileForBook(bookId);

        assertNotNull(profile);
        assertEquals(bookId, profile.bookId());
        assertEquals("content-profile-v1", profile.policyVersion());
        assertEquals(1, profile.profileRevision());
        assertEquals(3, profile.observedPages());
        assertEquals(5, profile.totalPages());
        assertEquals("TRADITIONAL", profile.primaryScript());
        assertEquals("vertical-rl", profile.primaryWritingMode());
        assertTrue(profile.estimatedCharCount() > 30);
        assertEquals(2, profile.chapters().size());
        assertEquals("第一章 曆法原委", profile.chapters().get(0).title());
        assertEquals("第二章 歲差推步", profile.chapters().get(1).title());

        // 验证 sidecar 文件已原子写入磁盘
        Path sidecarPath = store.contentProfilePath(bookId);
        assertTrue(Files.exists(sidecarPath));
    }

    @Test
    void cachesProfileInMemoryAndReusesUntilInvalidated() throws Exception {
        String bookId = createTestBook("现代计算机网络", 3);
        writePage(bookId, 1, "第1章 计算机网络概述\n现代通信基础设施。", "horizontal-tb");

        BookContentProfile profile1 = profileService.profileForBook(bookId);
        BookContentProfile profile2 = profileService.profileForBook(bookId);

        // 内存缓存命中，同一对象引用
        assertSame(profile1, profile2);
        assertEquals("SIMPLIFIED", profile1.primaryScript());
        assertEquals("horizontal-tb", profile1.primaryWritingMode());

        // 主动触发失效
        profileService.invalidate(bookId);

        BookContentProfile profile3 = profileService.profileForBook(bookId);
        assertNotSame(profile1, profile3);
        // 内容未改变，修订号保持单调不增
        assertEquals(profile1.profileRevision(), profile3.profileRevision());
    }

    @Test
    void preservesRevisionWhenNonStructuralEditsOccur() throws Exception {
        String bookId = createTestBook("天文历算", 2);
        writePage(bookId, 1, "第一章 观测\n日月星辰运行。", "horizontal-tb");

        BookContentProfile initial = profileService.profileForBook(bookId);
        assertEquals(1, initial.profileRevision());

        // 修改非结构性文本（增加同类字符，不改变章节、排版方向与文字体系）
        writePage(bookId, 2, "观测数据详实。", "horizontal-tb");
        profileService.invalidate(bookId);

        BookContentProfile afterMinor = profileService.profileForBook(bookId);
        // 章节与主排版未变，revision 仍为 1
        assertEquals(1, afterMinor.profileRevision());
    }

    @Test
    void incrementsRevisionWhenStructuralChangesOccur() throws Exception {
        String bookId = createTestBook("大型编年史", 5);
        writePage(bookId, 1, "第一章 起源\n天地玄黄。", "horizontal-tb");

        BookContentProfile initial = profileService.profileForBook(bookId);
        assertEquals(1, initial.profileRevision());

        // 新增章节：发生实质结构变化
        writePage(bookId, 3, "第二章 变迁\n天下大治。", "horizontal-tb");
        profileService.invalidate(bookId);

        BookContentProfile afterChapter = profileService.profileForBook(bookId);
        assertEquals(2, afterChapter.profileRevision());
        assertEquals(2, afterChapter.chapters().size());
    }

    @Test
    void recoversFromSidecarOnColdStart() throws Exception {
        String bookId = createTestBook("冷启动测试", 2);
        writePage(bookId, 1, "第一卷 序言\n古之学者必有师。", "horizontal-tb");

        BookContentProfile profile1 = profileService.profileForBook(bookId);
        assertEquals(1, profile1.profileRevision());
        assertTrue(Files.exists(store.contentProfilePath(bookId)));

        // 模拟重启：新建 service 实例（无内存缓存）
        BookContentProfileService coldService = new BookContentProfileService(store, mapper, converter);
        BookContentProfile profileFromDisk = coldService.profileForBook(bookId);

        assertNotNull(profileFromDisk);
        assertEquals(profile1.profileRevision(), profileFromDisk.profileRevision());
        assertEquals(profile1.contentSignature(), profileFromDisk.contentSignature());
    }
}
