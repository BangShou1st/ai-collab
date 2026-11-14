# Planning Python Skeleton 主链（G）

> 日期：2026-11-14
> 基线：`dev @ c6eff5b1`
> 默认：`PLANNING_DRAFT_MODE=java` 保持不变

## 1. 边界

Python `POST /v1/planning/draft` 返回的是完整草稿，但当前 Python 契约不携带 Java skeleton
的不可变身份，也没有 Java 的 `S1..S12` 文档来源命名空间。因此 G 只把 Python 草稿接入
**skeleton 阶段**：

- Python 草稿由 `PlanningDraftParser` 转成 Java 草稿；
- 剥离 detail 字段后，重跑 `TaskPlanDraftValidator(ValidationMode.AI_SKELETON)`；
- 通过后仍沿既有 `appendGeneratedVersion`、attempt、事件与人工确认链提交；
- detail 阶段继续走 Java，避免把 Python 的完整草稿误当成可替换 skeleton 的事实。

Python 不执行写操作、不创建审批、不写业务表、不改变计划状态机或版本号。

## 2. 路由语义

`PlanningDraftRouter` 只在 `planning.draft.mode=python` 且处于 skeleton attempt 时启用：

| 情况 | Java 行为 | 诊断标记 |
| --- | --- | --- |
| 合法 Python 草稿 + Java Validator 通过 | 作为 skeleton 候选进入既有提交链 | `python:SKELETON` |
| 非法 JSON / 未知字段 / 解析失败 / skeleton 形状不符 / Validator 失败 | 回退 Java skeleton 生成 | `python_fallback:schema|skeleton_shape|validator` |
| worker 超时、不可用、HTTP/契约失败 | 回退 Java | `python_fallback:worker_<reason>` |
| attempt 已取消/不活跃 | 不继续生成 | `python_canceled` |
| 无剩余预算、快照不可构造、活跃复核失败 | 回退 Java | `python_fallback:deadline|snapshot|active_check` |
| 默认 java / shadow | 不进入主链，零额外 DB/外呼/指标 | `disabled` |

路由诊断写入对应 attempt 的 `diagnostics_json`，只含 stage/outcome/marker/reason/latency，
不含 goal、项目事实、模型正文或令牌。

## 2.1 预算与截止时间

`PlanningDraftRouter` 接收调用方给出的 `remainingMillis`，实际发送的
`deadlineMs = min(planning.draft.deadline-ms, remainingMillis)`，并在调用前后复核
attempt 的 `SKELETON_GENERATING` 状态。当前 Java 规划主链没有独立的运行级剩余预算
（`TaskPlanGenerationOrchestrator` 只按 attempt/version 身份收口），因此编排器传入
无界值，实际 deadline 等于 `planning.draft.deadline-ms` 上限；接口保留运行预算参数，
后续若接入运行级 deadline，无需改动路由。

预算上限不因 Python 路由放宽：`constraints.maxTasks/maxMilestones`、任务数量与
Java Validator 的 `TASK_LIMIT_EXCEEDED` 等规则仍在 Java 侧重跑。Python worker 的
请求没有幂等键，但 worker 是无状态只读服务、gateway 不重试；重复 API 请求的
落库幂等仍由既有 attempt/generationSeq/version 链保证。

## 3. 不变量

- **Validator 是权威**：Python 的自校验结果不参与 Java 决策；Java 始终重跑
  `TaskPlanDraftValidator`。
- **人工确认不变**：Python skeleton 仍需用户确认才能落业务任务。
- **来源由 Java 负责**：Python 的 `sourceRefs` 为空；Java skeleton context 的 sources 仍由
  `withSources` 绑定。
- **detail 不硬塞**：Python 契约没有 skeleton 身份保持和来源映射，detail 继续 Java。
- **默认零成本**：`java`/`shadow` 不调用 `PlanningDraftRouter`，不读取 validation context。
- **不新增迁移**：G 不占 V103；无表结构变化。

## 4. 验收

- `PlanningDraftPropertiesBindingTest`：python 可启动、默认仍 java、未知值仍失败。
- `PlanningDraftRouterTest`：合法候选、非法 plan JSON、Validator 失败/异常、worker
  超时/不可用/未知失败、任务数超预算、空/非法 shape、取消（调用前与调用后）、
  活跃复核失败、deadline 取较小值、snapshot 回退与默认零成本。
- `TaskPlanGenerationOrchestratorIntegrationTest`：Python skeleton 进入既有提交链，
  Java model 只执行 detail 一次；Python 回退时 Java skeleton + detail 各执行一次；
  两条路径都保持 `READY` 且 confirmation 行数为 0（人工确认未被跳过）。
- 真实 worker 和 shadow→python 采数：由中心 E1/集成验收补测，本 lane 不冒充。
