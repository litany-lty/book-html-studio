# U6：复杂版式、离线与性能收口（纸页工坊）

- 基线：`ux-reader-remediation`；U0～U5 已提交。
- 依赖：U1、U3、U4；模型并发相关验证依赖 U5。
- 性质：高频正确、复杂降级、测试覆盖；困难场景明确退路，不掩盖短板。

## 1. 精确改动点

| # | 文件 | 改动 | 不得破坏 |
|---|---|---|---|
| 1 | `store/BookStore.java` | 变更监听（`addChangeListener`/`notifyBookChanged`）；页变更唯一出口 + 书变更通知 | 锁语义；监听器只做轻量失效 |
| 2 | `service/BookPresentationService.java` | 画像内存缓存（命中计数测试可见）；角色判定用归一化视角；`fallbackMode`（NONE/REGION_IMAGE/PAGE_IMAGE） | 已验证图表/对开规则（适配不丢弃） |
| 3 | `service/BookService.java` | 书架统计缓存（变更失效） | 统计可重建校验（refresh 语义不变） |
| 4 | `static/reader.js` | PAGE_IMAGE 整页原稿呈现；figure 懒加载 + 异步解码 | 原文/疑点原图/视觉块/来源定位 |
| 5 | 测试 | `LayoutFallbackAndCacheTest` 6 项；`ExportServiceTest` 选页冻结一致；`UxU6CloseoutTest` 5 项 | 真实保存冲突测试保留 |
| 6 | `docs/verification/ux-u6-layout-matrix.md` | 28 场景矩阵：路径/禁区/退路/验证状态；缺口诚实列出 | 不宣传未经验证的速度/准确率 |

## 2. 测试（actual）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -q -Dtest='studio.bookhtml.service.LayoutFallbackAndCacheTest,studio.bookhtml.service.ExportServiceTest' test  # PASS
mvn test -Dtest='studio.bookhtml.service.*Test'  # 见提交输出
node --check reader.js  # OK
```

## 3. 验收映射（LAY-01～12、CONS-01～10）

| ID | 状态 | 证据 |
|---|---|---|
| LAY-01～03/05～07/09～11/15～24/26/28 | PASS（逻辑/既有） | 既有规则测试 + U3/U6 新增；矩阵注明文件行 |
| LAY-04/08/25/27 | 缺口 | 无栏检测器/变换链/重复检测；退路为原图可读；真实样本未验 |
| LAY-12/13/14 | PASS（逻辑） | U3 title02/10；装订方向用户指定为基础能力 |
| CONS-01/02/03 | PASS | U3 政策测试 |
| CONS-04/05/06 | PASS（逻辑） | 冻结画像 zip 级测试（目录一致） |
| CONS-07 离线零出站 | 未执行 | 需离线网络探针环境；离线包不含密钥/诊断（代码级：脱敏既有） |
| CONS-08 非连续选页 | PASS（逻辑） | 既有映射测试 + 本批冻结测试 |
| CONS-09 旧数据兼容 | PASS（既有） | 旧导出可读测试未动 |
| CONS-10 导出一致快照 | 部分 | 一致版本快照机制（冻结画像）；导出中途更新的混合版本阻断留 U7 专项 |

## 4. 性能收口（诚实记录）

- 画像：按需构建 + 变更失效缓存（命中计数可测）；每页全量响应只带当页投影。
- 统计：书架列表不再为首页全读页面 JSON（缓存+失效）。
- 图片：figure 懒加载；字体沿用子集加载。
- 未做实测：250ms p95、并发翻页压测、受限配置验证——缺浏览器/性能 harness，
  列未执行，不用单次最快值充数。
