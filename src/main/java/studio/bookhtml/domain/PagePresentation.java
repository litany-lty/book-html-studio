package studio.bookhtml.domain;

import java.util.List;
import java.util.Map;

/**
 * U3：只读展示投影。{@code blocks} 是存储页面的可编辑内容，
 * {@code presentation} 是只读派生结果；校对保存不得把“阅读层隐藏”当删除源内容。
 *
 * <p>{@code hideInReading} 与 {@code includeInOutline} 是两个独立决策：
 * 不进入导航不等于从正文抹去。未知角色默认保留可见内容。
 */
public record PagePresentation(int pageNumber,
                               int pageRevision,
                               long profileRevision,
                               String policyVersion,
                               String layoutKind,
                               String fallbackMode,
                               List<BlockPresentation> blocks) {
    public PagePresentation {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    public Map<String, BlockPresentation> byBlockId() {
        Map<String, BlockPresentation> map = new java.util.LinkedHashMap<>();
        for (BlockPresentation b : blocks) {
            if (b != null && b.blockId() != null) map.putIfAbsent(b.blockId(), b);
        }
        return Map.copyOf(map);
    }

    public record BlockPresentation(String blockId,
                                    String sourceHash,
                                    String role,
                                    String renderAs,
                                    int readingOrder,
                                    String flowKind,
                                    String joinGroupId,
                                    boolean allowJoinNext,
                                    boolean showInReading,
                                    boolean includeInOutline,
                                    String evidenceLevel,
                                    List<String> reasonCodes) {
        public BlockPresentation {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    // 展示角色（初期不塞进 Block.type，避免牵动全部 OCR parser 与校验器）。
    public static final String ROLE_BOOK_TITLE = "BOOK_TITLE";
    public static final String ROLE_RUNNING_HEADER = "RUNNING_HEADER";
    public static final String ROLE_RUNNING_FOOTER = "RUNNING_FOOTER";
    public static final String ROLE_PAGE_NUMBER = "PAGE_NUMBER";
    public static final String ROLE_CHAPTER_HEADING = "CHAPTER_HEADING";
    public static final String ROLE_SECTION_HEADING = "SECTION_HEADING";
    public static final String ROLE_PRINTED_TOC_ENTRY = "PRINTED_TOC_ENTRY";
    public static final String ROLE_BODY = "BODY";
    public static final String ROLE_FOOTNOTE = "FOOTNOTE";
    public static final String ROLE_CAPTION = "CAPTION";
    public static final String ROLE_VISUAL = "VISUAL";
    public static final String ROLE_DECORATION = "DECORATION";
    public static final String ROLE_UNKNOWN = "UNKNOWN";

    // 证据等级：MANUAL（人工覆盖）/ SUFFICIENT（充分重复证据）/ INSUFFICIENT（证据不足，保留）。
    public static final String EVIDENCE_MANUAL = "MANUAL";
    public static final String EVIDENCE_SUFFICIENT = "SUFFICIENT";
    public static final String EVIDENCE_INSUFFICIENT = "INSUFFICIENT";
}
