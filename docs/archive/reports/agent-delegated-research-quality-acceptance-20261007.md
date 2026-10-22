# 真实资料下的回答质量验收与委派链路修复记录（2026-10-07）

> 基线：`codex/context-foundation` / `40a23dc` + 未提交的摘要候选修复（接手自上一会话）。
> 本轮完成上一会话遗留的"真实资料下的回答质量验收"：配好 embedding、准备 4 份代表性文档、
> 对照主 Agent 直接研究与委派研究。实验暴露三个真实缺陷（R12/R12b/R12c），全部修复并有
> 红绿回归；验收结论见第 4 节。
> 证据类型：[测试] 为自动测试（真实 PostgreSQL），[实验] 为真实模型 + 真实文档的浏览器实测，
> [环境] 为运行环境事实。

## 1. 环境与实验设置

- [环境] Ollama `qwen3-embedding:0.6b`（1024 维）连通正常；项目「Zen规划冒烟」4 份文档全部 READY
  （`test-docs/`：验收标准v2.1、评分模块技术方案、报名流程与容量规划、数据安全与脱敏规范）。
- 模型 `mimo-v2.6-flash-free`（与生产配置一致）。
- 实验问题（固定以保证可比）：
  - **A/B 通用研究问题**：按验收标准逐项判断评分聚合是否合规、批量导入差距、数据脱敏一致性，
    给出文档依据、缺口和未读章节。
  - **B 系列一律显式要求**"把这次研究委派给文档研究子 Agent 完整执行"。
  - **B-narrow**：同样的委派要求，但范围收窄到《评分模块技术方案》一篇。
- [实验] 全部通过 browser-skill 在真实 Web UI 发起，运行事实以 `agent_run` / `agent_step` /
  `agent_run_event` 落库数据为准；证据 JSON 在 `docs/acceptance-evidence/2026-10-07/`。

## 2. 实验结果总表

| 实验 | 委派 | 结果 | 子运行行为 | 用户可见交付 | 耗时 |
| --- | --- | --- | --- | --- | --- |
| A（`857722fc`） | 否（直接研究） | SUCCEEDED | — | 1840 字逐项结论 + 16 条真实 chunk 引用 + 诚实缺口声明 | 89s |
| B（`4de31957`/`25df657b`，修复前） | 是 | 双双 BUDGET_EXCEEDED | 第 0 步即死 | 部分回答（父运行自有检索片段） | 107s |
| B2（`d037099f`/`a800fed9`，摘要候选修复后） | 是 | 父 SUCCEEDED（如实报告失败） | 3 步 / 2 工具即死，回收内容仅错误码 | "子 Agent 产出：零"的诚实失败报告 | 73s |
| B3（`5b5cec1a`/`96ceca78`，预留修复后） | 是 | 父 SUCCEEDED（如实报告失败） | 7 步 / 5 工具（4 篇文档全部提纲），部分回答未回传 | 诚实失败报告，子产出仍丢失 | 83s |
| B4（`3e92ed11`/`3d816801`，三修复齐） | 是 | BUDGET_EXCEEDED（真实预算终点） | 7 步 / 5 工具（全部提纲） | 2092 字部分回答：含 4 篇文档清单与全部提纲，状态如实标注 | 98s |
| B-narrow（`0c7c64a0`/`f8ca9b43`） | 是（单文档） | BUDGET_EXCEEDED（真实预算终点） | 7 步 / 5 工具（提纲 + 检索 + read_document_section 读到正文） | 部分回答（子运行已取得的片段与提纲） | 65s |

## 3. 实验暴露的三个真实缺陷与修复

三个缺陷均为委派链路在真实模型行为下的可复现问题，修复全部按红-绿流程验证
（先在未修复代码上复现失败，再在修复后通过；`AgentDelegationPostgresTest` 18 项全过，
协调器相关 11 个测试类 151 项全过）。

### R12 运行级输出预留误判：子运行永远无法发起第二次模型请求

- **现象**：B2 重跑（含上一会话的摘要候选修复）仍 BUDGET_EXCEEDED。子运行第一轮消耗任意输出后
  （实测 31 token），下一次请求准入即失败；三次委派实验全部复现。
- **根因**：委派子运行的输出硬上限 `childOutput = min(8000, …)`（`AgentRunEventRecorder` 委派受理）
  恰好等于生产 `outputReserveTokens=8000`。协调器把该全局预留直接用于**运行级**判定：
  `needsFinalRequest` 的 `remainingOutput <= outputReserve` 立即强制收尾、
  摘要预留检查 `refreshedRemainingOutput < outputReserveTokens` 直接超限——
  子运行剩余额度（8000-31=7969）永远小于预留。测试基建的预留值是 4000（生产 8000），
  掩盖了该边界，15 项既有回归全绿而生产必挂。
- **修复**：运行级预留按运行自身输出预算等比收紧：
  `runOutputReserve = min(outputReserveTokens, maxOutputTokens/2)`（`AgentRuntimeCoordinator`），
  `needsFinalRequest` 与摘要预留检查共用。模型窗口级预留（`AgentContextBudget.perRequest`）
  是窗口容量记账，仍用全局值不变。
