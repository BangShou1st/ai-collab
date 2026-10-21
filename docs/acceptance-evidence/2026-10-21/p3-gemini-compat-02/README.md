# P3-GEMINI-COMPAT-02：Gemini 协议兼容性加固与可靠性优化验收报告

日期：2026-10-21（Asia/Shanghai）  
任务范围：**P3-GEMINI-COMPAT-02**（基于上一阶段基线执行独立代码核查、复现缺陷、最小修复及全量验证）

---

## 一、基线与核验

- **目标分支**：`dev`
- **核查起始 HEAD**：`25d86b7e628689502d9f27a17fa50d96c9f3b34a`
- **起始工作区**：干净（clean，无任何未跟踪文件）
- **起始 CI 状态**：Run `39489487456`（Frontend 与 Backend 均 completed success）
- **保护规范**：不修改 `main`，不 force push，不 reset --hard，不修改 Flyway 迁移脚本，不消耗真实限额型 Provider 配额。

---

## 二、独立代码核查与证实缺陷（先调查、再复现、再最小修复）

在对 `GeminiModelAdapter`、`JsonHttpModelClient`、`ModelToolCall`、`AgentToolCallExecutor`、`AgentModelMessageComposer` 等全链路代码进行深入核查后，证实并复现了以下 3 项缺陷：

### 缺陷 1（严重/高危）：内部 Tool Call ID 基于候选 parts 数组下标生成导致跨轮次 ID 重复冲突
1. **源码位置**：`GeminiModelAdapter.java:154`
2. **根因**：原代码采用 `id = "gemini-call-" + (existingFunctionCalls + partIndex);`。其中 `partIndex` 是当前候选所有 parts（含思考草稿块 `thought: true` 或前置 text）的索引。当首个 part 是思考块时，第一轮工具调用的 ID 会被偏移为 `gemini-call-1`。若第二轮调用直接以 `functionCall` 开头（`partIndex = 0`），且历史已有 1 个工具调用（`existingFunctionCalls = 1`），第二轮生成的 ID 亦为 `gemini-call-1`！
3. **严重影响**：两轮不同调用的 ID 完全一致。在 `AgentRepository.knownInvocationResult` 中根据 `tool_call_id` 恢复时，会触发 `调用身份与参数冲突` 抛出 `IllegalStateException`，导致 Agent 崩溃挂死；或误复用前一轮结果导致执行状态混乱。
4. **复现测试**：`GeminiTurnContractTest.multiTurnSequentialToolCallsWithThoughtBlockDoesNotDuplicateToolCallId`，修复前断言 `gemini-call-0` 实际得到 `gemini-call-1`，第二轮亦为 `gemini-call-1`，测试失败复现。
5. **最小修复**：改为基于当前轮次已生成的工具调用列表计数：`String id = "gemini-call-" + (existingFunctionCalls + toolCalls.size());`。无论 parts 中前置多少 text 或 thought 块，生成的 ID 严格全局单调递增，跨轮唯一。

### 缺陷 2（高危）：Gemini 原生请求序列化连续同 role content 违反严格交替与并行调用契约
1. **源码位置**：`GeminiModelAdapter.java:194-236`（`turnRequest` 方法）
2. **根因**：
   - 当模型返回多个并行工具调用时，Agent 执行完成后传入连续的 `ModelMessage.ToolResult`。原代码为每个 `ToolResult` 各自生成独立的 `{role: "user", parts: [...]}`，导致在 `contents` 中产生连续的多个 `role: "user"`。
   - 当传入连续的 `ModelMessage.User` 时，同样生成连续的 `role: "user"`。
   - 当 `ModelMessage.Assistant` 为空时，生成了 `parts: []` 空数组。
3. **严重影响**：Google Gemini 官方 API 严格要求对话历史必须在 `user` 与 `model` 之间交替，且并行调用的 `functionResponse` 必须合并到同一 `user` content 的 `parts` 列表中。违规时官方 API 直接报 `400 Invalid argument: Please ensure that multiturn requests alternate between user and model`。
4. **复现测试**：`GeminiTurnContractTest.turnRequestMergesConsecutiveSameRoleMessagesAndParallelToolResponses`，修复前 `contents` 长度为 5 且存在连续同 role，测试失败复现。
5. **最小修复**：在 `turnRequest` 构建 `contents` 时，若待追加消息的 role（user 或 model）与上一 content 的 role 相同，将其 parts 追加合并至上一 content 的 `parts` 数组；过滤无文本且无调用的空 Assistant 消息。

