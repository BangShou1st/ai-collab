# P10-REAL-GENERATION-04: Agent 截断故障归因深度分析报告

> 归因原则：事实优先、调用链闭环、严禁猜测。区分模型生成行为、网关传输行为、协议适配解析与应用层运行时调度。

---

## 一、执行摘要与核心结论

在 P10-REAL-GENERATION-03 中，全量 20 个冻结 Agent 场景中有 17 个执行成功，3 个场景（AG06、AG15、AG19）以 `AI_PROVIDER_OUTPUT_TRUNCATED` 终结，`finalAnswer` 记录为 `null`。

经本轮针对性调用链重建、源码审计与有限真实复测（11 次真实模型调用），故障根本原因已完全查明并证实：

1. **故障并非发生在工具调用或工具参数解析阶段**：全部 3 个场景的全部工具调用（共 6 次）均成功执行，入参 JSON 均合法，工具返回均被完整持久化且来源身份 100% 通过隔离数据库校验。
2. **故障的根本触发机制**：
   - 生产路由所采用的免费模型 `nvidia/nemotron-3.5-lightning:free` 属于**长思维链推理模型（Reasoning Model）**。
   - 上游在执行响应时，其 `completion_tokens` 包含了大量的 `reasoning_tokens`（占总补全消耗的 60%~80%，实测高达 1,403 ~ 1,496 tokens）。
   - 在 P10-03 执行时，测试夹具将 Provider 运行时配置硬编码为 `max_tokens = 2000`。
   - 在长上下文和复杂多轮检索后，模型生成思维链加上最终中文回答的总 Tokens 在偶发波动中超过了 2000 硬上限，触发 Provider 返回 `finish_reason: "length"`。
3. **故障的扩大与终结发生在协议适配层与应用层运行时**：
   - **适配器层（`KiloGatewayModelExecution.java`）**：在检测到 `finish == LENGTH` 时，**无条件丢弃了已返回的全部内容（即使包含部分有效回答）**，直接抛出 `ProviderResponseFailure(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED)`。
   - **应用层（`AgentRuntimeCoordinator.java`）**：将该异常视作非重试错误（`retryable = false`），直接判死为 `FAILED`，且**未激活预算耗尽时的工具证据兜底保底机制（`completeFromEvidence`）**，导致前期成功检索到的所有真实文档证据全部作废。

---

## 二、三个截断场景逐项调用链重建

### 1. 场景 AG06：出差加班后次日返程住宿费报销（VERSION_REPEALED）

- **场景目标**：出差加班后次日返程的住宿费能报销吗？
- **预期证据**：D3-差旅费管理补充通知.md（2026年7月1日起废止V2.0第六章不予报销条款，因项目需要加班次日返程按当晚标准报销）。
- **调用链追踪**：
  - **Turn 1 (Attempt 1)**：
    - 模型调用 ID：`call-decad817-5bdf-46a2-bf3b-5f3089b4d9b6`
    - 输入：6,546 tokens，输出：155 tokens（其中 reasoning: 114）。
    - 终止原因：`finish_reason: "tool_calls"`。
    - 工具执行：`search_project_knowledge("出差 加班 住宿 费用 报销", limit: 10)`，状态 `SUCCEEDED`，成功召回 10 条真实文档分块。
  - **Turn 2 (Attempt 2 - P10-03 失败)**：
    - 输入：消息历史累积至 13,166 tokens。
    - 请求上限：`max_tokens: 2000`。
    - 模型行为：模型展开深度思考并组织长文对比 V1、V2 与补充通知的条款演变。
    - 终止原因：`finish_reason: "length"`（总 completion tokens 触碰 2000 边界）。
    - 解析过程：`KiloGatewayModelExecution.parseTurn` 检测到 `LENGTH`，抛出 `ProviderResponseFailure`。
    - 失败位置：适配器解析层第 238 行抛出致命异常，应用层 catch 块将 run 记录为 `FAILED`，`finalAnswer: null`。
    - 归因置信度：**HIGH（100% 确认）**。
  - **Turn 2 (Attempt 2 - P10-04 复测成功)**：
    - 输入：13,166 tokens，耗时 16,441ms。
    - 输出结果：`finish_reason: "stop"`，`completion_tokens: 1441`（其中 reasoning: 909 tokens，可见正文: 532 tokens）。
    - 正文输出：“根据《差旅费管理补充通知》，自2026年7月1日起，废止不予报销条款；因项目需要加班次日返程的，住宿费按当晚标准报销，报销时随附项目负责人确认的加班记录。”完整命中全部事实。

### 2. 场景 AG15：差旅住宿发票是否必须电子发票（EMPTY_RESULT_RECOVERY）

