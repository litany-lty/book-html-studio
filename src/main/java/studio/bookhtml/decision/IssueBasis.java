package studio.bookhtml.decision;

import java.util.LinkedHashMap;
import java.util.Map;
import studio.bookhtml.domain.Block;
import studio.bookhtml.domain.ContentIssue;

/**
 * J07：在线问题基线。按固定顺序、明确 UTF-8 的规范序列化计算 hash；
 * 不改变旧 issueId；原始文字/目标范围变化才导致旧确认需重新核对。
 */
public final class IssueBasis {
    public static final String MAPPING_VERSION = "issue-basis-v1";

    private IssueBasis() {}

    public static String basisHash(Block block, ContentIssue issue) {
        if (block == null || block.original() == null || issue == null)
            throw new IllegalArgumentException("基线输入为空");
        Map<String, Object> basis = new LinkedHashMap<>();
        basis.put("blockId", block.id());
        basis.put("issueId", issue.id());
        basis.put("kind", issue.kind());
        basis.put("start", issue.start());
        basis.put("end", issue.end());
        String original = block.original();
        if (issue.start() < 0 || issue.end() > original.length() || issue.end() <= issue.start())
            throw new IllegalArgumentException("问题区间越界");
        basis.put("originalQuote", original.substring(issue.start(), issue.end()));
        basis.put("resolved", issue.resolved());
        basis.put("replacement", issue.replacement());
        basis.put("mappingVersion", MAPPING_VERSION);
        return DecisionHash.of(basis);
    }
}
