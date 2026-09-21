package studio.bookhtml.store;

/**
 * A1-04：页面提交的具体操作类别。同一存储锁内按类别校验资格，
 * 不靠调用方在锁外各自检查后再写。
 */
public enum CommitOp {
    /** 人工保存：revision 匹配，且页面未被有效任务占用。 */
    MANUAL_SAVE,
    /** 人工回退：同 MANUAL_SAVE，成功生成更高的新 revision。 */
    MANUAL_REVERT,
    /** 任务开始本页：当前 jobId、任务活跃、页属于任务、未取消、revision 匹配。 */
    JOB_START,
    /** 任务成功提交：当前 jobId、仍为 RUNNING、页属于任务、processing 版本匹配。 */
    JOB_COMPLETE,
    /** 任务取消/失败恢复：当前 jobId、页属于任务、恢复目标版本匹配；绝不覆盖较新版本。 */
    JOB_RESTORE,
    /** 启动恢复专用：仅恢复上下文使用，不作为普通 API/任务入口。 */
    SYSTEM_RECOVERY
}