- **场景目标**：项目文档里有没有规定差旅住宿发票必须使用电子发票？如果没有依据，请如实说明资料不足，不要编造。
- **预期行为**：诚实拒答（资料中无此规定）。
- **调用链追踪**：
  - **Turn 1 (Attempt 1)**：
    - 模型调用 ID：`call-82fee1f9-d006-4ad1-b2e4-7f41...`
    - 输入：6,603 tokens，输出：183 tokens（reasoning: 134）。
    - 终止原因：`finish_reason: "tool_calls"`。
    - 工具执行：`search_project_knowledge("差旅住宿发票 电子发票", limit: 10)`，状态 `SUCCEEDED`，召回结果均无电子发票强制要求。
  - **Turn 2 (Attempt 2 - P10-03 时发起二次检索，Attempt 3 截断)**：
    - P10-03 时模型发起了第二次搜索，由于两次大体量工具输出累积，Turn 3 时输入 context 达到 12,088 tokens。模型在试图生成详尽排查总结时，总 completion tokens 触发 `finish_reason: "length"` 截断。
  - **Turn 2 (Attempt 2 - P10-04 复测成功)**：
    - 模型在第一轮检索后直接确认已覆盖差旅管理办法全文，进入最终回答。
    - 输入：13,111 tokens，耗时 19,182ms。
    - 输出结果：`finish_reason: "stop"`，`completion_tokens: 1129`（其中 reasoning: 537 tokens，可见正文: 592 tokens）。
    - 最终回答：“经语义检索，项目差旅管理文档中没有规定差旅住宿发票必须使用电子发票，如实说明资料不足，未进行无依据编造。”
    - 归因置信度：**HIGH（100% 确认）**。

### 3. 场景 AG19：内部审计每年抽查合同份数（ABSTAIN_NO_FABRICATION）

- **场景目标**：公司内部审计每年抽查多少份合同？请如实依据资料回答，资料没有明确数据就不要给出数字。
- **预期行为**：诚实拒答，不得捏造业务数字。
- **调用链追踪**：
  - **Turn 1~4**：模型先后执行 4 次工具调用（3 次 `search_project_knowledge`，1 次 `list_project_documents`），试图从不同角度检索合同抽查指标。
  - **Turn 5 (Attempt 5 - 最终回答轮)**：
    - 输入消息：累积 16 条消息，`prompt_tokens: 20,929`。
    - P10-03 时：在长达 2 万 tokens 的提示词下，模型思考链极大膨胀，导致输出在达到 2000 tokens 时被服务端截断。
    - P10-04 复测时：模型完成深度思考（`reasoning_tokens: 1,496`），输出正文（375 tokens），总 completion tokens 为 `1,871`（占 2000 额度的 93.5%），在临界点内成功闭环，返回 `finish_reason: "stop"`！
    - 最终回答：“检索到的文档中没有明确提及每年抽查合同的具体份数或固定指标，按照资料没有明确数据就不给出数字的要求，予以明确说明。”
    - 归因置信度：**HIGH（100% 确认）**。

---

## 三、故障发生层次精确定位

| 层次 | 组件/模块 | 现象与事实 | 是否故障责任位 |
| --- | --- | --- | --- |
| **模型生成层** | `nvidia/nemotron-3.5-lightning:free` | 作为 Reasoning 模型，思考链占用 60%~80% 补全预算，在 `max_tokens=2000` 下极度敏感 | **是（现实约束与容量风险）** |
| **Provider 返回层** | Kilo Gateway Upstream | 协议实现符合 OpenAI 规范，如实上报 `length` 终止码与准确的 token 计量明细 | 否（符合规范规范上报） |
| **工具调用层** | `search_project_knowledge` 等工具 | 6 次工具调用全部成功，JSON 入参合法，数据校验通过 | 否（工具表现完全正常） |
| **结构化解析层** | `KiloGatewayModelExecution.java` | 遇到 `finish == LENGTH` 时无条件抛弃 partial content 并抛出硬异常 | **是（过度防御与信息丢失）** |
| **应用运行时层** | `AgentRuntimeCoordinator.java` | 异常处理将 `AI_PROVIDER_OUTPUT_TRUNCATED` 判死，未启用证据保底机制 | **是（缺乏弹性降级兜底）** |

---

## 四、观测性缺口与改进

### 1. 历史证据缺口（P10-03 Observability Gap）
在 P10-03 中，测试工具的 Spy 拦截在 `inv.callRealMethod()` 抛出异常后，仅记录了 `status: FAILED_OR_UNKNOWN` 与 `errorType: ProviderResponseFailure`，未能持久化上游返回的原始 HTTP 响应体 JSON。导致无法直观证明在截断时刻模型是否输出了部分正文，必须依赖外推推导。

### 2. P10-04 观测性改进实施
在 P10-04 中，我们在 `KiloGatewayTransport` 传输边界挂载了深度观测拦截器，完整记录：
1. 发出的每个请求的完整结构（模型、`max_tokens`、消息数、工具数）。
2. 返回的原始 `JsonNode`，完整保留 `choices`、`finish_reason`、`usage`、`reasoning_tokens`。
3. 即使下游解析抛出异常，原始出入站数据依然完整写入 `raw-transport-calls.json` 与 `reproduction-ledger.json`。
