package studio.bookhtml.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import studio.bookhtml.api.ApiException;
import studio.bookhtml.api.PresentationOverrideRequest;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Book;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.PagePresentation;
import studio.bookhtml.domain.PresentationOverride;
import studio.bookhtml.store.BookStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * U3：展示层人工覆盖。精确作用本块；“应用到本书相同书眉”需另外调用并预览匹配页；
 * 新 OCR 导致 blockId/sourceHash 失配时覆盖进入 STALE 待重新定位。
 */
@Service
public class PresentationOverrideService {
    private final BookStore store;

    public PresentationOverrideService(BookStore store) {
        this.store = store;
    }

    public record ScopePreview(int matchPages, List<Integer> pages, List<String> excludedChapterStarts) {}

    public PresentationOverride.Store list(String bookId) {
        PresentationOverride.Store saved =
                store.readSidecar(store.presentationOverridesPath(bookId), PresentationOverride.Store.class);
        return saved == null ? PresentationOverride.Store.empty() : saved;
    }

    /** 本块的有效覆盖（ACTIVE 且 sourceHash 匹配当前块）。 */
    public Optional<PresentationOverride> activeFor(String bookId, Page page, Block block) {
        if (page == null || block == null || block.id() == null) return Optional.empty();
        String hash = sourceHash(block);
        for (PresentationOverride override : list(bookId).overrides()) {
            if (!override.active()) continue;
            if (override.pageNumber() != page.pageNumber()) continue;
            if (!Objects.equals(override.blockId(), block.id())) continue;
            if (override.sourceHash() != null && !override.sourceHash().equals(hash)) continue;
            return Optional.of(override);
        }
        return Optional.empty();
    }

    /** 所有 STALE（源身份已变化、待重新定位）的覆盖。 */
    public List<PresentationOverride> stale(String bookId) {
        List<PresentationOverride> result = new ArrayList<>();
        for (PresentationOverride override : list(bookId).overrides()) {
            if (!override.active()) continue;
            Page page = store.readPage(bookId, override.pageNumber());
            Block block = page == null || page.blocks() == null ? null : page.blocks().stream()
                    .filter(b -> b != null && override.blockId().equals(b.id())).findFirst().orElse(null);
            if (block == null || (override.sourceHash() != null && !override.sourceHash().equals(sourceHash(block)))) {
                result.add(override);
            }
        }
        return result;
    }

    /** 同文书眉候选的作用范围预览（不直接应用）。 */
    public ScopePreview previewSameText(String bookId, int pageNumber, String blockId) {
        Page page = store.readPage(bookId, pageNumber);
        Block block = blockOf(page, blockId);
        if (block == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在");
        String normalized = BookPresentationService.normalizeEdgeText(
                BookPresentationService.displayOriginal(block));
        if (normalized.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "空文本不能作为全书范围");
        Book book = store.readBook(bookId);
        List<Integer> matches = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (int n = 1; n <= book.totalPages(); n++) {
            Page candidate = store.readPage(bookId, n);
            if (candidate == null || !"READY".equals(candidate.status()) || candidate.blocks() == null) continue;
            for (Block b : candidate.blocks()) {
                if (b == null || !"heading".equals(b.type())) continue;
                if (!normalized.equals(BookPresentationService.normalizeEdgeText(
                        BookPresentationService.displayOriginal(b)))) continue;
                if (BookPresentationService.hasChapterStartEvidence(candidate, b)) {
                    excluded.add("第 " + n + " 页疑似章节起始，已排除");
                    continue;
                }
                if (!matches.contains(n)) matches.add(n);
            }
        }
        Collections.sort(matches);
        return new ScopePreview(matches.size(), List.copyOf(matches), List.copyOf(excluded));
    }

    public PresentationOverride.Store apply(String bookId, int pageNumber,
                                            PresentationOverrideRequest request, String operator) throws IOException {
        if (request == null || request.blockId() == null || request.blockId().isBlank())
            throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 blockId");
        if (request.action() == null) throw new ApiException(HttpStatus.BAD_REQUEST, "缺少 action");
        Page page = store.readPage(bookId, pageNumber);
        if (page == null) throw new ApiException(HttpStatus.NOT_FOUND, "页面不存在");
        int currentRev = BookStore.revisionOrZero(page);
        if (request.expectedRevision() != null && request.expectedRevision() != currentRev)
            throw new ApiException(HttpStatus.CONFLICT, "页面已被更新，请刷新后重试");
        Block block = blockOf(page, request.blockId());
        if (block == null) throw new ApiException(HttpStatus.NOT_FOUND, "指定的块不存在");
        if (request.sourceHash() != null && !request.sourceHash().equals(sourceHash(block)))
            throw new ApiException(HttpStatus.CONFLICT, "块内容已变化，覆盖未应用；请重新定位");
        if (request.effectiveScope() == PresentationOverrideRequest.OverrideScope.SAME_TEXT_IN_BOOK) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "全书范围需先预览匹配页并逐页确认，暂仅支持本块范围");
        }
        PresentationOverride.Store saved = list(bookId);
        List<PresentationOverride> next = new ArrayList<>();
        for (PresentationOverride existing : saved.overrides()) {
            boolean same = existing.pageNumber() == pageNumber
                    && Objects.equals(existing.blockId(), request.blockId());
            if (same && existing.active()
                    && request.action() == PresentationOverrideRequest.OverrideAction.CLEAR_OVERRIDE) continue;
            if (same && existing.active()) continue;
            next.add(existing);
        }
        if (request.action() != PresentationOverrideRequest.OverrideAction.CLEAR_OVERRIDE) {
            next.add(new PresentationOverride(bookId, pageNumber, request.blockId(), sourceHash(block),
                    currentRev, request.action().name(), request.effectiveScope().name(),
                    operator == null ? "manual" : operator, Instant.now(), "ACTIVE"));
        }
        PresentationOverride.Store updated =
                new PresentationOverride.Store(saved.overrideRevision() + 1, List.copyOf(next));
        store.writeSidecar(store.presentationOverridesPath(bookId), updated);
        return updated;
    }

    /** 覆盖决定的展示角色（调用方已确认 active 且 sourceHash 匹配）。 */
    public static String roleForAction(String action) {
        return switch (action) {
            case "INCLUDE_IN_OUTLINE" -> PagePresentation.ROLE_CHAPTER_HEADING;
            case "MARK_RUNNING_HEADER" -> PagePresentation.ROLE_RUNNING_HEADER;
            case "MARK_RUNNING_FOOTER" -> PagePresentation.ROLE_RUNNING_FOOTER;
            case "EXCLUDE_FROM_OUTLINE" -> PagePresentation.ROLE_UNKNOWN;
            default -> PagePresentation.ROLE_UNKNOWN;
        };
    }

    private static Block blockOf(Page page, String blockId) {
        if (page == null || page.blocks() == null) return null;
        return page.blocks().stream().filter(b -> b != null && blockId.equals(b.id())).findFirst().orElse(null);
    }

    /** 块来源身份：原文 + 简体 + sourceIds 的稳定 hash。重识别导致变化即失配。 */
    public static String sourceHash(Block block) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String raw = block.id() + "\n" + Objects.toString(block.original(), "")
                    + "\n" + Objects.toString(block.simplified(), "")
                    + "\n" + Objects.toString(block.sourceIds(), "");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
