package studio.bookhtml.service;

import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Bounded, book-scoped evidence, captured once before dispatching concurrent review chunks. */
@Service
public class BookContextService {
    static final int MAX_PAGES = 14;
    static final int MAX_CHARACTERS = 8000;
    static final String POLICY = "book-context-v1";
    private final BookStore store;

    public BookContextService(BookStore store) { this.store = store; }

    public record Excerpt(int pageNumber, int revision, String blockId, String text, String evidence) {}
    public record Context(String bookId, int pageNumber, String bookTitle, String chapterTitle,
                          List<Excerpt> excerpts, String fingerprint) {
        public Context { excerpts = excerpts == null ? List.of() : List.copyOf(excerpts); }
        public static Context empty() { return new Context("", 0, "", "", List.of(), POLICY + ":empty"); }
    }

    public Context capture(String bookId, int pageNumber) {
        Book book = store.readBook(bookId);
        if (pageNumber < 1 || pageNumber > book.totalPages())
            throw new IllegalArgumentException("page outside book");
        // Current/previous pages for chapter continuity, then next pages and front matter for theme.
        Set<Integer> candidates = new LinkedHashSet<>();
        candidates.add(pageNumber);
        for (int offset = 1; offset <= 8; offset++) if (pageNumber - offset >= 1) candidates.add(pageNumber - offset);
        for (int offset = 1; offset <= 2; offset++) if (pageNumber + offset <= book.totalPages()) candidates.add(pageNumber + offset);
        for (int n = 1; n <= Math.min(3, book.totalPages()); n++) candidates.add(n);
        List<Excerpt> excerpts = new ArrayList<>();
        String chapter = "";
        int remaining = MAX_CHARACTERS;
        int readPages = 0;
        for (int number : candidates) {
            if (readPages++ >= MAX_PAGES || remaining <= 0) break;
            Page page;
            try { page = store.readPage(bookId, number); }
            catch (RuntimeException unavailable) { continue; } // Missing context must not block OCR.
            if (page == null || !"READY".equals(page.status()) || page.blocks() == null) continue;
            int pageBudget = Math.min(1500, remaining);
            List<Block> ordered = page.blocks().stream().filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparingInt(Block::order)).toList();
            for (Block block : ordered) {
                if (!reliable(page, block)) continue;
                if (chapter.isEmpty() && number <= pageNumber && pageNumber - number <= 8
                        && BookPresentationService.hasChapterStartEvidence(page, block)) {
                    chapter = clip(block.original(), 180);
                }
                // The current page is already supplied as owned/context ranges; do not duplicate it.
                if (number == pageNumber || pageBudget <= 0) continue;
                String text = clip(block.original(), Math.min(700, pageBudget));
                if (text.isBlank()) continue;
                excerpts.add(new Excerpt(number, BookStore.revisionOrZero(page), block.id(), text,
                        page.reviewed() || block.reviewed() ? "HUMAN_CONFIRMED" : "OCR_HIGH_CONFIDENCE"));
                remaining -= text.length();
                pageBudget -= text.length();
            }
        }
        String title = clip(book.title(), 180);
        // Length-prefixed fields: no concatenation ambiguity, no timestamps or secret material.
        StringBuilder identity = new StringBuilder();
        field(identity, POLICY); field(identity, bookId); field(identity, Integer.toString(pageNumber));
        field(identity, title); field(identity, chapter);
        for (Excerpt item : excerpts) {
            field(identity, Integer.toString(item.pageNumber())); field(identity, Integer.toString(item.revision()));
            field(identity, item.blockId()); field(identity, item.text()); field(identity, item.evidence());
        }
        return new Context(bookId, pageNumber, title, chapter, excerpts, digest(identity.toString()));
    }

    private static boolean reliable(Page page, Block block) {
        if (block.id() == null || block.original() == null || block.original().isBlank()) return false;
        if (!Set.of("text", "heading", "caption").contains(block.type() == null ? "" : block.type())) return false;
        if (block.issues().stream().anyMatch(issue -> issue != null && !issue.resolved())) return false;
        if (page.reviewed() || block.reviewed()) return true;
        return block.confidence() != null
                && Double.isFinite(block.confidence()) && block.confidence() >= .85;
    }

    static String clip(String text, int limit) {
        if (text == null || limit <= 0) return "";
        if (text.length() <= limit) return text;
        int end = limit;
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
    private static void field(StringBuilder target, String text) {
        String value = text == null ? "" : text;
        target.append(value.length()).append(':').append(value);
    }
    static String digest(String value) { return digest(value.getBytes(StandardCharsets.UTF_8)); }
    static String digest(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
