# 内容解析优化 R026–R050

基线：`da838c32ee744f786070c45031a40099726f0045`。仅修改获授权的解析/证据服务及自己的测试；不调用真实付费模型。

## 预先列出的 25 个关注点

下表保留启动时的计划状态，仅用于追溯；完成结果见后文的“当前结果”和每轮记录。

| 轮次 | 关注点 | 初始状态 |
|---|---|---|
| R026 | Baidu OCR 输入层级与非文本块证据保留 | 待检查 |
| R027 | Paddle OCR 文本、置信度与坐标边界 | 待检查 |
| R028 | OCR 旋转/归一化坐标的有限数校验 | 待检查 |
| R029 | 竖排分栏顺序及跨栏误合并 | 待检查 |
| R030 | 原文、简体与待确认缺字的展示 | 待检查 |
| R031 | 目录标题与正文标题识别 | 待检查 |
| R032 | 重复书名/书眉的章节抑制 | 待检查 |
| R033 | 页码、页眉装饰与正文短句区别 | 待检查 |
| R034 | 图表、表格与关系图的结构保护 | 待检查 |
| R035 | 扫描页证据文本与缺字恢复 | 待检查 |
| R036 | 稀疏页保护与空白页区别 | 待检查 |
| R037 | 质量门禁中低置信与未解决问题 | 待检查 |
| R038 | 人工复核标志与模型推断隔离 | 待检查 |
| R039 | 局部复识别候选选择及范围安全 | 待检查 |
| R040 | 手稿转录中原文证据与置信度 | 待检查 |
| R041 | 页内原生文本的排序和可信门槛 | 待检查 |
| R042 | 段落可理解性诊断的证据边界 | 待检查 |
| R043 | 上下文依赖与失效识别 | 待检查 |
| R044 | 书内上下文选取与截断边界 | 待检查 |
| R045 | 内容画像与书种/版式上下文 | 待检查 |
| R046 | 模型任务规划与无凭据外呼边界 | 待检查 |
| R047 | 模型目录恢复的来源与页码约束 | 待检查 |
| R048 | 广告过滤的误删保护 | 待检查 |
| R049 | 繁简转换对原文与异常输入的保护 | 待检查 |
| R050 | 页面处理流水线的证据、版式与复核集成 | 待检查 |

## 验证约定

真实 Java 验证使用 `bash ../tooling/mvn-session -Dtest=... test`（仓库目录）。每轮按发现记录修复或保留，不以断言数充当轮次。未执行 Java 的检查仅记静态证据，不标通过。

## 当前结果

25 个关注点已全部完成：9 轮实际修复、16 轮经检查与验证后保留。各轮是独立行为假设，测试数不作为轮次数。

## 每轮记录

### R026 — Baidu OCR 坐标与物理书写方向

- 目标/假设：单页布局解析和方向判断必须保持原始文本框的物理长宽比。
- 发现：在非正方形页面中，用归一化 bbox 比较宽高会让横行变竖栏、竖栏变横行；Baidu 是行级解析器，原文、置信度和 sourceRect 必须保留。
- 改动或保留：方向启发式改用已校验的 raw 矩形；归一化输出及用户指定方向保持原语义。
- 真实验证：RED1 两项方向失败；GREEN1 的新回归与 BaiduPpOcrParserTest 4 项通过。
- 状态：**已修复并验证**。

### R027 — Paddle OCR 布局与来源证据

- 目标/假设：核查 position/polygon、未知类型、表格，以及纵向页面上的竖栏。
- 发现：Paddle 同样用归一化比例，窄而长页面上的竖栏被判横排；未知类型保守保留、表格文字和来源仍可追踪。
- 改动或保留：仅将写向启发式的输入改为物理 raw；保留 vertical_text 及显式布局优先级。
- 真实验证：RED1 竖排失败、横排基线通过；GREEN1 两项回归与 PaddleOcrParserTest 4 项通过。
- 状态：**已修复并验证**。

### R028 — 几何有限数与书眉证据

