package studio.bookhtml.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * U3：展示层人工覆盖命令。默认作用范围为本块；全书同文需另外确认。
 * 仅修改目录/展示角色，不算“全文已人工校对”；恢复自动判断即删除对应覆盖。
 */
public record PresentationOverrideRequest(@NotNull Integer expectedRevision,
                                          @NotBlank String blockId,
                                          String sourceHash,
                                          @NotNull OverrideAction action,
                                          OverrideScope scope) {
    public enum OverrideAction {
        INCLUDE_IN_OUTLINE,
        EXCLUDE_FROM_OUTLINE,
        MARK_RUNNING_HEADER,
        MARK_RUNNING_FOOTER,
        CLEAR_OVERRIDE
    }

    public enum OverrideScope {
        BLOCK,
        SAME_TEXT_IN_BOOK
    }

    public OverrideScope effectiveScope() {
        return scope == null ? OverrideScope.BLOCK : scope;
    }
}
