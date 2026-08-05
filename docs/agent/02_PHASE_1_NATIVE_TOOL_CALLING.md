# 02 Phase 1：原生 Tool Calling 模型协议

> **状态：已通过** (2026-07-30)
> 426 tests passing, 748 lines added, 17 lines modified.

## 1. 阶段目标

在不破坏知识问答、规划和现有流式聊天的前提下，为 Agent 增加独立的多轮工具调用协议。

本阶段只解决：

```text
模型消息 -> 供应商请求 -> 结构化 Tool Call -> Tool Result 回传
```

本阶段不改 Agent UI、不接 MCP、不改业务工具、不改数据库。

## 2. 为什么新增 Model Turn 协议，而不是直接大改 ChatCompletionCommand

当前 `ChatCompletionCommand(systemPrompt, userPrompt, ...)` 被知识问答、规划和其他模块使用。Agent 需要的是多轮消息：

```text
system
user
assistant(tool calls)
tool(result)
assistant(final or more calls)
```

直接把旧 record 改成消息列表会扩大回归面。推荐新增并行协议，稳定后再评估统一。

## 3. 新增核心类型

推荐目录：

```text
infrastructure/ai/turn/
  ModelTurnGateway.java
  ModelTurnCommand.java
  ModelTurnResult.java
  ModelMessage.java
  ModelToolCall.java
  ModelFinishReason.java
  ModelUsage.java
```

### 3.1 消息模型

```java
package com.shitulelv.aicollab.infrastructure.ai.turn;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public sealed interface ModelMessage permits
        ModelMessage.System,
        ModelMessage.User,
        ModelMessage.Assistant,
        ModelMessage.ToolResult {

    record System(String content) implements ModelMessage {
        public System {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("system content 不能为空");
            }
            content = content.strip();
        }
    }

    record User(String content) implements ModelMessage {
        public User {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("user content 不能为空");
            }
            content = content.strip();
        }
    }

    record Assistant(String content, List<ModelToolCall> toolCalls)
            implements ModelMessage {
        public Assistant {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (content.isBlank() && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("assistant message 不能同时为空");
            }
        }
    }

    record ToolResult(
            String toolCallId,
            String toolName,
            JsonNode result,
            boolean error) implements ModelMessage {
        public ToolResult {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId 不能为空");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName 不能为空");
            }
            if (result == null) {
                throw new IllegalArgumentException("tool result 不能为空");
            }
        }
    }
}
```

### 3.2 Tool Call 与返回

```java
public record ModelToolCall(
        String id,
        String name,
        JsonNode arguments) {
    public ModelToolCall {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id 不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (arguments == null || !arguments.isObject()) {
            throw new IllegalArgumentException("arguments 必须是 JSON object");
        }
        name = name.strip();
        arguments = arguments.deepCopy();
    }
}

public enum ModelFinishReason {
    STOP,
    TOOL_CALLS,
    LENGTH,
    CONTENT_FILTER,
    ERROR,
    UNKNOWN
}

public record ModelUsage(Integer inputTokens, Integer outputTokens) {
    public ModelUsage {
        if (inputTokens != null && inputTokens < 0) throw new IllegalArgumentException();
        if (outputTokens != null && outputTokens < 0) throw new IllegalArgumentException();
    }
}

public record ModelTurnResult(
        String content,
        List<ModelToolCall> toolCalls,
        ModelFinishReason finishReason,
        String provider,
        String model,
        ModelUsage usage,
        long latencyMs) {
    public ModelTurnResult {
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (content.isBlank() && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("模型既没有文本也没有工具调用");
        }
    }
}
```

### 3.3 命令与 Gateway

```java
public record ModelTurnCommand(
        ModelPurpose purpose,
        List<ModelMessage> messages,
        List<ModelToolDefinition> tools,
        boolean toolsRequired) {
    public ModelTurnCommand {
        purpose = purpose == null ? ModelPurpose.AGENT : purpose;
        messages = messages == null ? List.of() : List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
        if (messages.isEmpty()) throw new IllegalArgumentException("messages 不能为空");
        if (toolsRequired && tools.isEmpty()) {
            throw new IllegalArgumentException("toolsRequired 时 tools 不能为空");
        }
    }
}

public interface ModelTurnGateway {
    ModelTurnResult turn(ModelTurnCommand command);
}
```

第一版不要求 `turnStream()`。Agent 前端实时性由运行事件 SSE 提供。

## 4. 供应商适配结构

不要修改现有 `ModelProviderAdapter` 的旧方法签名。新增：

```java
public interface ModelTurnProviderAdapter {
    ModelProviderType providerType();

    ModelTurnResult turn(
            ModelConfiguration configuration,
            String apiKey,
            ModelTurnCommand command);
}
```

