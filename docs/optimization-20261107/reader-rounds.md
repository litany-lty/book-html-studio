# 阅读前端优化 R001–R025

日期：2026-11-07。范围：线上静态前端与对应验证脚本。基线：`da838c32ee744f786070c45031a40099726f0045`。

本记录的轮次按实际问题目标划分，包含检查、判断、必要修改与验证；25 轮不等于 25 处产品改动。完成 25 个目标：7 轮修复产品问题，18 轮有针对性地检查并保留现有行为。未修改视觉设计、CSS、Java 或授权配置。

## 逐轮记录

### R001 目录定位是否会抢走新页面的焦点

- 目标：目录跳转后立即翻页或换书，旧双动画帧回调应失效。
- 发现：复现旧目录回调在新页执行滚动、聚焦；换书但页号相同时还会写入旧 `activeOutlineBlockId`。
- 修改：`static/app.js` 的 `goToOutlineEntry` 增加书籍请求代次、页请求代次、编辑代次和目录目标检查。
- 验证：`probe_reader_navigation_edges.mjs` 中两个负向场景修复前失败、修复后通过；正常当前目录目标仍滚动并聚焦。
- 状态：已修复，Node 定向验证通过。

### R002 同页后台重读是否会显示旧错误

- 目标：A→B→A 快速导航后，第一次 A 的同页后台读取不能影响新会话。
- 发现：成功分支检查请求代次，错误分支只检查书号和页号；请求也未传入取消信号。复现旧错误进入 `showError`。
- 修改：`static/app.js` 同页重读共享页面取消控制器，递增页请求代次；成功与错误使用同一会话判断，丢弃 `StaleRequest`。
- 验证：`probe_reader_navigation_edges.mjs` 的 A→B→A 失败响应测试红→绿，检查实际收到 `AbortSignal`。
- 状态：已修复，Node 定向验证通过。

### R003 进度滑杆一次操作是否提交两次

- 目标：鼠标、触摸或键盘提交原生 range 后只进行一次页面导航。
- 发现：同时注册 `change` 和 `pointerup`，一次释放会调用两次 `goToPage`；复现调用 `[4,4]`。
- 修改：`static/app.js` 保留原生 `change` 提交和 `input` 预览，删除重复 `pointerup` 提交。
- 验证：`probe_reader_navigation_edges.mjs` 依次触发释放及 change，修复后仅 `[4]`。这是事件绑定回归，浏览器原生手势复核交根代理集成执行。
- 状态：已修复，Node 定向验证通过；没有声称本子任务已完成原生触屏实测。

### R004 开启省流量后是否还留下旧预取

- 目标：省流量模式禁用新预取时仍取消旧窗口的数据读取。
- 发现：`saveData` 的提前 return 位于 `cancelReads` 之前；已有两个邻页 GET 未取消。
- 修改：`static/reading-window.js` 先取消旧读取，再判断是否禁止新预取。
- 验证：`probe_prefetch_cancellation.mjs` 先真正启动两个受控 GET，再切页并开启省流量；旧 signal 均 aborted，且没有新预取计时器。修复前失败、修复后通过。
- 状态：已修复，Node 定向验证通过。

### R005 正文发布更新是否保留正在阅读的位置

- 目标：正文块上方发生高度变化时，当前可见文字保持原视口位置。
- 发现：`reading-anchor.js` 用局部 `offsetTop` 比较滚动容器的 `scrollTop`，嵌套布局选择了不可见的首块；恢复还固定放在视口三分之一处。
- 修改：`static/reading-anchor.js` 用实际矩形记录可见块的视口偏移；恢复只补偿块位置变化；选区必须属于当前阅读容器，块消失时保留滚动位置回退。
- 验证：`probe_reading_anchor.mjs` 的嵌套布局复现红→绿：上方新增 100px 时滚动量仅补偿 100px；块消失的回退也通过。
- 状态：已修复，Node 几何回归通过。真实浏览器刷新位置复核由根代理执行。

