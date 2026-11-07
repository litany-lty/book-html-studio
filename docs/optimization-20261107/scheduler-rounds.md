# R051–R075 调度与资源生命周期优化记录

基线：`da838c32ee744f786070c45031a40099726f0045`。计划日期：2026-11-07（Asia/Shanghai）。

范围：独占授权 service 类及对应测试；不修改模型、配置、其他 Store/API/前端。不调用真实收费模型。每轮按目标假设、阅读/复现、必要修复、定向验证记录；查无缺陷时保留审计证据。

## 执行前 25 轮计划

| 轮次 | 目标 | 可证伪假设 | 主要路径 |
| --- | --- | --- | --- |
| R051 | 延迟重试的逻辑调用去重 | 同一 logicalCallId 只能保留一个延迟回调，取消必须覆盖该回调。 | `DelayedCallQueue / DelayedRetryOwnershipTest` |
| R052 | 延迟重试关闭与回调交接 | 关闭或即时回调不能留下登记项、漏取消或向已关闭调度器提交。 | `DelayedCallQueue` |
| R053 | 当前页请求合并与代际所有权 | 同页同代合并，不同 attempt/generation 不复用旧工作。 | `PageWorkScheduler / PageWorkSchedulerOwnershipTest` |
| R054 | 前台优先和后台公平 | P0 当前页优先，后台在有界前台流量中仍可获得执行。 | `PageWorkScheduler / PriorityTaskScheduler` |
| R055 | 排队取消和执行中取消 | 排队任务不执行，执行中取消等待实际资源退出后才释放所有权。 | `PageWorkScheduler` |
| R056 | 调度器关闭与调用拒绝 | 关闭后拒绝新任务，已接纳任务都有可观察终态。 | `PriorityTaskScheduler / PageWorkScheduler` |
| R057 | 阅读窗口更新及过期会话 | 切页后优先级更新，旧会话不会持续占用当前页资源。 | `ReadingWindowService / ReadingPriority` |
| R058 | 阅读窗口取消与已发布内容 | 取消预取和切换任务不删除可读修订。 | `ReadingWindowService / PageProcessingService` |
| R059 | 进度计划与实际完成量 | 进度以有效计划和真实阶段完成量归约，不伪造百分比。 | `ProcessingProgressService / ProgressJournal` |
| R060 | 进度重复事件与顺序 | 重复、迟到和不同代事件不会重复计数或倒退已完成工作。 | `ProgressJournal / ProcessingProgressService` |
| R061 | 终态与部分失败进度 | 失败、取消、部分成功正确终结，不停在固定百分比。 | `ProcessingProgressService / JobService` |
| R062 | 任务取消与重启恢复 | 持久化取消状态不会在恢复时重新发起已取消收费调用。 | `JobService` |
| R063 | 任务创建与重处理去重 | 相同重处理意图并发提交不会创建重复工作。 | `JobService / PageProcessingService` |
| R064 | 远程任务登记和恢复 | 远程任务已有标识时恢复轮询，不重复创建远端收费任务。 | `RemoteJobRegistry` |
| R065 | 远程过期任务回收 | 超时或终止只回收自身所有权，持久化恢复保持状态。 | `RemoteJobRegistry` |
| R066 | 物理调用预算预留生命周期 | 未发送预留归还，已发送调用扣费一次且不重复释放。 | `AttemptCallBudgetStore / PhysicalCallSession / QwenRequestGate` |
| R067 | 重试边界与调用截止时间 | 重试等待不占用物理许可，截止后不再发起请求。 | `PhysicalCallService / PhysicalCallCommand` |
| R068 | 供应商前后台并发和队列上界 | 所有调用入口共享有界资源且为当前页保留容量。 | `ProviderResourceRegistry` |
| R069 | 资源许可异常与中断回收 | 超时、中断、异常退出归还唯一许可，不泄漏内存预算。 | `ResourceBudgetManager / RenderBudget` |
| R070 | 图像对象与编码对象释放 | 图像、编码字节、许可的 close 可重复调用且实际释放引用。 | `ImageArtifact / EncodedImageArtifact` |
| R071 | HTTP响应体及输出字节上界 | 未知长度或超大响应有界读取，失败时关闭响应资源。 | `BoundedHttp / BoundedByteOutputStream / ManagedTransport` |
| R072 | 手稿恢复游标及完整性 | 损坏或过期恢复数据不覆盖新成果，合法数据减少重复调用。 | `ManuscriptResumeStore` |
| R073 | 可理解性恢复与身份核验 | 恢复标识及完成状态隔离不同任务，异常数据安全降级。 | `ComprehensibilityResumeStore` |
| R074 | 辅助调用与账本一致性 | 取消、失败和重试不会重复记账或遗失实际发送记录。 | `QwenAssistCoordinator / UsageLedger / UsageContext / QwenExecutionScope` |
| R075 | 统计缓存与OCR路由生命周期 | 缓存对实际页面状态及时失效，OCR入口遵守统一资源边界。 | `PageStatisticsCache / CloudOcrPipeline / PaddleOcrPipeline` |

