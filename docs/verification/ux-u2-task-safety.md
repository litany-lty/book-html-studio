# U2：任务生命周期、重试与授权（纸页工坊）

- 基线：`ux-reader-remediation`；U0（`550dbe9`）、U1（`88ad1eb`）已提交。
- 本批依赖：U0。**任何扩大并发（U5）必须等待本批与 U4 的验收门通过。**
- 性质：P0 数据/授权/任务生命周期风险先修；界面看似更快之前，先保证不丢可读版本、
  无永久处理中、不增加未明确授权的调用。

## 1. 精确改动点

| # | 文件 | 改动 | 不得破坏 |
|---|---|---|---|
| 1 | 新增 `domain/PageAttempt.java` | 页面处理身份：bookId/pageNumber/runId/attemptId/generation/expectedRevision/expectedSourceHash/allowedCommitOps/lifecycle；`nextGeneration()`/`withLifecycle()` | 内存登记仅调度用，不单独作为最终写入权限 |
| 2 | 新增 `api/PageReprocessRequest.java` | expectedRevision/clientOperationId/explicitOverwriteAuthorization/provider/assist；同 ID 同参数返回同一任务，不同参数同 ID 拒绝 | 旧 JobRequest 语义不变 |
| 3 | `service/JobService.java` | `requestReprocess` 统一准入：预约/归档/页范围→人工保护（manual/reviewed 默认拒）→版本匹配→同页活动 attempt 幂等→`submitReserved(force=true)`；操作指纹幂等表 | 批量 `submit` 路径与既有回归/缩水保护 |
| 4 | `service/JobService.java` | 资格拆分：`ownsAttempt`（归属，不判取消）/`mayDispatch`（+未停止）/`mayPublish`（+任务身份）/`mayRestore`（取消后仍可恢复）；删除混用的 `stillCurrentReserved` | `run()` 批量路径的 `stillCurrent` 世代保护 |
| 5 | `service/JobService.java` | `cancelReadingPage` 只标记+中断，不提前 `remove`；释放发生在 worker `finally`；物理槽在响应流收尾后释放 | 中断协作、租约关闭 |
| 6 | `service/JobService.java` | runReserved 发布门用 `mayPublish`，取消/失败恢复门用 `mayRestore`；成功提交失败（磁盘满/冲突）记入候选 warnings，可诊断，不吞异常报成功 | QualityGate/缩水保护/候选留档/人工优先 |
| 7 | `service/JobService.java` | `submitReserved` 为每页登记 attempt；新增 `attemptSnapshot()` 测试可见性 | 登记 key 冲突时 `nextGeneration` |
| 8 | `service/ReadingWindowService.java` | `retryPage` 改安全路径：不写 `Page.pending`，经 `requestReprocess` 建独立 attempt，失败仅留消息，页置队首 + 重试集 + tick | 当前可读 Page 不变；同序号重复提交走快照幂等 |
| 9 | `service/ReadingWindowService.java` | tick 新增重试集派发（force=true，容量/授权仍约束；CONFLICT 移出等待在途结果）；容量满时中心页队首等待，不强杀最远在途请求（DRAIN） | 中心优先、窗口/自动全书队列语义 |
| 10 | `service/ReadingWindowService.java` | `getEnabledChannels` 按 `allowedProviders`（默认 `[primary]`）过滤已配置通道；Session 新增授权快照（fallback/parallel 默认关闭）；停留阈值默认 300ms→1000ms | 未配置主通道仍按原路径报错 |
| 11 | `static/reading-window.js` | `readyInFlight` 与 `seenReady` 分离：入队只记在途，成功才记成功标记，失败/取消清在途（同 revision 可重 GET，重试只读 GET）；epoch 切换清理；缓存窗口/派发窗口命名区分 | 脏稿/冲突/旧响应隔离；切书/旧 epoch 隔离 |
| 12 | 更新 `ReadingWindowServiceTest` 4 项 | 强杀→等待、双通道×3→主通道×3、重试清空→保留快照 | 保存/冲突/证据类断言无（本文件无此类断言） |
| 13 | 新增 `PageAttemptLifecycleTest` 9 项 | SAFE-01/02/03/05/06+07/09/10+11/15/16，latch 排列时序 | 隔离合成夹具，不碰日常数据 |
| 14 | 新增 `UxU2TaskSafetyTest` 6 项 | 结构回退门禁 | — |
| 15 | 新增 `probe_ready_fetch_retry.js` | ready GET 重试契约（ocrPostDelta=0） | 需独立端口合成书运行 |