- 目标/假设：NaN、Infinity、负坐标不能变成自动隐藏书眉的依据。
- 发现：OCR parser/BlockValidator 已有有限数检查，但 edgeZoneOf 会把 NaN 高度或负 y 视为 TOP。positionBucket 仅在 zone 有效后参与聚类。
- 改动或保留：edgeZoneOf 复用现有 validBbox，拒绝不完整、非有限或越界几何。
- 真实验证：RED2 的 malformedGeometryCannotBecomeRunningHeaderEvidence 失败；GREEN2 转绿，BookPresentationServiceTest 16 项通过。
- 状态：**已修复并验证**。

### R029 — 竖排分栏与标注保留

- 目标/假设：分栏整理不能改写疑点范围、简体正文或人工确认记录。
- 发现：rebuild/最终排序的旧构造静默清空 issues，simplified 被原文覆盖；相邻 reviewed 块也会合并并清掉确认状态。普通分区来源覆盖已有有效测试。
- 改动或保留：对含 reviewed/manual/issues 的输入页保留整个原结构，先验证再返回；没有可靠范围映射时不自动重建。普通无标注竖排仍走原排序/合并。
- 真实验证：RED2 两项标注/人工确认回归失败；GREEN2 转绿，VerticalLayoutNormalizerTest 2 项保持通过。
- 状态：**已修复并验证**。

### R030 — 原文、简体与未确认候选展示

- 目标/假设：原文展示应使用原字确认值，简体展示应使用简体确认值；未确认推测不能进入原文模式。
- 发现：HeadingText.display(false) 与 displayConfirmed 直接使用简体 replacement，忽略 ReviewResolution.originalReplacement。
- 改动或保留：统一选择确认替换值；原文优先 resolution.originalReplacement，缺该值时保留原字；简体及旧记录兼容路径不变。
- 真实验证：RED2 originalHeadingUsesOriginalScriptConfirmation 失败；GREEN2 转绿，HeadingTextTest 3 项、EvidenceAndCompletionTest 9 项通过。
- 状态：**已修复并验证**。

### R031 — 印刷目录与正文标题

- 目标/假设：目录恢复结果/目录引线不能成为正文目录节点，正文同页多标题仍按位置保留。
- 发现：OutlineService 只接受 READY 页、按页和 order 排序，排除明确目录来源/目录模式，并跳过已确认删除的空标题。没有发现需要改变的行为。
- 改动或保留：保留现有实现；不添加文件名或重复标题黑名单。
- 真实验证：GREEN3 OutlineServiceTest 3 项通过：混合页、多标题、重复章节、未确认候选及已确认空标题。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R032 — 重复书名与书眉

- 目标/假设：重复边缘书名不生成章节，而同名正文和人工标题应继续可用。
- 发现：BookPresentation 按版式、页码奇偶、位置和文字聚类；正文位置、单次出现和人工覆盖提供反证，来源供应商或文件名不决定章节。
- 改动或保留：本轮保留现有分类规则；畸形几何由 R028 单独修复。
- 真实验证：GREEN1/GREEN2 BookPresentationServiceTest 16 项通过，含 title01–09、块定位、profile 失效、来源不变等。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R033 — 页码装饰与正文短句

- 目标/假设：页码/页边装饰可归类，普通正文短句不能因字符少而隐藏。
- 发现：ReadingStructureNormalizer 检查边缘/数字模式，跳过人工和已复核内容，复制原文与 issues。
- 改动或保留：保留，不放宽短文本启发式。
- 真实验证：GREEN1 ReadingStructureNormalizerTest 9 项通过；重点为 normalizesMarginPageNumbersAndDecoratorsToPageNumber 与 verifiedSpreadOrderWorksWithMarginPageNumbersAndDecorators。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R034 — 图表、矩阵与跨栏保护

- 目标/假设：图表/关系图应保留裁图和转录，普通短列表、正文箭头或跨中缝内容不能误合并。
- 发现：结构正规化有关系符/短单元条件、普通文本反例、人工/无效裁切保护及竖排分区反证。
- 改动或保留：保留；标注保护增强属于 R029。
- 真实验证：GREEN1 ReadingStructureNormalizerTest 9 项与 VerticalLayoutNormalizerTest 2 项通过，覆盖来源只使用一次、左右分区、疑点不变和图形反例。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R035 — 扫描文字证据与恢复

