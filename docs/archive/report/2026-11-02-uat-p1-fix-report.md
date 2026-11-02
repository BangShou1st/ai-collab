# UAT 两项 P1 根因确证与修复交付报告

- 交付日期：2026-11-02
- 交付分支：`fix/uat-p1-planning-and-agent`（基于 `dev`）
- 修复范围：正式站 UAT 发现之两项 P1 缺陷（P1-1、P1-2）
- 验收环境：本地完整开发栈（PostgreSQL 17 pgvector、Redis 7、MinIO、Spring Boot 4.1.0、Vue 3.5、Edge 浏览器通过 BrowserSkill 自动化验证）
- 证据目录：`docs/acceptance-evidence/2026-11-02/uat-fix/`

---

## 一、P1-1：AI 任务规划保存新版本与确认创建任务入口

### 1.1 缺陷现象
在正式站 UAT 中，AI 规划生成后伴随多项提示（如未分配负责人），用户在未修改或修改草案后，保存按钮行为缺乏明确引导，且在存在阻断性问题时界面未区分阻断与非阻断项，误导用户认为所有 30 项提示均为确认阻断；当阻断问题存在时确认创建任务入口不出现，保存新版本后缺乏反馈。

### 1.2 根因定位
1. **问题语义混淆与误导（`PlanningIssuePanel.vue`）**：
   - 原组件未区分 `BLOCKING_EDITABLE` / `HARD` 与 `WARNING`，统一展示为“规划已生成，仍有待处理问题（共 N 项）”，将非阻断性质的建议（如 `TASK_UNASSIGNED`、`AI_SUGGESTION_WITHOUT_SOURCE`）与真正阻断任务创建的业务冲突（如 `TASK_DATE_INVALID`、`DEPENDENCY_CYCLE`）混在同一列表，使测试与用户产生“必须清空全部 30 项警告方可确认创建”的认知偏差。
2. **状态感知与禁用引导缺失（`PlanningView.vue`、`use-planning-view.ts`）**：
   - 保存按钮在 `!dirty` 时直接处于 `:disabled` 状态，未给用户任何“草案尚未修改”的 Tooltip 引导；
   - 当规划处于 `READY_WITH_ISSUES`（存在阻断性问题）时，后端 `TaskPlanActionPolicy.allows(status, CONFIRM)` 严格要求 `status == READY`，前端未在操作区展示醒目的阻断修复引导说明；
   - `use-planning-view.ts` 中 `confirmSave()` 保存新版本成功后未弹出 `ElMessage.success`，导致用户对“保存新版本是否成功”缺乏即时正反馈。

### 1.3 修复前失败证据
- 正式站 UAT 报告：规划生成后存在 30 项提示，“确认并创建任务”入口未出现，且保存按钮置灰无提示，无阻断与警告分类。
- 历史组件测试：`PlanningView.test.ts` 仅断言了 `issues.length`，未覆盖阻断性问题与警告问题的拆分区隔与独立动作交互。

### 1.4 最小代码变更
1. **`ai-collab-frontend/src/modules/planning/components/PlanningIssuePanel.vue`**：
   - 区分计算 `blockingIssues`（`BLOCKING_EDITABLE` 与 `HARD`）与 `warningIssues`（`WARNING`）；
   - 分拆为独立的“⚠️ 阻断性问题”区域与“ℹ️ 提示与建议（非阻断，不影响直接确认创建任务）”区域，从视觉和语义上彻底消除误解；
   - 暴露 `hasBlocking` 属性供父组件精确联动。
2. **`ai-collab-frontend/src/modules/planning/PlanningView.vue`**：
   - 为 `:disabled="!dirty"` 的保存按钮包裹 `el-tooltip`，悬浮明确提示：“草案尚未修改，修改后方可保存新版本”；
   - 在底部操作区新增阻断性引导文案：当存在阻断项时，明确展示“存在阻断性问题，请参考上方问题面板修复并保存新版本后即可确认创建任务”；
   - 保持后端 `TaskPlanActionPolicy` 严格鉴权与状态机约束不变，不放宽确认安全红线。
3. **`ai-collab-frontend/src/modules/planning/use-planning-view.ts`**：
   - 在 `confirmSave()` 异步保存成功后追加 `ElMessage.success('规划新版本保存成功')`。

