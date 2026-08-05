import { httpClient } from '../../api/http-client'
import { apiResultFromResponse } from '../../api/api-result'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  AgentApproval, AgentMessage, AgentPageContext, AgentRun, AgentSchedule, AgentSession, McpBinding,
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
  async cancel(projectId: string, runId: string): Promise<void> {
    await httpClient.post(`${root(projectId)}/runs/${runId}/cancel`)
  },
  async approvals(projectId: string): Promise<ApiResult<AgentApproval[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentApproval[]>>(`${root(projectId)}/approvals`))
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
  async schedules(projectId: string): Promise<ApiResult<AgentSchedule[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<AgentSchedule[]>>(`${root(projectId)}/schedules`))
  },
  async createSchedule(projectId: string, body: Record<string, unknown>): Promise<ApiResult<AgentSchedule>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentSchedule>>(`${root(projectId)}/schedules`, body))
  },
  async setSchedule(projectId: string, item: AgentSchedule, enabled: boolean): Promise<ApiResult<AgentSchedule>> {
    return apiResultFromResponse(await httpClient.post<ApiResponse<AgentSchedule>>(
      `${root(projectId)}/schedules/${item.id}/${enabled ? 'enable' : 'disable'}`, null,
      { params: { version: item.version } },
    ))
  },
  async mcpBindings(projectId: string): Promise<ApiResult<McpBinding[]>> {
    return apiResultFromResponse(await httpClient.get<ApiResponse<McpBinding[]>>(`${root(projectId)}/mcp-bindings`))
  },
  async bindMcp(
    projectId: string,
    connectionId: string,
    body: { enabled: boolean; allowedTools: string[]; allowedResources: string[]; configuration: Record<string, unknown>; version: number },
  ): Promise<ApiResult<McpBinding>> {
    return apiResultFromResponse(await httpClient.put<ApiResponse<McpBinding>>(
      `${root(projectId)}/mcp-bindings/${connectionId}`, body,
    ))
  },
  async unbindMcp(projectId: string, connectionId: string): Promise<void> {
    await httpClient.delete(`${root(projectId)}/mcp-bindings/${connectionId}`)
  },
}