- 目标/假设：网格、水印和模糊字迹不能被误当空白；恢复按区域保留证据且失败停止后续请求。
- 发现：ScanTextEvidence/OcrTextRecovery 使用原图笔画证据、有限区域坐标、部分成功保留和受控停止；重复词按区域而非全文字符串去重。
- 改动或保留：保留。
- 真实验证：GREEN1 ScanRecoveryTest 13 项通过，覆盖浅字、网格/遮挡、区域恢复、空/插画反例、畸形几何、后续失败及取消。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R036 — 稀疏页与空白页区别

- 目标/假设：低墨量不应单独抹去正文；满页密集表格声称与原图明显矛盾才回退。
- 发现：SparsePageGuard 同时检查满页结构面积、字符占比及 trueBlank；回退原图的块保留来源 ID 和明确警告。
- 改动或保留：保留；不采用字符数或单一阈值扩大过滤。
- 真实验证：GREEN3 SparsePageGuardTest 3 项通过；长伪表格、浅色稿、真实密集文字、局部表格与纯插图反例均覆盖。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R037 — 质量门禁与未解决问题

- 目标/假设：低置信/未核验结果不能用来源集合相同掩盖改字，已确认疑点载荷不得丢失。
- 发现：Baidu 置信度范围受控且输出保持 uncertain；QualityGate 分重排、结构合并、新视觉转录三类合同，核对来源覆盖、文字、确认载荷和视觉几何。
- 改动或保留：保留；没有把来源检查误当文字已确认。
- 真实验证：GREEN1 BaiduPpOcrParserTest 覆盖 0.9/0.5 与 uncertain；GREEN3 QualityGateTest 15 项通过，包括同 ID 改字、标点、丢失/重复、确认篡改和错误范围平移。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R038 — 人工确认与模型推断隔离

- 目标/假设：局部模型复识别只能追加候选，不应覆盖原文、已确认疑点或把块变成人工确认。
- 发现：AssistedReview 的成功和失败分支均复用原文、issues/resolution；reviewed 块直接跳过，取消传播。
- 改动或保留：保留此契约；候选输入边界修复另记 R039。
- 真实验证：GREEN3 AssistedReviewServiceTest 6 项通过，包含 verifyNoInteractions 的人工跳过/取消、候选不覆盖及 caller 图像不被释放；GREEN2 EvidenceAndCompletionTest 9 项通过。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R039 — 局部复识别候选边界

- 目标/假设：空候选不应排序崩溃，limit=0 不应选中一个区域。
- 发现：排序在 null 检查之前造成 NPE；上限在先加入候选后才检查，零上限失效。
- 改动或保留：空列表/null/非正上限早退，排序前过滤 null；重叠去重和人工跳过保持。
- 真实验证：RED1 分别 ERROR/FAIL；GREEN1 两项转绿，GREEN3 AssistedReviewServiceTest 6 项通过。
- 状态：**已修复并验证**。

### R040 — 手稿转录与置信来源

- 目标/假设：手稿栏序/裁切必须可追溯，候选和未辨认字必须保持未确认，不能失败后再发送额外请求。
- 发现：HandwritingTranscribeService 固定模型来源、uncertain=true/reviewed=false，有分栏覆盖、像素预算和响应约束，配置/审计依赖不满足则不发送。
- 改动或保留：保留；使用注入 transport 和合成图验证，未调用真实付费模型。
- 真实验证：GREEN3 HandwritingTranscribeServiceTest 17 项通过：分栏无重叠、候选未确认、Unicode 范围、部分失败、取消、缺配置/审计、响应关闭与缩放。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R041 — 原生文本排序与信任门槛

- 目标/假设：横排拉丁空格和竖排从右至左顺序应来自位置证据，不能单凭归一化窄字形猜测方向。
- 发现：NativeTextExtractor 使用相邻字形方向/旋转证据与可信字符门槛，远距离栏分离。
- 改动或保留：保留，不调阈值。
- 真实验证：GREEN1 NativeLayoutTest 5 项通过，覆盖竖栏、英文空格、远栏、宽页窄英文及横向中文反例。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R042 — 段落理解诊断与缓存证据

