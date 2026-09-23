package studio.bookhtml.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.Page;
import studio.bookhtml.domain.PageHead;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Atomic store for individual page heads: books/<bookId>/heads/<page>.json.
 * Bounded file size (<= 64KiB), atomic replacement, and zero cross-page scan amplification.
 */
public final class PageHeadStore {
    private static final int MAX_HEAD_BYTES = 65536;
    private final ObjectMapper json;

    public PageHeadStore(ObjectMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public static Path headPath(Path bookDir, int page) {
        return bookDir.resolve("heads").resolve(page + ".json");
    }

    public PageHead readHead(Path bookDir, int page) {
        Path path = headPath(bookDir, page);
        try {
            DurableJson.rejectLinks(path);
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            byte[] bytes;
            try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_HEAD_BYTES + 1);
            }
            if (bytes.length > MAX_HEAD_BYTES) {
                return null;
            }
            return json.readValue(bytes, PageHead.class);
        } catch (Exception ex) {
            return null; // Corrupted head will be repaired from single page on demand
        }
    }

    public void writeHead(Path bookDir, PageHead head) throws IOException {
        Objects.requireNonNull(head, "head");
        Path target = headPath(bookDir, head.pageNumber());
        DurableJson.write(target, head, json, MAX_HEAD_BYTES);
    }

    public synchronized PageHead updateAttempt(Path bookDir, int page, UUID attemptId, Long attemptSeq,
                                               String lifecycle, String stage) throws IOException {
        PageHead existing = readHead(bookDir, page);
        PageHead updated;
        if (existing != null) {
            updated = existing.withAttempt(attemptId, attemptSeq, lifecycle, stage);
        } else {
            updated = new PageHead(page, 0, null, "PENDING", false, false,
                    0, 0, "第 " + page + " 页", 600.0, 800.0, null, 0L,
                    Instant.now(), attemptId, attemptSeq, lifecycle, stage);
        }
        writeHead(bookDir, updated);
        return updated;
    }

    public PageHead createOrUpdatePublication(Path bookDir, Page page, String contentHash, long sourceSeq,
                                              String title, UUID attemptId, Long attemptSeq,
                                              String claimOutcome, String lifecycle) throws IOException {
        Objects.requireNonNull(page, "page");
        int uncertain = page.blocks() == null ? 0 : (int) page.blocks().stream().filter(b -> !"advertisement".equals(b.type()) && b.uncertain()).count();
        int reading = page.blocks() == null ? 0 : (int) page.blocks().stream().filter(b -> !"advertisement".equals(b.type())).count();
        boolean processed = "READY".equals(page.status());
        PageHead head = new PageHead(
                page.pageNumber(),
                page.revision() == null ? 0 : page.revision(),
                page.lastCommitId(),
                page.status(),
                processed,
                page.reviewed(),
                uncertain,
                reading,
                title == null || title.isBlank() ? "第 " + page.pageNumber() + " 页" : title,
                page.width(),
                page.height(),
                contentHash,
                sourceSeq,
                Instant.now(),
                attemptId,
                attemptSeq,
                lifecycle,
                claimOutcome
        );
        writeHead(bookDir, head);
        return head;
    }
}
