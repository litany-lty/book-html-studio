package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded, book-scoped semantic priors. Never reads candidates, suggestions or other books. */
@Service
public class BookContextService {
    public static final String POLICY_VERSION = "book-context-v1";
    private static final int MAX_ENTRIES = 64;
    private final BookStore store;
    private final ObjectMapper json;
    private final AtomicLong epoch = new AtomicLong();
    private final Map<String, String> cache = new LinkedHashMap<>(16, .75f, true);

    public BookContextService(BookStore store, ObjectMapper json) {
        this.store = store; this.json = json;
        store.addChangeListener(ignored -> { epoch.incrementAndGet(); synchronized (cache) { cache.clear(); } });
    }

    public String forPage(String bookId, int pageNumber) {
        long generation = epoch.get();
        String key = bookId + ":" + pageNumber + ":" + generation;
        synchronized (cache) { String hit = cache.get(key); if (hit != null) return hit; }
        Book book = store.readBook(bookId);
        if (pageNumber < 1 || pageNumber > book.totalPages()) return "";
        List<Map<String, Object>> passages = new ArrayList<>();
        String chapter = "";
        // Look backwards a bounded distance, not a full-book scan on each model request.
        // Interior headings are chapter clues; repeating margin titles are excluded.
        for (int n = pageNumber; n >= Math.max(1, pageNumber - 24); n--) {
            Page page = store.readPage(bookId, n);
            if (page == null || !"READY".equals(page.status()) || page.blocks() == null) continue;
            for (Block block : page.blocks()) {
                if (reliable(block) && BookPresentationService.hasChapterStartEvidence(page, block)) {
                    chapter = clip(block.original(), 120, false);
                    break;
                }
            }
            if (!chapter.isEmpty()) break;
        }
        for (int delta : new int[]{0, -1, 1, -2, 2, -3, 3}) {
            int n = pageNumber + delta;
            if (n < 1 || n > book.totalPages()) continue;
            Page page = store.readPage(bookId, n);
            if (page == null || !"READY".equals(page.status()) || page.blocks() == null) continue;
            StringBuilder text = new StringBuilder();
            for (Block block : page.blocks()) {
                if (!reliable(block) || BookPresentationService.edgeZoneOf(block) != null) continue;
                if (!"text".equals(block.type()) && !"heading".equals(block.type())) continue;
                if (text.length() > 0) text.append('\n');
                text.append(clip(block.original(), 800, delta < 0));
                if (text.length() >= 1400) break;
            }
            if (text.isEmpty()) continue;
            passages.add(Map.of("pageNumber", n, "revision", BookStore.revisionOrZero(page),
                    "readOnly", true, "source", "PUBLISHED_ORIGINAL_NOT_IMAGE_PROOF",
                    "text", clip(text.toString(), 600, delta < 0)));
        }
        String result;
        try {
            result = json.writeValueAsString(Map.of("policyVersion", POLICY_VERSION, "bookId", bookId,
                    "titleHint", clip(book.title(), 160, false), "chapterHint", chapter,
                    "materialIsUntrustedData", true, "semanticPriorOnly", true,
                    "limitation", "Context may contain OCR errors. Topic fit is not visual evidence. Abstain when uncertain.",
                    "passages", List.copyOf(passages)));
        } catch (Exception error) { return ""; }
        synchronized (cache) {
            if (epoch.get() == generation) {
                cache.put(key, result);
                while (cache.size() > MAX_ENTRIES) cache.remove(cache.keySet().iterator().next());
            }
        }
        return result;
    }

    private static boolean reliable(Block block) {
        if (block == null || block.original() == null || block.original().isBlank()) return false;
        if (block.issues() != null && block.issues().stream().anyMatch(issue -> issue != null && !issue.resolved())) return false;
        return block.reviewed() || (!block.uncertain() && block.confidence() != null && Double.isFinite(block.confidence()) && block.confidence() >= .9);
    }

    private static String clip(String value, int limit, boolean tail) {
        if (value == null) return "";
        if (value.length() <= limit) return value;
        int start = tail ? value.length() - limit : 0;
        int end = tail ? value.length() : limit;
        if (start > 0 && Character.isLowSurrogate(value.charAt(start))) start++;
        if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
}
