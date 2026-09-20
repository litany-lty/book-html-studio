package studio.bookhtml.store;

/** 页面提交的操作者类型：决定提交时的所有权校验规则。 */
public enum CommitActor {
    /** 人工校对保存：必须携带 expectedRevision；PROCESSING 页拒绝。 */
    MANUAL,
    /** 后台识别任务：必须携带仍有效的 expectedJobId。 */
    JOB,
    /** 版本回退：必须携带 expectedRevision，成功生成更高的新 revision。 */
    REVERT,
    /** 系统内部写入（导入、启动恢复）：不做版本比较。 */
    SYSTEM
}
