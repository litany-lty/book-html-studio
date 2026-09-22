package studio.bookhtml.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * U2：安全重新处理命令。替代直接写 {@code Page.pending(...)} 的清空式重试。
 *
 * <p>语义：保持当前可读 Page 不变，后台产生候选，CAS 通过后才替换。
 * 同一个 {@code clientOperationId} 重复提交返回同一任务，不重复收费；
 * 服务端请求指纹核对参数一致，不同参数同 ID 拒绝。
 */
public record PageReprocessRequest(@NotNull Integer expectedRevision,
                                   @NotBlank String clientOperationId,
                                   boolean explicitOverwriteAuthorization,
                                   String provider,
                                   Boolean assist) {
    public boolean assistEnabled() {
        return assist == null || assist;
    }
}