三个现有 Adapter 同时实现旧接口和新接口。新增路由器：

```java
@Component
public class RoutingModelTurnGateway implements ModelTurnGateway {
    private final ModelConfigurationRepository configurations;
    private final ModelSecretCipher secrets;
    private final Map<ModelProviderType, ModelTurnProviderAdapter> adapters;

    public RoutingModelTurnGateway(
            ModelConfigurationRepository configurations,
            ModelSecretCipher secrets,
            List<ModelTurnProviderAdapter> adapters) {
        this.configurations = configurations;
        this.secrets = secrets;
        EnumMap<ModelProviderType, ModelTurnProviderAdapter> indexed =
                new EnumMap<>(ModelProviderType.class);
        for (ModelTurnProviderAdapter adapter : adapters) {
            if (indexed.putIfAbsent(adapter.providerType(), adapter) != null) {
                throw new IllegalArgumentException("重复 Model Turn Adapter");
            }
        }
        this.adapters = Map.copyOf(indexed);
    }

    @Override
    public ModelTurnResult turn(ModelTurnCommand command) {
        ModelConfiguration config = configurations.findAssigned(command.purpose())
                .orElseThrow(() -> new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        if (!config.enabled() || !config.capabilities().contains(ModelCapability.NATIVE_TOOLS)) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "Agent 模型必须支持 NATIVE_TOOLS");
        }
        ModelTurnProviderAdapter adapter = adapters.get(config.providerType());
        if (adapter == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return adapter.turn(config, secrets.decrypt(config.encryptedApiKey()), command);
    }
}
```

Agent 2.0 主路径不使用 legacy fallback。没有支持原生工具的 Agent 模型配置时，应明确返回不可用，不应悄悄退回知识问答模型。

## 5. OpenAI-compatible 映射

### 5.1 请求

```java
private ArrayNode openAiMessages(ModelTurnCommand command) {
    ArrayNode messages = mapper.createArrayNode();
    for (ModelMessage message : command.messages()) {
        switch (message) {
            case ModelMessage.System m -> messages.addObject()
                    .put("role", "system").put("content", m.content());
            case ModelMessage.User m -> messages.addObject()
                    .put("role", "user").put("content", m.content());
            case ModelMessage.Assistant m -> {
                ObjectNode node = messages.addObject().put("role", "assistant");
                if (!m.content().isBlank()) node.put("content", m.content());
                if (!m.toolCalls().isEmpty()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ModelToolCall call : m.toolCalls()) {
                        ObjectNode function = calls.addObject()
                                .put("id", call.id())
                                .put("type", "function")
                                .putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.arguments().toString());
                    }
                }
            }
            case ModelMessage.ToolResult m -> messages.addObject()
                    .put("role", "tool")
                    .put("tool_call_id", m.toolCallId())
                    .put("name", m.toolName())
                    .put("content", m.result().toString());
        }
    }
    return messages;
}
```

工具定义继续使用现有 OpenAI `type=function` 格式。若 `toolsRequired=true`，设置：

```json
{"tool_choice":"required"}
```

否则使用 `auto`，不要强制每一轮调用工具。

### 5.2 响应

必须处理：

- `message.content` 为 null；
- 只有 `tool_calls`；
- 多个 tool calls；
- `function.arguments` 是 JSON 字符串；
- arguments 不是 object 时返回 `AI_PROVIDER_INVALID_RESPONSE`；
- `finish_reason=length` 转换为截断错误。

```java
private List<ModelToolCall> parseOpenAiToolCalls(JsonNode message) {
    List<ModelToolCall> calls = new ArrayList<>();
    for (JsonNode node : message.path("tool_calls")) {
        String id = node.path("id").asText();
        String name = node.path("function").path("name").asText();
        String raw = node.path("function").path("arguments").asText("{}");
        JsonNode args;
        try {
            args = mapper.readTree(raw);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
        }
        calls.add(new ModelToolCall(id, name, args));
    }
    return calls;
}
```

## 6. Anthropic 映射

### 6.1 请求规则

- system 单独放顶层 `system`；
- user 文本使用 `role=user`；
- assistant 文本和 `tool_use` block 可共存；
- 工具结果使用 **user message 的 `tool_result` content block**；
- `tool_use_id` 必须对应原调用 ID。

示例结构：

```json
{
  "role": "assistant",
  "content": [
    {"type":"text","text":"我先读取任务。"},
    {"type":"tool_use","id":"toolu_1","name":"task.search","input":{"overdueOnly":true}}
  ]
}
```

工具结果：

```json
{
  "role": "user",
  "content": [
    {
      "type":"tool_result",
      "tool_use_id":"toolu_1",
      "content":"{\"success\":true,\"summary\":\"找到3个任务\"}",
      "is_error":false
    }
  ]
}
```

