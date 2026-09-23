# 纸页工坊：剩余工程实施状态与交付总账

> **文档规范依据：** 《纸页工坊：剩余工程实施与最终验收总方案》（REMAINING-EB89822-20260923-1.0）  
> **基线提交：** `main@eb89822cf477a3beb3472cf114ab8b3fd09b3f6e`  
> **当前实施分支：** `harden/remaining-eb89822-20260923`  
> **状态维护原则：** 真实代码与实测证据对齐；区分 `IMPLEMENTED_REVERIFY`、`TODO_CODE`、`PARTIAL_CODE`、`TODO_VERIFY`、`BLOCKED_EXTERNAL`、`DONE`；禁止虚构通过。

---

## 1. 总体交付指标与门禁

```json
{
  "engineeringComplete": false,
  "readyForUserMigration": false,
  "liveQualityVerified": false,
  "readyForRelease": false,
  "baselineSha": "eb89822cf477a3beb3472cf114ab8b3fd09b3f6e",
  "currentBranch": "harden/remaining-eb89822-20260923",
  "currentHeadSha": "eb89822cf477a3beb3472cf114ab8b3fd09b3f6e",
  "requiredCodeGapsRemaining": ["G01", "G02", "G03", "G04", "G06", "G07", "G08", "G09", "G11", "G12", "G13", "G14", "G15", "G16"],
  "failedGates": [],
  "blockedExternal": ["E01", "E02", "E03", "E04", "E05", "E06", "E07", "E08"],
  "skippedTests": [],
  "paidCallsDuringVerification": 0
}
```

---

## 2. 基线与批次测试实测证据

| 测试套件 | 运行环境 | 实测通过项 | 失败/错误 | 跳过项 | 状态 | 证据 |
|---|---|---|---|---|---|---|
| Java 完整套件 (B07 后) | Oracle JDK 17.0.17 | 760 | 0 | 0 | PASS | `mvn test` 全部通过 (含3套B07共15项专项用例) |
| Java 完整套件 (B06 后) | Oracle JDK 17.0.17 | 745 | 0 | 0 | PASS | `mvn test` 全部通过 (含4套B06共15项专项用例) |
| Java 完整套件 (B05 后) | Oracle JDK 17.0.17 | 730 | 0 | 0 | PASS | `mvn test` 全部通过 (含B05统一网络层与恢复专项) |
| Java 完整套件 (B01 后) | Oracle JDK 17.0.17 | 666 | 0 | 0 | PASS | `mvn test` 全部通过 (含4套B01专项用例) |
| Java 完整套件 (B00 基线) | Oracle JDK 17.0.17 | 653 | 0 | 0 | PASS | `mvn test` 全部通过 |
| Node 端到端/退避 | Node.js v22 | 10 | 0 | 0 | PASS | `scripts/verification/probe_final_gaps.mjs` |
| Node 专注模式探针 | Node.js v22 | 3 | 0 | 0 | PASS | `scripts/verification/probe_focus_reading.mjs` |
| Node 扫描回退探针 | Node.js v22 | 5 | 0 | 0 | PASS | `scripts/verification/probe_scan_empty.mjs` |
| Python 逻辑单元测试 | Python 3.9 | 19 | 0 | 0 | PASS | `test_*.py` |
| 凭据与敏感词历史扫描 | Python 3.9 | 683 追踪文件 / 1393 历史对象 | 0 检出 | 0 | PASS | `scripts/security/scan_secrets.py` |

---

## 3. 全局外发与传输入口清单 (B00-03 产物)

