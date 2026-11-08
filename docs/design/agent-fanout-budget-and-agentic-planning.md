# 设计：子 Agent 并行编排治理、预算治理 v3 与 Agentic 任务规划

> 状态：草案（待评审）
> 日期：2026-11-08
> 关联：`docs/design/agent-runtime-design.md`（运行时基线）、`docs/design/agent-budget-and-resume-design.md`（v2 预算语义）、`docs/design/agent-experience-design.md`
> 本文所有"现状"均经源码核实，标注了文件与行号；行号以 2026-11-08 的 dev 分支为准。

---

## 0. 背景与问题定义

Agent 运行时（`agent/`）与任务规划（`planning/`）目前存在三类结构性缺口：

| # | 问题 | 一句话现状 |
|---|---|---|
| P1 | 子 Agent 只有单只读委派，串行单飞，天花板低 | 每次委派必须独占批次，父运行同一时刻最多一个在途子运行 |
| P2 | "循环"风险：重复委派烧 token | 无跨 run 的委派去重；`AgentLoopGuard` 签名按 run 隔离 |
| P3 | token 只记账不管控 | v2 预算语义下累计 token 上限为 NULL，不参与准入与停机 |
| P4 | 任务规划是纯 workflow，不是 agentic | planner 无工具、批次写死 4、修复上限 1 次、零反思 |

---

## 1. 现状事实（源码核实）

### 1.1 子 Agent 链路现状

- 全库唯一委派链路：`delegate_document_research` 工具 → `AgentToolCallExecutor.executeDelegation()`（`AgentToolCallExecutor.java:395-429`）→ `AgentRunEventRecorder.documentResearchDelegationResult()`（`AgentRunEventRecorder.java:1897-2007`）。
- **串行单飞**：批次中委派与其他工具调用混用直接拒绝（`DELEGATION_BATCH_LIMIT`，`AgentToolCallExecutor.java:216-222`）；父运行受理后立即转 QUEUED 挂起（`AgentRunEventRecorder.java:1990-1997`）。
- **深度硬限 1**：DB CHECK `ck_agent_run_depth`（`V22__add_agent_runtime.sql:62-65`）+ 受理入口 `depth != 0` 硬拒（`AgentRunEventRecorder.java:1900-1902`）；子运行 `max_children=0`。
- **子 Agent 白名单只有 5 个只读工具**，不含委派工具本身（`DocumentResearchDelegateAgentTool.java:26-28`）——递归环在结构上不可能。
- **回传半结构化**：只回最终文本，截断 20000 字符（`AgentRunEventRecorder.java:614`），工具原文不回传（L1712-1714 注释）；coverage 仅从持久化工具结果推导。
- **唤醒靠轮询**：子终态把父转 QUEUED，父真正继续要等 `AgentRuntimeJob` 下一轮 tick（默认 1000ms，`AgentDynamicScheduleRegistrar.java:31`）。

### 1.2 预算体系现状

- **v2 语义（所有新运行）**：`createRun` 显式写 `max_input_tokens=NULL, max_output_tokens=NULL`（`AgentRepository.java:181-190`），只保留 `max_steps=64 / max_tool_calls=64 / 模型轮 24 / 活跃时长 45min / max_children=3 / retry≤10`。**累计 token 只记账（`_used` + `_actual` 双列、`PROVIDER/ESTIMATED/UNKNOWN` 三源），不参与任何准入或停机**（`AgentResourcePolicy` 类注释 L15-18 明示）。
- worker 准入每 tick 重查（`AgentWorker.java:92-108`），但 v2 下 token 判据恒不触发。
- **无任何软阈值**：全 agent 包无 80%/soft 处理；最接近的是 `AgentConvergencePolicy.needsFinalRequest` 的启发式预留（v2 下只看剩余推进步）。
- **子 Agent 预算 = 独立默认值，不从父切分**：`AgentDelegationAdmission.split`（`AgentDelegationAdmission.java:110-132`）v2 返回 `(16 步, 24 工具, null, null)`；父不预扣子将消耗的额度（`AgentRunEventRecorder.java:1990-1997`）。**推论：一次 SUPERVISOR 运行开满 3 个子运行，聚合 token 消耗无任何上限约束。**
- token 估算：`codePoint/3`（`AgentRunEventRecorder.java:1517-1521`）与 `字符长度/3`（`AgentRuntimeCoordinator.java:1280-1283`），**无 CJK 感知**——中文场景系统性低估约 2 倍（现代 tokenizer 中文约 0.6-1 token/字符，而 1/3≈0.33）。