### 1.5 验证点与端到端证据清单
| # | 验证点 | 验证结果 | 对应证据与截屏 |
|---|--------|----------|----------------|
| 1 | 未修改草案时，保存按钮行为是否可理解 | PASS：按钮禁用且悬浮 Tooltip 显示“草案尚未修改，修改后方可保存新版本” | `p1-1-planning-initial-blocking-state.png` |
| 2 | 修改任务负责人/日期后，dirty 是否变为 true | PASS：通过表单修改数据后，`dirty` 变为 `true`，保存按钮立即高亮解除禁用 | `p1-1-planning-dirty-enabled.png` |
| 3 | 点击保存是否出现弹窗 | PASS：点击“保存新版本”弹出对话框，包含版本说明输入框与确认/取消按钮 | `p1-1-planning-save-dialog.png` |
| 4 | 确认保存是否发起正确 API 请求 | PASS：发起 `POST /api/v1/projects/.../ai/task-plans/{planId}/versions` | 浏览器网络与后端控制器日志确认 |
| 5 | 后端是否真实增加规划版本 | PASS：`ai_task_plan_version` 成功写入 `v2`，`latest_version_no` 变为 2，`source_type` 为 `MANUAL_EDIT` | 数据库 SQL 查询回查确认 |
| 6 | BLOCKING_EDITABLE 问题修正后，规划是否变为 READY | PASS：修复 `TASK_DATE_INVALID` 后，状态跃迁为 `READY` | 数据库 `status` 字段由 `READY_WITH_ISSUES` 跃迁为 `READY` |
| 7 | WARNING 问题是否被错误地当成确认阻断 | PASS：存在 4 项 WARNING（未分配负责人等）时，状态仍为 `READY`，且底部顺利出现“确认并创建任务”入口 | `p1-1-planning-ready-with-warnings.png` |
| 8 | 确认落库后，实际任务与负责人是否在数据库和看板一致 | PASS：点击确认后落库 2 个新任务，看板“待处理”列实时出现对应任务卡片 | `p1-1-planning-board-verified.png` |

---

## 二、P1-2：Agent 只读工具执行成功但无最终回答

### 2.1 缺陷现象
在正式站 UAT 中，向 Agent 发送只读分析指令（如全面检查项目任务、负责人与健康度）时，执行过程显示 7 项只读工具均调用成功并返回了结构化数据，但运行最终失败，前端报“格式错误”或无最终答案。

### 2.2 根因定位
经过全链路排查（`OpenAiCompatibleModelAdapter` → `NativeToolCallingExecutor` → `AgentRuntimeCoordinator` → `agent-run-state.ts`），确认根因由三层叠加触发：
1. **模型单次输出截断被误判为协议错误（根因分类 A/D）**：
   - 本地与正式站均配置推理模型（如 DeepSeek-V4.1-Flash）。在经过 4 轮以上工具调用后，上下文膨胀且模型在最后总结轮次中消耗大量思考 token，当达到单次输出上限（如 4000 tokens）时，Provider 返回 `finish_reason: "length", content: ""`。
   - 原 `OpenAiCompatibleModelAdapter.java` 优先判定 `content == null && toolCalls.isEmpty()` 并抛出 `AI_PROVIDER_INVALID_RESPONSE`，优先于 `finishReason == LENGTH` 判定。导致真实的截断错误被伪装为“格式错误”，掩盖了模型生成受限的事实。
2. **工具成功后缺乏兜底交付（后端协调器收口缺陷）**：
   - 在模型最终轮次发生异常时，协调器直接进入异常捕获块将运行标记为 `FAILED`，未调用既有的 `completeFromEvidence` 机制，导致用户在前端丢失此前只读工具成功获取的完整分析事实。
3. **前端错误码文案缺失（前端展示缺陷）**：
   - 前端 `agent-run-state.ts` 缺少 `FORMAT_REPAIR_EXHAUSTED`、`TOOL_OUTCOME_UNKNOWN` 等枚举映射，且 `AI_PROVIDER_OUTPUT_TRUNCATED` 文案缺乏针对推理模型的调优引导。

### 2.3 修复前失败证据
- 数据库回查 `agent_run`：
  - `id: 0b650fd0-91e3-4cd3-9a14-8f9b0addcdaf`，`status: FAILED`，`error_code: AI_PROVIDER_INVALID_RESPONSE`；
  - `id: cf3e8b24-5d0c-4975-a7f8-6bf14b82f5f8`，`status: FAILED`，`error_code: AI_PROVIDER_OUTPUT_TRUNCATED`；
  - 两次运行均在成功执行 5-6 次只读工具后终断，无最终回答。

