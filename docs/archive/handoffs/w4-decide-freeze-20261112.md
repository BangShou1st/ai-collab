# W4 施工冻结（J4 + P2：query plan 契约 + agent decide shadow）

> 日期：2026-11-12
> 基线：W3 收尾后 `dev`（rerank 默认 `java` 不变）
> 设计依据：[Python AI 能力平面](../../design/python-ai-capability-plane-2026-11-11.md) §1.4 / §4 / §5.2（J4 退出）/ §5.3（J4 边界）/ §6.3（P2）/ §7.1 / §7.4 / §7.5 / §7.8 / §9 / §10
> 上游：[W3 收尾冻结](w3-final-freeze-20261112.md)、[J3 报告](../report/j3-rerank-20261112.md)
> 性质：Phase 0 冻结。**先冻结、再实现**；实现期偏离必须回写并说明。

---

## 0. 范围

**做**

| 侧 | 交付 | 默认 |
| --- | --- | --- |
| Python | `POST /v1/rag/plan`（§7.4 契约、PromptPack、解析、边界、快照、测试） | 端点可用但无生产调用方 |
| Python | `POST /v1/agent/decide`（§7.5 契约、四决策、工具校验、幂等、deadline、测试） | 端点可用 |
| Python | `ai-eval decide`（冻结集、决策一致率/JSON 合法率/无效工具调用数） | 离线 |
| Java | `decide.mode=java|shadow`（默认 `java`；`python` 配置直接启动失败） | `java` |
| Java | `AgentDecideShadowService`：工具轮边界有界快照 + 异步有界执行 + 指标；**只 `PROJECT_RESEARCH` 只读** | 关闭 |
| Java | `QueryPlan` 侧 **只做契约测试**（客户端 + DTO + stub 测试），不接入检索 | 不接线 |

**不做**：不改 `rerank.mode`、不切 `decide.mode=python`、不让 Python 结果进入执行/落库/事件流；
不改前端；零迁移；不新增第二运行时；不改 `AgentLoopGuard` / `AgentApprovalService` / `ProjectAccessGuard`。

**红线**：Python 结果只是建议；Java 每次仍重校验工具名/Schema/项目权限/`grantedTools`/写审批/预算/幂等。
本波 shadow **绝不**把 Python 的 toolCall 传给 `AgentToolCallExecutor`。

---

## 1. Python `POST /v1/rag/plan`（§7.4 落地版）

请求：

```json
{"schemaVersion":1,"query":"…","locale":"zh-CN","historySummary":"…",
 "documentIds":["doc-1","doc-2"],"deadlineMs":8000}
```

| 字段 | 约束 |
| --- | --- |
| `query` | 1–4000，去首尾空白非空 |
| `locale` | 2–35，`^[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*$` |
| `historySummary` | 0–8000，可缺省 |
| `documentIds` | 0–200，每项 1–128、同一请求唯一 |
| `deadlineMs` | 1–30000，默认 8000；硬 deadline，超时 `DEADLINE_EXCEEDED`(504)，无半成品 |

响应：

```json
{"schemaVersion":1,"rewrittenQuery":"…","subQueries":["…"],
 "filters":{"documentIds":["doc-1"],"docTypes":["SPEC"],"dateRange":{"from":"2026-01-01","to":"2026-06-30"}},
 "confidence":0.78}
```

- `rewrittenQuery` 1–4000；`subQueries` 0–8，每项 1–500，请求内不重复；
- `filters` **只允许** `documentIds` / `docTypes` / `dateRange` 三个键（`extra=forbid`）；
  - `documentIds` ⊆ 请求 `documentIds`（模型编造的 id 丢弃并记 `query_plan_unknown_document_ids_total`，不报错）；
  - `docTypes` 0–10，每项 1–64，大写白名单由 Java 侧再校验（Python 只做形状与长度）；
  - `dateRange` 为 `{from,to}`，ISO-8601 日期（`YYYY-MM-DD`），两端可空，`from<=to`；
- `confidence` ∈ [0,1]；`confidence<0.5` 时 **Java 使用原查询**（本波无调用方，规则写入契约测试
  与后续接线文档，不改检索）。
- 解析：允许 Markdown 围栏包裹的单个 JSON object；字段缺失/类型错/越界 → `SCHEMA_VIOLATION`(422)。
- 幂等：无（只读建议端点）；`Idempotency-Key` 可选支持与否**不要求**。
- Prompt：PromptPack 新增 `query_plan.system`、`query_plan.user`（版本化；只加不删）。

---

## 2. Python `POST /v1/agent/decide`（§7.5 落地版）

请求：

