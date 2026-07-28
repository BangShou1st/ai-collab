import { apiResultFromResponse } from '../../api/api-result'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  KnowledgeAnswer,
  KnowledgeSession,
  KnowledgeSessionDetail,
} from './types'

export const knowledgeApi = {
  async listSessions(projectId: string): Promise<ApiResult<KnowledgeSession[]>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<KnowledgeSession[]>>(
        `/projects/${projectId}/knowledge/sessions`,
      ),
    )
  },
  async createSession(projectId: string): Promise<ApiResult<KnowledgeSession>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<KnowledgeSession>>(
        `/projects/${projectId}/knowledge/sessions`,
      ),
    )
  },
  async renameSession(projectId: string, sessionId: string, title: string): Promise<ApiResult<KnowledgeSession>> {
    return apiResultFromResponse(
      await httpClient.patch<ApiResponse<KnowledgeSession>>(
        `/projects/${projectId}/knowledge/sessions/${sessionId}`,
        { title },
      ),
    )
  },
  async getSession(
    projectId: string,
    sessionId: string,
  ): Promise<ApiResult<KnowledgeSessionDetail>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<KnowledgeSessionDetail>>(
        `/projects/${projectId}/knowledge/sessions/${sessionId}`,
      ),
    )
  },
  async deleteSession(projectId: string, sessionId: string): Promise<void> {
    await httpClient.delete(`/projects/${projectId}/knowledge/sessions/${sessionId}`)
  },
  async ask(
    projectId: string,
    sessionId: string,
    question: string,
    documentIds: string[],
  ): Promise<ApiResult<KnowledgeAnswer>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<KnowledgeAnswer>>(
        `/projects/${projectId}/knowledge/sessions/${sessionId}/questions`,
        { question, documentIds },
      ),
    )
  },
}