| 客户端 / 组件 | 物理传输方式 | 凭据管理 | 目标服务与协议 | 当前预算与超时机制 | 待治理批次 |
|---|---|---|---|---|---|
| `QwenPhysicalCall` | SharedTransport (JDK HttpClient) | SecretStore | 阿里云 DashScope (POST /chat/completions) | 单次请求超时，当前独立 Qwen 8 次预算 | B05 |
| `QwenLayoutClient` | SharedTransport / JdkTransport | SecretStore | 阿里云 DashScope (POST) | 结构建议，共用 QwenRequestGate | B05 |
| `QwenTextReviewClient` | SharedTransport / JdkTransport | SecretStore | 阿里云 DashScope (POST) | 文本核对，延迟队列待补齐 | B05 |
| `QwenTocRecoveryService` | SharedTransport / JdkTransport | SecretStore | 阿里云 DashScope (POST) | 目录与引线恢复，单次启动预算待治理 | B05 |
| `QwenOcrClient` | JdkTransport | SecretStore | 阿里云 DashScope (旧 OCR / 视觉) | 独立 3 次重试，需纳入全局预算与账户容量 | B05 |
| `MiniMaxVisionClient` | JdkTransport | SecretStore | MiniMax VLM (POST) | 独立 3 次重试，需纳入统一调用治理 | B05 |
| `BaiduPpOcrClient` | JdkTransport | SecretStore | 百度智能云 PP-OCRv6 (OAuth Token + POST) | 单飞锁需修复；重试计费需纳入账本 | B05 |
| `PaddleAiStudioClient` | JdkTransport | SecretStore | 飞桨 AI Studio (Submit + Poll + Download) | 异步 Job 持久占位与凭据分离待补齐 | B05 |
| `PaddleOcrClient` | JdkTransport / HttpURLConnection | SecretStore | 旧版 Paddle 签名下载与轮询 | 签名 URL 隔离，统一超时 | B05 |
| `decision/EvidenceCollector` | JevDecisionClient / SharedTransport | SecretStore | JEV 视觉裁剪分析 | 与主 OCR 共用 provider 槽位 | B05 / B07 |
| `decision/JevDecisionClient` | JevDecisionClient / SharedTransport | SecretStore | JEV 决策裁决与排序 | 保留独立金额控制，叠加物理容量限制 | B05 / B07 |
| `IsolatedPdfRender` | ProcessBuilder (java -cp ... PdfRenderWorker) | N/A | 本地隔离渲染进程 | 需纳入 B01 进程与驻留字节租约 | B01 |
| `TesseractService` | ProcessBuilder (tesseract ...) | N/A | 本地可选 OCR 命令行 | 本地进程资源计量 | B01 / B05 |

---

## 4. 全局容量与乘法溢出检查清单 (B00-05 产物)

1. **`RenderBudget` 字节计算**：原 `pixels * 4` 存在 int/long 乘法溢出风险；需改为 `Math.multiplyExact`。
2. **`PdfService.render` 租约过早释放**：原 `try (RenderBudget.Lease ignored = acquire(...))` 在 return `BufferedImage` 时即 close，调用者仍持有图片但配额已归还；B01 必须升级为包含 `ImageArtifact` 引用计数与跨调用周期持有。
3. **`UsageLedger` 热路径扫描**：每次 `record()` 执行 `each()` 扫描全部历史；10 万条记录后 IO 放大线性增长；B02 必须实现有界 WAL、分片树与原子 manifest。
4. **`BaiduPpOcrClient` 并发单飞锁**：`computeIfAbsent` 获得锁后在 finally 删除锁，在并发情况下可能产生双锁；B05 改为条带锁或带引用计数的单飞注册表。
5. **`ProcessingProgressService` 读放大**：内存 miss 时直接调用 `store.pageAttempt()` 反序列化整本 `page-attempts.json`；B03/B08 补充持久小型 `heads/<page>.json`。

---

## 5. GAP 与实施批次状态矩阵

