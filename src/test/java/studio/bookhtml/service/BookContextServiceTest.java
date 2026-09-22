package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.*;
import studio.bookhtml.store.BookStore;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BookContextServiceTest {
    @TempDir Path dir;
    BookStore resource;
    @org.junit.jupiter.api.AfterEach void close() { if (resource != null) resource.close(); }
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private String seed(BookStore store, String title, int pages) throws Exception {
        String id = UUID.randomUUID().toString(); store.createBookDirectory(id);
        store.writeBook(new Book(id, title, "book.pdf", pages, Instant.now(), Instant.now(), 0, 0));
        return id;
    }
    private static Block text(String content) {
        return new Block(UUID.randomUUID().toString(), "text", 0, null, "horizontal-tb", content,
                content, .99, false, false, null, "ocr", List.of(), null, null);
    }
    private void page(BookStore store, String id, int n, String source, String display) throws Exception {
        store.writePage(id, new Page(n, 600, 800, "READY", "ocr", List.of(text(display)), List.of(),
                false, null, List.of(text(source))), false);
    }
    @Test void isolatesBooksChaptersAndUsesSourcesNotUnreviewedDisplay() throws Exception {
        {
            BookStore store = resource = new BookStore(TestConfigs.config(dir, "", ""), json);
            String id = seed(store, "天文观测", 30), other = seed(store, "医学", 30);
            page(store, id, 9, "第一章 天体位置", "伪造章节");
            page(store, id, 10, "真太阳时与均时差", "模型幻想答案");
            page(store, id, 11, "观测实例", "显示内容");
            page(store, id, 12, "第二章 历法", "显示内容");
            page(store, other, 10, "不应跨书出现的药名", "显示内容");
            String result = new BookContextService(store, json).snapshot(id, 11);
            assertTrue(result.contains("第一章 天体位置"));
            assertTrue(result.contains("真太阳时与均时差"));
            assertFalse(result.contains("模型幻想答案"));
            assertFalse(result.contains("不应跨书"));
            assertFalse(result.contains("第二章 历法"));
            assertTrue(result.contains("OCR_UNVERIFIED"));
            assertFalse(json.readTree(result).path("chapterHintVerified").asBoolean());
        }
    }
    @Test void invalidatesOnSourceChangeAndBoundsReadsIndependentlyOfBookLength() throws Exception {
        {
            BookStore store = resource = spy(new BookStore(TestConfigs.config(dir, "", ""), json));
            String id = seed(store, "大书", 10000);
            page(store, id, 4999, "旧证据", "忽略显示");
            BookContextService context = new BookContextService(store, json);
            clearInvocations(store);
            assertTrue(context.snapshot(id, 5000).contains("旧证据"));
            verify(store, atMost(15)).readPage(eq(id), anyInt());
            clearInvocations(store);
            assertTrue(context.snapshot(id, 5000).contains("旧证据"));
            verify(store, never()).readPage(anyString(), anyInt());
            page(store, id, 4999, "新证据", "不应反馈");
            String result = context.snapshot(id, 5000);
            assertTrue(result.contains("新证据")); assertFalse(result.contains("旧证据"));
            assertEquals("{}", context.snapshot(id, 10001));
        }
    }
    @Test void clipsWithoutSplittingSurrogatePairs() {
        assertEquals("a", BookContextService.clip("a\uD83D\uDE00b", 2));
        assertEquals("", BookContextService.clip(null, 20));
    }
}
