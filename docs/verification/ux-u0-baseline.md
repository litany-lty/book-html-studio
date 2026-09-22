# U0 基线：固定审查 SHA 与可重复失败证据（纸页工坊）

- 审查基线：`main@148e9c5d3db967d525db81d1ebbe08025201da89`（2026-09-22T01:37:42Z）
- 执行分支：`ux-reader-remediation`（由 `origin/main` 建出，未合并旧开发分支）
- 本文档性质：实施前基线记录，不是整改完成证明。真实性能与准确性均未验证。

## 1. 基线固定（actual command/output）

```bash
$ git status --short
# （空：工作区干净）

$ git branch --show-current
main

$ git rev-parse HEAD
148e9c5d3db967d525db81d1ebbe08025201da89

$ git rev-parse --verify origin/main
148e9c5d3db967d525db81d1ebbe08025201da89

$ git log -5 --oneline origin/main
148e9c5 Optimize reading UI and filter margin page-number symbols
6214e9f Improve book reliability, evidence review and adaptive reading
1d10860 fix: verify JEV CI and add source-only pilot labeling board
fab1514 JR harden: save-before-offer race, poll reschedule, blank-candidate gap, browser matrix, 100-concurrent drain
bd345c3 JR integrity fix: scope binding, single-attempt vision, budget ledger, CAS, calibration, eval runner, CI evidence

$ git diff --stat 148e9c5d3db967d525db81d1ebbe08025201da89 origin/main
# （空：远端 main 与审查 SHA 文件内容一致）

$ git diff --stat origin/main HEAD
# （空：本地 HEAD 与远端一致）
```

后续执行：

```bash
$ git switch -c ux-reader-remediation origin/main
# Switched to a new branch 'ux-reader-remediation'
```

- JDK：`java version "17.0.17" 2025-10-21 LTS`（`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk`）
- Maven：3.9.12（注意默认 `mvn -version` 显示 Java 25 runtime；测试均显式 `export JAVA_HOME=jdk-17` 后执行）
- 工作区：基线核对时 `git status` 干净，无未提交用户修改，无需冲突处理。

## 2. 本轮复核的静态证据（与规范第 2 节一致）

| 规范编号 | 复核结果 |
|---|---|
| F01 假 92% | `app.js:390-394` `getConvertingPagePct()` 含 `Math.min(92, ...)`；`app.js:490-499` 100ms `setInterval` 刷新标题。仍存在。 |
| F02 悬浮任务条 | `styles.css:267-308` `.reading-window-bar` 为 `position:fixed; z-index:8; bottom:54px` + `pulseDot` 动画。仍存在。 |
| F04/F06 目录 | `OutlineService.fromPages()` 仅收集 READY heading + 部分目录页排除；`ReadingWindowService:426` 随读 snapshot 对单页调用 `fromPages(List.of(page))`。仍存在。 |
| F07/F08 Qwen 大请求 | `QwenLayoutClient` 整页图+区域图+全块同请求；`QwenTocRecoveryService` 多目录区域同请求。仍存在（未改）。 |
| F09 重试清空 | `ReadingWindowService:122-138` `retryPage()` 直接 `store.writePage(... Page.pending(...))`。仍存在。 |
| F10 取消恢复 | `JobService:63-72` `cancelReadingPage()` 立即 `activeReserved.remove`；`stillCurrentReserved()` 以 `cancelled` 返回 false。仍存在。 |
| F11 通道授权 | `ReadingWindowService:206-220` `getEnabledChannels()` 自动加入已配置次通道。仍存在。 |
| F13 简体推测 | `reader.js:58` `(assisted \|\| (script !== 'original' && issue.inferredText))` 可显示推测。仍存在。 |
| F17 ready 标记 | `reading-window.js:118-122` 先 `seenReady.set` 再 `readyQueue.push`；`runReadyWorkers` 失败路径不清理标记。仍存在。 |

## 3. 可重复复现入口

### 3.1 Java 基线复现测试（已运行 PASS）

