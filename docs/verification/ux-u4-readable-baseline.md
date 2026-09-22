# U4：可读基线与真实阶段（纸页工坊）

- 基线：`ux-reader-remediation`；U0～U3 已提交；U4 后端先入库（`3eca1cc`）、
  行为测试入库（`3dd35ee`）。
- 本批依赖：U2；与 U3 协作投影接口（标题/摘要不受阶段影响）。
- 性质：先发布可读基线，再做可选增强；进度事件全部来自真实代码入口/出口。

## 1. 精确改动点

| # | 文件 | 改动 | 不得破坏 |
|---|---|---|---|
| 1 | 新增 `domain/ProcessingSnapshot.java` | schemaVersion/bookId/page/attemptId/snapshotVersion/lifecycle/stage/availability/publishedRevision/起止与进展时间/units/canRead/canStop/canRetry/messageCode；`idle()` | 计数非负、终态和≤total、心跳不算进展 |
| 2 | 新增 `service/ProcessingProgressService.java` | 阶段/单位事件聚合（begin/stage/plan/unitDone/inFlight/baselinePublished/finish/snapshot/latest）；512 上限终态淘汰；不保存正式页 | 时钟各自时间线，不跨钟相减 |
| 3 | `store/CommitOp.java` + `store/BookStore.java` | `JOB_BASELINE`（同 COMPLETE 资格）/`JOB_ENHANCEMENT`（+人工版本拒绝覆盖）；逐分支测试 | 旧操作校验 |
| 4 | `service/PageProcessor.java` | `processBaseline()`（同管线关增强）；`enrichBaseline()`（镜像同名辅助段，只返候选，不存页；空白无源跳过；广告分类一致） | 各 provider 分支与质量门语义 |
| 5 | `service/JobService.java`（随读） | 两阶段：基线发布可读→增强最多发布一次；增强回归/为空保留基线+PARTIAL；人工期间保存优先（存储层+调用层双拒）；取消分基线前后（已发布不恢复）；意图落盘/完成/重启对照；`setProgress` 可选注入 | U2 资格、缩水保护、候选留档 |
| 6 | `service/JobService.java`（批量） | 保持单次提交（`process()`），不改变来源质量门 | 同上 |
| 7 | `api/ReadingWindowResponse.java` | `readablePages`（窗口 READY 数，与 job completed 分离）；`PageState.processing`（可空） | 旧字段含义不变 |
| 8 | `service/ReadingWindowService.java` | 快照附带本页最新真实快照（`setProgress` 可选注入；无事件为 null） | 旧快照语义 |
| 9 | `static/app.js` | 状态槽显示真实阶段；固定计划显示“已核对 x/y 组”（耗时完成率不宣称）；基线可读文案；终态“已结束 X/Y·N 条需处理” | 脏稿/冲突/epoch 保护 |
| 10 | 测试 | `ProcessingProgressTest` 7 项（PROG-04/回归保留/人工优先/存储拒覆盖/契约/意图/可读计数）；`UxU4ProgressTest` 5 项 | 隔离合成夹具 |
| 11 | 本文档 | 验收映射 + 未验证项 | — |

## 2. 测试（actual）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -q -Dtest='studio.bookhtml.service.ProcessingProgressTest' test  # 7/7 PASS
mvn test -Dtest='studio.bookhtml.service.*Test'                        # 见提交输出
node --check app.js                                                    # OK
```

- 修复中发现的真实问题（已修）：增强回归门初版比较了 sourceRecords（增强不改
  来源，永远不触发），改为比较正文块文本量（`textChars`），显著缩水/掏空拒绝发布。
- 阶段计时：任务入队/派发/处理起止/提交/前端获取均为真实事件点；`timeToFirstReadable`、
  `timeToEnhanced` 可从 startedAt/lastProgressAt 时间线分别统计（跨钟不对减）。
- 真实模型耗时对比（first-readable p50/p95 vs enhanced）无授权测试：未验证。

## 3. 验收映射（PROG-01～10）

| ID | 状态 | 证据 |
|---|---|---|
| PROG-01 无计时百分比 | PASS | U1 删除 + U4 门禁（无 getConvertingPagePct） |
| PROG-02 阶段对应事件 | PASS | stage 调用点 + 前端 stageLabels |
| PROG-03 提交阻塞不宣称 | PASS | baselinePublished 只在 commit 成功后 |
| PROG-04 基线先行 | PASS | prog04（阻塞增强仍可读基线） |
| PROG-05 单位计数 | PASS | 契约测试 + units 渲染 |
| PROG-06 部分失败 | PASS（逻辑） | 终态计数文案；20 页固定批量实测留浏览器/集成 |
| PROG-07 动态窗口无全书 % | PASS（逻辑） | 当前页无 %；窗口范围与批量集合分别记录 |
| PROG-08 心跳不算进展 | PASS | inFlight 不碰 lastProgressAt（契约测试） |
| PROG-09 旧 attempt 晚到 | PASS（逻辑） | mayPublish 世代保护（U2）+ attemptId 隔离；专项晚到测试留 U7 |
| PROG-10 断网同步不确定 | PASS（既有） | 只读查询失败显示同步不确定（reading-window.js 未动） |

## 4. 未做（留给后续）

- U5：Qwen 分组并发（units kind=REVIEW_CHUNK 预留；QwenAssistCoordinator/RequestGate/Planner）。
- U6：画像/统计增量失效与前端缓存；离线端到端；性能收口。
- 真实耗时/费用对比：无授权，未验证。
- 批量路径两阶段：保持单次提交（有记录，如需扩展另行批次）。
