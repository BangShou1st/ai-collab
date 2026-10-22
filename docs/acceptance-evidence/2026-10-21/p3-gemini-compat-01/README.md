# P3-GEMINI-COMPAT-01：Gemini 原生多轮工具调用与 SSE 流兼容验收报告

日期：2026-10-21（Asia/Shanghai）  
任务范围：**P3-GEMINI-COMPAT-01**（最小必要修改，保持现有 OpenAI 兼容协议、Agent、RAG、任务规划稳定）

---

## 一、基线与核验

- **目标分支**：`dev`
- **修改前 HEAD**：`2dc684df49f807a4ce9e89ab3c90692523f20015`
- **修改前工作区**：干净（clean，无未提交文件）
- **修改前 CI**：Run `39472382677`（frontend 与 backend 均成功）
- **交付状态**：停留在 `dev` 分支，未合并到 `main`

---

## 二、问题与根因分析

### P0：多轮工具调用中的 thoughtSignature 丢失

1. **原始现象**：
   在对接具备思考/推理能力的 Gemini 原生模型（如 Gemini 2.5 / 3.x）进行多轮 Agent 工具调用时，模型在返回 `functionCall` 的同时附带了加密签名 `thoughtSignature`（或 `thought_signature`）。如果下一轮向模型回传工具执行结果时丢弃了该签名，模型 API 会以 `400 INVALID_ARGUMENT` 拒绝请求。
2. **根因**：
   - `ModelToolCall` 仅包含 `id`, `name`, `arguments`，缺失可选的签名元数据承载能力。
   - `GeminiModelAdapter.turn` 在解析候选部分的 parts 时，仅提取了函数名与参数，未保留 `thoughtSignature`。
   - `GeminiModelAdapter.turnRequest` 在构建回传给模型的 `contents` 时，仅组装了 `functionCall: { name, args }`，丢失了签名字段。
   - `AgentToolCallExecutor` 与 `AgentModelMessageComposer` 在跨 tick / 跨步骤持久化与重建工具消息时，未在 `step.input()` 中保存和提取 `thoughtSignature`。
3. **修复方案**：
   - 在 `ModelToolCall` 扩展可选字段 `thoughtSignature`（提供 3 参数重载构造器保持与既有调用方完全兼容，无签名时为 `null`）。
   - 在 `GeminiModelAdapter.turn` 中解析 `thoughtSignature` / `thought_signature`，并在 `turnRequest` 序列化时在 functionCall 所在 part 中如实回传。
   - 过滤候选 parts 中标记为 `thought: true` 的思考草稿块，避免将思考过程误拼为正式文本回答。
   - 在 `AgentToolCallExecutor` 中将 `thoughtSignature` 持久化至 `step.input()`，并在 `AgentModelMessageComposer` 重建 `ModelToolCall` 时保留，确保多轮及跨 tick 恢复不丢失签名。

### P1：原生 SSE 流结束与异常截断兼容

1. **原始现象**：
   Gemini 的 `:streamGenerateContent?alt=sse` 流式接口在合法生成结束后以正常 HTTP EOF 关闭连接，不发送 OpenAI 风格的 `data: [DONE]` 哨兵。此前 `JsonHttpModelClient.readStream` 在读到 EOF 时无条件抛出 `PROVIDER_STREAM / MISSING_DONE`，导致合法的 Gemini 流被误报为失败。
2. **根因**：
   - `JsonHttpModelClient` 将 `data: [DONE]` 假定为所有 Provider 流结束的唯一合法标记。
   - 缺乏对模型完成信号（如 Gemini `candidates[0].finishReason`）与流异常中断的明确区分。