```json
{
  "schemaVersion":1,
  "runId":"…","attempt":2,"turnNo":5,"snapshotHash":"sha256:…",
  "scenario":{"code":"PROJECT_RESEARCH","category":"SCENARIO","promptPack":"scenario.project_research@2026-11-11.1"},
  "goal":"…","locale":"zh-CN",
  "context":{
    "facts":["…"],
    "messages":[{"role":"USER","content":"…"}],
    "observations":[{"tool":"search_project_knowledge","summary":"…"}]
  },
  "tools":[{"name":"search_project_knowledge","description":"…","inputSchema":{…},"risk":"READ_ONLY"}],
  "policy":{"allowWrite":false,"allowExternal":false},
  "budget":{"maxInputTokens":24000,"maxOutputTokens":2000,"deadlineMs":30000}
}
```

| 字段 | 约束 |
| --- | --- |
| `runId` | 1–128 |
| `attempt` | 1–50 |
| `turnNo` | 0–500 |
| `snapshotHash` | 1–128，建议 `sha256:<hex>` |
| `scenario.code` | 1–64，`^[A-Z][A-Z0-9_]{1,63}$` |
| `scenario.category` | `SCENARIO` \| `WORKFLOW` |
| `scenario.promptPack` | 0–160，可缺省（仅追溯用） |
| `goal` | 1–4000 |
| `locale` | 同 §1 |
| `context.facts` | 0–20 项，每项 1–2000，总 ≤16000 字符 |
| `context.messages` | 0–20 项，`role ∈ USER/ASSISTANT/TOOL`，`content` 1–4000，总 ≤24000 字符 |
| `context.observations` | 0–20 项，`tool` 1–128、`summary` 1–2000，总 ≤16000 字符 |
| 上下文总字符 | ≤ 48 KiB（同时满足 Java 网关 64 KiB prompt 上限） |
| `tools` | 1–32；`name` 1–128、`description` 1–1000、`inputSchema` JSON object（≤8 KiB/项）、`risk ∈ READ_ONLY/WRITE`；name 唯一 |
| `policy` | 布尔；Python 不得因 `allowWrite=true` 扩大工具集 |
| `budget.maxInputTokens` / `maxOutputTokens` | ≥1，≤ 2_000_000 |
| `budget.deadlineMs` | 1–600000，硬 deadline |

响应：

```json
{"schemaVersion":1,"decision":"CALL_TOOL",
 "toolCall":{"name":"search_project_knowledge","arguments":{"query":"…"}},
 "usage":{"inputTokens":8123,"outputTokens":96,"source":"PROVIDER"},
 "promptVersion":"2026-11-11.1","modelAlias":"agent-main","latencyMs":2140}
```

规则：

1. `decision ∈ {CALL_TOOL, FINAL, ASK_USER, NO_OP}`；其它值 → `SCHEMA_VIOLATION`(422)；
2. `CALL_TOOL` 必须带 `toolCall.name`（∈ 本次 `tools`）与 `arguments`（JSON object）；其它决策
   `toolCall` 必须缺省或 `null`；
3. 模型给出不在 `tools` 内的工具名 → Python **内部重试一次**（附纠错指令）；仍非法 →
   `SCHEMA_VIOLATION`(422)，并记 `ai_worker_decide_invalid_tool_call_total`；
4. 幂等键 `(runId, attempt, turnNo, snapshotHash)`：命中同 body 指纹 → 逐字节重放；
   同键不同 body → `INVALID_REQUEST`(400)；缓存 10 分钟、上限 1024、只缓存成功；
5. Python **不接收也不解释** `leaseEpoch`、不返回 token 流、不写任何业务表；
6. `promptVersion` 取本次实际使用的 PromptPack 版本（decide 主 Prompt 的 manifest version）；
7. `latencyMs` 为本次模型调用耗时（来自 Java 网关 `latencyMs`），缺失为 `null`；
8. `usage` 缺失标 `UNKNOWN`、token 为 `null`，不填 0。

Prompt：新增 `decide.system`、`decide.user`（版本化）。scenario 文案由 Java 传 `goal`/`scenario`，
Python 不自行拼接场景指令正文以外的权限语义；Prompt 中明确"只输出 JSON"。

---

## 3. Java `decide.mode`

```yaml
decide:
  mode: "${DECIDE_MODE:java}"          # java | shadow | python（python 未实现，配置即启动失败）
  worker-base-url: "${DECIDE_WORKER_BASE_URL:http://ai-worker-api:9100}"
  deadline-ms: "${DECIDE_DEADLINE_MS:30000}"
  max-concurrency: "${DECIDE_MAX_CONCURRENCY:8}"
  max-tools: "${DECIDE_MAX_TOOLS:16}"
  snapshot-max-chars: "${DECIDE_SNAPSHOT_MAX_CHARS:32000}"
```

