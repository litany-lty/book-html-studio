# U1：阅读区安静化（纸页工坊）

- 基线：`ux-reader-remediation` 基于 `origin/main@148e9c5`；U0 已提交（`550dbe9`）。
- 本批依赖：U0。可不等待 Qwen 拆分先交付。
- 性质：前端阅读优先 + 假进度删除；后台仍可用旧处理结果。真实阶段（U4）与任务安全（U2）未动。

## 1. 精确改动点

| # | 文件 | 改动 | 保留 |
|---|---|---|---|
| 1 | `static/app.js` | 删除 `getConvertingPagePct()`、`convertingState.timer/startTime`、100ms 标题刷新；转换中标题改为“正在识别第 N 页/正在二次处理第 N 页”，完成只写“转化完成”（无百分比） | 脏草稿/conflict/save-in-flight/epoch 保护；`acceptReadyPage` 旧版本隔离 |
| 2 | `static/app.js` `renderQuality` | 处理中徽标去脉冲点与百分比；默认阅读 `quality-detail` 置空，技术细节写入新增 `#quality-diagnostics` 按需查看 | 徽标标签语义；后台任务计数（真实已结束数）仍在诊断区 |
| 3 | `static/app.js` `renderPageMessage` | 当前页进度卡去百分比/填充宽度，改不确定条；文案“原稿可以继续阅读”“未知进度不显示百分比” | 失败/空白语义由 `reader.js` 提供；重试入口保留 |
| 4 | `static/app.js` 任务入口 | `#reading-window-open` 改为唯一安静入口“任务·N”（预留宽度更新，无呼吸灯/流光）；失败页仅设 `data-attention` 与 title，不闪烁 | 停止/刷新/手动重试失败页/更新本页按钮与对话框能力 |
| 5 | `static/app.js` 授权文案 | 批量提交 confirm 改写为 4.3 形状：服务名+页范围+图片外发+可能费用+原稿保留；Qwen 另计注明 | 二次确认（覆盖）保留；U2 再拆回退/并行/全书授权 |
| 6 | `static/app.js` | 接入 `initReaderMode()`；`acceptReadyPage` 改用阅读锚点恢复（同源块/邻近回退，fallback scrollTop） | 选择文字/编辑保护（`currentPageProtected`→待应用）不变 |
| 7 | `static/styles.css` | `.reading-window-bar` 改静态内联（删 fixed/z-index/bottom/pulse/补偿 padding）；删 `pulseDot/pulse-glow/pulse-dot`；进度卡改不确定条；删两处媒体查询浮层定位与 174/230px 补偿 | 字体/原稿比例/触控尺寸/可见焦点；保存冲突栏；`reading-progress` 底栏不动 |
| 8 | `static/styles.css` | 新增 U1 外壳：`app-shell` 网格行、工具栏最小 52px、底栏最小 44px+安全区、正文 `36em` 上限、桌面阅读模式网格、`prefers-reduced-motion` 禁动画 | 高度均为最小高度，不固定裁切；窄屏抽屉行为不变 |
| 9 | `static/index.html` | `body[data-reader-mode=reading]` 默认阅读；`#paper` 去整篇 `aria-live`（状态由 `#page-message/#page-save-status` 播报）；视图改名“原版排布/阅读”；新增 `#quality-diagnostics`；新增 `#proof-toggle`；翻页+滑块裹入同一 `footer.reader-footer-u1` | 全部既有 ID 保留；导入/导出/设置/书架/证据/保存/停止能力 |
| 10 | `static/reader.js` | `statusMessage`：失败/处理中/未识别文案按 4.2 改写；空白/零块页“空白页或仅含插图”；>3 条 warning 收敛为一句话；`qualityOf` 标签“正在识别本页/有待核对文字”，细节保留供诊断；新增 `qualityDiagnostics`；阅读流空态与原版排布说明改名 | 原文/疑点原图/复杂视觉块/来源定位；辅助语义（F13）留待 U3 |
| 11 | 新增 `static/reader-mode.js` `static/task-center.js` `static/reading-anchor.js` | 模式切换（持久化+回焦+Esc+窄屏默认阅读）、唯一入口（后台不自动打开/不抢焦点）、锚点记录/恢复 | 均不调用云识别与页面 API（测试断言） |
| 12 | `scripts/verification/cdp_reader_ux.py` | 桌面流程：先断言默认阅读模式（校对栏隐藏），再显式点击 `#proof-toggle` 进入校对，后续保存/冲突/证据断言不变 | 保存/冲突/旧响应隔离断言全部保留 |
| 13 | 新增 `scripts/verification/probe_reader_attention.js` | `attentionOverlap()` 几何函数：自动任务层与阅读视口交叠面积+命中抽查 | 用户主动打开的详情不计入 |

## 2. 测试

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -q -Dtest='studio.bookhtml.service.UxU1ReaderQuietTest,studio.bookhtml.service.UxU0BaselineReproTest' test
# EXIT:0（7+5 项，U0 的 F01/F02 已退役转由 U1 门禁覆盖）
node --check static/app.js static/reader.js static/reader-mode.js static/task-center.js static/reading-anchor.js static/reading-window.js
python3 -m py_compile scripts/verification/cdp_reader_ux.py
```

- 全量 Java 回归：`mvn test`（见提交时输出）。
- 浏览器几何（UX-02/UX-03）：需独立端口合成书 + 真实任务状态运行 `cdp_reading_window.py` 与新探针；空闲页截图不计证据。U1 提交时若无运行环境，记为“未执行”，不伪报 PASS。

## 3. 与验收的对应（本批覆盖 UX-01～UX-16 中的静态部分）

- UX-01 默认阅读模式：`data-reader-mode=reading` + 脚本断言。
- UX-02/UX-03/UX-04：浮层/脉冲/补偿删除 + 探针函数；真实任务态几何待浏览器运行。
- UX-05 触控目标：未缩小既有 44px（`save-dock` 按钮、移动端按钮规则保留）。
- UX-06 固定高度裁切：工具栏/底栏只设最小高度。
- UX-07 回焦：模式切换回焦触发元素；阅读设置 Esc 回焦测试保留。
- UX-08/UX-09 选择与草稿：锚点恢复 + `currentPageProtected` 待应用路径保留。
- UX-12 长 warning：收敛为一句话，详情可查。
- UX-13 空白/纯图：轻量空态，不报失败、不诱导重试。
- UX-14/UX-15 播报与动效：去整篇 aria-live、去持续脉冲、reduced-motion 禁动画。
- UX-16 校对回归：脚本显式进入校对，保存/冲突断言保留，待运行。

## 4. 未做（留给后续批次）

- U2：安全 reprocess、attempt 登记、取消恢复、通道授权过滤、GET/POST 分离、窗口统一。
- U3：书眉投影、原文/辅助独立、随读增量目录。
- U4：真实阶段事件与 ProcessingSnapshot、baseline/enhancement 分离。
- 真实任务态浏览器几何与 200% 缩放/屏幕阅读器探针：有运行环境即补测。