3. **修复方案**：
   - 在 `JsonHttpModelClient.stream` 增加 `requireDoneSentinel` 控制（默认 `true` 保持 OpenAI 兼容 Provider 的严格哨兵检查；Gemini 显式传 `false`）。
   - 在 `GeminiModelAdapter.completeStream` 中根据 Gemini 官方协议建立完整的完成判定状态机：
     - **正常结束**：接收到含有明确 `finishReason`（如 `"STOP"`）的候选帧，且 HTTP EOF 正常关闭 -> 调用 `onDone` 成功交付完整文本。
     - **提前截断**：未收到任何含有 `finishReason` 的帧即发生 EOF -> 明确抛出 `PROVIDER_STREAM / TRUNCATED_BEFORE_FINISH`（`AI_PROVIDER_INVALID_RESPONSE`），绝不误报成功。
     - **空流**：0 个数据块即 EOF -> 抛出 `PROVIDER_STREAM / EMPTY_STREAM`（`AI_PROVIDER_INVALID_RESPONSE`）。
     - **输出超限**：收到 `finishReason: "MAX_TOKENS"` -> 映射为 `AI_PROVIDER_OUTPUT_TRUNCATED`。
     - **安全阻断**：收到 `finishReason: "SAFETY"` / `"RECITATION"` -> 映射为 `AI_PROVIDER_REQUEST_REJECTED`。
     - **错误帧**：收到 `error` 对象信封 -> 映射为 `AI_PROVIDER_INVALID_RESPONSE`（`PROVIDER_STREAM / ERROR_ENVELOPE`）。
     - **网络连接异常/超时**：分别正确保留 `AI_PROVIDER_ERROR` 与 `AI_MODEL_TIMEOUT`。

---

## 三、修改文件清单

| 文件路径 | 修改类型 | 目的说明 |
| --- | --- | --- |
| `infrastructure/ai/turn/ModelToolCall.java` | 核心模型 | 增加可选 `thoughtSignature` 字段与兼容构造器，避免跨 Provider 污染 |
| `infrastructure/ai/model/GeminiModelAdapter.java` | Provider 适配器 | 解析与回传 `thoughtSignature`、过滤思考块、区分 SSE 正常结束与截断 |
| `infrastructure/ai/model/JsonHttpModelClient.java` | 传输客户端 | 支持 `requireDoneSentinel` 参数，允许非 [DONE] 协议合法 EOF 结束 |
| `agent/application/runtime/AgentToolCallExecutor.java` | Agent 运行时 | 在 `step.input()` 中持久化 `thoughtSignature` |
| `agent/application/runtime/AgentModelMessageComposer.java` | Agent 运行时 | 跨步骤重建消息时从 `step.input()` 还原 `thoughtSignature` |
| `infrastructure/ai/model/GeminiTurnContractTest.java` | 单元测试 | 覆盖单工具/多工具/连续多轮工具调用、签名解析与回传、思考块过滤 |
| `infrastructure/ai/model/GeminiStreamContractTest.java` | 单元测试（新增） | 独立轻量 HTTP 服务验证 Gemini SSE 正常 EOF、截断 EOF、空流、超时等 10 项契约 |
| `agent/application/runtime/AgentModelMessageComposerV2Test.java` | 单元测试 | 验证 Composer 从步骤历史还原携带 `thoughtSignature` 的工具调用 |
| `infrastructure/ai/turn/ModelTurnContractTest.java` | 单元测试 | 覆盖 `ModelToolCall` 签名有效性、空值规整与向后兼容 |
| `infrastructure/ai/model/GeminiLiveAcceptanceTest.java` | 真实验收测试 | 新增真实多轮工具调用及签名回传验证，升级 SSE 验证为无错误成功接收 |

---

## 四、自动化测试执行记录