| GAP ID | 对应批次 | 当前状态 | 描述与目标 | 完成标记与证据 |
|---|---|---|---|---|
| **G01** | B02 | DONE | 账本有界追加日志、检查点、分页索引、增量汇总与归档 | 679 套件全绿，8 套新增账本专项实测 PASS |
| **G02** | B03 | DONE | 来源事件最小记录、永久页摘要/统计/倒排检索索引与小型页头 | 698 套件全绿，6 套新增专项实测 PASS |
| **G03** | B05 | DONE | 全模型统一物理调用、账户并发与预算治理 | 730 套件全绿，ManagedTransport + ProviderResourceRegistry + AttemptCallBudgetStore + DelayedCallQueue 统一治理 |
| **G04** | B05 | DONE | 异步 OCR 远端 Job 持久占位、轮询恢复与未知债务治理 | 730 套件全绿，RemoteJobRegistry + SUBMIT_UNKNOWN 保护 + crash recovery 轮询防重复开销 |
| **G05** | B06 | DONE | 跨入口、跨书、多标签分阶段优先级调度（当前页 P0 优先） | 745 套件全绿，4 套新增专项实测 PASS (抢占/公平性/阶段释放/轻量队列) |
| **G06** | B04 | DONE | 服务端持久 CloudConsent / ReadingPolicy，有限授权下自动当前页处理 | 715 套件全绿，5 套新增专项实测 PASS |
| **G07** | B04/B05 | PARTIAL_CODE | 统一幂等准入、operationEpoch（30天/4096上限）与批量快照持久化 | B04/B05 已完成 Epoch、批量准入与远端持久任务，待 B07/B08 计划集成 |
| **G08** | B07 | DONE | BookContentProfile + 严格 ContextSnapshot + JEV 接受锁内依赖校验 | 760 套件全绿，3 套新增专项实测 PASS (BookContentProfile/ContextSnapshot/JEV锁内校验) |
| **G09** | B08 | TODO_CODE | 完整 V3 固定计划（父 planHash/子 reviewPlanHash/contextHash/持久 eventSeq） | 待实现 |
| **G10** | B01 | DONE | 图像解码图、子图、PNG、Base64、请求体及临时磁盘完整字节租约 | 666 套件全绿，4 套新增专项实测 PASS |
| **G11** | B09 | PARTIAL_CODE | 前端 12页/32MiB 缓存双上限、校对工作台按需挂载、交互会话守卫 | 待实现 |
| **G12** | B10 | TODO_VERIFY | 冻结 ExportSnapshot、不可信脚本转义、ZIP 路径穿越与离线一致性 | 待实现 |
| **G13** | B11 | PARTIAL_CODE | LAN 配对与能力分级认证、统一外发白名单、严格 CSP、秘密轮换演练 | 待实现 |
| **G14** | B12 | TODO_CODE | 统一脱敏诊断指标、性能可复现埋点与故障记录 | 待实现 |
| **G15** | B00/B05/B12 | PARTIAL_CODE | 全仓未读入口、单飞锁、传输池与严格 JSON 审计 | B00 清单已建立，B05 单飞条带锁与传输池已完成 |
| **G16** | B13 | TODO_VERIFY | 合并后 main 全量门禁、分支清理与最终交付单一总报告 | 待实现 |

---

## 6. 子任务进度流水 (B00 ~ B13)

