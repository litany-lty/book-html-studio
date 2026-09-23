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
  "requiredCodeGapsRemaining": ["G01", "G02", "G03", "G04", "G05", "G06", "G07", "G08", "G09", "G11", "G12", "G13", "G14", "G15", "G16"],
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
| **G01** | B02 | TODO_CODE | 账本有界追加日志、检查点、分页索引、增量汇总与归档 | 待实现 |
| **G02** | B03 | TODO_CODE | 来源事件最小记录、永久页摘要/统计/倒排检索索引与小型页头 | 待实现 |
| **G03** | B05 | PARTIAL_CODE | 全模型统一物理调用、账户并发与预算治理 | 待实现 |
| **G04** | B05 | TODO_CODE | 异步 OCR 远端 Job 持久占位、轮询恢复与未知债务治理 | 待实现 |
| **G05** | B06 | PARTIAL_CODE | 跨入口、跨书、多标签分阶段优先级调度（当前页 P0 优先） | 待实现 |
| **G06** | B04 | TODO_CODE | 服务端持久 CloudConsent / ReadingPolicy，有限授权下自动当前页处理 | 待实现 |
| **G07** | B04/B05 | PARTIAL_CODE | 统一幂等准入、operationEpoch（30天/4096上限）与批量快照持久化 | 待实现 |
| **G08** | B07 | PARTIAL_CODE | BookContentProfile + 严格 ContextSnapshot + JEV 接受锁内依赖校验 | 待实现 |
| **G09** | B08 | TODO_CODE | 完整 V3 固定计划（父 planHash/子 reviewPlanHash/contextHash/持久 eventSeq） | 待实现 |
| **G10** | B01 | DONE | 图像解码图、子图、PNG、Base64、请求体及临时磁盘完整字节租约 | 666 套件全绿，4 套新增专项实测 PASS |
| **G11** | B09 | PARTIAL_CODE | 前端 12页/32MiB 缓存双上限、校对工作台按需挂载、交互会话守卫 | 待实现 |
| **G12** | B10 | TODO_VERIFY | 冻结 ExportSnapshot、不可信脚本转义、ZIP 路径穿越与离线一致性 | 待实现 |
| **G13** | B11 | PARTIAL_CODE | LAN 配对与能力分级认证、统一外发白名单、严格 CSP、秘密轮换演练 | 待实现 |
| **G14** | B12 | TODO_CODE | 统一脱敏诊断指标、性能可复现埋点与故障记录 | 待实现 |
| **G15** | B00/B05/B12 | TODO_VERIFY | 全仓未读入口、单飞锁、传输池与严格 JSON 审计 | B00 已建立清单 |
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
| **B02-01** | B02 | TODO_CODE | 实现 `DurableEventJournal` 与 `AtomicManifestStore` | `LedgerJournalTest` | 待实现 |
| **B02-02** | B02 | TODO_CODE | `UsageLedger` 改造为追加日志写与内存分片索引 | `UsageLedgerTest` | 待实现 |
| **B02-03** | B02 | TODO_CODE | 实现定期 Checkpoint 生成与 WAL 截断归档 | `LedgerCheckpointRecoveryTest` | 待实现 |
| **B02-04** | B02 | TODO_CODE | 保持 UNKNOWN 债务在归档时的完整性 | `LedgerAdmissionScaleTest` | 待实现 |

*(后续批次 B03～B13 子任务随各批次执行实时更新)*

---

## 7. 下一步行动
立即执行 **B02 批次**（账本有界追加日志、检查点、分页索引、增量汇总与归档 / G01）：
1. 编写失败用例与恢复测试：`LedgerJournalTest`、`LedgerCheckpointRecoveryTest`、`LedgerAdmissionScaleTest`。
2. 实现 `DurableEventJournal` 与 `AtomicManifestStore`。
3. 改造 `UsageLedger`，消除 10 万条热路径全局扫描，实现原子检查点与有界追加写。
