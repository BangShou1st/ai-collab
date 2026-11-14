# AI Model Bridge 协议适配替换验收

- 日期：2026-11-14
- 范围：后端 Chat Provider 协议适配（OpenAI-compatible / Anthropic / Gemini）
- 设计：[`docs/design/ai-model-bridge-adapter-2026-11-14.md`](../../../design/ai-model-bridge-adapter-2026-11-14.md)

## 1. 实现结果

- 协议实现切换到 `ai-model-bridge-core:v0.2.0`。
- 保留 `RoutingChatModelGateway` / `RoutingModelTurnGateway`、数据库 Provider 配置、
  API Key 加密、能力门控、共享 Provider 与调用审计。
- 删除原三家 Provider 的请求/响应解析代码与 `AbstractModelProviderAdapter`。
- 未启用桥接库 starter 或独立 server。

## 2. 自动化验证

```powershell
cd ai-collab-backend
.\mvnw.cmd -B -ntp verify
```

结果：

- tests：2908
- failures：0
- errors：0
- skipped：62

最终收尾后定向回归：

```powershell
.\mvnw.cmd -B -ntp "-Dtest=ModelBridgeProviderAdapterTest,AgentContentPreviewStreamIsolationTest" test
```

结果：26 tests，0 failures / errors。

## 3. 真实模型冒烟

命令（Key 仅从环境变量注入，不在报告中出现）：

```powershell
$env:AI_MODEL_BRIDGE_LIVE = 'true'
# GEMINI_API_KEY、MODELSCOPE_API_KEY 由本机 Key 文件注入当前进程环境
$env:MODELSCOPE_BASE_URL = 'https://api-inference.modelscope.cn'
$env:MODELSCOPE_MODEL = 'deepseek-ai/DeepSeek-V4.1-Flash-1017'
$env:GEMINI_MODEL = 'gemini-3.5-flash-lite'
.\mvnw.cmd -B -ntp "-Dtest=ModelBridgeLiveSmokeTest" test
```

结果：2/2 PASS。

| Provider | 文本 | SSE | 原生工具调用 |
| --- | --- | --- | --- |
| ModelScope / `deepseek-ai/DeepSeek-V4.1-Flash-1017` | PASS | PASS | PASS |
| Gemini / `gemini-3.5-flash-lite` | PASS | PASS | PASS |

说明：

- Gemini 工具 schema 不支持 `additionalProperties`；首次冒烟使用该字段得到真实 HTTP 400，
  移除测试 schema 中的该字段后通过。这是测试输入问题，不是协议适配缺陷。
- 本轮未调用 Anthropic 真实付费模型；Anthropic 的 DTO、工具消息与错误映射由端口级契约测试覆盖。
- 未保存上游响应正文、Prompt 或任何 API Key。

## 4. 已知边界

- 桥接库不暴露上游错误体，Gemini 无效 Key 的 HTTP 400 只归类为 `AI_PROVIDER_ERROR`。
- 桥接库 `v0.2.0` 的 Anthropic tool result 不支持 `is_error` 字段；错误正文使用
  `TOOL_ERROR:` 前缀表达。
- Gemini `minimal` thinking 以 `thinkingBudget=1` 近似；不支持 `thinkingConfig` 的模型
  需声明 `THINKING` 能力。

## 5. 清理

- 未在仓库、日志或证据目录保存明文凭据。
- 测试临时产物位于 `ai-collab-backend/target/`，未写入项目根目录或源码目录。