### R006 离开页面的停止请求是否如实报告送达

- 目标：浏览器拒绝排队 beacon 时不声称停止请求已经发送。
- 发现：`stop({beacon:true})` 忽略 `sendBeacon` 的布尔值，且异常会直接退出。分别复现拒绝和抛错。
- 修改：`static/reading-window.js` 仅在返回 true 时显示“已发请求、送达未确认”；false 或异常继续现有普通停止 API；最终状态仍由该请求结果决定。
- 验证：`probe_reading_window_stop.mjs` 三种传输结果通过；false/异常两场景修复前失败。未添加自动处理请求，也未声称卸载阶段的普通请求必然送达。
- 状态：已修复，Node 定向验证通过。

### R007 疑字和广告定位的延迟聚焦是否跨页

- 目标：点击疑字或广告定位后换页、换书，旧帧回调不得抢占新页焦点。
- 发现：`renderCurrent` 生成的两个回调均未检查页面身份。测试复现换书后继续 focus/scroll。
- 修改：`static/app.js` 的回调检查书籍、页号、编辑代次及当前所选块/疑点；广告定位同时检查原图模式。
- 验证：`probe_reader_navigation_edges.mjs` 的两场景红→绿；该文件最终共 7 项通过。
- 状态：已修复，Node 定向验证通过。

### R008 当前正文是否被配置或全书元数据阻塞

- 目标：书架、配置、目录中的慢请求不应阻塞当前页正文。
- 检查：`app.js` 的 `init` 并行启动配置与书架并独立渲染；`selectBook` 取得只读 manifest 后先 `goToPage`，随后异步元数据/目录。
- 修改：无，保留当前页优先顺序。
- 验证：复用根代理已通过的 `browser_reader_load.py` 基线，包含慢配置、慢书架元数据、慢目录和配置失败场景；本子任务再次核对实际调用顺序。改后完整浏览器复核由根代理负责。
- 状态：检查完成，保留；基线浏览器证据复用，不冒充本子任务新跑通过。

### R009 快速翻页是否接受错误页或旧正文

- 目标：请求页身份严格匹配，旧请求不能覆盖当前页。
- 检查：`api.js` 要求页号精确等于请求的整数并包含 blocks 数组；`goToPage` 同时检查 request、书籍与 SessionGuard。
- 修改：除 R002 的同页补齐外，无额外更改。
- 验证：实跑 `probe_reader_api.mjs` 四项通过；错误页、数字字符串、缺 blocks 均拒绝；正常内容保持原对象。复用 load 基线的跨页/跨书延迟响应场景。
- 状态：检查完成，保留。

### R010 自动新正文是否覆盖未保存校对

- 目标：后台 READY 或手动刷新不能覆盖当前未保存修改或冲突。
- 检查：`currentPageProtected` 覆盖 dirty/conflict/in-flight save；`acceptReadyPage` 及 `refreshVisiblePage` 均先保护并记录 deferredReady。
- 修改：无，保持延后呈现。
- 验证：实跑 `probe_job_scope.js`，旧会话 6 组成功/失败隔离和当前会话 3 组正向更新通过；复用 load 基线的编辑中刷新测试。新浏览器 workbench 复核交根代理。
- 状态：检查完成，保留。

### R011 小范围元数据更新是否倒退或重复重绘

- 目标：相同快照保持静止，旧 revision 不回退 READY 数量与目录。
- 检查：`mergeReadingMetadata` 比较签名、页 revision 与 profileRevision，只更新受影响页。
- 修改：产品逻辑保持。修复现有 `probe_reading_window.js` 夹具遗漏的 `metadataEpoch`、`readingMetadataProfiles`；此前夹具无法执行到断言。
- 验证：实际运行 `node scripts/verification/probe_reading_window.js` 得到 `READING_WINDOW_PROBE_PASS`，覆盖 READY 42 增量、同快照无重绘、旧 revision 不回退。
- 状态：检查完成，保留产品行为；既有验证夹具修正。

