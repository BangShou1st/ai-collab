import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  AgentApproval, AgentMessage, AgentPageContext, AgentRun, AgentRunDetail, AgentRunEvent, AgentSession, AgentSessionSummary, AgentSkill,
} from './types'

const root = (projectId: string) => `/projects/${projectId}/agent`
const idempotencyKeys = new Map<string, string>()
const idempotencyKey = (approvalId: string, action: string) => {
  const key = `${approvalId}:${action}`
  if (!idempotencyKeys.has(key)) idempotencyKeys.set(key, crypto.randomUUID())
  return idempotencyKeys.get(key)!
}
export const agentApi = {
  async sessions(projectId: string): Promise<ApiResult<AgentSession[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentSession[]>>(`${root(projectId)}/sessions`))
  },
  async createSession(projectId: string, title: string): Promise<ApiResult<AgentSession>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentSession>>(`${root(projectId)}/sessions`, { title }))
  },
  async renameSession(
    projectId: string,
    sessionId: string,
    title: string,
  ): Promise<ApiResult<AgentSession>> {
    return apiResultFromResponse(await httpClient.patch<ApiResponse<AgentSession>>(
      `${root(projectId)}/sessions/${sessionId}`,
      { title },
    ))
  },
  async deleteSession(projectId: string, sessionId: string): Promise<void> {
    await httpClient.delete(`${root(projectId)}/sessions/${sessionId}`)
  },
  async messages(projectId: string, sessionId: string): Promise<ApiResult<AgentMessage[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentMessage[]>>(`${root(projectId)}/sessions/${sessionId}/messages`))
  },
  async submit(
    projectId: string,
    sessionId: string,
    body: { content: string; skillCode?: string | null; pageContext?: AgentPageContext | null },
  ): Promise<ApiResult<AgentRun>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentRun>>(
      `${root(projectId)}/sessions/${sessionId}/messages`, body,
    ))
  },
  async run(projectId: string, runId: string): Promise<ApiResult<{ run: AgentRun }>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<{ run: AgentRun }>>(`${root(projectId)}/runs/${runId}`))
  },
  async retry(projectId: string, runId: string): Promise<ApiResult<AgentRun>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentRun>>(`${root(projectId)}/runs/${runId}/retry`))
  },
  async continueRun(
    projectId: string,
    runId: string,
    content: string,
  ): Promise<ApiResult<AgentRun>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentRun>>(
      `${root(projectId)}/runs/${runId}/continue`, { content },
    ))
  },
  async cancel(projectId: string, runId: string): Promise<void> {
    await httpClient.post(`${root(projectId)}/runs/${runId}/cancel`)
  },
  async approvals(projectId: string): Promise<ApiResult<AgentApproval[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentApproval[]>>(`${root(projectId)}/approvals`))
  },
  async sessionSummaries(projectId: string): Promise<ApiResult<AgentSessionSummary[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentSessionSummary[]>>(`${root(projectId)}/session-summaries`))
  },
  async latestRun(projectId: string, sessionId: string): Promise<ApiResult<AgentRunDetail | null>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentRunDetail | null>>(`${root(projectId)}/sessions/${sessionId}/latest-run`))
  },
  async runApprovals(projectId: string, runId: string): Promise<ApiResult<AgentApproval[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentApproval[]>>(`${root(projectId)}/runs/${runId}/approvals`))
  },
  async runEvents(projectId: string, runId: string, afterSequence = 0): Promise<ApiResult<AgentRunEvent[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentRunEvent[]>>(`${root(projectId)}/runs/${runId}/events-history?afterSequence=${afterSequence}`))
  },
  async approve(projectId: string, item: AgentApproval): Promise<ApiResult<AgentApproval>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentApproval>>(
      `${root(projectId)}/approvals/${item.id}/approve`, { nonce: item.nonce },
      { headers: { 'Idempotency-Key': idempotencyKey(item.id, 'approve') } },
    ))
  },
  async reject(projectId: string, item: AgentApproval, reason: string): Promise<ApiResult<AgentApproval>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentApproval>>(
      `${root(projectId)}/approvals/${item.id}/reject`, { nonce: item.nonce, reason },
      { headers: { 'Idempotency-Key': idempotencyKey(item.id, 'reject') } },
    ))
  },
  async skills(projectId: string): Promise<ApiResult<AgentSkill[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentSkill[]>>(`${root(projectId)}/skills`))
  },
}
