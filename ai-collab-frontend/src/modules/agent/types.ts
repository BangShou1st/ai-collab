export type AgentRunStatus =
  | 'CREATED' | 'QUEUED' | 'RUNNING' | 'WAITING_FOR_APPROVAL'
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
}
export interface AgentRun {
  id: string; sessionId: string; projectId: string; goal: string; status: AgentRunStatus
  skillCode?: string | null
  stepsUsed: number; maxSteps: number; toolCallsUsed: number; maxToolCalls: number
  inputTokensUsed: number; maxInputTokens: number; outputTokensUsed: number
  maxOutputTokens: number; errorCode: string | null
}
export type AgentEventType =
  | 'RUN_CREATED' | 'CONTEXT_CAPTURED' | 'SKILL_SELECTED'
  | 'PLAN_CREATED' | 'PLAN_UPDATED' | 'MODEL_STARTED' | 'MODEL_COMPLETED'
  | 'TOOL_CALL_PROPOSED' | 'TOOL_CALL_STARTED' | 'TOOL_CALL_COMPLETED'
  | 'TOOL_CALL_FAILED' | 'APPROVAL_REQUESTED' | 'APPROVAL_APPROVED'
  | 'APPROVAL_REJECTED' | 'APPROVAL_EXPIRED' | 'APPROVAL_UPDATED'
  | 'RESULT_VERIFIED'
  | 'RUN_RETRY_SCHEDULED' | 'RUN_CANCELED' | 'RUN_SUCCEEDED'
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
