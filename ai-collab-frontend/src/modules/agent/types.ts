export type AgentRunStatus =
  | 'CREATED' | 'QUEUED' | 'RUNNING' | 'WAITING_FOR_APPROVAL'
  | 'SUCCEEDED' | 'FAILED_RETRYABLE' | 'FAILED' | 'CANCELED' | 'BUDGET_EXCEEDED'

export interface AgentSession {
  id: string; projectId: string; creatorId: string; title: string
  status: string; version: number; createdAt: string; updatedAt: string
}
export interface AgentRun {
  id: string; sessionId: string; projectId: string; goal: string; status: AgentRunStatus
  stepsUsed: number; maxSteps: number; toolCallsUsed: number; maxToolCalls: number
  inputTokensUsed: number; maxInputTokens: number; outputTokensUsed: number
  maxOutputTokens: number; errorCode: string | null
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
  frequency: 'DAILY' | 'WEEKLY'; timeZone: string; localTime: string
  weeklyDay: number | null; enabled: boolean; nextFireAt: string
  lastStatus: string | null; version: number
}