### R012 邻页预取与 READY 窗口是否有界

- 目标：千页快照不启动全书读取，邻页读取不挤占当前页。
- 检查：预取等待 1 秒、范围前后各 5 页、两个 worker；READY 发布最多三个并发，并优先当前页。
- 修改：产品有界策略保留。`probe_reading_window.js` 的旧断言把现有两个预取 worker 误写成三个，修正为“一次旧 READY + 两次新预取”，删除不存在的第四个待响应对象。
- 验证：实跑 `probe_ready_window_bounds.mjs` 六项通过，覆盖大快照、越界/小数页号、错误页、非 READY 响应及在途合并；reading-window 探针通过。
- 状态：检查完成，保留产品行为；过时断言修正。

### R013 READY 读取失败是否还能重读且不重复处理

- 目标：第一次 GET 失败后，同 revision 仍可重读，不重复付费 POST。
- 检查：失败时清理 readyInFlight，seenReady 仅在成功接受后写入。
- 修改：产品保持；新增真实执行的 `probe_reader_ready_retry.mjs`。原 `probe_ready_fetch_retry.js` 只是判定契约，本轮没有把执行该占位文件当通过证据。
- 验证：第一次 GET 人工失败，第二次同版本 GET 成功；总读取 2 次、处理 POST 仅初始 1 次、发布 `[1,2]`，实跑通过。
- 状态：检查完成，保留；补足有意义的回归覆盖。

### R014 页缓存是否计入原文、证据和深层数据

- 目标：大 sourceRecords、翻译文字、候选项和几何数据不能绕过字节预算。
- 检查：`store.js` 的迭代估算遍历实际保留字段、用 WeakSet 避免别名重复计入，限制深遍历并拒绝 getter。
- 修改：无。
- 验证：实跑 `probe_reader_cache.mjs`，来源文本/简体/几何数组、循环数据、15,000 层结构、getter 不执行均通过。
- 状态：检查完成，保留。

### R015 有界缓存是否保护当前页并保留有用邻页

- 目标：12 页上限和字节预算下，过大预取不能清空可读缓存。
- 检查：`LruPageCache` 保护当前页、拒绝过大非当前条目、按 LRU 淘汰并准确重算字节。
- 修改：无。
- 验证：同一 `probe_reader_cache.mjs` 的实际淘汰场景通过：15 次插入保留当前页且最终 12 项，过大邻页不驱逐小邻页，替换/删除后计数正确。
- 状态：检查完成，保留。当前正在阅读的超大单页允许超预算是已有的可读性取舍。

### R016 刷新后恢复处理窗口是否只恢复停止能力

- 目标：恢复旧 session 不自动续租或重新派发，旧书响应不删除新书记录。
- 检查：`restore` 只读取状态；book/epoch/sessionId 共同隔离；没有调用 heartbeat 或处理 POST。
- 修改：无。
- 验证：实跑 `probe_reader_restore_scope.mjs` 六项通过，包括旧成功、旧失败、停止中的恢复和 A→B→A。
- 状态：检查完成，保留。

### R017 手机阅读与校对面板是否保持单一状态

- 目标：窄屏关闭校对回到可见按钮，目录和校对不同时覆盖正文，重复初始化无重复监听。
- 检查：`reader-mode.js` 由一个 applyMode 管理所有控制与 scrim，窄屏初始进入阅读。
- 修改：无。
- 验证：实跑 `probe_reader_mode.mjs` 六项通过，涵盖手机返回焦点、重复初始化、视口变化、模态框 Escape 优先级。
- 状态：检查完成，保留。这是 DOM 夹具验证，不等同真实手机像素验收。

### R018 专注翻页是否误吞选字、滚动和长按