## 实际执行记录

最终完成：**25/25 轮，11 轮修复、14 轮审计保留**。本代理三批 Maven 共285次测试执行全部通过；其后独立审查补充的第21个新方法已隔离编译执行通过，主线程完整 verify 负责最终集成确认。

### R051 — 延迟重试逻辑调用去重（已修复，隔离验证通过）

- 目标/假设：同一 logicalCallId 只能有一个待执行所有者；取消必须覆盖这个任务。
- 发现：原 schedule 无条件覆盖 Map，但旧定时任务仍会执行；旧回调还会删除新登记。重复提交返回 true 的红测已实际复现。
- 修改：`DelayedCallQueue` 在向计时器提交前先按逻辑 ID 登记 PendingTask，重复准入返回 false；回调按实例精确移除。对应 `OptimizationDelayedCallQueueTest.duplicateLogicalCallKeepsOneOwnerAndCancellationStopsIt`。
- 验证：在共享 target 外，以 Java 编译器编译实际生产类及 JUnit 测试并用反射执行 @Test。修复前该断言失败，修复后通过；取消后回调次数和登记数均为 0。未使用外部模型或 HTTP。
- 状态：完成；Maven 第一批回归已通过，详见批次记录。

### R052 — 延迟重试关闭和回调交接（已修复，隔离验证通过）

- 目标/假设：计时器回调可能早于 schedule 返回；关闭/拒绝应清理登记，不遗留幽灵任务。
- 发现：原实现先提交后登记，快速回调留下 activeDelayedCount=1；计时器关闭抛 RejectedExecutionException。两项红测实际失败。
- 修改：先登记、后提交；schedule/cancel/close 同步串行所有权交接，拒绝时精确删除本次登记并返回 false。回调完成后不再重新插入已完成 Future。
- 验证：相同隔离编译/执行，4 个队列回归方法全部通过（修复前 3 失败、1 通过）；包括回调中同 ID 的下一次合法重试仍能排入。日志位于仓库外 `../scheduler-verification/queue-red.log` 与 `queue-green.log`。
- 状态：完成；Maven 第一批回归已通过，详见批次记录。

以下记录保留执行批次顺序；轮次最终状态以各轮结果与本节最终完成统计为准。


### R053 — 当前页合并与代际所有权（审计保留，已验证）

- 假设：同页同 attempt 返回同一 Future；不同 attempt/generation 不得借用旧物理槽。
- 阅读：`PageWorkScheduler.schedule` 对 attemptId、generation 显式校验，提升优先级只调整排队位置；运行中取消仍持有 active 登记。
- 结果：未发现需要修改；`CrossEntrySchedulerTest.testPriorityOrderingAndPromotionWithoutDuplicates` 与 `PageWorkSchedulerOwnershipTest.cancelledFutureAndOwnershipRemainUntilPhysicalWorkerExits` 实跑通过。

### R054 — 前台优先与后台公平（审计保留，已验证）

- 假设：P0 先行，非 P0 前台连续 8 次后让后台前进；供应商本地队列保留前台槽。
- 阅读：`PageWorkScheduler.take` 的分阶段公平计数、P0 例外；`PriorityTaskScheduler.eligible/submit` 的前台优先和后台并发上限。
- 结果：无变更；CrossEntry 的优先级、8 次公平、P0 例外三项与 PriorityTaskScheduler 的保留 worker/queue 两项实跑通过。

### R055 — 排队与执行中取消（审计保留，已验证）