### 1.3 循环防护现状

- `AgentLoopGuard`（`AgentLoopGuard.java`，80 行）：滑动窗口 6 个 `TOOL_CALL_COMPLETED` 步，`toolName|args|output` 拼接签名（**output 参与签名**，输出变化视为进展），任一签名 ≥3 次 → 无进展；且有预测性拦截（窗口内计数达阈值且下一步 `tool|args` 完全一致时，执行前拒绝，L45-60）。
- **防护边界**：只看本 run 的 steps，不感知 parent/child，无跨 run 委派去重。委派防环完全靠"depth=1 结构封死"。
- **真实循环风险**：父运行可用略不同的 objective 反复开新子 Agent（每次 `children_used+1`，最多 3 次/运行，但 retry/重新生成后又是一轮）——签名按 run 隔离检测不到。

### 1.4 任务规划现状

- 状态机：`TaskPlanStatus`（`SKELETON_GENERATING → DETAIL_GENERATING → READY / READY_WITH_ISSUES / REPAIRING / FAILED / ...`），细粒度阶段在 `ai_task_plan_attempt.stage`（`SKELETON/DETAIL/REPAIR`，`V5__create_ai_task_planning.sql:80-108`）。
- **planner 无工具**：全部走 `TaskPlanModelClient.generate()`（纯 prompt 进 prompt 出）；上下文由 `TaskPlanContextAssembler` 一次性组装，planner 看不到现有任务/成员负载/知识库。
- **批次写死 4**：`MAX_MODEL_TASKS_PER_BATCH = 4`（`TaskPlanGenerationOrchestrator.java:54`）；截断批对半拆（`splitTruncatedDetailBatch`，L869-951）。
- **修复上限 1 次**：DB CHECK `repair_count BETWEEN 0 AND 1`（`V5` L108）。
- 校验两层：结构层 `TaskPlanOutputParser`（strict ObjectMapper）+ 领域层 `TaskPlanDraftValidator`（`ValidationIssueCatalog` 硬伤码）。
- **零反思环节**：无 critique/reflection；唯一"再评估"是修复后的重新 `assess`（代码校验，非模型自评）。
- 用户编辑：版本追加式（`MANUAL_EDIT` 新版本，乐观锁防串版本），regenerate 从头跑骨架，detail-only 重试会继承用户编辑过的骨架（`latestDraft`，orchestrator L1160-1162）。

---

## 2. 设计目标与非目标

**目标**

1. G1：支持父运行并行 fan-out 多个子 Agent，聚合等待、按子回传结构化结果综合。
2. G2：建立跨 run 的委派循环治理，封死"重复委派烧 token"路径。
3. G3：预算治理 v3——把 token 从"只记账"升级为"聚合上限 + 软阈值分级收敛"，并修正中文估算偏差。
4. G4：任务规划从纯 workflow 升级为 agentic workflow——planner 有工具、生成后有反思、批次按依赖拓扑与 token 预算动态切。

**非目标**

- 不做深度 >1 的多层编排（维持 depth=1，见 §8 取舍）。
- 不做 MQ 迁移（另行方案，本文唤醒机制预留接口）。
- 不做计费/账单系统，成本台账只做统计与展示。
- 不改 RAG 检索管线。

---

## 3. 方案 A：子 Agent 并行编排治理

### 3.1 并行 fan-out（G1）

**核心改动：父运行从"挂起转 QUEUED"改为"带待决子计数的显式等待态"。**

1. `agent_run` 新增 `children_pending integer NOT NULL DEFAULT 0`。
2. 委派受理时（`documentResearchDelegationResult` 重构）：
   - 允许同一批次含多个委派调用（移除 `DELEGATION_BATCH_LIMIT` 的独占要求），每受理一个 `children_pending+1`、`children_used+1`；
   - 父运行转 `RUNNING` 但 `lease_expires_at = now()`（立即释放租约，不占 worker），状态语义"等待子运行"由 `children_pending > 0` 表达，而非状态字段——避免状态机膨胀。
3. 子运行终态时（`resumeParent` 重构）：
   - `children_pending-1`（CAS 到 0 为止）；
   - 归零时立即触发**即时唤醒**：`AgentDelegationWakeEvent`（Spring `ApplicationEvent`），监听器直接向 `agentRunTaskExecutor` 提交一次 claim 尝试，绕过 1s tick。单实例内这是安全优化；`claimNext` 的 `FOR UPDATE SKIP LOCKED` 保证与 tick 竞争不出双跑。