- `java`（默认）：**零外呼、零快照、零指标**（与未实现时完全一致）；
- `shadow`：只在满足全部条件的工具轮边界发起后台调用；任何失败只记指标；
- `python`：本波不实现，绑定阶段抛 `IllegalStateException`（防止"以为切了"）。

### 3.1 触发条件（全部满足才发起）

1. `mode == shadow`；
2. `run.depth() == 0` 且 `scenario.code() == "PROJECT_RESEARCH"`，`scenario.allowWriteTools() == false`；
3. 本轮 `turn.toolCalls()` 非空（工具轮边界；纯文本轮不 shadow），且每个工具都能在本次
   `exposed` 定义里找到且 `writesBusinessData() == false`；
4. 快照构造后总字符 ≤ `snapshot-max-chars`（超限截断并记 `decide.shadow.truncated`，仍可发送）；
5. 信号量可用；否则 `decide.shadow.skipped{reason="saturated"}`。

### 3.2 快照（有界、稳定哈希）

```
{runId, attempt(=retryCount+1), turnNo(=stepsUsed+1), snapshotHash,
 scenario{code, category}, goal(≤2000，截断),
 locale(固定 "zh-CN" 或 run.pageContext 中的 locale，取不到用 "zh-CN"),
 context{facts≤8(每条≤500), messages≤6(最近，每条≤800), observations≤6(工具名+结果摘要≤400)},
 tools: 本次 exposed 中的只读工具 ≤maxTools（name/description≤300/inputSchema 压缩≤2000/risk）,
 policy{allowWrite=false, allowExternal=false},
 budget{maxInputTokens: 剩余输入预算或 24000 上限, maxOutputTokens: 1000, deadlineMs}}
```

- `snapshotHash = "sha256:" + sha256(canonicalJson(除 snapshotHash 外的快照))[0:32]`；
  canonical JSON：键排序、无空格、UTF-8；
- 快照**不含**：用户原始消息全文、文档正文、工具结果全文、令牌、任何密钥、项目成员名单；
- 快照只在内存构造，不落库、不进事件、不写日志内容。

### 3.3 执行与隔离

- 虚拟线程 + `Semaphore(maxConcurrency)`；提交前 `tryAcquire`，饱和记 skipped 并返回；
- HTTP：connect 2s、read `deadlineMs+5s`；`Authorization: Bearer <短令牌或静态令牌>`；
  `Idempotency-Key = sha256(runId|attempt|turnNo|snapshotHash)`；`X-Call-Id` 同键前缀；
- 重试：本端点天然幂等，但 shadow 不得放大延迟，**不重试**；失败只记 `reason`；
- 超时/非法 JSON/契约不符/`decision` 未知 → 记 failure/contract_violation，丢弃；
- 不写 `agent_run*` 任何表，不发 SSE，不修改 `turn`/`steps`/`exposed`。

### 3.4 Java 侧"决策"与一致率

Java 决策从**本轮真实响应**推导（不重新问模型）：

| 实际 | Java decision |
| --- | --- |
| `turn.toolCalls()` 非空 | `CALL_TOOL`，`toolName = 第一个调用的 name` |
| 无工具、`content` 非空 | `FINAL` |
| 其它（不会发生，防御） | `NO_OP` |

一致率：
- 类型相同且（非 `CALL_TOOL` 或工具名相同）→ `match`；
- 记 `decide.shadow.agreement{result=match|mismatch}` 与 `decide.shadow.calls{decision}`；
- 无效工具调用：Python 返回的 `toolCall.name` 不在本次 `tools` 内 → `decide.shadow.invalid_tool_call`；
- JSON 合法率：HTTP 200 且响应可解析为契约对象 / 全部尝试 → `decide.shadow.contract{result=valid|invalid}`；
- 延迟：`decide.shadow.latency`。

### 3.5 指标（Micrometer，低基数）

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `decide.shadow.calls` | Counter | `decision`(CALL_TOOL/FINAL/ASK_USER/NO_OP) |
| `decide.shadow.agreement` | Counter | `result`(match/mismatch) |
| `decide.shadow.invalid_tool_call` | Counter | 无 |
| `decide.shadow.contract` | Counter | `result`(valid/invalid) |
| `decide.shadow.failure` | Counter | `reason`(timeout/unavailable/schema/http/unexpected) |
| `decide.shadow.skipped` | Counter | `reason`(disabled/scenario/turn/concurrency/saturated) |
| `decide.shadow.truncated` | Counter | 无 |
| `decide.shadow.latency` | Timer | `outcome`(success/failure) |

---

## 4. Java `QueryPlan`（只做契约，不接线）