- 目标：专注区边缘点击可以翻页，选字、拖动、长按和正在滚动时不能误翻。
- 检查：`focus-reading.js` 的短按判定、指针 ID、scope、选区、交互元素及页脚排除；事件未阻止原生默认行为。
- 修改：无。
- 验证：实跑 `probe_focus_reading.mjs` 三组边界/手势/命中宽度测试通过，包含首末页、NaN、320/390px 和滚动变化。
- 状态：检查完成，保留；不宣称本子任务完成真实触屏手势测试。

### R019 页处理进度是否来自真实阶段并隔离旧尝试

- 目标：进度不能当作文字准确率；旧服务实例、旧 attempt 和跨书响应不回退当前状态。
- 检查：`page-progress.js` 以服务端 lifecycle/stage/percent 渲染；版本单调、attemptSeq 和 serverInstanceId 隔离，后台页面暂停轮询。
- 修改：无。
- 验证：实跑 `probe_page_progress.mjs` 通过状态、跨书/attempt、响应体期限和相邻状态标记；实跑 `probe_api_lifecycle.mjs` 的 5 个完整响应生命周期场景通过。
- 状态：检查完成，保留。

### R020 任务停止或中断是否被误写成全部成功

- 目标：errors 为空也不能把 CANCELLED/INTERRUPTED/PARTIAL 变为处理完成。
- 检查：`job-summary.js` 按真实终态和已结束数量组织说明，明确文字仍须核对。
- 修改：无。
- 验证：实跑 `probe_job_summary.mjs` 四项通过，包括无错误但中断、部分完成、非法/负数/无限计数。
- 状态：检查完成，保留。

### R021 共享书架是否按本浏览器的阅读位置继续

- 目标：不把另一浏览器或越界页号当成本设备阅读记录。
- 检查：`bookshelf.js` 的 readingEntry 校验安全整数和书籍页数；shelfEntries 从所提供的本地偏好计算最近阅读。
- 修改：无。
- 验证：实跑 `probe_lan_bookshelf.mjs`，两套浏览器偏好分别排序，负数/零/小数/越界/Infinity 回到开始阅读。
- 状态：检查完成，保留。

### R022 返回书架的旧刷新是否擦除新上传

- 目标：并发返回只读一次列表，慢列表不能覆盖期间的新书籍列表。
- 检查：`createShelfRefresh` 合并在途 Promise，并用启动时列表身份判断是否仍拥有更新权。
- 修改：无。
- 验证：实跑 `probe_lan_bookshelf.mjs` 的并发合并、新上传保护、失败/畸形响应保留及下次显式重试通过。
- 状态：检查完成，保留。

### R023 默认局域网阅读是否出现多余配对设置

- 目标：可信局域网默认 READ/UPLOAD 无额外配对；界面能力不能推出编辑或付费权限。
- 检查：`lan-reader.js` 按 capability 隐藏不需要的访问设置；已有有效能力不会每次上传前再读状态。
- 修改：无。
- 验证：实跑 `probe_lan_bookshelf.mjs`，默认 LAN_SHARED 和 LOOPBACK 无配对设置，上传能力不隐含 EDIT/PAID/MANAGE；同时 `probe_auto_reading_policy.mjs` 五项通过。
- 状态：检查完成，保留；服务器真实局域网授权测试由根代理负责。

### R024 书签与位置存储是否隔离且能容忍浏览器限制

- 目标：书签和位置仅存本浏览器、按书隔离；禁用/损坏 localStorage 不阻塞阅读。
- 检查：`store.js` 以书籍 key 分离书签与偏好，解析失败回空值，写入失败不影响阅读。
- 修改：产品保持；新增 `probe_reading_preferences.mjs`。
- 验证：实跑两项通过，检查 A/B 书签与滚动位置隔离、去重排序、损坏 JSON/非法页号过滤，以及存储拒绝读写。
- 状态：检查完成，保留；没有引入服务器同步。