### 6.2 响应解析

遍历 `content[]`：

```java
StringBuilder text = new StringBuilder();
List<ModelToolCall> calls = new ArrayList<>();
for (JsonNode block : response.path("content")) {
    switch (block.path("type").asText()) {
        case "text" -> text.append(block.path("text").asText());
        case "tool_use" -> calls.add(new ModelToolCall(
                block.path("id").asText(),
                block.path("name").asText(),
                block.path("input")));
        default -> { /* 忽略未知展示块，但不能当工具 */ }
    }
}
```

`stop_reason=tool_use` 映射 `TOOL_CALLS`；`end_turn` 映射 `STOP`；`max_tokens` 抛截断错误。

## 7. Gemini 映射

### 7.1 请求规则

- system instruction 放 `systemInstruction.parts[].text`；
- 用户与工具结果放 `role=user`；
- assistant 放 `role=model`；
- tool call 使用 `functionCall`；
- tool result 使用 `functionResponse`；
- Gemini 响应不一定提供稳定的 tool call ID。系统必须生成稳定 ID 并在本轮保存映射，例如：

```text
gemini:<runId>:<turnNo>:<partIndex>
```

不要只使用工具名作为 ID，因为同一轮可能调用同名工具多次。

示例：

```json
{"functionCall":{"name":"task.search","args":{"status":"BLOCKED"}}}
```

工具结果：

```json
{
  "functionResponse": {
    "name": "task.search",
    "response": {"success":true,"summary":"找到2个阻塞任务","data":{}}
  }
}
```

### 7.2 响应解析

遍历 `candidates[0].content.parts[]`，同时收集 `text` 和 `functionCall`。`finishReason=MAX_TOKENS` 为截断错误。

## 8. 统一验证

Provider 返回前执行：

```java
private void validate(ModelTurnResult result, ModelTurnCommand command) {
    if (result.toolCalls().size() > 4) {
        throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                "单轮工具调用超过限制");
    }
    Set<String> allowed = command.tools().stream()
            .map(ModelToolDefinition::name)
            .collect(Collectors.toSet());
    for (ModelToolCall call : result.toolCalls()) {
        if (!allowed.contains(call.name())) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE,
                    "模型调用了未暴露工具");
        }
    }
}
```

参数业务 Schema 校验属于 Tool Executor，不在 Provider 层完成。

## 9. 契约测试夹具

必须新增固定 JSON fixture，不能只手工构造部分 JsonNode：

```text
src/test/resources/ai/tool-calls/
  openai-single-tool.json
  openai-multiple-tools.json
  openai-text-only.json
  openai-invalid-arguments.json
  anthropic-tool-use.json
  anthropic-text-and-tool.json
  anthropic-max-tokens.json
  gemini-function-call.json
  gemini-text-and-function.json
  gemini-max-tokens.json
```

至少包含以下断言：

```java
assertThat(result.content()).isEqualTo(...);
assertThat(result.toolCalls()).hasSize(2);
assertThat(result.toolCalls().getFirst().arguments()).isObject();
assertThat(result.finishReason()).isEqualTo(ModelFinishReason.TOOL_CALLS);
assertThat(result.provider()).isEqualTo("OPENAI_COMPATIBLE");
```

还要捕获真实请求 body，验证 Tool Result 序列化正确，而不是只测试响应解析。

## 10. 子阶段

### Phase 1.1 类型与路由

允许修改：

```text
infrastructure/ai/turn/**
infrastructure/ai/model/ModelTurnProviderAdapter.java
infrastructure/ai/model/RoutingModelTurnGateway.java
相关测试
```

禁止改 AgentWorker。

### Phase 1.2 OpenAI-compatible

只实现 OpenAI-compatible 原生工具和测试。使用一个真实兼容模型做最小冒烟：暴露 `echo` 测试工具，确认返回结构化调用。

### Phase 1.3 Anthropic

只实现 Anthropic 适配与测试。

### Phase 1.4 Gemini

只实现 Gemini 适配与测试。

### Phase 1.5 兼容性回归

运行原有知识问答、规划、流式问答和模型配置测试。此时仍不切换 AgentWorker。

## 11. 阶段验收

- 三家 Provider 各有请求序列化和响应解析契约测试；
- 文本与 Tool Call 同时存在时不丢失；
- 多 Tool Call 顺序稳定；
- Tool Result ID 正确回传；
- 非法 arguments 被拒绝；
- 截断被识别；
- 旧 `ChatModelGateway.complete/completeStream` 全部回归通过；
- 真实 Agent 模型至少成功产生一次原生 Tool Call；
- AgentWorker 尚未切换，不得声称 Agent 2.0 已完成。
