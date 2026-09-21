# TypeSafe 契约 v1（J00）

- 契约版本：`v1-2026-09-21`，定义见 `contract-version.json`。
- 读取来源：`https://docs.typesafe.ai/llms.txt`、`.../api`、`.../primitives/choice|noul|score`（2026-09-21 可达并取回；`models` 未取得）。
- 夹具：`sanitized-request-response-fixtures.json` 全为人工合成（`synthetic:true`），用于离线解析测试，不是供应商真实返回。
- 真实状态：**BLOCKED**——无 API key、无外发/费用授权，未执行真实 smoke。`model` 字段在授权前保持占位符，空值时禁用外呼。
- 未核实项（见 contract-version.json `unverified`）：图像输入、模型清单、价格、数据政策、分布容差。生产代码绝不能断言这些。
- Mock 只能用独立测试 profile，不能与真实 SHADOW 混记；`materialIsUntrustedData` 只是辅助说明，不是防注入边界。
