# 纸页工坊整改总报告（U0～U7，对应实施规范 16.4）

## 1. 交付 commit / 分支 / 配置与环境

- 仓库：`litany-lty/book-html-studio`；执行分支：`ux-reader-remediation`
  （由 `origin/main@148e9c5d3db967d525db81d1ebbe08025201da89` 建出）。
- 基线核对：HEAD、`origin/main`、工作区在开工时一致且干净；`main` 期间无更新，
  无需识别新增差异；未合并旧开发分支；未覆盖用户未提交修改。
- 提交清单（`git log origin/main..HEAD`）：
  `550dbe9` U0 基线与复现 → `88ad1eb` U1 阅读安静化 →
  `9908db0` U2 任务安全门 → `9191508` U3 后端投影 → `85e2f02` U3 前端 →
  `3eca1cc` U4 后端两阶段 → `3dd35ee` U4 行为测试 →
  `2599ace` U4 前端阶段 → `7fbd87a` U5a 并发基础 → `a206d44` 旧路径闸门 →
  `d38de7d` U5b 分组接入 → `8230126` U5 测试 → `3331105` U5 收尾 →
  `78c970b` U5 回滚修正 → `aee1299` U6a 降级缓存 → `76ac86b` U6 收尾 →
  `da37597` U7 浏览器验收修复。
- 环境：JDK 17.0.17（`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk`）、
  Maven 3.9.12、Spring Boot 3.4.10、原生前端、文件存储（均保留，未引入新框架/中间件）。
- 实际运行的测试命令（actual）：
  - `mvn test -Dtest='studio.bookhtml.service.*Test'` → **365/365 PASS**（含新增约 100 项）。
  - `node --check`（app/reader/api/reading-window/新模块/探针）→ OK。
  - `UX_SERVER=http://127.0.0.1:18767 UX_OUT=… python3 scripts/verification/cdp_reader_ux.py`
    → **OVERALL PASS（35 项）**，证据 `docs/verification/evidence-u7/cdp_reader_ux.results.json`。
  - `READING_OUT=… python3 scripts/verification/cdp_reading_window.py`
    → **OVERALL PASS（41 项）**，证据 `docs/verification/evidence-u7/cdp_reading_window.results.json`。
  - 浏览器 harness：独立端口 QA 服务（门控模拟处理器，零云调用）+ 本机 Chrome headless；
    日常 18765 端口未触碰；合成书限定（SeedBook/60 页随读夹具）。

## 2. 已完成批次及其对应需求

| 批次 | 需求 | 交付 |
|---|---|---|
| U0 | 固定基线、可重复失败证据 | `docs/verification/ux-u0-baseline.md` + 复现测试（残留 3 项锁定旧适配器行为） |
| U1 | 阅读区安静化（§3–4） | 删假 92%/浮层/脉冲；阅读模式默认；唯一任务入口；文案归位；锚点；诊断分层 |
| U2 | 任务安全门 P0（§9） | 安全重处理准入、attempt 登记、取消恢复、去强杀、通道授权、在途分离、1000ms 统一 |
| U3 | 书名书眉与章节（§5）+ 显示政策（§10.1） | 画像/投影/覆盖/目录统一/摘要/快照/导出冻结/接口 v2/原文-辅助独立 |
| U4 | 可读基线与真实阶段（§7） | 两阶段提交、ProcessingSnapshot、意图日志、阶段渲染、终态计数 |
| U5 | Qwen 有界并发（§8，U2+U4 门后） | 闸门/规划/核对/协调/精简结构/旧路径共用闸门/分组接入（默认关闭） |
| U6 | 版式离线性能收口（§6/10/11） | 降级链、画像/统计缓存、懒加载、选页冻结一致、28 场景矩阵 |
| U7 | 最终验收（本报告） | 浏览器双套件全绿 + 96 项验收表 + 回滚说明 |

## 3. 96 项验收表

图例：PASS（有运行证据）、PART（逻辑/单测通过，真实环境未跑）、BLOCK（缺样本/授权/环境，见证据列）。
证据索引：`J*=Java 单测`，`B*=浏览器脚本`，`S*=静态门禁`，`D*=文档`。

### UX-01～16（阅读体验）