- 假设：排队页可以立即终结，运行页要等物理退出才释放身份，重复取消只能中断一次。
- 阅读：`PageWorkScheduler.cancel/settle`、`PriorityTaskScheduler.cancel`、JobService 等待 scheduler future 收尾的处理。
- 结果：无变更；CrossEntry 的整书取消、PageWorkScheduler 的物理所有权保留和 PriorityTaskScheduler 的关闭中断测试实跑通过。单靠 Future.cancel 不构成释放物理所有权的依据。

### R056 — 调度器关闭后的准入与收尾（审计保留，已验证）

- 假设：close 后新任务应立即失败，已排队结果都可观察终结；正在运行的结果等真实退出。
- 阅读：两个 scheduler 的 closed guard、队列清理、结果终结和 PageWorkScheduler 的有界 join。
- 结果：无变更；`PageWorkSchedulerOwnershipTest.closedSchedulerRejectsAdmissionWithoutDanglingFuture`、`PriorityTaskSchedulerTest.closeTerminatesQueuedWorkButWaitsForPhysicalExitOfRunningWork/coordinatorCannotReopenAfterClose/closingCoordinatorCancelsStructureAndNeverStartsQueuedImageFactories` 通过。

### R059 — 重启后的真实阶段进度（已修复，已验证）

- 假设：已完成 OCR/结构的进度不能因冻结核对分母或重启而丢失。
- 发现：ProgressJournal 在每个 PLAN_FROZEN 新建 WorkPlan，丢弃此前 STAGE_TRANSITION；真实 67% 回放成 17%。另 PAGE 计划被误当成核对分母。
- 修改：回放开始建立阶段计划，冻结核对时保留已完成阶段，只在有 reviewPlanHash 时冻结核对子计划；单位完成按核对子计划归属归约。
- 路径：`ProgressJournal`、`OptimizationProgressJournalTest.reviewPlanFreezePreservesAlreadyCompletedStagesOnRecovery`。
- 验证：独立实际执行红测 67 != 17，修复后 67 == 67，父/子计划哈希保持，未完成恢复为 INTERRUPTED；Maven 新回归与 V3ProgressApi 全部通过。

### R060 — 重复/迟到事件及同代身份隔离（已修复，已验证）

- 假设：重复完成事件只计一次，终态不复活，相同 generation 的另一个 attempt 不能覆写当前日志。
- 发现：同 unitId 的第二条事件会增加计数；FINISHED 后还能写入事件；同代不同 attemptId 会清空旧日志。两个红测实际复现。
- 修改：回放按 unitId 去重并忽略非递增序列，FINISHED 截止；记录端拒绝终态后事件和同代身份冲突。
- 路径：`ProgressJournal`、`OptimizationProgressJournalTest`。
- 验证：新增两方法与 `ProgressReducerRegressionTest`、`ProgressPlanAuthorityTest` 的事件/身份/持久取消约束通过。

### R061 — 终态与部分失败不能伪报完成（审计保留，已验证）

- 假设：未完成或失败单位不能因 SUCCEEDED 请求伪报 100%；轮询不得制造进度。
- 阅读：`ProcessingProgressService.finish/snapshot/latest` 将不完整成功降为 PARTIAL、保留可重试并关闭 canStop；只由事件推进版本。
- 结果：无生产修改；`ProcessingProgressServiceTest.successCannotHideUnfinishedFrozenWork`、V3 的 PARTIAL 语义、ProgressReducer 终态不复活、ProgressPlanAuthority 持久取消权威均实跑通过。

## 已执行验证批次

- 第一批：`bash ../tooling/mvn-session -Dtest=OptimizationDelayedCallQueueTest,DelayedRetryOwnershipTest,PageWorkSchedulerOwnershipTest,CrossEntrySchedulerTest,PriorityTaskSchedulerTest,OptimizationProgressJournalTest,V3ProgressApiTest,ProgressReducerRegressionTest,ProgressPlanAuthorityTest,ProcessingProgressServiceTest test`。
- 结果：**44 tests / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS，11.092 秒**。日志 `../scheduler-verification/batch1-maven.log`（仓库外工具记录）。
- 该批结束时已闭环：9/25。此处保留批次历史，最终 25 轮结论见文末。


### R057 — 阅读窗口更新、当前页与会话过期（审计保留，已验证）