- 目标/假设：诊断只生成疑点候选，恢复缓存必须绑定同一书页、原文、模型及上下文。
- 发现：ParagraphComprehensibilityService 校验 quote/范围/Unicode，resolved 保留；ComprehensibilityResume 按输入身份/邻页/模型失效，部分组不冒称完成。
- 改动或保留：保留。
- 真实验证：GREEN3 ParagraphComprehensibilityServiceTest 4 项和 ComprehensibilityResumeTest 20 项通过，覆盖引用漂移、重叠、缓存续跑、候选未确认、邻页变化、拒绝异常与代理对边界。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R043 — 上下文依赖和失效

- 目标/假设：文本被编辑后，旧建议不得通过依赖校验；上下文范围不能拆开代理对或超字节预算。
- 发现：ContextDependencyValidator 比较页版本、原文范围/内容、码点和哈希；ContextSnapshot 创建时约束 Unicode/字节预算。
- 改动或保留：保留；仅运行相关故障矩阵方法，未把整个 Fault Matrix 冒称本轮覆盖。
- 真实验证：GREEN3 ContextSnapshotTest 5 项、FailureMatrixTest#testNewD16_DecisionAcceptServiceRejectsStaleDecisionWhenContextDrifts 1 项通过。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R044 — 书内上下文与文本上限

- 目标/假设：上下文书籍隔离、范围受限；证据字符串必须是有效 UTF-16，确认替换后的最终输出也受上限约束。
- 发现：BookContext 已有最多 15 页读、章节边界、sourceRecords 优先及代理对截断。EvidenceTextResolver 只校验 replacement 字符，未校验 original；最后尾串追加后未复查 256×1024 UTF-16 单元上限。
- 改动或保留：校验原文 UTF-16，并在追加尾部后执行最终长度检查；不改 sourceRecords 或候选内容。
- 真实验证：RED2 两项失败；GREEN2 新回归与 BookContextServiceTest 3 项、EvidenceAndCompletionTest 9 项通过。
- 状态：**已修复并验证**。

### R045 — 内容画像与版式上下文

- 目标/假设：画像应来自观察页的原字/方向、版本变化可追踪，不能把弱主题提示当视觉证明。
- 发现：BookContentProfile 观察最多 128 页并记录 observedPages，按结构签名复用或提升 revision；BookContext 将 chapterHintVerified=false、coverage=LIMITED。
- 改动或保留：保留；这是有界画像，不声称对全书所有页做事实确认。
- 真实验证：GREEN3 BookContentProfileTest 5 项通过，覆盖繁简/方向、缓存、结构/非结构版本、sidecar 冷启动。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R046 — 任务规划、分组预算与配置边界

- 目标/假设：owned/context 权限分离、原子图形不拆分；短尾组不得突破硬上限，未配置的增强不能冒称完成。
- 发现：尾组无条件并入前组使 12 块变 13、2500 字变 2800；另排序先于 null 过滤。planner 本身不发送请求，配置检查在执行服务。
- 改动或保留：排序前剔除 null；只有合并后块数和字数均合规才合并尾组，否则保留独立小组和完整原文。
- 真实验证：RED3 两项 FAIL、一项 ERROR；GREEN3 三项转绿、QwenTaskPlannerTest 5 项及 PageProcessorChunkedEnrichTest 6 项通过，含无配置不报完成。
- 状态：**已修复并验证**。

### R047 — 模型目录恢复的完成语义与来源

- 目标/假设：目录恢复必须有本地图像/页码线索、具名区域和可信完成响应，异常返回时保留来源。
- 发现：现有区域边界/点引线数量/页码密度保护已通过。另发现仅拒绝 finish_reason=length，过滤中止、工具中止、多个 choices、缺完成原因或 refusal 均可能被接受。
- 改动或保留：复用 ModelCompletion.singleText，要求单一正常 stop 完成且没有 refusal/tool call；异常响应保持来源并返回未恢复。
- 真实验证：RED4 确认 content_filter 被错误报告 recovered=true；GREEN4 新测试五类异常均拒绝，QwenTocRecoveryServiceTest 共 9 通过、1 缺真实 PNG 样本跳过；相关 ChunkedEnrich 6 项仍通过。
- 状态：**已修复并验证**。

