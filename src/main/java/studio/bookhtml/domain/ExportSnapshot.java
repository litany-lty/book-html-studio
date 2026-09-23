package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * G12 / B10: 冻结的导出快照。
 * 在导出开始时一次性固定书籍身份、sourceSeq、页面版本及哈希、版式画像版本、人工覆盖版本和决策建议依赖，
 * 避免在导出过程中逐页重新读取导致版本拼合与竞态不一致。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExportSnapshot(
        String snapshotId,
        String bookId,
        String title,
        int totalPages,
        int sourceTotalPages,
        String sourcePdfSha256,
        long sourceSeq,
        List<Integer> selectedPages,
        List<PageRef> pageRefs,
        Long profileRevision,
        Long overrideRevision,
        int schemaVersion,
        Map<String, Object> frozenDecisions,
        Instant createdAt
) {
    public ExportSnapshot {
        if (snapshotId == null || snapshotId.isBlank()) {
            snapshotId = UUID.randomUUID().toString();
        }
        selectedPages = selectedPages == null ? List.of() : List.copyOf(selectedPages);
        pageRefs = pageRefs == null ? List.of() : List.copyOf(pageRefs);
        frozenDecisions = frozenDecisions == null ? Map.of() : Map.copyOf(frozenDecisions);
        if (schemaVersion <= 0) {
            schemaVersion = 2;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (profileRevision == null) {
            profileRevision = 0L;
        }
        if (overrideRevision == null) {
            overrideRevision = 0L;
        }
    }

    /**
     * 单页版本引用，固定特定页码在快照时刻的 revision、commitId 与 contentHash。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageRef(
            int sourcePageNumber,
            int exportPageNumber,
            int revision,
            UUID commitId,
            String contentHash,
            String status,
            boolean processed,
            boolean reviewed
    ) {}

    public PageRef pageRef(int sourcePageNumber) {
        for (PageRef ref : pageRefs) {
            if (ref.sourcePageNumber() == sourcePageNumber) {
                return ref;
            }
        }
        return null;
    }

    public int exportPageNumber(int sourcePageNumber) {
        PageRef ref = pageRef(sourcePageNumber);
        return ref != null ? ref.exportPageNumber() : sourcePageNumber;
    }

    public boolean isPageSelected(int sourcePageNumber) {
        return selectedPages.contains(sourcePageNumber);
    }

    /**
     * 计算快照内容的确定性 SHA-256 哈希值。
     */
    public String snapshotHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            sb.append(bookId).append("|").append(sourcePdfSha256).append("|")
                    .append(sourceSeq).append("|").append(profileRevision).append("|")
                    .append(overrideRevision).append("|").append(schemaVersion).append(";");
            for (PageRef ref : pageRefs) {
                sb.append(ref.sourcePageNumber()).append(":")
                        .append(ref.revision()).append(":")
                        .append(ref.commitId()).append(":")
                        .append(ref.contentHash()).append(";");
            }
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "";
        }
    }
}