4. `claimNext` 保持 `ORDER BY depth DESC`（子先于父），无需新增优先级字段。
5. `max_children` 上限 3 → 8（`ck_agent_run_budgets` 迁移同步改），`AgentResourcePolicy.V2_ROOT_MAX_CHILDREN` 保持 3 作为默认，fan-out 场景由委派工具 schema 显式传更大的 `maxChildren`（≤8）。

**部分失败语义**：fan-out 中部分子失败不整体失败。`resumeParent` 为每个子写一条 `DELEGATION_COMPLETED` / `DELEGATION_FAILED` 父事件（含 `childRunId、status、summary、usage`），父 prompt 注入区块按"成功列表 + 失败列表"分组，父可决定是否对失败项重新委派（受 G2 去重约束）。

### 3.2 委派循环治理（G2）

新增 `AgentDelegationGuard`（领域策略，纯函数）+ 持久化台账：

1. 新表 `agent_delegation_ledger`：
   ```sql
   CREATE TABLE agent_delegation_ledger (
     id BIGSERIAL PRIMARY KEY,
     session_id BIGINT NOT NULL,
     parent_run_id BIGINT NOT NULL,
     child_run_id BIGINT NOT NULL,
     objective_hash VARCHAR(64) NOT NULL,   -- sha256(normalize(objective))
     status VARCHAR(20) NOT NULL,           -- RUNNING/COMPLETED/FAILED/CANCELED
     created_at TIMESTAMPTZ NOT NULL DEFAULT now()
   );
   CREATE INDEX idx_adl_session_hash ON agent_delegation_ledger (session_id, objective_hash);
   ```
2. `normalize(objective)`：小写化 → 去标点/空白折叠 → 截断 512 字符。目标是抓"换个说法重复委派"。
3. 受理准入（并入 `AgentDelegationAdmission.reject`）新增拒绝码：
   - `DELEGATION_DUPLICATE_OBJECTIVE`：同 `session_id` 下同 `objective_hash` 已存在非 CANCELED 记录；
   - `DELEGATION_IN_FLIGHT_LIMIT`：同父运行 `children_pending ≥ 4`（并行上限，独立于 `max_children` 累计上限）。
4. 子 Agent 完成时若结论为"证据不足/未找到"，允许父对**同 objective 变体**追加一次委派——台账记录 `retry_of` 语义：同 hash 第 2 次直接拒绝，不给第 3 次。
5. 预测性拦截扩展：`AgentLoopGuard` 的窗口签名对委派工具改用 `toolName|normalize(args.objective)`，使"参数微调的重复委派"在窗口内即可命中 ≥3 次规则。

### 3.3 结构化回传协议（G1 配套）

`DELEGATION_COMPLETED` 载荷从"最终文本 + coverage"升级为：

```json
{
  "summary": "≤2000 字符结论",
  "findings": ["带证据引用的发现，每条 ≤500 字符，≤10 条"],
  "citations": ["S1", "..."],
  "coverage": { "已覆盖范围": "...", "未覆盖原因": "..." },
  "openQuestions": ["留给父运行决策的问题，≤5 条"],
  "evidenceDigest": ["子运行关键工具结果摘录，每条 ≤800 字符，≤8 条"],
  "usage": { "inputTokens": 0, "outputTokens": 0, "steps": 0 }
}
```

- `evidenceDigest` 解决"模型看不到子工具原文，只能信 coverage"的保真度问题；摘录由子运行收敛阶段自行挑选（prompt 约束），落库前过 `AgentToolResultSanitizer`。
- 总载荷上限 40000 字符（现 20000 放宽，仍受 sanitizer 硬顶约束）。
- 旧格式向后兼容：无 `findings` 字段时按现逻辑降级。

### 3.4 唤醒机制（G1 配套）

- 阶段 1（本文）：§3.1 第 3 条的进程内事件唤醒。
- 阶段 2（MQ 方案落地后）：子终态发 MQ 消息触发 claim，事件监听器接口 `AgentRunWakeListener` 保持不变，仅换实现。

---

## 4. 方案 B：预算治理 v3

### 4.1 会话聚合 token 上限（补 v2 最大缺口）

**问题**：v2 单 run token 无上限 + 子运行独立预算 + 父不预扣 → 一次会话的聚合消耗只有步数间接约束。

**设计**：