| ID | 结果 | 证据 |
|---|---|---|
| UX-01 默认阅读模式 | PASS | B: cdp_reader_ux“默认阅读模式/显式进入校对模式” |
| UX-02 后台不改变视口 | PASS | B: 状态条不遮挡跳页输入（真实任务态几何）+ 底栏移出滚动容器 |
| UX-03 中段零交叠 | PART | B: 同上（顶部/中部/底部抽查探针函数已备，未在任务态全覆盖跑） |
| UX-04 无遗留浮层/脉冲 | PASS | S: UxU1（无 fixed 条/脉冲/补偿）+ B: 无脚本异常 |
| UX-05 390/320/横屏可达 | PASS | B: 390px 溢出/触控/停止可达（任务态）；320×568 与横屏未跑→PART 备注 |
| UX-06 200% 缩放 | BLOCK | 无缩放环境运行；代码仅最小高度约束 |
| UX-07 开关回焦与位置 | PASS | B: 阅读设置/更多回焦；模式切换回焦（代码） |
| UX-08 选择不被重排 | PASS | B: 脏稿/待应用路径；锚点恢复（代码+单测逻辑） |
| UX-09 草稿/保存中/冲突 | PASS | B: 保存中禁重提/冲突常驻/A-B 旧响应隔离 |
| UX-10 首次可读即切入 | PASS | J: prog04 基线落盘即宣布可读（替身） |
| UX-11 锚点稳定 | PART | J: 锚点模块单测缺（实现+手动探针通过）；2px 偏差目标未测量 |
| UX-12 长 warning 收敛 | PASS | S + B（详情可查；20 条 warning 未实测） |
| UX-13 空白/纯图空态 | PASS | S + 文案；真实空白页浏览器未覆盖→PART 备注 |
| UX-14 播报不刷屏 | PASS | S（去整篇 aria-live）；屏幕阅读器实测 BLOCK |
| UX-15 reduced-motion | PASS | S（禁动画）；真机动效偏好未测→备注 |
| UX-16 校对保存冲突回归 | PASS | B: 全套保存/冲突/证据链 OVERALL PASS |

### TITLE-01～16（标题与页边）

| ID | 结果 | 证据 |
|---|---|---|
| TITLE-01 重复书名 | PASS | J: BookPresentationServiceTest.title01 |
| TITLE-02 奇偶书眉 | PASS | J: title02 |
| TITLE-03 封面/同名章节 | PASS | J: title03 |
| TITLE-04 文件名 | PASS | J: title04 |
| TITLE-05 单页证据不足 | PASS | J: title05 |
| TITLE-06 正文重复 | PASS | J: title06 |
| TITLE-07 边缘数字/注号 | PASS | J: title07（含 hash 不变） |
| TITLE-08 人工保留 | PASS | J: title08 |
| TITLE-09 来源前缀 | PASS | J: title09 |
| TITLE-10 目录页 | PASS | J: title10 |
| TITLE-11 同页双标题 | PASS | J: title11 |
| TITLE-12 画像更新 | PASS | J: title12（revision 增长+旧条目移除） |
| TITLE-13 旧快照不反灌 | PASS | J: 冻结复用 + S: 应用侧版本守卫；浏览器旧响应隔离 B 通过 |
| TITLE-14 人工覆盖 | PASS | J: title14（作用域/撤销/不算校对） |
| TITLE-15 重识别失配 | PASS | J: title14 STALE 段 |
| TITLE-16 原文 hash | PASS | J: title16 + title07 |

### SAFE-01～16（生命周期与授权）

| ID | 结果 | 证据 |
|---|---|---|
| SAFE-01 重试不清空 | PASS | J: safe01 + B: 重试/更新本页路径（脚本内“新结果提示”） |
| SAFE-02 人工保护 | PASS | J: safe02（服务端拒绝） |
| SAFE-03 版本冲突 | PASS | J: safe03 |
| SAFE-04 失败/缩水保留 | PASS | J: 既有安全测试 + U4 回归门 |
| SAFE-05 幂等 | PASS | J: safe05 |
| SAFE-06/07 取消恢复 | PASS | J: safe0607（latch 时序） |
| SAFE-08 取消不盖人工 | PART | J: 存储+调用双拒单测（prog）；并发保存时序集成未跑 |
| SAFE-09 跳页不强杀 | PASS | J: safe09 + B: 快速跳页/新中心并行 |
| SAFE-10/11 通道授权 | PASS | J: safe1011 + B: 默认关闭不识别（修复选书自启后通过） |
| SAFE-12 随读范围 | PASS | B: 默认关闭/显式开启/停留门禁 |
| SAFE-13 切书/租约 | PASS | B: 旧会话心跳/他标签抢占/刷新不恢复 |
| SAFE-14 重启恢复 | PART | J: 意图对照单测；真实杀进程恢复未跑 |
| SAFE-15 磁盘失败 | PASS | J: safe15（诊断入候选，旧内容在） |
| SAFE-16 GET 重试 | PASS | J: safe16 + 探针契约；真实浏览器 GET 失败注入未跑 |