### R048 — 广告过滤误删保护

- 目标/假设：只有独立页边促销信号可隐藏，正文、出版社信息、单独联系方式和人工内容必须可见。
- 发现：AdvertisementFilter 同时要求页边、长度、促销词与联系标识；只改派生类型，sourceRecords 不变；已确认疑点触发保留。
- 改动或保留：保留，不扩大词表或基于单一关键词删除。
- 真实验证：GREEN3 AdvertisementFilterTest 4 项、PageProcessorAdvertisementTest 3 项通过，覆盖误删反例、原图/来源保留、搜索/目录不纳入广告和预览取消。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R049 — 繁简转换的原文保护

- 目标/假设：转换只产生展示文本，不得改写 OCR 原文；缺失文本应有稳定空值处理。
- 发现：TraditionalConverter 对 null 返回空串，通过 OpenCC 转为简体；PageProcessor.simplify 保存 b.original，mapIssues 保留 resolution 并映射展示范围。
- 改动或保留：保留，不替换字典或加入无证据纠错。
- 真实验证：GREEN3 TraditionalConverterTest 1 项通过；GREEN2 HeadingTextTest/EvidenceAndCompletionTest 与 GREEN3 页面处理集成验证原文/候选边界。null 处理为静态检查，未单独宣称新增测试。
- 状态：**已检查并保留（有真实 Java 验证）**。

### R050 — 页面流水线集成与降级

- 目标/假设：混合页漏识应转 OCR，真空白可完成，辅助来源丢失、失败或缺配置必须保留基线并明确未完成。
- 发现：PageProcessor 在辅助前后使用对应质量合同，真实空白/视觉页/未识别分开；自动简化输出仍为未人工复核；chunked 路径及旧路径失败回退有覆盖。
- 改动或保留：保留已有集成行为；本次解析修复由定向测试及既有流程共同验证。
- 真实验证：GREEN3 PageProcessorQualityTest 4 项、PageProcessorChunkedEnrichTest 6 项通过；广告页集成 3 项亦通过。没有执行真实 PDF 全书付费端到端转换。
- 状态：**已检查并保留（有真实 Java 验证）**。

## 验证日志和准确命令

工作目录为仓库根目录。全部 Java 命令通过临时 `../tooling/mvn-session` 包装器使用当前代理和 ByteBuddy premain；包装器不属于产品改动。

- RED1：`bash ../tooling/mvn-session -Dtest=OptimizationParsingRegressionTest test`，6 项中 4 失败、1 错误、1 通过，`/tmp/parsing-red.log`。此前 import 拼错的编译失败不算有效 RED。
- GREEN1：62 项，0 失败/错误/跳过，`/tmp/parsing-green-1.log`。
- RED2：同 RED1 命令，12 项中新增 6 失败，原 6 通过，`/tmp/parsing-red-2.log`。
- GREEN2：45 项，0 失败/错误/跳过，`/tmp/parsing-green-2.log`。
- RED3：同 RED1 命令，15 项中新增 2 失败、1 错误，原 12 通过，`/tmp/parsing-red-3.log`。
- GREEN3：126 项，125 通过、1 跳过，0 失败/错误，`/tmp/parsing-green-3.log`。
- RED4：仅目录完成合同新方法，1 项失败（content_filter 被错误接受），`/tmp/parsing-red-4.log`。
- GREEN4：31 项，30 通过、1 跳过，0 失败/错误，`/tmp/parsing-green-4.log`。