### 缺陷 3（中高危）：SSE 流式与原生非流式调用中漏检扩展安全阻断导致潜在误判成功
1. **源码位置**：`GeminiModelAdapter.java` 中的 `completeStream`、`parse`、`turn`
2. **根因**：原代码仅检查了 `"SAFETY"` 与 `"RECITATION"`，漏掉了 Gemini 协议中同属安全与违规策略的 `"BLOCKLIST"`、`"PROHIBITED_CONTENT"` 与 `"SPII"`。在流式输出中，若模型因 BLOCKLIST 终止且已产生部分字符，原代码记录该原因但在 EOF 时因该原因非 null 且 content 非空，误调用 `onDone` 将被阻断的内容交付为成功。
3. **严重影响**：违规阻断被误报为正常完成；或在 `turn` / `parse` 中被误分类为 `AI_PROVIDER_INVALID_RESPONSE`。
4. **复现测试**：
   - `GeminiStreamContractTest.finishReasonBlocklistThrowsRequestRejected`，修复前 `doneCount` 为 1 且未抛出安全拒绝异常，测试失败复现。
   - `GeminiTurnContractTest.turnWithSafetyFinishReasonThrowsRequestRejected`，修复前抛出 `INVALID_RESPONSE`，测试失败复现。
5. **最小修复**：
   - 提取 `isSafetyFinishReason` 统一识别 `SAFETY`、`RECITATION`、`BLOCKLIST`、`PROHIBITED_CONTENT`、`SPII` 并映射为 `AI_PROVIDER_REQUEST_REJECTED`。
   - 在流式完成判定中增加严格约束：`!"STOP".equals(finishReason.get())` 必须作为 `PROVIDER_STREAM / UNEXPECTED_FINISH_REASON` 拒绝，唯有明确的 `"STOP"` 且正常 EOF 才允许判定为成功。

---

## 三、审查但未发现缺陷的项目确认

1. **thoughtSignature 跨持久化生命周期**：
   已全面审查 `AgentToolCallExecutor.input(call)`、`step.input()` 数据库 JSON 落库、`AgentModelMessageComposer.extractThoughtSignature`。签名在单工具、多工具、多轮及跨 tick 恢复中均被忠实提取与回传，且对非 Gemini Provider 与历史旧数据具备良好兼容（无签名时安全回退为 null）。
2. **Provider 故障与重试语义边界**：
   现有 `JsonHttpModelClient` 对 408/504 网络读取超时具备有界指数退避重试（受单次请求 deadline 严格约束）；对 429 配额不足、401 凭证无效等非超时错误即刻抛出对应 `BusinessException`。未引入可能引发重复副作用的无限制盲目重试，既有审批与幂等边界完好。
3. **非 Gemini Provider 严格隔离**：
   `OpenAiCompatibleModelAdapter`、`AnthropicModelAdapter`、`KiloGatewayClientTransport` 等既有 Provider 适配器完全独立于 Gemini 专属逻辑。`requireDoneSentinel` 默认保持 `true` 保护既有 Provider 的严格哨兵检查。

---

## 四、修改文件清单

| 文件路径 | 修改类型 | 目的说明 |
| --- | --- | --- |
| `ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/GeminiModelAdapter.java` | 生产代码（Provider 适配器） | 修复 ID 跨轮冲突、保持 Gemini 严格交替契约、合并并行工具响应、扩展安全阻断识别并阻断非 STOP 异常结束 |
| `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/model/GeminiTurnContractTest.java` | 测试代码 | 增加思考草稿块跨轮多调用 ID 唯一性测试、严格交替与并行结果合并契约测试、安全阻断映射测试 |
| `ai-collab-backend/src/test/java/com/shitulelv/aicollab/infrastructure/ai/model/GeminiStreamContractTest.java` | 测试代码 | 增加 SSE 流黑名单与安全策略阻断拒绝回归测试 |

