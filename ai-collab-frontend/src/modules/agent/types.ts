export type AgentRunStatus =
  | 'CREATED' | 'QUEUED' | 'RUNNING' | 'PAUSED' | 'WAITING_FOR_APPROVAL'
  | 'WAITING_FOR_USER_INPUT'
  | 'SUCCEEDED' | 'FAILED_RETRYABLE' | 'FAILED' | 'CANCELED' | 'BUDGET_EXCEEDED'

export interface AgentSession {
  id: string; projectId: string; creatorId: string; title: string
  status: string; version: number; createdAt: string; updatedAt: string
}
export interface AgentSessionSummary extends AgentSession {
  creatorName: string | null; latestRunId: string | null; latestRunStatus: string | null; latestActivityAt: string | null
}
export interface AgentRunDetail {
  run: AgentRun; plan: { steps: Array<{ title: string; status: string }> } | null; lastEventSequence: number; pendingApprovalId: string | null
  modelConfiguration?: { configurationId: string; provider: string; model: string; mode: string; maxOutputTokens: number; budgetEnforced: boolean } | null
  recoveryCounters?: Record<string, number>
  pauseRequestedAt?: string | null
}
export interface AgentRun {
  id: string; sessionId: string; projectId: string; goal: string; status: AgentRunStatus
  skillCode?: string | null
  /** 本运行自身的推进步计数与上限（不含子运行消耗）。 */
  stepsUsed: number; maxSteps: number
  /** 本运行自身的工具调用计数与上限（委派本身计 1 次；不含子运行消耗）。 */
  toolCallsUsed: number; maxToolCalls: number
  /**
   * 本运行累计输入/输出 token 用量。
   *
   * `maxInputTokens` / `maxOutputTokens` 是**运行累计**上限：新策略（contextPolicyVersion=2）
   * 下为 `null`，表示累计用量只统计、不限额。此时不得展示"剩余额度"或百分比，
   * 也不得把 `null` 当成 0 显示成"额度已耗尽"。
   * 模型配置的单次最大输出是另一层限制，见 `modelConfiguration.maxOutputTokens`。
   */
  inputTokensUsed: number; maxInputTokens: number | null
  outputTokensUsed: number; maxOutputTokens: number | null
  errorCode: string | null
  /** 运行真实消耗（可高于累计上限，仅作审计显示）。 */
  inputTokensActual?: number; outputTokensActual?: number
  /** 资源策略版本：1=既有累计额度语义，2=累计只统计 + 父子独立执行额度。 */
  contextPolicyVersion?: number
  /** 运行内自动重试已发生次数：FAILED_RETRYABLE 时用于"第 N 次尝试"展示 */
  retryCount?: number
  /** 暂停意图落库时间：非空且状态为 RUNNING 时表示"正在暂停"；PAUSED 时为暂停请求时间 */
  pauseRequestedAt?: string | null
}
export type AgentEventType =
  | 'RUN_CREATED' | 'CONTEXT_CAPTURED' | 'SKILL_SELECTED'
  | 'PLAN_CREATED' | 'PLAN_UPDATED' | 'MODEL_STARTED' | 'MODEL_COMPLETED'
  | 'TOOL_CALL_PROPOSED' | 'TOOL_CALL_STARTED' | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED' | 'APPROVAL_REQUESTED' | 'APPROVAL_APPROVED'
  | 'APPROVAL_REJECTED' | 'APPROVAL_EXPIRED' | 'APPROVAL_UPDATED'
  | 'RESULT_VERIFIED'
  | 'RUN_RETRY_SCHEDULED' | 'RUN_PAUSE_REQUESTED' | 'RUN_PAUSED' | 'RUN_RESUMED'
  | 'RUN_CANCELED' | 'RUN_SUCCEEDED'
  | 'RUN_FAILED' | 'RUN_BUDGET_EXCEEDED'
  | 'WAITING_FOR_USER_INPUT'

export interface AgentRunEvent<T extends Record<string, unknown> = Record<string, unknown>> {
  id: string
  projectId: string
  runId: string
  sequence: number
  type: AgentEventType
  payload: T
  createdAt: string
}

/**
 * 临时正文帧（SSE 事件名 MODEL_CONTENT）：只服务当前请求的实时展示，
 * 不进入持久事件流、不占序号；text 是自包含的累计正文快照，按 revision 幂等替换。
 */
export interface AgentContentFrame {
  modelCallId: string
  revision: number
  text: string
  final: boolean
}

export interface AgentPageContext {
  route: string
  selectedTaskId?: string | null
  selectedMilestoneId?: string | null
  selectedDocumentId?: string | null
  selectedPlanId?: string | null
  filters: Record<string, unknown>
}

export interface AgentPlanView {
  version: number
  objective: string
  steps: Array<{ id: string; title: string; purpose?: string; status: string }>
  successCriteria?: string[]
}
export interface AgentMessage {
  id: string; role: 'USER' | 'ASSISTANT'; content: string
  sessionId: string | null; runId: string | null
  citations: unknown[]; inferences: unknown[]; createdAt: string
}
export type AgentProposalFamily =
  | 'TASK_CREATE' | 'TASK_UPDATE' | 'MILESTONE_CREATE' | 'MILESTONE_UPDATE' | 'MEMORY_CREATE'

export interface AgentApproval {
  id: string; runId: string; toolName: string; arguments: Record<string, unknown>
  diff: Record<string, unknown>; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CONFLICTED'
  nonce: string | null; expiresAt: string; createdAt: string; result: unknown
  rejectionReason: string | null
  // V37 新增字段
  sessionId: string; proposalFamily: AgentProposalFamily; subjectKey: string
  revision: number; updatedAt: string
}

export interface AgentProposalRevision {
  id: string; projectId: string; approvalId: string; sourceRunId: string
  revision: number; beforeArguments: Record<string, unknown>
  afterArguments: Record<string, unknown>; diff: Record<string, unknown>
  createdAt: string
}
export interface AgentSkill {
  code: string; displayName: string; description: string
  recommendedRoutes: string[]; inputSchema: Record<string, unknown>
}
