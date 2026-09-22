package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
public class OutlineService {
    private static final String PAGE_TOKEN = "(?:[0-9]{1,5}|[〇○零一二三四五六七八九十百千兩两廿卅]{1,8})";

    private final BookStore store;
    private BookPresentationService presentation;

    public OutlineService(BookStore store) {
        this.store = store;
    }

    /** U3：统一书籍画像投影。未注入时回退旧适配器（保守兼容）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPresentation(BookPresentationService presentation) {
        this.presentation = presentation;
    }

    public List<OutlineEntry> outline(String bookId) {
        if (presentation != null) {
            return presentation.outline(bookId);
        }
        Book book = store.readBook(bookId);
        List<Page> pages = new ArrayList<>(book.totalPages());
        for (int pageNumber = 1; pageNumber <= book.totalPages(); pageNumber++) {
            Page page = store.readPage(bookId, pageNumber);
            if (page != null) pages.add(page);
        }
        return fromPages(pages);
    }

    public static List<OutlineEntry> fromPages(List<Page> pages) {
        if (pages == null || pages.isEmpty()) return List.of();
        List<OutlineEntry> entries = new ArrayList<>();
        pages.stream()
                .filter(page -> page != null && "READY".equals(page.status()))
                .sorted(Comparator.comparingInt(Page::pageNumber))
                .forEach(page -> blocks(page).stream()
                        .filter(block -> block != null && "heading".equals(block.type()))
                        .sorted(Comparator.comparingInt(Block::order))
                        .forEach(block -> {
                            String title = HeadingText.display(block, true).strip();
                            if (!title.isEmpty() && !directoryHeading(block, title)) {
                                entries.add(new OutlineEntry(page.pageNumber(), block.id(), title, level(block)));
                            }
                        }));
        return List.copyOf(entries);
    }

    private static List<Block> blocks(Page page) {
        return page.blocks() == null ? List.of() : page.blocks();
    }

    private static int level(Block block) {
        int level = block.headingLevel() == null ? 2 : block.headingLevel();
        return Math.max(1, Math.min(level, 6));
    }

    private static boolean directoryHeading(Block block, String title) {
        if (directoryIdentifier(block.id())) return true;
        if (directorySource(block.source())) return true;
        if (block.sourceIds() != null && block.sourceIds().stream()
                .anyMatch(value -> directorySource(value) || directoryIdentifier(value))) return true;

        String compact = title.replaceAll("[\\s【】「」『』《》〈〉()（）:：]", "");
        if (compact.matches("(?i)(?:总|總)?目[录錄](?:索引)?|目次|contents|tableofcontents")) return true;

        List<String> lines = title.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() >= 3) {
            long directoryLines = lines.stream().filter(OutlineService::directoryEntryLine).count();
            if (directoryLines >= 3 && directoryLines * 10 >= lines.size() * 7L) return true;
        }
        return title.matches("(?s).*?(?:\\.{2,}|…+|·{2,}|-{3,})\\s*" + PAGE_TOKEN + "\\s*$");
    }

    private static boolean directoryEntryLine(String line) {
        return line.matches("(?s).+?(?:\\s|\\.{2,}|…+|·{2,}|-{3,})" + PAGE_TOKEN + "\\s*$");
    }

    private static boolean directorySource(String source) {
        if (source == null) return false;
        String normalized = source.toLowerCase(Locale.ROOT);
        return normalized.startsWith("qwen-toc-recovery")
                || normalized.startsWith("paddle-span")
                || normalized.startsWith("paddle-aistudio-span");
    }

    private static boolean directoryIdentifier(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith("qwen-toc-");
    }

    public record OutlineEntry(int pageNumber, String blockId, String title, int level) {}
}