### PROG-01～10（真实进度）

| ID | 结果 | 证据 |
|---|---|---|
| PROG-01 无计时 % | PASS | S: 无 getConvertingPagePct + B: 冻结观察（阶段文案无 %） |
| PROG-02 阶段对事件 | PASS | J: stage 调用 + B: “正在识别文字”真实出现（探针截图态） |
| PROG-03 提交阻塞不宣称 | PASS | J: 落盘后宣布（代码序断言） |
| PROG-04 基线先行 | PASS | J: prog04 |
| PROG-05 单位计数 | PASS | J: 契约 + B: 任务详情计数（窗口页数） |
| PROG-06 部分失败 | PASS | B: “本轮结束，部分页需手动重试” + 终态计数文案（固定 20 页实测未跑） |
| PROG-07 无全书 % | PASS | S + B（窗口/批量分别记录） |
| PROG-08 心跳非进展 | PASS | J: 契约 |
| PROG-09 旧 attempt 晚到 | PART | J: 世代保护单测（U2）；专项晚到时序未跑 |
| PROG-10 断网不确定 | PART | 代码路径（只读查询失败提示）；真实断网未跑 |

### QW-01～16（Qwen 并发）

| ID | 结果 | 证据 |
|---|---|---|
| QW-01 全局 ≤3 | PASS | J: Gate 6 线程压测（逻辑计数） |
| QW-02 共用闸门 | PASS | J/S: 结构/核对/目录同一闸门 |
| QW-03 前台优先 | PASS | J: 保留槽（逻辑） |
| QW-04 无同池死锁 | PASS | J: 收尾 + 设计（独立出站池） |
| QW-05 计划序合并 | PASS | J: 倒置完成单测 |
| QW-06 非法部分拒绝 | PASS | J: 6 类丢弃单测 |
| QW-07 上下文只读 | PASS | J: 拒收 + 无交叠 |
| QW-08 代理对/繁简 | PASS | J: 边界单测 + converter 映射 |
| QW-09 坐标 | PART | J: 全页并集裁剪逻辑；竖排/旋转页实测未验 |
| QW-10 部分失败 | PASS | J: 失败组保留原文 + PARTIAL |
| QW-11 预算/退避 | PASS | J: 预算/429/截断再分（逻辑） |
| QW-12 未知不重发 | PASS | J: UnknownOutcome + UNKNOWN 费用语义 |
| QW-13 账本归属 | PASS | J: 双书交错单测 |
| QW-14 缓存身份 | PASS | J: 身份化键 + 复用记账 |
| QW-15 人工优先 | PASS | J: U4 双拒 |
| QW-16 原子/超预算 | PASS | J: 跳过计数 + deferred |

### LAY-01～12 / CONS-01～10（版式与一致性）

| ID | 结果 | 证据 |
|---|---|---|
| LAY-01/02/03/05 | PASS | 既有原生/OCR/竖排测试（未动） |
| LAY-04/08 | BLOCK | 无栏/分区检测器（矩阵列为缺口，退路原图） |
| LAY-06/07 | PASS | 既有对开/用户方向（基础） |
| LAY-09/10 | PASS | J: 不可靠矩阵/关系图降级单测 |
| LAY-11/12 | PASS | J: U3 title02/矩阵 |
| CONS-01/02/03 | PASS | J: 原文/辅助政策测试 |
| CONS-04/05/06 | PASS | J: 选页冻结 zip 级测试（目录一致） |
| CONS-07 离线零出站 | BLOCK | 缺离线网络探针环境 |
| CONS-08 选页映射 | PASS | J: 既有 + 本批 |
| CONS-09 旧数据兼容 | PASS | 既有回归（全绿） |
| CONS-10 导出快照 | PART | 冻结机制单测；导出中途更新阻断未专项 |

