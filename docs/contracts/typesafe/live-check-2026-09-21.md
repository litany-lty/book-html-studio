# J00 实时核实（2026-09-21，不含真实调用）

核实方式：重取官方文档（`docs.typesafe.ai/models`、`.../api`），未做真实外呼。
`v1-2026-09-21` 冻结契约继续有效，以下为新增已核实项与设计影响。

## 已核实（可用于生产断言）

- 模型：`jev-1.13.0`；别名 `jev-latest`/`jev-preview` 均指向它（`models.md` 当日）。
- 端点：`POST https://api.typesafe.ai/v1/systemone`（models 页简写 `/v1/systemone`，同一地址）。
- 价格：输入 $0.042/Mtok（$42/Btok），输出免费。`pricingVersion=docs-2026-09-21-jev-1.13.0`。
- 限流：250k tokens/s、1200 req/min；超限 429（可带 `retry-after`）。首版仍不隐式重试，用户明确重试才产生新 attempt。
- 上下文：64k 总量；`state` + 最长问题 32k。`maxRequestBytes=32768` 初值远小于此，后续按实测余量调整，不直接放宽到 64k。
- 输入：**纯文本**（string/JSON/array of text）。无图像/音频/视频输入——此前“图像未核实”关闭为“已证实不支持”，视觉证据路线维持“Qwen 裁图 OCR 转录文本后再比较”，绝不把图片 base64 塞进 state。
- 别名漂移：别名会随发版移动，响应 `model` 报告实际版本 ID。已按文档建议钉死版本 `jev-1.13.0`（阈值已针对固定版本调试），不用 `jev-latest` 做评测与线上。
- 数据：不在客户请求上训练；零保留（ZDR）仅企业版——启用说明只承诺“不在请求上训练”，不承诺 ZDR。
- 账户可用模型：`GET /v1/models`（需 key，见下）。

## 对 M2/M3 的直接影响

- CJK（中日韩）明确弱于英文，要求在自有内容上实测——J11 必须按中文样本单独报告，不得引用英文基准。
- usage 仍为 `{input_tokens, output_tokens}`；费用按输入 token 结算，M1 的 UNKNOWN 保留逻辑不变，有 pricingVersion 后方可结算核对。
- 真实 smoke 待 key：`GET /v1/models`（核对账户可用名）+ 1 次合成 choice+noul 最小调用（记录 requestId/usage/模型回执）。

## 仍未核实

- 账户侧实际限流、账单口径、地域/ZDR 条款（需企业合同）。
- 概率分布求和容差等未在文档承诺的细节：维持本地严格规则，不放宽。