```bash
# GREEN1
bash ../tooling/mvn-session -Dtest=OptimizationParsingRegressionTest,BaiduPpOcrParserTest,PaddleOcrParserTest,VerticalLayoutNormalizerTest,BookPresentationServiceTest,HeadingTextTest,ScanRecoveryTest,ReadingStructureNormalizerTest,NativeLayoutTest test

# GREEN2
bash ../tooling/mvn-session -Dtest=OptimizationParsingRegressionTest,VerticalLayoutNormalizerTest,BookPresentationServiceTest,HeadingTextTest,EvidenceAndCompletionTest,BookContextServiceTest test

# GREEN3
bash ../tooling/mvn-session '-Dtest=OptimizationParsingRegressionTest,OutlineServiceTest,SparsePageGuardTest,QualityGateTest,AssistedReviewServiceTest,HandwritingTranscribeServiceTest,ParagraphComprehensibilityServiceTest,ComprehensibilityResumeTest,ContextSnapshotTest,FailureMatrixTest#testNewD16_DecisionAcceptServiceRejectsStaleDecisionWhenContextDrifts,BookContentProfileTest,QwenTaskPlannerTest,QwenTocRecoveryServiceTest,AdvertisementFilterTest,PageProcessorAdvertisementTest,TraditionalConverterTest,PageProcessorQualityTest,PageProcessorChunkedEnrichTest' test
```

最后一轮的准确命令：

```bash
# RED4
bash ../tooling/mvn-session '-Dtest=QwenTocRecoveryServiceTest#rejectsIncompleteAmbiguousAndRefusedCompletionsWithoutReplacingEvidence' test

# GREEN4
bash ../tooling/mvn-session -Dtest=QwenTocRecoveryServiceTest,PageProcessorChunkedEnrichTest,OptimizationParsingRegressionTest test
```

上述四次 GREEN 按类/方法最新结果去重为 **28 类范围、195 个不同测试，194 通过、1 跳过**。不能把 62+45+126+31 当作不同测试数量。新增回归共 16 个测试方法（OptimizationParsingRegressionTest 15 个、QwenTocRecoveryServiceTest 新增 1 个），其中 15 个在对应修复前观察到有效失败/错误，另 1 个为 Paddle 横排防反转基线。

## 本代理修改文件

| 文件（相对仓库） | 作用 |
|---|---|
| `src/main/java/studio/bookhtml/service/BaiduPpOcrParser.java` | 方向使用物理宽高比 |
| `src/main/java/studio/bookhtml/service/PaddleOcrParser.java` | 方向使用物理宽高比 |
| `src/main/java/studio/bookhtml/service/AssistedReviewService.java` | 候选空项和非正上限 |
| `src/main/java/studio/bookhtml/service/BookPresentationService.java` | 书眉几何合法性 |
| `src/main/java/studio/bookhtml/service/VerticalLayoutNormalizer.java` | 已标注/人工页保留原结构 |
| `src/main/java/studio/bookhtml/service/HeadingText.java` | 原字确认与简体展示分离 |
| `src/main/java/studio/bookhtml/service/EvidenceTextResolver.java` | 原文 UTF-16 与最终输出上限 |
| `src/main/java/studio/bookhtml/service/QwenTaskPlanner.java` | 尾组硬预算和空项 |
| `src/main/java/studio/bookhtml/service/QwenTocRecoveryService.java` | 单一正常完成响应合同 |
| `src/test/java/studio/bookhtml/service/OptimizationParsingRegressionTest.java` | 新增 15 个回归测试方法 |
| `src/test/java/studio/bookhtml/service/QwenTocRecoveryServiceTest.java` | 新增 1 个完成合同回归测试方法 |
| `docs/optimization-20261107/parsing-rounds.md` | 25 轮人工可读记录 |
| `docs/optimization-20261107/parsing-rounds.json` | 25 轮机器摘要 |

代码已冻结。修改范围 `git diff --check` 通过；主线程后续全仓 verify 独占执行，本代理不并行修改 Java 或测试。

## 实际限制

- `realDirectoryFixturesHaveIndependentDividersWhenAvailable` 由于缺少 `verification/first20-source/page-002.png`、`page-003.png`、`page-004.png` 而按既有 Assumption 跳过；合成目录图片用例已执行，真实目录图片未验证。
- OCR/模型调用全部使用注入假 transport 或 mock，没有调用任何真实付费模型，没有新用量。
- 竖排已标注页采用保守保留策略，本次没有实现跨块合并的疑点范围迁移；这是为保留人工内容作出的明确决定。
- 画像与上下文已有观察上限，验证不代表全书 OCR 准确率或线上供应商响应质量已得到衡量。
- 没有修改域模型/API/Store/前端/CI，没有提交、推送或切换分支。本报告只描述解析代理的测试范围；主线程另行承担全仓整合验收。