- `retrieval.queryplan.QueryPlanGatewayClient`：`POST /v1/rag/plan`，connect 2s / read
  `deadlineMs+5s`，`Authorization` 同内部令牌，**不重试**；
- DTO 与 Python 契约逐字段对应；`filters` 解析时只接受三类键，其它键视为契约违规；
- `query.plan.enabled=false` 默认；本波**没有**任何检索调用方（只被测试构造）；
- 契约测试：本地 `HttpServer` stub 覆盖正常/缺字段/未知 filter 键/HTTP 500/超时；
- 不新增迁移、不改检索管线、不影响 `rerank.mode`。

---

## 5. `ai-eval decide`

冻结集：`ai-collab-ai-worker/tests/fixtures/decide_set/decide_baseline.v1.jsonl`

每条 case：

```json
{"id":"d-001","scenario":{"code":"PROJECT_RESEARCH","category":"SCENARIO"},
 "goal":"…","locale":"zh-CN","context":{"facts":[],"messages":[],"observations":[]},
 "tools":[…],"policy":{"allowWrite":false,"allowExternal":false},
 "budget":{"maxInputTokens":24000,"maxOutputTokens":1000,"deadlineMs":30000},
 "expected":{"decision":"CALL_TOOL","toolName":"search_project_knowledge"},
 "modelOutput":"{\"decision\":\"CALL_TOOL\",\"toolCall\":{…}}"}
```

- `expected` 是人工标注的正确决策；`modelOutput` 是**冻结的代表性模型输出**（用于 `replay` 打分器，
  不声称来自真实模型）；
- 打分器：
  - `replay`（默认）：不联网，把 `modelOutput` 走**真实解析/校验核心**，产出决策；
  - `worker`：`POST /v1/agent/decide` 打真实 worker（需 `--worker-url` 与令牌），用于将来 staging；
- 指标：`decisionAgreement`（决策类型+工具名一致 / 总数）、`jsonValidityRate`（可解析且契约合法的
  输出 / 总数）、`invalidToolCalls`（返回了工具名但不在 `tools` 内的条数）、`schemaViolations`、
  `cases`、`confidence`；
- 目标（设计 §10.2）：一致率 ≥98%、JSON 合法率 ≥99.5%、无效工具调用不增（本波基线 = 0）；
- **无真实模型 → 结果标 `TESTED (stub)` + `LOW_CONFIDENCE`**；真实模型结果只能在 staging 产生，
  本会话不可达 → `NOT_VERIFIED`；
- canonical 口径沿用 `ai-eval`（两次运行逐字节一致、含 sha256、不打印正文）。

---

## 6. 默认开关与回滚

| 开关 | 默认 | 作用 | 回滚 |
| --- | --- | --- | --- |
| `DECIDE_MODE` | `java` | java/shadow | 设 `java` + 重启 |
| `DECIDE_WORKER_BASE_URL` | `http://ai-worker-api:9100` | worker 地址 | 无需（mode=java 不访问） |
| `DECIDE_DEADLINE_MS` | `30000` | shadow 调用 deadline | 调小或 mode=java |
| `DECIDE_MAX_CONCURRENCY` | `8` | 后台并发上限 | 调小；饱和只丢 shadow |
| `DECIDE_MAX_TOOLS` / `DECIDE_SNAPSHOT_MAX_CHARS` | `16` / `32000` | 快照边界 | 无需 |
| `QUERY_PLAN_ENABLED` | `false` | 本波无调用方 | 保持 false |

`rerank.mode` / `planning.draft.mode` 默认值不变。

---

## 7. 停止条件

1. 一致率/合法率无法在无真实模型下达到目标 → 如实标 `TESTED (stub)`/`LOW_CONFIDENCE`，不宣称达标；
2. 若 shadow 实现需要改 `AgentToolCallExecutor` / 审批 / 租约 / 权限语义 → 停止该实现，只交付
   Python 端点 + Java 契约测试，报告卡点；
3. Python 返回被误用于执行（哪怕测试里）→ 视为严重违规，回退该实现并记录；
4. 默认路径出现任何行为变化（事件、延迟、DB 写入）→ 回退。

---

## 8. 证据等级（实现后回填）

| 项 | 预期 |
| --- | --- |
| Python plan/decide 契约、边界、幂等、错误码 | TESTED |
| Java decide shadow 只读场景、快照、指标、失败隔离 | TESTED（stub worker） |
| 决策一致率/JSON 合法率/无效工具调用 | TESTED (stub, replay) + LOW_CONFIDENCE |
| 真实模型一致率 | NOT_VERIFIED（本会话无 staging） |
| QueryPlan Java 契约 | TESTED（stub） |