- **回归**：`childRunWithProductionOutputReserveMustIssueSecondModelRequest`
  ——生产默认配置下，子运行第一轮消耗 output=31 后必须能发起第二次请求并正常收尾。
  [测试] 红灯：`expected: SUCCEEDED but was: BUDGET_EXCEEDED`（与线上完全一致）；绿灯通过。

### R12b 子运行预算部分回答未随回收进入父综合

- **现象**：B3 中子运行已有 5 次成功工具结果（4 篇文档提纲），父运行回收到的
  `DELEGATION_COMPLETED.content` 只有错误码占位 `AGENT_BUDGET_EXCEEDED`，
  子运行的实际研究产出对父运行不可见。
- **根因**：`recordBudgetExceeded` → `resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED")`
  固定回传错误码；`recordBudgetPartialAnswer` 的部分回答只对 depth=0 落会话消息，
  depth=1 的部分回答没有任何持久化出口。
- **修复**：`recordBudgetPartialAnswer` 对 depth>0 且内容非空时，把部分回答回填到本事务内
  刚写入的 `DELEGATION_COMPLETED`（仅覆盖错误码占位，不覆盖已有产出）。
- **回归**：`childBudgetPartialAnswerMustReachParentCollection`。
  [测试] 红灯：回收内容仍为错误码占位；绿灯通过。

### R12c 委派型父运行证据兜底为空：预算部分回答空手而终

- **现象**：B3/B4 中父运行综合轮想批量补读 4 篇文档正文，但回收子运行用量后工具额度只剩 2
  （6/8），整批 4 个调用被拒绝；证据兜底 `completeFromEvidence` 只看父运行自有
  `TOOL_CALL_COMPLETED`（委派型运行没有——工具消耗记在子运行名下）→ 返回 null →
  硬停，连子运行已取得的研究产出都没交付（B3 第四次运行无任何最终回答落库）。
- **修复**：`completeFromEvidence` 在自有工具证据为空时回退到已回收的子运行研究产出
  （`DELEGATION_COMPLETED.content`，错误码占位视为无产出），作为预算部分回答交付。
- **回归**：`delegationParentBudgetFallbackMustCarryChildFindings`。
  [测试] 红灯：部分回答不含子运行产出；绿灯通过，且 depth=0 部分回答落为会话消息。

### 修复边界说明

- 上一会话的摘要候选修复（`AgentModelMessageComposer`，子运行不做会话摘要、
  持久化候选与本地候选同时置空）方向正确且保留，R12 是它未覆盖的另一半。
- 子运行在 8 步预算终点耗尽属**真实限额**而非缺陷，本轮不改预算架构（见第 4 节结论与第 5 节建议）。

## 4. 验收结论

1. **委派链路机制修复完成**：子运行可正常多轮执行；部分回答随回收进入父综合；
   每一层预算终点都能优雅降级并如实标注状态与覆盖缺口。B4 与 B-narrow 的用户可见交付
   均为"部分产出 + 如实状态"，不再出现"产出全丢"或"零交付"。
2. **全库研究任务在当前预算架构下不适合委派**：子运行预算从父剩余切出（≤8 步 / ≤8 工具），
   "4 篇文档逐项对照"级研究子运行只能覆盖到目录级；直接研究（主 Agent 12 步 / 8 工具）
   可完成并给出 16 条真实 chunk 引用。委派形态下同等任务无法完成。
3. **并行工具调用放大步数消耗**是子运行提前耗尽的机制性因素：一轮 4 个并行
   `get_document_outline` 记 4 步。收敛策略注释明确"不提高限额"，该记账属有意设计，
   但对委派研究不友好。
4. **耗时**：直接研究 89s 出完整答案；委派完整任务 98s 只出部分产出。当前形态下
   全库研究委派"不值得"；委派的价值场景是**范围收窄、可独立完成的研究子任务**
   （B-narrow 中子运行已能读到目标文档正文，距离成文只差步骤预算）。

## 5. 遗留与建议（不在本轮范围）

- 子运行步骤预算与并行批次的步数记账（一轮并行 N 个调用记 N 步）是下一步最值得
  评估的预算调优点；改动会影响收敛策略与回收语义，需要专门一轮。
- 父 RUNNING 竞争窗口、FAILED_RETRYABLE 刷新恢复：保持上一会话的待验证清单，不动已提交批次。
- 真实 HTTP Last-Event-ID 补回验收（SSE 批次可选传输证据）仍未做，不与本轮绑定。

## 6. 证据清单

- [实验] `docs/acceptance-evidence/2026-10-07/quality-a-direct.json`（实验 A）
- [实验] `docs/acceptance-evidence/2026-10-07/quality-b-delegated.json`、`quality-b2-delegated.json`（修复前）
- [实验] `docs/acceptance-evidence/2026-10-07/quality-b4-delegated-postfix.json`（修复后全链路）
- [测试] `AgentDelegationPostgresTest` 18 项（含 3 项新回归，均经红灯验证），
  surefire：`Tests run: 18, Failures: 0, Errors: 0`
- [测试] 协调器相关 11 个测试类 151 项：`Tests run: 151, Failures: 0, Errors: 0, Skipped: 0`
  （`ai-reserve-regression2.log`）
- [环境] 实验文档语料 `test-docs/`（4 份，即知识库上传原件）