1. `agent_session` 新增 `max_aggregate_tokens BIGINT NULL`（NULL = 不限，兼容存量会话）与 `aggregate_tokens_used BIGINT NOT NULL DEFAULT 0`。
2. 每次**模型轮出站记账**时（`AgentRunEventRecorder` 记录 usage 处），对该 run 所属 session 执行 `agent_capped_add(session.aggregate_tokens_used, delta)`——复用现有封顶函数语义。
3. **准入点两处**：
   - 委派受理（`AgentDelegationAdmission`）：预估子消耗（按 §4.3 估算器对子 prompt 估算 + 子步数上限×平均输出）超过 session 剩余额度 → `SESSION_BUDGET_EXCEEDED` 拒绝；
   - 每次模型轮前（`AgentWorker` 准入段）：session 剩余 < 该 run 本轮预估 → 本轮强制**无工具收尾**（与 `AgentConvergencePolicy.needsFinalRequest` 合流，不硬杀，保证产出部分结果）。
4. 默认值：新会话 `max_aggregate_tokens = 2,000,000`（约 ¥/$ 量级可配置，进 `AgentRuntimeProperties`）。

### 4.2 软阈值分级收敛（G3）

引入 run 级 token 软顶（v2 下 `max_input_tokens` 复用为软顶，语义标注进 `budget_semantics` 文档）：

| 阈值 | 动作 | 实现位置 |
|---|---|---|
| 累计 input ≥ 软顶 60% | 注入一次性系统提示："预算将尽，收敛已有信息作答，禁止开启新的探索方向" | 模型轮 prompt 组装处（`AgentRuntimeCoordinator`），幂等标记进 run 事件 |
| ≥ 85% | 禁止新增委派（`AgentDelegationAdmission` 新拒绝码 `RUN_BUDGET_SOFT_EXCEEDED`）+ 强制进入收敛路径 | 同上 + 准入 |
| 步数剩余 ≤ 20% | 复用现有 `needsFinalRequest`，追加同样的收敛提示 | `AgentConvergencePolicy` |
| 会话聚合 ≥ 硬顶 | §4.1 准入兜底 | `AgentWorker` / 受理 |

所有软阈值动作发 Micrometer 事件（`agent.budget.soft.warning` 等 counter），为后续可观测性接 Prometheus 留点。

### 4.3 CJK 感知 token 估算修正

现 `codePoint/3` 对中文低估约 2 倍，导致估算型预算（会话预估、软阈值触发时机）系统性偏晚。

- 新估算器 `TokenEstimator`（纯函数，`agent/domain/model/`）：
  ```
  tokens ≈ ascii/4 + cjk×0.75 + other×0.5
  ```
  （cjk = CJK 统一表意文字及扩展区 codepoint；系数常量集中定义并附基准说明。）
- 替换 `AgentRuntimeCoordinator.estimateInput`（L1280-1283）与 `AgentRunEventRecorder.tokens`（L1517-1521）的 fallback 估算；provider 上报（`PROVIDER` basis）优先级不变。
- 用真实 tokenizer 对 20 组中英混合样本回归，断言估算偏差 ≤ ±40%（写入单元测试）。

### 4.4 成本台账（非计费）

- `model_config` 表新增 `price_per_1k_input NUMERIC(10,6) NULL`、`price_per_1k_output NUMERIC(10,6) NULL`（用户级模型配置由用户自填，NULL = 不计成本）。
- 每次 usage 记账时同步写 `agent_run_event` 扩展列 `cost_amount NUMERIC(12,6) NULL`（不建新表，事件表已含 model/usage 上下文）。
- 会话/run 列表接口聚合返回 `totalCost`；前端 Agent 会话页展示。非目标：账单、配额扣费。

---

## 5. 方案 C：任务规划 → Agentic Workflow

### 5.1 目标形态

```
SKELETON_GENERATING ──► SKELETON_REVIEW ──► DETAIL_GENERATING ──► REFLECTING ──► READY / REPAIRING
      （+工具检索）        （人工检查点）        （动态批次+工具）       （模型自评）
```

保持现有"两阶段 + Schema 约束 + 版本追加 + 幂等提交"骨架不变，升级三处。

### 5.2 给 planner 配工具（有据规划）

新增 planning 专属只读工具集（复用 agent 模块的 `AgentToolResultSanitizer` 清洗）：

| 工具 | 用途 | 实现 |
|---|---|---|
| `query_project_tasks` | 查现有任务，避免生成重复/冲突任务 | 复用 `work` 模块查询服务 |
| `get_member_workload` | 按成员负载分配 assignee，让排布有依据 | 复用 `WorkVisualizationService` 负载口径 |
| `search_project_knowledge` | 检索知识库文档，让任务拆解引用项目实际材料 | 复用 retrieval 管线（受 `ProjectAccessGuard` 约束） |
| `get_project_milestones` | 对齐既有里程碑 | 复用 work 模块查询 |