文件：`src/test/java/studio/bookhtml/service/UxU0BaselineReproTest.java`

```bash
$ export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
$ export PATH="$JAVA_HOME/bin:$PATH"
$ mvn -q -Dtest=studio.bookhtml.service.UxU0BaselineReproTest test
# EXIT:0（quiet 模式无 ERROR；7 tests pass）
```

覆盖（均为隔离合成夹具，不碰日常数据目录，不调用付费模型）：

1. `u0f01_fake92ProgressFormulaStillPresent` — 断言 `Math.min(92` / `getConvertingPagePct` / 100ms 刷新存在。
2. `u0f02_floatingTaskBarStillOverlaysReadingViewport` — 断言 fixed + bottom 54px + 脉冲存在。
3. `u0f04_duplicateRunningHeaderFloodsOutline` — 10 页相同书眉 + 2 真章节 → 当前返回 12 条（含 10 书眉），复现目录被书名挤满。
4. `u0f09_readyRetryPendingWipesReadableBlocks` — `Page.pending()` 清空 blocks，复现重试丢失当前可读快照的风险模型。
5. `u0f17_readySeenMarkedBeforeGetSuccess` — 先记 `seenReady` 再入队、失败不清理，复现同 revision 失败后不再 GET。
6. `u0f11_secondaryChannelAutoEnabledWhenConfigured` — 次通道自动加入，复现“已配置即并行外发”风险。
7. `u0controllableDouble_countsRequestsWithoutNetwork` — 本地替身：2 个阻塞调用在途计数=2，可释放、可断言；证明后续 U2/U5 的并发计数与取消收尾可观测。`@TempDir` 隔离目录。

### 3.2 本地模型替身说明

- 本轮沿用仓库既有可注入 seam：`ReadingWindowQaServer`（门控 Mockito `PageProcessor`）+ `ReadingWindowServiceTest` 的 mock processor + 上述计数 latch。
- 该替身可控制延迟（`CountDownLatch` 阻塞）、429/截断/超时/乱序（后续 U2/U4/U5 在 `QwenTextReviewClient`/`QwenRequestGate` 测试中扩展）、GET 失败（`reading-window.js` 探针 `probe_ready_fetch_retry.js` 待 U2 新增）、取消收尾（`future.cancel(true)` + 物理槽释放）。
- 真实书籍、真实模型延迟、真实费用均未调用，明确标为未验证。

### 3.3 浏览器复现（U0 只读核对，未改日常数据）

- 既有脚本 `scripts/verification/cdp_reader_ux.py` + `SeedBook`（3 页合成书 `aaaaaaaa-1111-…`）继续作为 U1 的几何/遮挡验收入口。
- U0 未对日常 18765 端口执行任何写操作；后续浏览器脚本一律要求独立端口 + 合成书身份校验。

## 4. U0 通过标准核对

- [x] 替身请求次数可断言（`u0controllableDouble` PASS）。
- [x] 测试环境明确不是日常数据目录（`@TempDir` + SeedBook 独立端口约束）。
- [x] 代码审查基线可追溯（本节文件行号 + 测试内文件读取断言）。

## 5. 已知未复现/待时序确认项

- F10 的 latch 时序（取消后物理槽提前释放）需 U2 用 `CountDownLatch`/可控 Future 排列“发送—取消—仍占用—收尾—释放”，不靠 sleep 猜测。本轮仅静态确认代码路径。
- 真实等待拆分（OCR/Qwen/结构校验/磁盘提交/前端 GET）需 U4 的阶段计时落点后测量；本轮不把全部等待归咎于 Qwen。
- 5 本真实书籍/100 页标题精确率/召回率样本尚未授权，标为 BLOCKED/未验证。

## 6. 下一步（U1 入口，不在本提交实现）

U1 精确改动点预告（见规范 §13/U1）：新外壳与阅读/校对模式、唯一任务入口、删除 `getConvertingPagePct`+100ms 刷新、质量细节移入详情、页码导航合并、空白/纯图正确文案。禁止只调透明度/把 92 改 99/删除停止按钮。
