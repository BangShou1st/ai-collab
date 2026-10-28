# P7-BUSINESS-E2E-01 真实缺陷与处理

## I-1（P2·已修复）未知路径与未暴露 Actuator 端点返回 500 而非 404

- **复现**：持有效 token `GET /actuator/metrics`（metrics 未在 local profile 暴露）→ HTTP 500 `INTERNAL_ERROR`；后端日志为 `NoResourceFoundException`。未认证时为 401（正确）。
- **根因**：`GlobalExceptionHandler` 的 `Exception` 兜底把 Spring 的 `NoResourceFoundException` 映射成 500；而 `AgentActuatorSecurityTest` 既有断言期望 404。500 假阳性会污染生产错误监控并误导客户端。
- **最小修复**：`GlobalExceptionHandler` 新增 `NoResourceFoundException` → `ErrorCode.RESOURCE_NOT_FOUND`（新增 404 枚举）。
- **修复前证据**：`network/` 见本轮记录（500 INTERNAL_ERROR）；修复后实测 `404 RESOURCE_NOT_FOUND 请求路径不存在`。
- **回归**：`AgentActuatorSecurityTest`（2 项）通过；dev.ps1 重启编译通过；线上行为复验通过。

## I-2（P2·已修复）AI 设置页 Provider 卡片按钮与长模型名文字重叠

- **复现**：自定义 Provider 模型 ID 较长（如 `deepseek-ai/DeepSeek-V4.1-Flash-1017`）时，`.model-main` 被 `.suite-panel`（能力测试面板，实测 713px）挤压至宽度 0，长文本溢出与右侧"启用/设为默认/编辑/删除"按钮叠放（用户截图报告）。
- **根因**：`.model-row` 单行 flex + `.model-main { flex:1; min-width:0 }` 可被压缩到 0 宽；`.suite-panel` 无换行路径。
- **最小修复**：`styles.css` 两行——`.model-row` 允许 `flex-wrap: wrap`；`.model-main` 改 `flex: 1 1 240px`。类仅被 AI 设置页使用，无其他引用。
- **修复后**：文字块 0→663px，按钮右置无重叠，能力面板换行展示（截图 `browser/fix-provider-card-after.png`）。

## I-3（P3·记录不改）能力探针"不支持"语义混淆

- **现象**：Provider 未声明 NATIVE_TOOLS 时，探针结果显示"原生工具：不支持"，实际是**声明门控短路**（`RoutingModelTurnGateway` 要求声明），模型能力并未被真实测试。
- **验证**：勾选 NATIVE_TOOLS 声明后重测，真实探针**通过**（魔搭 DeepSeek-V4.1-Flash-1017 确实支持原生 tool calling，与用户直连实测一致）。
- **建议**（后续可选）：将门控短路场景在 UI 中显示为"未声明（未实测）"而非"不支持"。本轮不做行为变更。

## I-4（P3·环境限制，非缺陷）免费规划模型输出截断

- cohere/north-mini-code:free 生成规划骨架两次 FAILED（`PLANNING_MODEL_OUTPUT_TRUNCATED`），UI 均正确展示"生成失败：规划骨架生成内容过长，请减少任务数量后重试"并提供重试入口。分批修复路径（P2 引入）真实触发。更换魔搭 DeepSeek 后一次成功（v2 READY），属模型能力差异，应用行为正确。

## 未执行项

- 真实 Provider 429 故障注入、Embedding 服务停机恢复：NOT_EXECUTED（延续 P2/P3 边界，不以 Mock 冒充）。
- 过期审批（处理期限到期后拒绝）：处理期限未到，浏览器不可安全制造；由既有自动化覆盖。
- 真实 Gemini：未获授权，未调用。