- 执行载体：`PlanningToolLoop`——编排器内的**有界小循环**（每次生成尝试内 ≤6 次工具调用，独立于 agent runtime 的 run 模型），结果经 sanitizer 后注入下一轮 prompt。
- 不复用 agent run 队列：规划是一次性生成任务，引入 run 队列的复杂度不值；有界循环即可，预算由 `PlanningAttemptThrottle`（现有 Redis Lua）+ 循环上限双重约束。
- 所有工具调用持久化进 `ai_task_plan_attempt.diagnostics_json`（V53 已有该列），可审计。

### 5.3 反思环节（REFLECT 阶段）

- 详情批次全部成功后，新增一次模型自评调用：输入完整草稿 + 评审 rubric（依赖一致性 / 粒度均匀度 / 里程碑分布 / 与现有任务冲突——`query_project_tasks` 的结果回灌），输出结构化 `issues[]`（severity: HARD/SOFT + 字段定位）。
- 硬伤 → 走现有 `repairScope/patchRepairPrompt` 修复链路（复用），**修复上限 1 → 2**（`V5` L108 的 CHECK 改为 `BETWEEN 0 AND 2`，新迁移改约束）。
- 软伤 → 正常落 `READY_WITH_ISSUES`，issues 进版本 JSON，前端编辑页展示。
- 反思调用计入 attempt 预算；`TaskPlanRuntimeProperties` 提供开关（默认开，可关）。

### 5.4 动态批次

- 替换写死的 `MAX_MODEL_TASKS_PER_BATCH=4`：
  - **依赖拓扑约束**：同批任务之间不得有依赖边（骨架产出的依赖图分批，避免跨批依赖导致详情互相引用失效）；
  - **token 预算约束**：批 prompt 预估（§4.3 估算器）+ 输出预留 ≤ 单批上限（常量起步，后可配置）；
  - 截断兜底保留现有 `splitTruncatedDetailBatch`。
- `PlanningAttemptThrottle` 配额不变。

### 5.5 人工检查点（SKELETON_REVIEW）

- 骨架完成 → 进入 `SKELETON_REVIEW`，等待用户确认（复用现有版本编辑 + confirm 流）或超时自动继续（`TaskPlanRuntimeProperties.skeletonReviewTimeoutMinutes`，默认 0 = 不等待，保持现行为；配置 >0 才启用检查点）。
- 理由：自动流程不打断现用户习惯，检查点作为可选增强；这与 agent 写操作"默认审批"的原则一致。

---

## 6. 数据库迁移清单（V81 起，版本号以实施时 Flyway 现状顺延）

| 迁移 | 内容 |
|---|---|
| V81 | `agent_run` 加 `children_pending`；`ck_agent_run_budgets` 的 `max_children` 上限 3→8；`agent_session` 加 `max_aggregate_tokens / aggregate_tokens_used` |
| V82 | `agent_delegation_ledger` 表 + 索引 |
| V83 | planning：`ai_task_plan.status` CHECK 增 `SKELETON_REVIEW/REFLECTING`；`repair_count` CHECK 0..1 → 0..2；`ai_task_plan_attempt.stage` 增 `REFLECT` |
| V84 | `model_config` 加价格两列；`agent_run_event` 加 `cost_amount` |

规则遵守：不改历史迁移，全部新文件；CHECK 兜底与应用层状态机校验同步。

---

## 7. 实施分期

| 期 | 内容 | 交付判据 |
|---|---|---|
| **P0（低风险纯后端）** | §3.2 委派防重（台账+拒绝码+签名扩展）、§4.2 软阈值、§4.3 估算修正 | 单测全绿；重复委派被拒的可复现测试；估算偏差回归测试 |
| **P1（编排核心）** | §3.1 并行 fan-out + §3.4 唤醒 + §3.3 结构化回传、§4.1 会话聚合预算 | Testcontainers 并发测试：3 子并行、交错终态、部分失败；聚合上限触发收敛测试 |
| **P2（规划 agentic）** | §5 全部 + §4.4 成本台账 | REFLECT 循环测试；动态批次依赖约束测试；成本聚合接口测试 |
| **P3（远期）** | 深度>1 编排、agent evals golden set、MQ 唤醒 | 另行设计 |

