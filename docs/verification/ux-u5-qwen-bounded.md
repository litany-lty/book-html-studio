# U5：Qwen 有界小任务并发（纸页工坊）

- 基线：`ux-reader-remediation`；U0～U4 已提交。**U5 在 U2 与 U4 验收门通过后启动。**
- 提交：`7fbd87a`（闸门/规划/核对/协调/精简结构）→ `a206d44`（旧路径共用闸门）
  → `d38de7d`（分组接入 enrich）→ U5-tests → 本批收尾。
- 性质：拆职责，不只是裁小图片。全局结构判断与局部文字核对拆开，
  小任务在统一预算和全局并发上限内执行。

## 1. 精确改动点

| # | 文件 | 改动 | 不得破坏 |
|---|---|---|---|
| 1 | `config/QwenAssistProperties.java` | `chunkedAssist=false`、`maxConcurrentRequests=3`、`maxBackgroundRequests=2`、`maxQueuedChunks=24`、`maxPhysicalCallsPerPageAttempt=8`、`reviewChunkChars=2500`、块数 6–12（工程起点，需校准） | 旧配置语义 |
| 2 | 新增 `service/QwenRequestGate.java` | 全局信号量（前台可用全部，后台仅低于保留量进入）；有界排队（只计数）；调用预算；物理计数；在途峰值；permit 覆盖发送到流收尾 | 旧路径未注入走旧行为 |
| 3 | 新增 `service/QwenTaskPlanner.java` | owned/context 分离；6–12 短块/≤2500 字符；长块区间切片（父块 ID，代理对安全）；视觉原子跳过；尾组并入；预算内 6 组 + 结构 + 重试位，超预算 deferred | 不截断原文 |
| 4 | 新增 `service/QwenTextReviewClient.java` | 组请求/解析；8.4 严格校验（身份/归属/UTF-16/代理对/quote/类型/长度/bbox-HTML 拒收）；429 释放重取有界重试；UNKNOWN 不重发；身份化缓存（命中记复用）；共享 HttpClient | 不重新定序/改他组/输出正式块 |
| 5 | 新增 `service/QwenAssistCoordinator.java` | 有界出站池（非 common pool）；scope 逐 chunk；结构+核对；截断预算内再分；计划顺序合并；冲突保留；从不存页 | 无同池 join 死锁 |
| 6 | `service/QwenLayoutClient.java` | `structurePlan()` 精简结构（顺序排列校验，循环/缺失/多余拒绝）；出站经闸门；连接复用；前台/后台重载 | 整页 assist 语义与测试 |
| 7 | `service/QwenTocRecoveryService.java` | 出站经闸门；连接复用（目录恢复独立种类，预算/闸门共享，最终一致性校验保留） | 像素证据与专用质量门 |
| 8 | `service/PageProcessor.java` | enrich 内 native+paddle 与 paddle 非恢复分支优先分组（`tryChunkedAssist`：开关+装配+结构可用+有组；区域裁图全页坐标并集；失败 null 回滚整页；取消直抛） | 各 provider 分支与质量门；qwen/minimax 分支保持旧路径 |
| 9 | 测试 | Gate 4 / Planner 5 / Review 7 / Coordinator 6 / Enrich 3 / 门禁 5 | 合成夹具+替身 |
| 10 | 本文档 | 验收映射 + 未验证项 | — |

## 2. 测试（actual）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -Dtest='studio.bookhtml.service.QwenRequestGateTest,studio.bookhtml.service.QwenTaskPlannerTest,studio.bookhtml.service.QwenTextReviewClientTest,studio.bookhtml.service.QwenAssistCoordinatorTest,studio.bookhtml.service.PageProcessorChunkedEnrichTest' test
# Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
mvn test -Dtest='studio.bookhtml.service.*Test'  # 见提交输出
```

- 修复中发现的真实问题（已修）：①核对成功路径漏放 permit（429 重试覆盖了失败分支，
  成功分支从未释放）；②缓存键缺书身份（跨书同文误命中），现含调用方书/页；
  ③测试替身正则贪婪穿越转义（`\\.` 吃掉分隔符），收紧为否定前瞻；
  ④增强回归门比较 sourceRecords（增强不改来源），U4 已改为正文块比较。
- 替身压测：门控 6 线程在途峰值 ≤3（QW-01 逻辑）；前台保留槽（QW-03 逻辑）。
- 真实模型对比（同页/同门/同账户：旧整页 vs 分组 vs 关增强的首读时间、
  物理调用数、费用、保真）：无授权，未验证。分块开关默认关闭。

## 3. 验收映射（QW-01～16）

| ID | 状态 | 证据 |
|---|---|---|
| QW-01 全局 ≤3 | PASS（逻辑） | Gate 6 线程压测 + 物理计数 |
| QW-02 共用闸门 | PASS | 结构/核对/目录恢复同一闸门；旧路径可达 WRAP |
| QW-03 前台优先 | PASS（逻辑） | 保留槽测试；借用不强杀（ acquire null 即等待/降级） |
| QW-04 无同池死锁 | PASS（逻辑） | 独立出站池 + 单测收尾 |
| QW-05 计划顺序合并 | PASS | 倒置完成仍按计划序 + 父块正确 |
| QW-06 非法部分拒绝 | PASS | 遗漏/重复/未知 sourceId 丢弃保留来源 |
| QW-07 上下文只读 | PASS | context 修改拒收；写入区间无交叠 |
| QW-08 代理对/繁简 | PASS | 边界与 quote  strict；simplified 映射经 converter（协调器单测） |
| QW-09 坐标 | PASS（逻辑） | 全页并集裁剪，无局部改写；竖排/旋转页实测未验证 |
| QW-10 部分失败 | PASS | 失败组保留原文 + PARTIAL（协调器单测；整页 PARTIAL 在 U4） |
| QW-11 预算/退避 | PASS（逻辑） | 预算耗尽不调用；429 释放重取；截断预算内再分 |
| QW-12 未知不重发 | PASS（逻辑） | UnknownOutcomeException；费用 UNKNOWN（账本既有语义） |
| QW-13 账本归属 | PASS（逻辑） | chunk scope 显式携带；双书交错单测 |
| QW-14 缓存身份 | PASS | 身份化键；命中记复用不伪造 token |
| QW-15 人工优先 | PASS | U4 存储+调用双拒；协调器不覆盖（只增疑点） |
| QW-16 原子/超预算 | PASS（逻辑） | 视觉跳过计数；超预算 deferred 明确部分增强 |

## 4. 已知限制与未验证

- 前台/后台判定：enrich 侧统一按前台取槽（窗口中心优先的 plumbing 留待后续，
  当作已知简化记录，不称最优）。
- 真实 Qwen 延迟/费用/质量对比：无授权，未验证；开关默认关闭，线上行为不变。
- 超时分解为排队/发送/读体：调用方传剩余期限到 acquire；跨 chunk 总期限无中央
  扣减（预算次数有界，单次超时有界），如实记录。
- JEV 不在分组链路中（既有开关与授权不变）。