### R025 字体设置是否本地可用并避免未知选项破坏正文

- 目标：本地字体白名单、存储异常与重复初始化不影响阅读控制。
- 检查：`reader-fonts.js` 对六种已支持值归一化并使用现有本地字体资源；初始化有重复控制保护和清理。
- 修改：无。
- 验证：实跑 `probe_reader_fonts.js`，输出 `READER_FONTS_PASS`，校验 325 个本地资源、持久化、白名单、控制同步和存储回退。
- 状态：检查完成，保留；未改变字体样式设计。

## 验证汇总与边界

以下命令在本子任务实际执行成功（路径均相对于仓库）：

```bash
node --test scripts/verification/probe_reader_navigation_edges.mjs scripts/verification/probe_prefetch_cancellation.mjs scripts/verification/probe_reading_anchor.mjs scripts/verification/probe_reading_window_stop.mjs scripts/verification/probe_reader_ready_retry.mjs scripts/verification/probe_reading_preferences.mjs
node --test scripts/verification/probe_reader_api.mjs scripts/verification/probe_reader_cache.mjs scripts/verification/probe_reader_restore_scope.mjs scripts/verification/probe_lan_bookshelf.mjs scripts/verification/probe_reader_mode.mjs scripts/verification/probe_auto_reading_policy.mjs scripts/verification/probe_api_lifecycle.mjs scripts/verification/probe_focus_reading.mjs scripts/verification/probe_ready_window_bounds.mjs scripts/verification/probe_page_progress.mjs scripts/verification/probe_job_summary.mjs scripts/verification/probe_scan_empty.mjs
node scripts/verification/probe_reading_window.js
node scripts/verification/probe_reader_fonts.js
node scripts/verification/probe_job_scope.js
node --check src/main/resources/static/app.js
node --check src/main/resources/static/reading-window.js
node --check src/main/resources/static/reading-anchor.js
```

- 新增六个 mjs 文件合计 **16/16** 子测试通过；上述既有 mjs 组 **62/62** 通过。空白页扫描是额外回归，没有算成第 26 轮。
- 现有 reading-window 夹具的两个过时前提已修正后实跑通过。它原先失败后存在活计时器而滞留；首次失败的进程已中断，后续通过运行正常退出。
- 本子任务尝试启动 `browser_reader_load.py` 与 `browser_reader_workbench.py`，但当前 exec 沙箱在 Chromium 创建 socket 时返回 `Operation not permitted`，产品页面未加载。这两次不能算产品通过或产品失败；根代理已接手其可用环境中的改后浏览器复核。
- 根代理先前已提供 browser_reader_load 完整基线通过证据，可用于现有行为检查；不得把这份基线当成所有新增修复的浏览器验收。
- 未发起真实模型/OCR 请求、真实书籍修改或付费操作；只使用 Node 受控夹具与合成浏览器尝试。

### 根代理集成复核补充

根代理在上述产品修改后，使用其可用的 Python Playwright 环境重新运行 `browser_reader_load.py` 与 `browser_reader_workbench.py`，两组全部通过。此项是改后真实浏览器集成证据（由根代理执行并回报），补充了本子任务受限沙箱不能启动 Chromium 的缺口；具体日志及浏览器断言结果由根代理纳入总报告。

## 交付路径

- 产品：`src/main/resources/static/app.js`、`src/main/resources/static/reading-window.js`、`src/main/resources/static/reading-anchor.js`。
- 新增探针：`scripts/verification/probe_reader_navigation_edges.mjs`、`probe_prefetch_cancellation.mjs`、`probe_reading_anchor.mjs`、`probe_reading_window_stop.mjs`、`probe_reader_ready_retry.mjs`、`probe_reading_preferences.mjs`。
- 已有探针修正：`scripts/verification/probe_reading_window.js`。
- 本报告：`docs/optimization-20261107/reader-rounds.md`。