P0 不碰运行时主链路，可先行；P1 是本设计核心，动 `AgentRunEventRecorder`（2013 行）前建议顺手把委派相关方法拆出到 `AgentDelegationRecorder`。

---

## 8. 关键取舍与风险

| 决策 | 备选 | 取舍理由 | 风险与缓解 |
|---|---|---|---|
| 维持 depth=1，不做多层编排 | 放开 depth≤2 + 运行时环检测 | 递归委派的价值场景目前不存在，且环检测的正确性成本高（防活锁需全局视图） | 未来放开时 `agent_delegation_ledger` 已具备链路数据基础 |
| `children_pending` 计数而非新状态 | 引入 `WAITING_CHILDREN` 状态 | 状态机不加环，崩溃恢复语义不变（父 RUNNING+租约过期照常被接管，接管时按存活子重算 pending） | 计数漂移：接管/取消路径需重算 `children_pending`（以非终态子运行数为准），放进 `AgentRecoveryJob` |
| 进程内事件唤醒而非 MQ | 直接上 MQ | 唤醒是优化非正确性依赖，tick 兜底仍在；MQ 另行方案 | 事件丢失无害（tick 1s 兜底），listener 异常需 catch 保证不影响终态事务 |
| 会话聚合预算走"模型轮准入"而非"预扣" | 父委派时预扣子预算 | 预扣要处理退还/对账，复杂度高；准入式最坏情况只超一个模型轮的量 | 并发子同时过准入的竞态：准入检查后记账仍在同一事务，`agent_capped_add` 保证不超顶 |
| 规划工具用有界小循环，不进 agent run 队列 | 规划统一成一种 agent run | 避免把一次性生成任务套上租约/恢复的复杂度 | 两套工具执行链并存：靠共享 sanitizer 与权限 guard 收敛 |
| REFLECT 默认开启 | 默认关闭灰度 | 每次规划多 1 次模型调用，成本可控；质量收益是本方案主要卖点 | 模型自评质量不稳 → issues 仅作修复输入与前端展示，不自动阻塞 READY |
| 中文估算系数 0.75 | 接入真实 tokenizer 库 | 引入 jtokkit 类依赖收益有限（provider usage 优先已覆盖大部分场景），系数法可测试可解释 | 系数漂移：常量集中 + 真实样本回归测试钉住 |

---

## 9. 验证方案

1. **单元**：`AgentDelegationGuard`（normalize 碰撞、重复判定）、`TokenEstimator`（中英样本偏差）、软阈值状态推进——全纯函数。
2. **Testcontainers 集成**：
   - fan-out：3 子并行交错终态（完成/失败/取消组合），断言父只在全终态后唤醒且 `DELEGATION_*` 事件数精确；
   - 计数恢复：父接管后 `children_pending` 重算正确；
   - 会话聚合：并发记账不超顶、达到硬顶后强制收敛且产出部分结果；
   - planning：REFLECT 硬伤触发修复、`repair_count=2` 后终态、动态批次无跨批依赖边。
3. **回归防线**：现有 2275 测试不退化；`AgentPauseResumePostgresTest` 同款断言风格（事件恰好一次）。
4. **指标**：新增 counter（`agent.delegation.rejected{reason}`、`agent.budget.soft.warning`、`agent.fanout.children.active`），为可观测性接 Prometheus 预留。

---

## 10. 附：现状核实的原始锚点（供实施时复查）

- `AgentLoopGuard.java` L13-14/29-40/45-60（窗口/签名/预测拦截）
- `AgentRepository.java` L181-190（v2 建跑参数）、L461-491（claimNext，`ORDER BY depth DESC`）
- `AgentRunEventRecorder.java` L614/1517-1521/1897-2007（截断/估算/新委派路径）、L973-1032（旧路径）
- `AgentDelegationAdmission.java` L28-41/110-132/144-172（常量/split/拒绝规则）
- `AgentWorker.java` L92-108（准入）、`AgentConvergencePolicy.java` L125-145
- `AgentResourcePolicy.java` L37-54/142-155（v2 上限常量）
- `V22__add_agent_runtime.sql` L62-65/63-77、`V64__agent_context_policy.sql` L56-71（CHECK/capped_add）
- `TaskPlanGenerationOrchestrator.java` L54/869-951/1017-1047/1160-1162、`V5__create_ai_task_planning.sql` L80-108
- `DocumentResearchDelegateAgentTool.java` L26-28、`AgentToolCallExecutor.java` L216-222/395-429