| 阶段 / 测试目标 | 执行命令 | 结果 | 耗时 |
| --- | --- | --- | --- |
| Gemini 工具调用契约测试 | `mvnw.cmd test -Dtest=GeminiTurnContractTest` | **22/22 通过**（0 失败，0 错误） | 0.66s |
| Gemini 原生 SSE 流契约测试 | `mvnw.cmd test -Dtest=GeminiStreamContractTest` | **10/10 通过**（0 失败，0 错误） | 4.41s |
| 模型合同与消息组装测试 | `mvnw.cmd test -Dtest=ModelTurnContractTest,AgentModelMessageComposerV2Test` | **59/59 通过**（0 失败，0 错误） | 1.96s |
| 现有 Provider 回归套件（8 类） | `mvnw.cmd test -Dtest=ModelProtocolHttpTest,KiloGatewayModelExecutionStreamContractTest,KiloGatewayModelTest,ZenKnowledgeStreamContractTest,AnthropicTurnContractTest,OpenAiTurnContractTest,OpenAiTurnStreamingTest,ModelProviderAdapterContractTest` | **92/92 通过**（0 失败，0 错误） | 15.6s |
| **后端完整验证** | `mvnw.cmd -B -ntp verify` | **1576/1576 通过**（0 失败，0 错误，18 正常跳过） | 14m05s |
| **前端构建巡检** | `pnpm run build` | **通过**（1835 modules transformed，0 errors） | 20.18s |

---

## 五、真实 Provider 验收记录（Google Gemini）

使用本地配置凭据执行 `GeminiLiveAcceptanceTest`，针对 `gemini-3.1-flash-lite` 模型运行：

```text
[ACCEPTANCE] 3.1-flash-lite Minimal Connection: content=CONNECTED, model=gemini-3.1-flash-lite, latencyMs=10389, inTokens=14, outTokens=1
[ACCEPTANCE] RAG with Evidence: content=..., latencyMs=4360, inTokens=197, outTokens=66
[ACCEPTANCE] Citation Validation: invalidOutput=false, invalidCitationCount=0, citedSourcesCount=1
[ACCEPTANCE] RAG without Evidence: content=当前项目资料不足以回答该问题。, latencyMs=3663
[ACCEPTANCE] Rejection Validation: isInsufficient=true, invalidCitationCount=0
[ACCEPTANCE] Agent Turn 1: toolCallsCount=1, latencyMs=5845
[ACCEPTANCE] Agent Turn 1 ToolCall: id=gemini-call-0, args={"query":"系统数据库选型"}
[ACCEPTANCE] Live Multi-Turn: Turn 1 Call: id=gemini-call-0, name=search_knowledge, hasThoughtSignature=true
[ACCEPTANCE] Live Multi-Turn: Turn 2 Output: content=..., finishReason=STOP, latencyMs=4006
[ACCEPTANCE] Planning Output length: 1413, latencyMs=6152, inTokens=226, outTokens=636
[ACCEPTANCE] Parsed Skeleton: summary=..., milestones=3, tasks=4
[ACCEPTANCE] SSE Verified: receivedTokens=1, 2, 3., errorClassification=none, doneResult=1, 2, 3.
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### 真实 Provider 验收结论：
1. **普通文本调用**：`phase1_minimalConnection_gemini31FlashLite` 成功，输出 `CONNECTED`。
2. **真实多轮工具调用与签名回传**：`phase3_agentMultiTurnWithSignatureAndFinalAnswer` 真实调用中观察到 `hasThoughtSignature=true`，将助手消息及签名随工具结果回传后，模型第二轮正常返回最终回答，`finishReason=STOP`，无 `400 INVALID_ARGUMENT` 错误。
3. **原生 SSE 正常结束**：`phase5_streamingSseCompletionVerified` 完整接收 `1, 2, 3.`，正常 EOF 结束，`errorClassification=none`，`doneResult` 完整匹配，原 `MISSING_DONE` 缺口已彻底消除。
4. **受限与未验证项目**：未人为打满免费层 429 真实配额（429 映射由离线测试严密覆盖，避免浪费在线配额）。

---

## 六、交付与状态确认

- **分支**：`dev`
- **提交规范**：`fix(ai): support Gemini thoughtSignature in multi-turn tool calling and clean SSE EOF termination`
- **所有新增及修改文件均已受控验证**。
