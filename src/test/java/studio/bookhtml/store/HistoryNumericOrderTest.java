package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;

import studio.bookhtml.config.AppProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R04：历史版本按数值保留最新 5 个。 */
class HistoryNumericOrderTest {
    @TempDir Path temp;

    private BookStore store() throws Exception {
        AppProperties config = new AppProperties(temp, 300, 5000, 2400, "tesseract", "", "qwen3.5-ocr",
                "https://dashscope.aliyuncs.com/api/v1", 5, "", "MiniMax-M3", "https://api.minimax.cn/v1", 5, false);
        return new BookStore(config, new ObjectMapper().findAndRegisterModules());
    }

    private String book(BookStore store) throws Exception {
        String id = UUID.randomUUID().toString();
        store.createBookDirectory(id);
        store.writeBook(new Book(id, "t", "t.pdf", 1, Instant.now(), Instant.now(), 0, 0));
        return id;
    }

    private static Page ready(int n, String marker) {
        return new Page(1, 600, 800, "READY", "manual",
                List.of(), List.of(marker), false, null);
    }

    @Test void keepsNumericallyNewestFiveAcrossTwelveWrites() throws Exception {
        BookStore store = store();
        String id = book(store);
        for (int i = 0; i < 12; i++) store.writePage(id, ready(1, "v" + i), false);
        assertEquals(11, BookStore.revisionOrZero(store.readPage(id, 1)));
        List<Integer> history = store.listRevisions(id, 1).stream().filter(r -> r != 11).toList();
        assertEquals(List.of(6, 7, 8, 9, 10), history);
        assertTrue(Files.exists(store.historyDir(id, 1).resolve("rev-10.json")), "两位数新版本不得被误删");
        assertTrue(Files.notExists(store.historyDir(id, 1).resolve("rev-5.json")));
    }

    @Test void keepsNewestFiveAcrossThreeDigitRevisions() throws Exception {
        BookStore store = store();
        String id = book(store);
        for (int i = 0; i < 105; i++) store.writePage(id, ready(1, "v" + i), false);
        assertEquals(104, BookStore.revisionOrZero(store.readPage(id, 1)));
        List<Integer> history = store.listRevisions(id, 1).stream().filter(r -> r != 104).toList();
        assertEquals(List.of(99, 100, 101, 102, 103), history);
    }

    @Test void malformedHistoryFilesArePreservedNotPruned() throws Exception {
        BookStore store = store();
        String id = book(store);
        for (int i = 0; i < 8; i++) store.writePage(id, ready(1, "v" + i), false);
        Path dir = store.historyDir(id, 1);
        Files.writeString(dir.resolve("rev-bad.json"), "{}");
        Files.writeString(dir.resolve("rev-1.json.bak"), "{}");
        Files.writeString(dir.resolve("notes.txt"), "do not touch");
        for (int i = 8; i < 12; i++) store.writePage(id, ready(1, "v" + i), false);
        assertTrue(Files.exists(dir.resolve("rev-bad.json")));
        assertTrue(Files.exists(dir.resolve("rev-1.json.bak")));
        assertTrue(Files.exists(dir.resolve("notes.txt")));
        List<Integer> history = store.listRevisions(id, 1).stream().filter(r -> r != 11).toList();
        assertEquals(List.of(6, 7, 8, 9, 10), history);
    }

    @Test void revertCreatesNewRevisionAndKeepsCurrentInHistory() throws Exception {
        BookStore store = store();
        String id = book(store);
        for (int i = 0; i < 4; i++) store.writePage(id, ready(1, "v" + i), false);
        int before = BookStore.revisionOrZero(store.readPage(id, 1));
        store.revertPage(id, 1, 1, before);
        int after = BookStore.revisionOrZero(store.readPage(id, 1));
        assertTrue(after > before, "回退必须生成更高的新 revision");
        assertTrue(store.listRevisions(id, 1).contains(before), "回退前的当前版本必须保留在历史中");
    }
}
