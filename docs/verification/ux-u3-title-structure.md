# U3：统一标题、书眉、页码与显示政策（纸页工坊）

- 基线：`ux-reader-remediation`；U0/U1/U2 已提交；U3 后端已先入库（`9191508`）。
- 本批依赖：U0；写操作版本保护来自 U2（覆盖 expectedRevision、STALE 不错套）。
- 性质：目录识别从“收集 heading”改为“书籍上下文中的结构判断”。

## 1. 精确改动点

| # | 文件 | 改动 | 不得破坏 |
|---|---|---|---|
| 1 | 新增 `domain/BookLayoutProfile.java` | 书籍画像：profileRevision/policyVersion/observedPages/edgeClusters；`clusterSignature()` 实质变化比较；sidecar 持久化 | 不存整本原文副本 |
| 2 | 新增 `domain/PagePresentation.java` | 只读投影：BlockPresentation（role/renderAs/readingOrder/flowKind/joinGroupId/allowJoinNext/showInReading/includeInOutline/evidenceLevel/reasonCodes） | 与存储 blocks 独立；校对保存不把隐藏当删除 |
| 3 | 新增 `service/BookPresentationService.java` | 证据索引→版式分组（纵横/书写方向/奇偶）→重复边缘簇（≥5 观察页/≥3 页/≥60%/12% 带/3% 容差/8% 厚度）→首现候选/后续收起→目录发布；封面书名（仅第 1 页+书名弱提示）；目录条目保留内容；视觉原子区；人工块永不隐藏 | 全局黑名单、来源前缀语义、边缘数字误删 |
| 4 | 新增 `domain/PresentationOverride.java` + `api/PresentationOverrideRequest.java` + `service/PresentationOverrideService.java` | 本块作用域/预览/撤销/STALE 待定位；sourceHash 失配不套用；只改角色不算人工校对 | BookStore 锁复用；operator/时间记录 |
| 5 | `service/HeadingText.java` | `EvidenceMode` 独立维度：`display`（原文，简体也不接受推测）、`displayAssisted`、`pageTitle(page, mode)`（无标题回“第 N 页”） | 已确认替换与区间语义 |
| 6 | `service/OutlineService.java` + `service/BookService.java` | outline 经投影（setter 注入，缺省旧适配器）；`pageSummary(bookId, page)` 同一投影标题 | 旧 `fromPages` 契约（兼容保留） |
| 7 | `service/ReadingWindowService.java` | 快照共用一次画像构建；增量目录走投影 + `profileRevision`；旧响应不反灌（app 侧 map） | 租约/互斥/停止后不派发 |
| 8 | `api/ApiController.java` | 页面载荷 +`presentation`；目录 v2 对象；`/config` 能力声明 | v1 数组缺省不变 |
| 9 | 新增 `api/PresentationController.java` | 覆盖列表/应用/范围预览 | 同源/写入保护沿用 |
| 10 | `service/ExportService.java` | 导出开始冻结画像，按选页筛选 | 选页映射/原稿/裁图/脱敏 |
| 11 | `store/BookStore.java` | sidecar 原子读写辅助 | 单写租约/目录锁 |
| 12 | `static/reader.js` + `static/app.js` + `static/api.js` + `index.html` + `styles.css` | 投影消费（人工块不隐藏、join 提示）；目录版本守卫；覆盖 API；校对“页面结构”；目录页脚说明 | 原文/疑点原图/复杂视觉块/来源定位 |
| 13 | 测试 | `BookPresentationServiceTest` 16 项（TITLE-01～14/16 + 回退标题）；`UxU3TitleStructureTest` 5 项；HeadingText/OutlineService 政策更新 | 保存/冲突/证据断言保留 |

## 2. 测试（actual）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -q -Dtest='studio.bookhtml.service.BookPresentationServiceTest' test  # 16/16 PASS
mvn test -Dtest='studio.bookhtml.service.*Test'                            # 见提交输出
node --check app.js reader.js api.js                                       # OK
```

- 真实样本（5 本/100 页标题精确率/召回率）无授权样本：BLOCKED/未验证。规则参数为
  待验证初始配置，不宣传准确率。
- 浏览器目录/页面结构交互未运行：未执行（空闲页截图不计证据）。

## 3. 验收映射（TITLE-01～16、CONS-01～06）

| ID | 状态 | 证据 |
|---|---|---|
| TITLE-01/02/03/04/05/06 | PASS | 同名测试 |
| TITLE-07 | PASS | 数字/注号保留 + hash 不变 |
| TITLE-08/09 | PASS | 人工保留；来源前缀不排除 |
| TITLE-10 | PASS | 目录页保留内容、不生成章节 |
| TITLE-11 | PASS | 同页双标题 distinct blockId |
| TITLE-12 | PASS | revision 增长 + 旧条目移除 + 首现内容保留 |
| TITLE-13 | PASS（逻辑） | 冻结画像复用；旧响应守卫在 app.js（静态门禁） |
| TITLE-14/15 | PASS | 作用域/撤销/STALE |
| TITLE-16 | PASS | 投影前后 hash 一致 |
| CONS-01/02/03 | PASS | HeadingText 政策测试；偏好独立（旧偏好无证据默认原文） |
| CONS-04/05/06 | 部分 | 冻结画像 + 选页映射机制测试；离线端到端一致性留 U6 |
| CONS-07～10 | 未验证 | 留 U6（离线零模型出站/映射/旧数据/版本快照） |

## 4.  workspace 异常记录（2026-09-22 11:26～11:33）

- 现象：未追踪新文件 `BookLayoutProfile.java`、`PagePresentation.java`、
  `BookPresentationService.java` 内容被另一套设计覆盖，
  `BookPresentationServiceTest.java` 被删除；另有一份来源不明的新测试出现后又消失。
  已追踪修改完好；reflog 无他人提交。
- 处理：异常文件已备份至 `/tmp/u3-alternate-backup/`（含来源不明测试），权威版本
  重写并经 grep 即时校验；后端先行提交入库（`9191508`）防止再次丢失。
- 后续：提交前对新增文件做存在性 + 标记 grep；最终报告列为风险项。
  本文件（规范）替代先前两份方案，不混用任务编号——异常内容未采纳。