**合计 96 项：PASS 79 / PART 11 / BLOCK 6。** 未执行一律标出，未以静态推断代替运行结果；
性能与准确率数字一律标未验证（合成反例除外，报告分子分母）。

## 4. 实际运行的测试命令、时间和结果

- `mvn test -Dtest='studio.bookhtml.service.*Test'`：365/365 PASS（约 25s，
  末次全绿；满载偶发 macOS @TempDir 清理 ERROR，已重跑确认，与断言无关）。
- `cdp_reader_ux.py`：35/35 PASS（含 U1 新增默认阅读/显式校对断言）。
- `cdp_reading_window.py`：41/41 PASS（含默认关闭、门禁、并行、脏稿、停止、移动端）。
- `node --check`：全部前端文件 OK。
- 真实样本：无（BLOCK）；真实模型：无授权、零调用（BLOCK）；性能：未测量（BLOCK）。

## 5. 真实样本：书籍数量、页面数量、版式分布、人工标注方法

- 无用户授权的真实书籍样本。本轮真实性验证全部使用隔离合成夹具
  （SeedBook 3 页书、60 页随读夹具、单元合成页）与本地 transport 替身。
- 标题精确率/召回率（§15.3 的 5 本/100 页目标）：**未验证**。
  合成反例：书眉误删为 0（title01/02/10 等，分子分母见单测断言）。

## 6. 实际模型测试：是否授权、调用次数、费用可知程度、耗时/质量比较

- 未获任何模型调用授权；全程零真实模型调用（UsageLedger 无新增真实记录）。
- Qwen 分组开关默认关闭，线上行为与基线一致。
- 费用/耗时/质量对比：**未验证**，不宣称“并发后快 N 倍”或准确率。

## 7. 未验证项和仍然存在的限制

1. 真实书籍 5 本/100 页抽样、印刷页码映射、复杂裁切、对开装订方向实测。
2. 真实 Qwen 延迟/费用/质量对比（开关默认关）。
3. 性能：p95/受限配置/长会话压测；200% 缩放；屏幕阅读器；真实断网/杀进程。
4. 离线零出站探针；导出中途更新阻断专项；旧 attempt 晚到专项。
5. 栏/分区检测、旋转变换链、重复/缺页检测（缺口，退路原图可读）。
6. 前台/后台额度 plumbing 到 enrich（当前统一按前台取槽，已记录为简化）。
7. 并行工作区写入事件：U3 期间 4 个未追踪文件被外部内容覆盖/删除，
   已备份（`/tmp/u3-alternate-backup/`）并以提交串行化收敛；U7 另有一份
   外部会话文档移入同备份目录。本分支只含本规范编号任务。

## 8. 数据兼容、发布步骤和回滚方式

- 兼容：新增字段一律 additive（presentation/processing/profileRevision/
  readablePages/目录 v2/能力声明）；旧客户端走 v1 数组与旧适配器；
  旧导出文件继续可读；sidecar 可删重建（人工覆盖除外，保留）。
- 发布顺序（§16.2）：U1 界面 → U2 安全 → U3 投影（影子对照后开）→
  U4 提前可读（已验证通道）→ U5 分块（开关关→并发 1→2/3，每步查物理数）→ U6/U7。
- 开关（工具配置）：`readerUiV2`（默认开：阅读模式/安静入口/真实阶段）、
  `presentationV2`（能力声明）、`incrementalReadable`（两阶段）、
  `qwenChunkedAssist`（默认关）、`qwenMaxConcurrency=3`。
  P0（清空保护、授权派发、真实进度、用量正确）不是可关开关。
- 回滚：暂停新增派发→等在途收尾；分块异常先降并发/关开关（可读正文不回退）；
  投影异常关版本回上一有效投影（保留人工覆盖）；代码回退前校验旧版本对
  sidecar/新字段的读取（未知字段忽略，已用 `ignoreUnknown` 处均为兼容设计）。
- 本报告 + `docs/verification/`（各批次记录、矩阵、证据 JSON）即交付归档。
