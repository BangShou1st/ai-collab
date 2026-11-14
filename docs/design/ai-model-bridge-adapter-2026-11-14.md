# AI Model Bridge 协议适配替换（2026-11-14）

## 1. 结论

本项目的三家 Chat Provider 协议适配已切换到
[`BangShou1st/ai-model-bridge`](https://github.com/BangShou1st/ai-model-bridge)
的 Spring-free `core`，版本固定为 `v0.2.0`。

替换范围仅限协议适配层：

- OpenAI-compatible Chat / SSE / Native Tool Calling
- Anthropic Messages / Native Tool Calling
- Gemini `generateContent` / `streamGenerateContent` / Function Calling

以下能力继续由本仓库负责，不下沉到桥接库：

- 用户级与全站共享 Provider 的数据库配置
- API Key 的 AES-GCM 加密存储与调用时解密
- 按 `purpose` 的用户模型路由、默认模型与共享模型授权
- 能力声明、模型发现、能力探针、发布准入与调用审计
- Agent、规划、RAG 的业务合同与审批边界

不启用桥接库的 `spring-boot-starter` 与 `server`：

- starter 的 Provider 配置来自静态 YAML，无法替代当前多租户数据库配置。
- server 是独立 OpenAI 兼容网关，会引入额外网络跳点和静态凭据管理，不符合当前产品边界。

## 2. 核心结构

```
RoutingChatModelGateway / RoutingModelTurnGateway
  → ModelBridgeProviderAdapter
    → ModelBridgeClient (singleton)
      → AI Model Bridge protocol adapters
        → OpenAI-compatible / Anthropic / Gemini
```

- `ModelBridgeGatewayConfiguration` 创建单例 `HttpTransport`、`AdapterRegistry` 与
  `ModelBridgeClient`。
- `ModelBridgeProviderAdapter` 负责 DTO、`ModelUsage`、`ModelFinishReason`、异常与
  超时/取消语义映射。
- `OpenAiCompatibleModelAdapter`、`AnthropicModelAdapter`、`GeminiModelAdapter`
  仅保留协议绑定与 Spring Bean 身份，不再包含各家请求/响应解析代码。

## 3. 关键映射

| 本项目 | AI Model Bridge |
| --- | --- |
| `ModelConfiguration.baseUrl + apiPath` | `ProviderConfig.baseUrl + EndpointConfig.chatPath` |
| `ChatCompletionCommand` | `ChatRequest` |
| `ModelTurnCommand` + `ModelMessage` | `ChatRequest` + `Message` |
| `ModelToolDefinition` / `toolsRequired` | `ToolDefinition` / `ToolChoice` |
| `ModelToolCall.thoughtSignature` | `ToolCall.thoughtSignature` |
| `JSON_OBJECT` / `PROMPT_JSON` | `StructuredOutput` / 应用侧校验 |
| `ModelUsage` | `TokenUsage` |
| `ModelFinishReason` | `FinishReason` |
| `ModelContentPreview` | SSE `CONTENT` chunk 聚合 |

Gemini 的临时 tool call ID 仍转换为 `gemini-call-N`，保持 Agent Runtime 既有重定界逻辑。

## 4. 安全与网络

- core 的 `OutboundEndpointPolicy` 复用本项目 local/test 回环白名单
  `ai.outbound.local-allowed-targets`。
- 非 local/test 环境保持公网 HTTPS 强校验，不使用宽泛的私网放行。
- 仍然禁止 userinfo、fragment、重定向与云元数据地址。
- API Key 只在单次 `ProviderConfig` 调用内出现，不进入日志、异常或持久化。
- 单次请求继续受 `AiRequestDeadline` 约束，传输上限保持 10 分钟。

## 5. 已知行为差异

1. **Gemini 无效 Key**
   桥接库不暴露上游响应体；Gemini 对无效 Key 返回 HTTP 400 时，只能归类为
   `AI_PROVIDER_ERROR`，不再从响应体 `API key not valid` 精确识别为凭据错误。
2. **Anthropic tool error**
   桥接库 `v0.2.0` 的 tool result 不携带 `is_error` 字段。本项目在错误结果正文前加
   `TOOL_ERROR:` 前缀，使模型仍能明确区分失败；后续可向上游提交 `is_error` 支持。
3. **Gemini thinking**
   `minimal` 在桥接库 `v0.2.0` 中没有直接映射，当前以 `thinkingBudget=1` 近似。
   对不支持 `thinkingConfig` 的模型，应声明 `THINKING` 能力，避免下发
   `thinkingBudget=0`。桥接库不再对 400 自动重试“去掉 thinkingConfig”。
4. **错误文本**
   上游响应体不会进入异常；错误分类基于 HTTP 状态与桥接库的稳定错误类别。

## 6. 验证

- 后端全量：`mvnw -B -ntp verify`
  - 2908 tests，0 failures，0 errors，62 skipped
- 真实模型冒烟：`ModelBridgeLiveSmokeTest`
  - ModelScope OpenAI-compatible：文本、SSE、原生工具调用
  - Gemini：文本、SSE、原生工具调用
  - Key 只从环境变量读取，不落库、不打印
- 桥接端口级契约测试覆盖：
  - DTO 与 tool/message 映射
  - Gemini `thoughtSignature` 与 tool ID
  - thinking 覆盖
  - OpenAI provider 扩展参数
  - LENGTH usage、空响应、错误码映射
  - SSE 内容、usage 与超时

## 7. 回滚与后续

依赖版本固定在 `ai-model-bridge.version`。如需回滚，可恢复替换前的三个协议适配器提交，
数据库、路由与前端均不受影响。

后续可选工作：

- 向上游贡献 Anthropic `tool_result.is_error` 支持。
- 评估 Embedding / Rerank / 模型发现是否继续复用桥接库。
- 为桥接库增加可配置的 provider 错误细分或安全的错误码透出。