- 假设：快速跳页丢弃旧排队页，最新当前页优先；已过期会话不能借旧 sequence 复活。
- 阅读：`ReadingWindowService.update/replaceWindow/tickSession`、`ReadingPriority`；授权当前页立即准入、邻页仍受窗口与通道容量限制，满容量让已发请求自然收尾。
- 验证：`ReadingWindowServiceTest` 的优先中心、快速跳页、并行当前页、满容量自然收尾、heartbeat/stale stop/expiry；`AuthorizedAutoReadingTest` 的初次打开与跳页立即准入；MultiReaderSession 的租约到期。第二批全部通过，生产逻辑保留。

### R058 — 停止阅读窗口的书籍身份与内容保留（已修复，已验证）

- 假设：某本书的停止请求只能停止该书窗口，停止/重试不得先清空已发布正文。
- 发现：`ReadingWindowService.stop` 用 sessionId 找到活动会话后未核对 bookId；B 书路由携 A 会话 ID 会把 A 停掉。新增确定性红测复现。
- 修改：修改会话 sequence / enabled 之前验证 bookId，一致后才停止。
- 验证：`MultiReaderSessionTest.stopCannotUseAnotherBooksSessionIdentity` 修复前未抛错、修复后 CONFLICT 且 A 仍启用；ReadingWindow 既有 retryCurrentPageKeepsReadyContentAndReprocesses、已有人工批次不被停止/抢占通过。

### R062 — 取消后的持久终态与重启恢复（审计保留，已验证）

- 假设：任务重启为 INTERRUPTED，不自动重新发云请求；取消先撤销页尝试资格，再等实际 worker 退出。
- 阅读：`JobService.markCancelled/cancel/run/executePage/reconcileAttemptIntents` 及 `PageProcessingService` 的发布/终结资格。
- 验证：`JobPersistenceAndCancellationTest` 10 项、`JobPhase1SafetyTest` 9 项通过；包括 restartMarksRunningJobInterruptedAndUnlocksPage、queuedCancellationIsTerminalWithoutRunningWorker、cancelThenRetryOldWorkerCannotOverwriteNewJob、恢复旧可读正文。无生产修改。

### R063 — 多书重处理的预约与重复意图（已修复，已验证）

- 假设：多书会话可以各自重试，同 clientOperationId 仍返回同一持久任务回执。
- 发现：`JobService.requestReprocess` 仍只检查最近一本书的旧单预约字段；打开 B 后 A 的有效预约被误拒“预约已失效”。
- 修改：首先按 bookId 查 readingReservations，并保留旧字段兼容回退；明确拒绝 null 预约。
- 验证：新增 MultiReaderSession 的双书重处理红测先失败，修复后成功；同操作再次提交返回同 jobId。整类 7 项及 Job 两类回归通过。未新增模型调用或改变授权规则。

### R064 — 远端任务恢复与禁止重复提交（审计保留，已验证）

- 假设：已有 remoteJobId 的任务恢复后只续查；SUBMIT_UNKNOWN 仍保留债务，不把“不知道”当作允许重发。
- 阅读：`RemoteJobRegistry.register/recover/markSubmitting` 的 book/page/provider/account/fingerprint 复用、SUBMITTING 写入与损坏隔离。
- 验证：`RemoteJobRecoveryTest` 3 项通过，包括满 3 槽、同 handle/remoteId 恢复、未知提交无自动重发。保留现有逻辑。

### R065 — 远端陈旧占位的回收条件（审计保留，已验证）

- 假设：只释放从未发送的陈旧 RESERVED；已发送/未知任务不靠时间伪造终态。
- 阅读：`RemoteJobRegistry.purgeStale/isStale/registerWaiting`，仅 physicalCallId 与 remoteJobId 均空的 RESERVED 可证明未提交。
- 验证：`RemoteJobStaleReclaimTest` 9 项通过，覆盖未提交占位回收、未知债务保留、等待释放/取消/超时。无生产修改。

### R069 — 取消、扩容和租约交接的一次释放（已修复，已验证）

- 假设：等待中取消不能在槽位释放瞬间仍被准入；扩容必须属于同一预算管理器且不能溢出；detach 和 close 只能有一个字节所有者。
- 发现：4 个独立行为红测全部失败：取消返回 ADMITTED、外来 encoded ticket 被另一管理器记账、Long.MAX_VALUE 扩容绕过上限、detach 归还 permit 前仍可被 close 释放图像字节。
- 修改：`ResourceBudgetManager` 获取 permit 后复查取消并归还；图像/编码字节准入、扩容与归还使用共同锁，验证票据所有者，使用减法上限检查防溢出；`RenderBudget.Lease.detachImageByteTicket` 先 CAS 交接所有权。
- 验证：`OptimizationResourceOwnershipTest` 4/4（修复前 4 失败）；`RenderBudgetTest` 5、`ResidentImageBudgetTest` 5、`EncodedRequestBudgetTest` 2 全通过，包括并发压力和幂等 close。

