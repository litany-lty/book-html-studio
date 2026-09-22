package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * U3：书籍级版式画像。只存已观察模板与重复边缘簇，不包含整本原文副本。
 * 首版用可重建 JSON sidecar 持久化（{@code layout-profile.json}）+ 内存按需重建；
 * {@code profileRevision} 只有实质结构改变才增长。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookLayoutProfile(String bookId,
                                long profileRevision,
                                String policyVersion,
                                int observedPages,
                                List<EdgeCluster> edgeClusters,
                                Instant updatedAt) {
    public BookLayoutProfile {
        edgeClusters = edgeClusters == null ? List.of() : List.copyOf(edgeClusters);
    }

    /** 同一可比版式组内对应边缘区域重复出现的规范化文本簇。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EdgeCluster(String normalizedText,
                              String edgeZone,
                              String layoutGroup,
                              int pageCount,
                              double coverage,
                              List<Integer> pages) {
        public EdgeCluster {
            pages = pages == null ? List.of() : List.copyOf(pages);
        }
    }

    public static BookLayoutProfile empty(String bookId, String policyVersion) {
        return new BookLayoutProfile(bookId, 0, policyVersion, 0, List.of(), Instant.now());
    }

    /** 簇集合签名：实质结构是否变化的比较依据。 */
    public String clusterSignature() {
        List<String> parts = edgeClusters.stream()
                .map(c -> c.normalizedText() + "|" + c.edgeZone() + "|" + c.layoutGroup()
                        + "|" + c.pageCount() + "|" + c.pages())
                .sorted().toList();
        return policyVersion + "#" + observedPages + "#" + String.join(";", parts);
    }
}
