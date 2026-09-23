package studio.bookhtml.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import studio.bookhtml.decision.DecisionHash;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * G08 / B07: 书籍级内容与语义画像。
 * 记录全书文字体系倾向、主排版方向、章节骨架、篇幅估算及源文件哈希；
 * profileRevision 仅在结构性内容变化时单调递增；
 * 配合 sourceEventSeq 提供基于事件修订号的缓存失效保障。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookContentProfile(
        String bookId,
        long profileRevision,
        String policyVersion,
        long sourceEventSeq,
        int observedPages,
        int totalPages,
        String primaryScript,
        String primaryWritingMode,
        long estimatedCharCount,
        List<ChapterEntry> chapters,
        Map<String, Long> scriptDistribution,
        String sourcePdfSha256,
        Instant updatedAt) {

    public static final String POLICY_VERSION = "content-profile-v1";

    public BookContentProfile {
        chapters = chapters == null ? List.of() : List.copyOf(chapters);
        scriptDistribution = scriptDistribution == null ? Map.of() : Map.copyOf(scriptDistribution);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChapterEntry(int pageNumber, String title, boolean verified) {}

    public static BookContentProfile empty(String bookId, String policyVersion) {
        return new BookContentProfile(bookId, 0, policyVersion, 0, 0, 0,
                "UNKNOWN", "horizontal-tb", 0, List.of(), Map.of(), "", Instant.now());
    }

    /**
     * 画像内容签名：判断是否发生实质结构变化的依据。
     */
    public String contentSignature() {
        List<String> chapterKeys = chapters.stream()
                .map(c -> c.pageNumber() + ":" + c.title() + ":" + c.verified())
                .sorted()
                .toList();
        return DecisionHash.of(Map.of(
                "policyVersion", policyVersion == null ? "" : policyVersion,
                "primaryScript", primaryScript == null ? "" : primaryScript,
                "primaryWritingMode", primaryWritingMode == null ? "" : primaryWritingMode,
                "chapters", chapterKeys,
                "sourcePdfSha256", sourcePdfSha256 == null ? "" : sourcePdfSha256
        ));
    }
}