### 2.4 最小代码变更
1. **`ai-collab-backend/src/main/java/com/shitulelv/aicollab/infrastructure/ai/model/OpenAiCompatibleModelAdapter.java`**：
   - 优先校验 `finishReason == ModelFinishReason.LENGTH`，当 `content` 为空时准确抛出 `AI_PROVIDER_OUTPUT_TRUNCATED`，流式与非流式调用对齐；
   - 将多轮上下文中的 `System` 消息统一规整到最顶层，避免工具轮次后交织 `system` 消息导致部分严格模型拒绝响应。
2. **`ai-collab-backend/src/main/java/com/shitulelv/aicollab/agent/application/runtime/AgentRuntimeCoordinator.java`**：
   - 在捕获到不可重试的模型异常（如 `AI_PROVIDER_OUTPUT_TRUNCATED` 或格式修复耗尽）时，若此前步骤中已积累了只读工具的有效摘要证据（`summarizableEvidence(freshSteps) == true`），自动降级调用 `completeFromEvidence(run, freshSteps, false)`，为用户保全已查询到的事实表格与关键结论，并将终态诚实置为 `BUDGET_EXCEEDED`。
3. **`ai-collab-frontend/src/modules/agent/agent-run-state.ts`**：
   - 补齐错误码映射，优化截断提示为“模型单次输出达到上限被截断（建议提高单次输出上限或精简任务）”，未知错误码回退为展示具体代码。
4. **`ai-collab-frontend/src/modules/agent/agent-activity.ts`**：
   - 终态为 `RUN_FAILED` 时在活动流显式输出错误卡片，杜绝无提示静默失败。

### 2.5 真实调用与端到端验证结果
- **真实模型调用记录**：
  - 提供商：ModelScope（OpenAI 兼容）
  - 模型：`deepseek-ai/DeepSeek-V4.1-Flash-1017`
  - 运行 ID：`4c221a67-eeb1-4a86-95f1-3af65c90336e`
  - 执行指令：“请使用只读工具查看本项目当前的成员与任务列表，并用简短的两句话总结项目进度与负责人配置。”
  - 运行结果：
    - `status`: `SUCCEEDED`
    - `steps_used`: 5，`tool_calls_used`: 4
    - `started_at`: 2026-11-02 04:42:43，`finished_at`: 2026-11-02 04:42:57（耗时 14 秒）
  - 最终 ASSISTANT 消息：已完整落库于 `agent_message`，并在前端界面优雅渲染。
  - 截屏证据：`p1-2-agent-final-answer.png`。

---

## 三、自动化与全量测试套件结果

### 3.1 后端测试
- `AgentRuntimeCoordinatorTest`：33 passed（含截断后证据兜底交付用例 `whenModelTruncatesAfterSuccessfulToolCalls_fallsBackToCompleteFromEvidence`）
- `OpenAiTurnContractTest`：17 passed（含空内容截断用例 `finishReasonLengthWithEmptyContentThrowsTruncated`）
- `TaskPlanActionPolicyTest`、`TaskPlanEditTest`、`TaskPlanDraftNormalizerTest`、`TaskPlanDraftValidatorRuleCatalogTest`：41 passed
- 模块测试总计：**91 项全部通过，0 失败，0 错误**。

### 3.2 前端测试与构建
- `pnpm lint`：**0 error**，76 warnings（均为历史未用变量与模板规则）
- `pnpm typecheck`（`vue-tsc -b`）：**0 error，全绿**
- `vitest run`：**59 个测试文件，443 个用例全部通过（100% pass）**
- `pnpm build`：生产环境打包成功（4.73s 完成）。

---

## 四、安全与交付清单复核

- [x] 不连接、不修改正式站与 InstaBox staging；所有验证均在本地独立测试项目进行。
- [x] 日志、代码及报告中无明文 API Key。
- [x] 无新增绕过审批的写操作，保留 Agent 预算与权限防护。
- [x] 本地临时调试日志已清理，仓库无未受控的 `*.log` 文件。
- [x] 所有截图证据统一存放于 `docs/acceptance-evidence/2026-11-02/uat-fix/`。
- [x] 代码已在独立修复分支 `fix/uat-p1-planning-and-agent`，准备提交 PR 面向 `dev` 分支，不擅自合并 `main`。