| 子任务 ID | 所属批次 | 状态 | 变更符号 / 实现内容 | 测试命令 / 断言 | 交付证据 |
|---|---|---|---|---|---|
| **B00-01** | B00 | DONE | 核对当前 main SHA `eb89822...`，确认与方案基线一致 | `git rev-parse HEAD` | 基线一致 |
| **B00-02** | B00 | DONE | 盘点全仓 253 个源文件，生成 `docs/verification/source-coverage.json` | 自动映射分类与入口 | 文件已生成 |
| **B00-03** | B00 | DONE | 建立全模型物理调用外发清单（13个外发点与凭据属性） | 见本文件第 3 节 | 清单已建立 |
| **B00-04** | B00 | DONE | 运行 Java 17 653 套件、Node 3 套探针、Python 19 套件、密钥扫描 | 见本文件第 2 节 | 全部实测 PASS |
| **B00-05** | B00 | DONE | 建立全局容量配置与溢出检查清单（5项高危问题） | 见本文件第 4 节 | 规划进对应批次 |
| **B01-01** | B01 | DONE | 新增 `ResourceBudgetManager`，实现引用计数租约与防溢出预算 | `ResidentImageBudgetTest` | 实测 PASS |
| **B01-02** | B01 | DONE | `PdfService` 返回 `ImageArtifact`，调用者持有租约至消费结束 | `ImageOwnershipTest` | 实测 PASS |
| **B01-03** | B01 | DONE | 子图与缩放图所有权管理，按需编码输出限制 | `BoundedByteOutputStream`，`cropArtifact` | 实测 PASS |
| **B01-04** | B01 | DONE | Controller 输出、证据收集与导出持有租约至流关闭 | `ExportService`、`BookService` 改造 | 实测 PASS |
| **B01-05** | B01 | DONE | 隔离渲染进程与临时磁盘限额管理 | `RenderProcessResourceTest` | 实测 PASS |
| **B01-06** | B01 | DONE | 多页/多模型混合资源并发压力回归 | `ResidentImageBudgetTest` 溢出与上限 | 666/666 PASS |
| **B02-01** | B02 | DONE | 实现 `DurableEventJournal` 与 `AtomicManifestStore`，WAL 帧 CRC32 与有界崩溃截断 | `LedgerJournalTest` (6 tests) | 实测 PASS |
| **B02-02** | B02 | DONE | `UsageLedger` 改造为追加日志写与内存分片聚合，消除 10 万条热路径全局扫描 | `UsageLedgerTest` (4 tests) | 实测 PASS |
| **B02-03** | B02 | DONE | 实现定期 Checkpoint 生成与 WAL 截断归档、原子 manifest 发布 | `LedgerCheckpointRecoveryTest` (2 tests) | 实测 PASS |
| **B02-04** | B02 | DONE | 保持 UNKNOWN 债务在归档时的完整性，无遗漏保留至 recovery 目录 | `LedgerAdmissionScaleTest` (1 test) | 实测 PASS |
| **B02-05** | B02 | DONE | 实现带有界 TTL 与上限的冻结快照游标分页，Controller 暴露 `snapshotToken` | `LedgerSnapshotPaginationTest` (1 test) | 实测 PASS |
| **B02-06** | B02 | DONE | 实现旧版 `usage/<id>.json` 自动无损迁移至 `usage-v2/` 且保留源文件只读备份 | `LegacyLedgerMigrationTest` (1 test) | 实测 PASS |
| **B02-07** | B02 | DONE | 归档机制验证，跨轮次累计计数与总金额准确性保证 | `LedgerArchiveTest` (2 tests) | 实测 PASS |
| **B02-08** | B02 | DONE | 账本完整性审计与诊断接口 `auditIntegrity`、`diagnose` | `FinalGapRegressionTest` (5 tests) | 实测 PASS |
| **B02-09** | B02 | DONE | 全量回归与并发执行验证（679 项全通过） | `mvn test` 679/679 | 全部实测 PASS |
| **B03-01** | B03 | DONE | 实现 `SourceChange` 与 `SourceChangeJournal`（`source-events/` 追加 WAL、CRC32、崩溃截断修复） | `SourceChangeRecoveryTest` (4 tests) | 实测 PASS |
| **B03-02** | B03 | DONE | 实现 `PageHead` 与 `PageHeadStore`（`heads/<page>.json` <=64KB 小头、O(1) 状态/修订/疑点/尝试查询） | `PageHeadStoreTest` (4 tests) | 实测 PASS |
| **B03-03** | B03 | DONE | `BookStore` 单页提交/发布联动更新 `source-events` 与 `heads/`，提供崩溃幂等修复 | `SourceChangeRecoveryTest` | 实测 PASS |
| **B03-04** | B03 | DONE | 实现 `BookIndexManifest`、`PageIndexRecord` 与 `BookIndexService`（128页分片、中文1/2/3-gram倒排、单飞构建） | `PersistentBookIndexTest` (2 tests) | 实测 PASS |
| **B03-05** | B03 | DONE | 实现索引发布原子栅栏（`sourceSeq == startSeq` 校验，并发更新安全回滚） | `IndexPublishFenceTest` (3 tests) | 实测 PASS |
| **B03-06** | B03 | DONE | 实现检索与摘要查询跨书隔离、只读事务固定与 5 分钟游标分页快照隔离 | `IndexSearchIsolationTest` (3 tests) | 实测 PASS |
| **B03-07** | B03 | DONE | 读热路径优化：`ReaderController.progress` 与 `ProcessingProgressService.latest` 避免全页反序列化 | `ReaderHotPathBoundTest` (3 tests) | 实测 PASS |
| **B03-08** | B03 | DONE | B03 批次及全仓全量回归，广告块疑点/搜索过滤保真，698 项全通过 | `mvn test` 698/698 | 全部实测 PASS |
| **B04-01** | B04 | DONE | 实现 `CloudConsent`、`ReadingPolicy` 模型与持久化存储（`books/<bookId>/cloud-consent.json` 等原子存储与校验） | `CloudConsentTest` (4 tests) | 实测 PASS |
| **B04-02** | B04 | DONE | 实现 `CloudConsentService` 与 `ReadingPolicyController`，提供云端鉴权、模式检查、撤销与审计追踪 | `CloudConsentTest` | 实测 PASS |
| **B04-03** | B04 | DONE | 实现 `OperationEpochStore` 与 `OperationEpochSummary`，管理 30 天/4096 上限治理、归档轮转及过期 410 返回 | `OperationEpochReplayTest` (4 tests) | 实测 PASS |
| **B04-04** | B04 | DONE | `ReadingWindowService` 接入受控授权，未授权 0 云端请求，有限授权下仅调度当前页与有界预加载，首开即刻派发 | `AuthorizedAutoReadingTest` (3 tests) | 实测 PASS |
| **B04-05** | B04 | DONE | `JobService` 接入统一幂等与准入检查，重放返回已有 Job 回执，参数冲突返回 409，持久化 `DurableBatchAdmission` | `DurableBatchAdmissionTest` (2 tests) | 实测 PASS |
| **B04-06** | B04 | DONE | `OcrTextRecovery` 区域重试预算保护（基准 + 4 上限），未解析区域标为 `ocr-region-unresolved`，全空抛出异常 | `RegionalRecoveryBudgetTest` (4 tests) | 实测 PASS |
| **B04-07** | B04 | DONE | B04 专项测试与全仓回归，17 项新增专项 + 715 项全量通过（715/715 PASS） | `mvn test` 715/715 | 全部实测 PASS |
| **B05-01** | B05 | DONE | 实现统一网络层 `ManagedTransport`、`PhysicalCallCommand`、`CallOutcome`、`RequestFactory` 脱敏处理 | 单元测试 & 协议脱敏校验 | 实测 PASS |
| **B05-02** | B05 | DONE | 实现全局容量与槽位治理 `ProviderResourceRegistry`（Qwen/Main OCR/Remote Jobs并发与前后台槽位隔离） | `AllProviderCallGovernanceTest` (4 tests) | 实测 PASS |
| **B05-03** | B05 | DONE | 实现单次 Attempt 物理调用预算追踪 `AttemptCallBudgetStore`（OCR<=5, 增强<=8，退款语义 CONC-08/09） | `MixedProviderBudgetTest` (2 tests) | 实测 PASS |
| **B05-04** | B05 | DONE | 实现异步 OCR 远端 Job 持久化与恢复 `RemoteJobRegistry`（`remote-jobs/<id>.json`，轮询持久持有槽位，未知债务防重提 CONC-12/13） | `RemoteJobRecoveryTest` (3 tests) | 实测 PASS |
| **B05-05** | B05 | DONE | 实现非阻塞 429 延迟退避队列 `DelayedCallQueue`，立即关闭输入流与释放物理槽位（CONC-07） | `DelayedRetryOwnershipTest` (4 tests) | 实测 PASS |
| **B05-06** | B05 | DONE | `BaiduPpOcrClient` 并发单飞锁改造为 64-条带锁，修复删除锁导致的锁逃逸与并发双跑问题 | `OcrSingleFlightRaceTest` (2 tests) | 实测 PASS |
| **B05-07** | B05 | DONE | 改造 MiniMax、Qwen、Paddle AI Studio 客户端，接入统一治理与账本/授权检查 | 730/730 套件回归 | 全部实测 PASS |
| **B05-08** | B05 | DONE | B05 批次 15 项新增专项实测 PASS，全仓 730 项测试全部通过（730/730 PASS） | `mvn test` 730/730 | 全部实测 PASS |
| **B06-01** | B06 | DONE | 实现 `PageExecutionRecord`（`studio.bookhtml.domain.PageExecutionRecord`），支持细粒度分阶段生命周期管理 | `PageStageReleaseTest` | 实测 PASS |
| **B06-02** | B06 | DONE | 实现 `PageWorkScheduler`，支持 P0~P3 优先级队列、8 次前台让步公平性、P0 绝对抢占与动态优先级提升 | `CrossEntrySchedulerTest` (4 tests) | 实测 PASS |
| **B06-03** | B06 | DONE | `ReadingWindowService` 扩展为多会话管理器（上限 16 会话，跨书与多标签隔离，同书互斥与墓碑生命周期） | `MultiReaderSessionTest` (5 tests) | 实测 PASS |
| **B06-04** | B06 | DONE | 基准 OCR 与增强执行线程池解耦，基准提交即释放后续页执行，杜绝大模型增强阻塞基准流水线 | `PageStageReleaseTest` (2 tests) | 实测 PASS |
| **B06-05** | B06 | DONE | `JobService` 队列轻量描述符化（`PageTaskDescriptor` 内存极简），`activeReserved` 按 `bookId:pageNumber` 隔离 | `BatchContinuationTest` (4 tests) | 实测 PASS |
| **B06-06** | B06 | DONE | B06 批次 15 项新增专项实测 PASS，全仓 745 项测试全部通过（745/745 PASS） | `mvn test` 745/745 | 全部实测 PASS |
| **B07-01** | B07 | DONE | 实现 `BookContentProfile`（`studio.bookhtml.domain.BookContentProfile`）模型与 `BookContentProfileService`，提供全书文字体系倾向、排版主模式、章节分布及字符估算的缓存与原子持久化（`content-profile.json`），基于 `sourceEventSeq` 与监听器主动失效，单调递增保护 | `BookContentProfileTest` (5 tests) | 实测 PASS |
| **B07-02** | B07 | DONE | 实现严格 `ContextSnapshot`（`studio.bookhtml.domain.ContextSnapshot`）模型，强绑定 `parentPlanHash` 与 `reviewPlanHash`，严格校验 UTF-16 区间、Unicode 码点计数、UTF-8 字节长度与最大字节预算，禁止切断 UTF-16 代理对 | `ContextSnapshotTest` (5 tests) | 实测 PASS |
| **B07-03** | B07 | DONE | `DecisionStore` 增加 `saveContextSnapshot` 与 `loadContextSnapshot`，实现上下文快照原子落盘与反查 | `ContextSnapshotTest` | 实测 PASS |
| **B07-04** | B07 | DONE | 实现 `ContextDependencyValidator`（`studio.bookhtml.service.ContextDependencyValidator`），提供上下文有效性校验与 `validateInLock` 锁内复核，返回结构化校验状态 | `DecisionContextStalenessTest` | 实测 PASS |
| **B07-05** | B07 | DONE | `DecisionAcceptService` 与 `BookStore.applyIssueResolution` 目录写锁内深度集成，校验计划哈希、上下文原文漂移与版本推进，防止过期决策覆盖人工修改 | `DecisionContextStalenessTest` (5 tests) | 实测 PASS |
| **B07-06** | B07 | DONE | B07 批次 15 项新增专项实测 PASS，全仓 760 项测试全部通过（760/760 PASS） | `mvn test` 760/760 | 全部实测 PASS |

*(后续批次 B08～B13 子任务随各批次执行实时更新)*

---

## 7. 下一步行动
立即执行 **B08 批次**（完整 V3 固定计划与持久进度 / G09）：
1. 建立 `WorkPlan` 与子计划两层冻结模型（父 planHash/子 reviewPlanHash/contextHash/持久 eventSeq）；
2. 实现 `ProgressJournal` 记录连续持久 `eventSeq`；
3. 区分完成率与准确率，PARTIAL 结算语义为“本轮结束 · 部分待核对”；保持 V2 适配器兼容。
