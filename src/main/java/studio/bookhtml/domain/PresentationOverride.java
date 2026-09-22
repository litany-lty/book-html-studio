package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * U3：展示层人工覆盖记录。落盘复用 BookStore 锁；覆盖进入待重新定位时
 * 不套到另一个块上（sourceHash 失配即 STALE）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PresentationOverride(String bookId,
                                   int pageNumber,
                                   String blockId,
                                   String sourceHash,
                                   int pageRevision,
                                   String action,
                                   String scope,
                                   String operator,
                                   Instant createdAt,
                                   String status) {
    public PresentationOverride {
        if (status == null) status = "ACTIVE";
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean active() {
        return "ACTIVE".equals(status);
    }

    /** sidecar 根：覆盖列表 + 版本。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Store(long overrideRevision, List<PresentationOverride> overrides) {
        public Store {
            overrides = overrides == null ? List.of() : List.copyOf(overrides);
        }

        public static Store empty() {
            return new Store(0, List.of());
        }
    }
}
