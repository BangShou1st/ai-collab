export type AgentRunStatus =
  | 'CREATED' | 'QUEUED' | 'RUNNING' | 'WAITING_FOR_APPROVAL'
  | 'SUCCEEDED' | 'FAILED_RETRYABLE' | 'FAILED' | 'CANCELED' | 'BUDGET_EXCEEDED'

export interface AgentSession {
  id: string; projectId: string; creatorId: string; title: string
  status: string; version: number; createdAt: string; updatedAt: string
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
  | 'APPROVAL_REJECTED' | 'APPROVAL_EXPIRED' | 'RESULT_VERIFIED'
  | 'RUN_RETRY_SCHEDULED' | 'RUN_CANCELED' | 'RUN_SUCCEEDED'
  | 'RUN_FAILED' | 'RUN_BUDGET_EXCEEDED'

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
  citations: unknown[]; inferences: unknown[]; createdAt: string
}
export interface AgentApproval {
  id: string; runId: string; toolName: string; arguments: Record<string, unknown>
  diff: Record<string, unknown>; status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CONFLICTED'
  nonce: string | null; expiresAt: string; createdAt: string; result: unknown
  rejectionReason: string | null
}
export interface AgentSchedule {
  id: string; sessionId: string; name: string; goal: string
  skillCode: string | null
  frequency: 'DAILY' | 'WEEKLY'; timeZone: string; localTime: string
  weeklyDay: number | null; enabled: boolean; nextFireAt: string
  lastStatus: string | null; version: number
}
export interface McpBinding {
  projectId: string; connectionId: string; connectionCode: string; connectionName: string
  connectionEnabled: boolean; enabled: boolean; allowedTools: string[]; allowedResources: string[]
  configuration: Record<string, unknown> | null; version: number; createdAt: string; updatedAt: string
}