### 第二批验证结果

- 命令：`bash ../tooling/mvn-session -Dtest=ReadingWindowServiceTest,MultiReaderSessionTest,AuthorizedAutoReadingTest,JobPersistenceAndCancellationTest,JobPhase1SafetyTest,RemoteJobRecoveryTest,RemoteJobStaleReclaimTest,OptimizationResourceOwnershipTest,RenderBudgetTest,ResidentImageBudgetTest,EncodedRequestBudgetTest test`。
- **77 tests / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS，18.106 秒**。日志 `../scheduler-verification/batch2-maven.log`。
- 第二批后已闭环：16/25。新图像生命周期、缓存与账本回归还需第三批结果确认。


### R066 — 物理调用预算的预留、扣减与退款（审计保留，已验证）

- 假设：只有实际发送才消耗物理预算；未发送退款，失败/未知已发请求不退还也不自动重发。
- 阅读：`AttemptCallBudgetStore.Reservation`、`QwenRequestGate.Budget/Reservation` 与 `PhysicalCallSession.prepareSend/sendBuffered/close`，预算是 attempt 所有者共享，close 幂等。
- 验证：`MixedProviderBudgetTest` 两项验证 8 次硬上限、第 9 次拒绝、未发送退款和已发送失败扣减；`QwenPhysicalCallTest` 17 项含失败审计不发送、取消审计后再次校验、未知结果不退款、重复 close 无重复退款。无生产修改。

### R067 — 重试与绝对截止时间（审计保留，已验证）

- 假设：429 等待不占 HTTP/供应商许可，重试不得重置页级总期限，未知结果不得自行重发。
- 阅读：`PhysicalCallService.execute/invokeSynchronous`、`PhysicalCallSession` 请求构造/审计前后 deadline 校验、QwenRequestGate 的 deadline constrain；429 仅返回 RetryEligible。
- 验证：第一批 DelayedRetryOwnership 的 429 后立即释放、过期拒绝，以及第三批 `QwenPhysicalCallTest.expiredSharedBudgetCannotAcquireAFreshDeadline/unknownSendOutcomeIsNotRefundedOrRetried/aSuccessfulHeaderWithStalledBodyIsBoundedAndUnknown` 通过。该轮未再更改已在 R051–052 修复的队列。

### R068 — 供应商并发、后台保留和配置缩容（审计保留，已验证）

- 假设：跨页/辅助种类共用并发上限，后台不能挤占前台槽，配置刷新不得生成额外许可。
- 阅读：`ProviderResourceRegistry.PoolState` 的共同锁、eligible 选择、queue 上限与 in-flight 保留式缩容；Qwen gate 仅代理资源登记。
- 验证：`QwenRequestGateTest` 7 项实跑通过，含 6 页压力并发 ≤3、后台 ≤2、队列0拒绝、并发 close、刷新3→1自然排空；现有 `UxU5QwenBoundedTest` 5 项通过。无生产修改。

### R070 — 图像与编码数据的实际持有生命周期（已修复，已验证）

- 假设：子图仍持有底图时字节不能提前归还；编码读取流仍打开时也必须持有字节租约。
- 发现：subImage 在检查 closed 后、增加引用前可与 close 交错，返回可读子图而预算已归零；EncodedImageArtifact.close 即使仍有打开的流也直接归零。两个确定性红测分别观测到应为 400/3 字节却为 0。
- 修改：`ResourceBudgetManager` 图像 wrapper 的 retain/subImage/close 串行交接；最后释放清空 raster 引用。编码流独立计数持有租约，工件关闭后最后一个流 close 才归还；关闭 wrapper 清空自持数据，byteLength 元数据保持。
- 验证：`OptimizationArtifactOwnershipTest` 2/2（红测2失败→绿测2通过）；第三批复跑 ResidentImageBudget、EncodedRequestBudget、ResourceOwnership 通过。

