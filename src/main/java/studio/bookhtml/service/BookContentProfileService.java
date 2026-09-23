package studio.bookhtml.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.BookContentProfile;
import studio.bookhtml.domain.Page;
import studio.bookhtml.store.BookStore;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * G08 / B07: 书籍内容画像服务 (BookContentProfileService)。
 * 提供全书文字体系倾向、排版主模式、章节分布及字符估算的缓存与原子持久化；
 * 基于 sourceEventSeq 与 store.addChangeListener 实现主动缓存失效；
 * 保持 profileRevision 单调递增，无结构性变化时不滥增版本。
 */
@Service
public class BookContentProfileService {

    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "(?i)^(第[一二三四五六七八九十百千〇零0-9]+[章节卷篇部回]|chapter\\s+[0-9ivxlc]+).{0,80}$");
    private static final int MAX_OBSERVED_PAGES = 128;
    private static final int MAX_CACHE_ENTRIES = 128;

    private final BookStore store;
    private final ObjectMapper json;
    private final TraditionalConverter converter;
    private final studio.bookhtml.decision.PdfIdentity pdfIdentity;
    private final AtomicLong changeGeneration = new AtomicLong();
    private final Map<String, BookContentProfile> profileCache = new LinkedHashMap<>();
    private final Set<String> warming = ConcurrentHashMap.newKeySet();

    @Autowired
    public BookContentProfileService(BookStore store, ObjectMapper json, TraditionalConverter converter) {
        this.store = Objects.requireNonNull(store, "store");
        this.json = Objects.requireNonNull(json, "json");
        this.converter = Objects.requireNonNull(converter, "converter");
        this.pdfIdentity = new studio.bookhtml.decision.PdfIdentity();

        this.store.addChangeListener(bookId -> {
            changeGeneration.incrementAndGet();
            synchronized (profileCache) {
                profileCache.remove(bookId);
            }
        });
    }

    public BookContentProfile profileForBook(String bookId) {
        if (bookId == null || bookId.isBlank()) {
            return BookContentProfile.empty("unknown", BookContentProfile.POLICY_VERSION);
        }

        synchronized (profileCache) {
            BookContentProfile hit = profileCache.get(bookId);
            if (hit != null) {
                return hit;
            }
        }

        // 尝试从持久化 sidecar 读取
        Path profilePath = store.contentProfilePath(bookId);
        BookContentProfile previous = store.readSidecar(profilePath, BookContentProfile.class);

        // 如果未命中或需更新，则执行分析
        long currentGen = changeGeneration.get();
        BookContentProfile computed = computeProfile(bookId, previous);

        synchronized (profileCache) {
            if (currentGen == changeGeneration.get()) {
                if (profileCache.size() >= MAX_CACHE_ENTRIES) {
                    Iterator<String> it = profileCache.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    }
                }
                profileCache.put(bookId, computed);
            }
        }

        return computed;
    }

    public void invalidate(String bookId) {
        changeGeneration.incrementAndGet();
        synchronized (profileCache) {
            profileCache.remove(bookId);
        }
    }

    private BookContentProfile computeProfile(String bookId, BookContentProfile previous) {
        Book book;
        try {
            book = store.readBook(bookId);
        } catch (Exception e) {
            return BookContentProfile.empty(bookId, BookContentProfile.POLICY_VERSION);
        }

        int totalPages = book.totalPages();
        int pagesToObserve = Math.min(totalPages, MAX_OBSERVED_PAGES);
        int observedCount = 0;
        long totalChars = 0;
        long traditionalChars = 0;
        long simplifiedChars = 0;
        long horizontalBlocks = 0;
        long verticalBlocks = 0;
        List<BookContentProfile.ChapterEntry> chapters = new ArrayList<>();

        for (int p = 1; p <= pagesToObserve; p++) {
            Page page = null;
            try {
                page = store.readPage(bookId, p);
            } catch (Exception ignored) {}

            if (page == null || page.blocks() == null) continue;
            observedCount++;

            for (Block block : page.blocks()) {
                if (block == null) continue;
                String text = block.original();
                if (text == null || text.isBlank()) continue;

                totalChars += text.length();
                if ("vertical-rl".equalsIgnoreCase(block.writingMode())) {
                    verticalBlocks++;
                } else {
                    horizontalBlocks++;
                }

                // 统计繁简字分布
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (isCjk(c)) {
                        String s = String.valueOf(c);
                        String simplified = converter.toSimplified(s);
                        if (!s.equals(simplified)) {
                            traditionalChars++;
                        } else {
                            simplifiedChars++;
                        }
                    }
                }

                // 识别章节 (按行匹配，避免后续正文干扰)
                for (String line : text.split("\\R")) {
                    String stripped = line.strip();
                    if (!stripped.isEmpty() && stripped.length() <= 80 && CHAPTER_PATTERN.matcher(stripped).matches()) {
                        chapters.add(new BookContentProfile.ChapterEntry(p, stripped, page.reviewed()));
                        break;
                    }
                }
            }
        }

        String primaryScript;
        long totalCjk = traditionalChars + simplifiedChars;
        if (totalCjk == 0) {
            primaryScript = "UNKNOWN";
        } else {
            double tradRatio = (double) traditionalChars / totalCjk;
            if (tradRatio > 0.15) {
                primaryScript = "TRADITIONAL";
            } else if (tradRatio > 0.02) {
                primaryScript = "MIXED";
            } else {
                primaryScript = "SIMPLIFIED";
            }
        }

        String primaryWritingMode = verticalBlocks > horizontalBlocks ? "vertical-rl" : "horizontal-tb";
        Map<String, Long> scriptDistribution = Map.of(
                "TRADITIONAL", traditionalChars,
                "SIMPLIFIED", simplifiedChars,
                "TOTAL_CJK", totalCjk
        );

        String pdfSha256 = "";
        try {
            Path pdf = store.pdf(bookId);
            if (java.nio.file.Files.exists(pdf)) {
                pdfSha256 = pdfIdentity.sha256(pdf);
            }
        } catch (Exception ignored) {}

        long sourceEventSeq = 0;
        try {
            sourceEventSeq = store.sourceJournal().currentSourceSeq(store.bookDir(bookId), bookId);
        } catch (Exception ignored) {}

        BookContentProfile candidate = new BookContentProfile(
                bookId,
                previous == null ? 1 : previous.profileRevision(),
                BookContentProfile.POLICY_VERSION,
                sourceEventSeq,
                observedCount,
                totalPages,
                primaryScript,
                primaryWritingMode,
                totalChars,
                chapters,
                scriptDistribution,
                pdfSha256,
                Instant.now()
        );

        // 单调修订号保护：无实质内容结构变化，沿用旧版本
        if (previous != null && previous.contentSignature().equals(candidate.contentSignature())) {
            return previous;
        }

        long nextRevision = previous == null ? 1 : previous.profileRevision() + 1;
        BookContentProfile published = new BookContentProfile(
                bookId,
                nextRevision,
                BookContentProfile.POLICY_VERSION,
                sourceEventSeq,
                observedCount,
                totalPages,
                primaryScript,
                primaryWritingMode,
                totalChars,
                chapters,
                scriptDistribution,
                pdfSha256,
                Instant.now()
        );

        try {
            store.writeSidecar(store.contentProfilePath(bookId), published);
        } catch (Exception ignored) {
            // sidecar 写失败不阻塞正常使用
        }

        return published;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS;
    }
}