## 2. 测试（actual）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -q -Dtest='studio.bookhtml.service.PageAttemptLifecycleTest' test   # 9/9 PASS
mvn test -Dtest='studio.bookhtml.service.*Test'                          # 见提交输出
node --check src/main/resources/static/reading-window.js                # OK
```

- 修复中发现的真实问题（已修）：`mayPublish/mayRestore` 初版比对了带派发页后缀的
  `expectedJobId`，而持久任务身份是 `reading:{reservation}`，导致随读 worker 在循环
  入口直接返回、永不处理。改为 `reservedJobMatches`（预约身份 + 登记表同一性）。
- 浏览器 ready-GET-失败重试（SAFE-16 后半）与真实取消时序（ latch 已覆盖逻辑分支，
  物理 HTTP 收尾计时）需独立端口合成书运行；未运行记“未执行”，不伪报。

## 3. 验收映射（SAFE-01～SAFE-16）

| ID | 状态 | 证据 |
|---|---|---|
| SAFE-01 重试不清空 | PASS | safe01 + 更新后的重试测试 |
| SAFE-02 人工保护 | PASS | safe02（服务端拒绝，前端禁用不影响） |
| SAFE-03 版本冲突 | PASS | safe03 |
| SAFE-04 失败/缩水保留 | PASS（逻辑） | 既有 `JobPhase1SafetyTest` + 缩水保护未动；U4 再补基线/增强分离 |
| SAFE-05 幂等 | PASS | safe05 |
| SAFE-06/07 取消恢复 | PASS | safe0607（latch 排列发送—取消—占用—收尾—恢复—释放） |
| SAFE-08 取消不盖人工 | 逻辑 PASS | mayRestore 只恢复旧基线；`commitPage` 锁内版本校验仍拒绝覆盖（U4 补并发保存测试） |
| SAFE-09 跳页不强杀 | PASS | safe09 + 等待语义测试 |
| SAFE-10/11 通道授权 | PASS | safe1011 + 主通道默认测试 |
| SAFE-12 随读范围 | PASS（逻辑） | autoProcessAll 显式开关未动（既有测试）；默认只派发云窗口 |
| SAFE-13 切书/租约 | PASS（既有） | `manualTaskAndOtherTabConflictAndRestartHasNoAutomaticWindow` 未动且通过 |
| SAFE-14 重启恢复 | 部分 | 重启不重发（既有测试通过）；持久意图 journal 留 U4（ProcessingSnapshot/恢复意图） |
| SAFE-15 磁盘失败 | PASS（逻辑） | safe15：诊断入候选 warnings，旧内容保留；原子意图 journal 留 U4 |
| SAFE-16 GET 重试 | PASS（逻辑+静态） | safe16 + 探针契约；真实浏览器运行未执行 |

## 4. 未做（留给后续）

- U4：ProcessingSnapshot 真实阶段、baseline/enhancement 分离提交（`JOB_BASELINE`/
  `JOB_ENHANCEMENT`）、持久恢复意图、内容/任务维度分离统计。
- U3：书籍级结构投影（F04/F06），随读增量目录届时再改。
- 真实浏览器 SAFE-16 与物理 HTTP 收尾计时：有独立端口环境即补。