### R071 — HTTP 字节上限、读取截止与异常关闭（审计保留，已验证）

- 假设：未知长度/缓慢响应不能绕过整体 deadline 或 maxBytes；读取队列拒绝要关闭已收到的流。
- 阅读：`BoundedHttp.consumeResponse/readBounded/awaitFuture`，使用同一绝对期限、写入前检查剩余字节；`BoundedByteOutputStream` 在生产受限图像编码调用路径的扩容前检查；ManagedTransport 统一传剩余期限。
- 验证：`BoundedHttpTest` 3 项以本地合成 HTTP/可控流验证响应头与体共用期限、过大响应/重定向/取消、读取任务拒绝关闭流；QwenPhysicalCall 的超大体和停滞体回归通过。未访问真实供应商。

### R072 — 手稿断点恢复与有界容量维护（已修复，已验证）

- 假设：同源/合同/书页才能复用，旧 writer 不能覆盖新 owner；维护不能永远困在几个不可回收前缀记录。
- 阅读：`ManuscriptResumeStore` 的严格读取、完整性哈希、源与合同身份、已发表来源判定、Session owner 比较；发现 makeRoom 每次只检查排序最前4条，后面的过期记录永远不可回收。
- 复现/修改：构造64条记录，仅63号已过期，重复16次新页准入仍失败。添加有界共享维护游标（最多128目录，每次仍只检查4条），目录枚举先限界、未来时间记录不删除；保持最新未完成证据。
- 验证：新增维护红测→绿测；`ManuscriptResumeStoreTest` 14项及 `ManuscriptResumeTest` 20项通过，含真实新JVM恢复、取消保留已完成区域、改源/模型/目标地址不复用、复用无新增请求/令牌。

### R073 — 可理解性恢复身份、完整性与可用性（审计保留，已验证）

- 假设：局部结果只能在相同计划和组输入下复用，不完整/损坏证据不能被当成完成，也不得覆盖可读正文。
- 阅读：`ComprehensibilityResumeStore.open/read/Session.update` 的 inputHash/planned/index、严格 JSON、完整性哈希、固定有效期、owner 替换及有界轮转维护。
- 验证：`ComprehensibilityResumeStoreTest` 20项、`ComprehensibilityResumeTest` 20项全部通过，含新JVM、过期/时钟回退、跨页隔离、缓存损坏保留、账本复用失败不改成付费重试。无生产修改。

### R074 — 用量账本与恢复分页的一致性（已修复，已验证）

- 假设：同一次物理调用的身份、时间和账务在恢复/分页间稳定，缓存命中不计为新增收费请求。
- 阅读：`QwenAssistCoordinator` 的显式工作线程 scope、`UsageContext/QwenExecutionScope` 恢复、`UsageLedger` durable 发送意图和终态；主线程真实API探针发现56条记录分页偶发50+7，原始ID证明重复。
- 根因：v2回放直接反序列化保留 Instant 纳秒；legacy readPath 先读 DoubleNode 再 treeToValue，`.110658318Z` 被舍入为 `.110658400Z`，第二次读取把游标记录移到游标之后。
- 修改：仅 readPath 的局部 ObjectReader 启用 USE_BIG_DECIMAL_FOR_FLOATS；重复字段、整数和其他记录校验不变，未改变写入协议。
- 验证：新增固定 Instant 的真实 writer→新 reader→cursor 回归，修复前第二页重复第一页ID，修复后只有第二条且纳秒精确；`OptimizationUsageLedgerPaginationTest`、`UsageLedgerTest`、`QwenLedgerIntegrationTest`、`LedgerCheckpointRecoveryTest`、`LegacyLedgerMigrationTest`、`LedgerArchiveTest`、`QwenAssistCoordinatorTest` 均通过。没有真实供应商请求。

### R075 — 统计缓存与取消后的 OCR 资源开销（已修复，已验证）

- 假设：统计缓存不能在出版并发中插回旧计数；已取消页不应继续编码图像或进入模型客户端。
- 阅读：`PageStatisticsCache` 64项容量、偶数 epoch 可缓存条件、增量变化；Cloud/Paddle 流程非拆页分支缺少取消前置检查，取消只靠下游客户端才会被发现。
- 修改：`CloudOcrPipeline`、`PaddleOcrPipeline` 在进入图像工作和进入供应商客户端前检查取消/线程中断，分割页同样执行检查；供应商选择与模型配置保持原值。
- 验证：3个取消红测先失败（已取消仍编码、编码期间取消仍调用客户端），修复后全过；`PageStatisticsCacheTest` 7项含5000页暖缓存只读1页、并发奇数epoch/旧扫描不可缓存；CloudOcr 2、Paddle路由6项全过。