---

## 五、自动化测试与全量回归记录

| 阶段 / 测试目标 | 执行命令 | 结果 | 耗时 |
| --- | --- | --- | --- |
| 定向回归与新增复现套件 | `mvnw.cmd test -Dtest=GeminiTurnContractTest,GeminiStreamContractTest,ModelTurnContractTest,AgentModelMessageComposerV2Test` | **95/95 通过**（0 失败，0 错误） | 19.69s |
| 现有 Provider 回归套件（8 类） | `mvnw.cmd test -Dtest=ModelProtocolHttpTest,KiloGatewayModelExecutionStreamContractTest,KiloGatewayModelTest,ZenKnowledgeStreamContractTest,AnthropicTurnContractTest,OpenAiTurnContractTest,OpenAiTurnStreamingTest,ModelProviderAdapterContractTest` | **92/92 通过**（0 失败，0 错误） | 12.46s |
| **后端全量验证** | `mvnw.cmd -B -ntp verify` | **1580 项：1555 通过，0 失败，0 错误，25 跳过** | 7m39s |
| **真实容器守卫核对** | 校验 10 个 required Testcontainers 用例 Surefire XML 报告 | **10/10 全部真实执行**（0 失败，无整类跳过） | 瞬时 |
| **前端构建巡检** | `pnpm run build` | **通过**（1835 modules transformed，0 errors） | 4.91s |
| 代码格式与差异检查 | `git diff --check` | **通过**（无 trailing whitespace，无空白冲突） | 瞬时 |

---

## 六、CI 运行与交付状态确认

- **交付分支**：`dev`
- **起始 SHA**：`25d86b7e628689502d9f27a17fa50d96c9f3b34a`
- **修复提交 SHA**：`872a7ad6c9d9b005223b717700449eaf50e0850c`
- **提交信息**：`fix(ai): harden Gemini tool call ID isolation, alternating role contract and SSE safety termination`
- **GitHub Actions CI 结果**：
  - **代码提交 CI**：[Run `39497711679`](https://github.com/BangShou1st/ai-collab/actions/runs/39497711679)，attempt 1，head SHA `872a7ad6c9d9b005223b717700449eaf50e0850c`，Frontend 与 Backend Job 均成功。
  - **文档提交 CI**：[Run `39498724955`](https://github.com/BangShou1st/ai-collab/actions/runs/39498724955)，attempt 1，head SHA `0bbfd38f5f8ab4fd5a533d542a48878d774a5d1b`，Frontend 与 Backend Job 均成功；此 SHA 是实际文档提交 SHA。
  - 两次 Backend Job 的 Surefire 统计均为 **1580 项、1555 通过、0 失败、0 错误、25 跳过**。其中原有 18 项环境门控测试跳过；`GeminiLiveAcceptanceTest` 共 8 项，7 项真实 Gemini 测试因未提供 key 而跳过，1 项本地错误分类测试执行通过。普通 CI 未产生真实 Gemini 调用证据。
  - 两次 CI 的容器测试守卫均通过：10/10 个 required 测试类实际执行，无整类跳过。

---

## 七、开放风险、未验证事项与回退方式

1. **未验证事项**：
   - P3-GEMINI-COMPAT-02 的两次普通 CI 均未调用真实 Gemini；`GeminiLiveAcceptanceTest` 的 7 项真实调用因未提供 key 而跳过。429、503 等错误仍由模拟 HTTP 服务与 Mockito 定向单元测试覆盖。
2. **回退方式**：
   - 若需回退本阶段代码，普通回退命令为 `git revert 872a7ad6c9d9b005223b717700449eaf50e0850c`。执行前应检查差异；若后续提交改动了相同位置，需按实际冲突解决。该命令不保证无冲突，也不保证工作树精确恢复到某个历史快照。
3. **后续建议**：
   - 保持当前基线，后续如需接入 Gemini 3.x 图像/多模态输入，可沿用本轮规范化后的 alternating role 与 parts 结构。