### 第三批验证结果

- 24个类：OptimizationArtifactOwnershipTest、OptimizationResourceOwnershipTest、ResidentImageBudgetTest、EncodedRequestBudgetTest、QwenPhysicalCallTest、MixedProviderBudgetTest、QwenRequestGateTest、BoundedHttpTest、ManuscriptResumeStoreTest、ManuscriptResumeTest、ComprehensibilityResumeStoreTest、ComprehensibilityResumeTest、UsageLedgerTest、OptimizationUsageLedgerPaginationTest、QwenLedgerIntegrationTest、QwenAssistCoordinatorTest、UxU5QwenBoundedTest、PageStatisticsCacheTest、CloudOcrPipelineTest、PaddleOcrPipelineRoutingTest、OptimizationOcrCancellationTest、LedgerCheckpointRecoveryTest、LegacyLedgerMigrationTest、LedgerArchiveTest。
- 通过 `bash ../tooling/mvn-session -Dtest=<上述逗号列表> test` 运行。
- **164 tests / 0 failures / 0 errors / 0 skipped；BUILD SUCCESS，44.495 秒**。日志 `../scheduler-verification/batch3-maven.log`。

## 独立审查后的补充闭环（归入 R059，不另计轮次）

- 审查指出合法的 PAGE 计划在尚未冻结/完成单位时可替换为带 ctx-v2 的 WorkPlan；初次修复取最早 context 会遗漏后续身份。
- 新增 `OptimizationProgressJournalTest.replacingAnUnfrozenPlanRestoresTheLatestContextIdentity`；红测实得空 context，期望 ctx-v2。
- 修复回放取最后有效计划 context，仍保留既有阶段历史；隔离编译实际生产类和测试执行 **4/4通过**，同时验证父/子哈希与 weightedPercent。
- 此补充在第三批 Maven 后执行，尚未由本代理重新跑 Maven；主线程最终套件需包含该第4个方法。没有把隔离执行冒称为新 Maven 结果。

## 本分支交付统计与边界

- **R051–R075 共25个目标闭环：11轮修复，14轮审计后保留。** 轮次按不同目标/根因记录，不按测试数量凑轮次。
- 10个生产文件改动；6个新测试类、2个既有测试类新增方法，共21个新方法（20个已纳入三批Maven，第21个审查补充已隔离通过）。
- 三批合计 **285次测试执行**，去重为 **42个测试类、274个测试方法**；全部0失败/0错误/0跳过。重跑资源回归的11次执行只在执行次数中重复统计。
- 未修改domain、模型/配置、Store/API/前端或CI；未commit/push/切分支；未调用收费模型。HTTP验证仅用本地合成服务/测试transport。
- 这里不声称全仓库测试、全部浏览器场景或真实云服务负载已通过；全量集成与浏览器结论由主线程的最终验证提供。


## R099 交叉审查补充（不计入本代理25轮）

- 只读审查非本代理编写的 `src/main/resources/static/app.js`、`reading-anchor.js`、`reading-window.js` 本次 diff，并追到 `api.js` 取消映射及 `acceptReadyPage` 的编辑保护路径。
- 核查目录/疑点延迟聚焦的 book/page/request/editor 身份、同页重取的 AbortController、进度条单次提交、嵌套布局视口偏移保存、saveData 开启时取消旧预取、beacon 返回 false/抛错时回退停止请求。
- 未发现此次补丁新增的实质回归；没有修改前端。
- 独立执行 `node --test scripts/verification/probe_reader_navigation_edges.mjs scripts/verification/probe_prefetch_cancellation.mjs scripts/verification/probe_reading_anchor.mjs scripts/verification/probe_reading_window_stop.mjs scripts/verification/probe_reader_ready_retry.mjs`，**14 tests / 14 pass / 0 fail / 0 skipped**，122.209707 ms。
- 这批是可控 DOM/transport 的逻辑探针，不能替代真实浏览器布局、网络服务与输入设备行为验证；日志 `../scheduler-verification/frontend-independent-review.log`。
