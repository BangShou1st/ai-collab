import { apiResultFromResponse } from '../../api/api-result'
import { authenticatedFetch } from '../../api/authenticated-fetch'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  KnowledgeAnswer,
  KnowledgeCitation,
  KnowledgeFeedback,
  KnowledgeSession,
  KnowledgeSessionDetail,
} from './types'

export interface StreamCallbacks {
  onToken: (text: string) => void
  onCitations: (citations: KnowledgeCitation[]) => void
  onDone: (messageId: string) => void
  onError: (code: string, message: string) => void
}

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
  async askStream(
    projectId: string,
    sessionId: string,
    question: string,
    documentIds: string[],
    callbacks: StreamCallbacks,
    signal?: AbortSignal,
  ): Promise<void> {
    const response = await authenticatedFetch(
      `/api/v1/projects/${projectId}/knowledge/sessions/${sessionId}/questions/stream`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ question, documentIds }),
        signal,
      },
    )
    if (!response.ok) {
      callbacks.onError('HTTP_ERROR', `请求失败 (${response.status})`)
      return
    }
    const reader = response.body?.getReader()
    if (!reader) {
      callbacks.onError('NO_BODY', '无法读取响应流')
      return
    }
    const decoder = new TextDecoder()
    let buffer = ''
    let eventType = ''
    let terminalReceived = false
    const consumeLine = (rawLine: string): void => {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine
      if (line.startsWith('event:')) {
        eventType = line.slice(6).trim()
        return
      }
      if (!line.startsWith('data:')) return

      const data = line.slice(5).trimStart()
      try {
        const parsed = JSON.parse(data)
        const resolvedType = eventType || parsed.type || ''
        if (terminalReceived) return
        switch (resolvedType) {
          case 'token':
            callbacks.onToken(parsed.text ?? '')
            break
          case 'citations':
            callbacks.onCitations(parsed.citations ?? [])
            break
          case 'done':
            terminalReceived = true
            callbacks.onDone(parsed.messageId ?? '')
            break
          case 'error':
            terminalReceived = true
            callbacks.onError(parsed.code ?? 'UNKNOWN', parsed.message ?? '未知错误')
            break
        }
      } catch {
        // 忽略无法解析的单条事件，连接结束时仍会检查是否收到终止事件。
      } finally {
        eventType = ''
      }
    }
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''
        lines.forEach(consumeLine)
      }
      buffer += decoder.decode()
      if (buffer) buffer.split('\n').forEach(consumeLine)
      if (!terminalReceived) {
        callbacks.onError('STREAM_INCOMPLETE', '回答连接提前结束，请重试')
      }
    } finally {
      reader.releaseLock()
    }
  },
  async submitFeedback(
    projectId: string,
    messageId: string,
    helpful: boolean,
  ): Promise<ApiResult<KnowledgeFeedback>> {
    return apiResultFromResponse(
      await httpClient.post<ApiResponse<KnowledgeFeedback>>(
        `/projects/${projectId}/knowledge/sessions/messages/${messageId}/feedback`,
        { helpful },
      ),
    )
  },
  async removeFeedback(
    projectId: string,
    messageId: string,
  ): Promise<ApiResult<KnowledgeFeedback>> {
    await httpClient.delete(`/projects/${projectId}/knowledge/sessions/messages/${messageId}/feedback`)
    return knowledgeApi.getFeedback(projectId, messageId)
  },
  async getFeedback(
    projectId: string,
    messageId: string,
  ): Promise<ApiResult<KnowledgeFeedback>> {
    return apiResultFromResponse(
      await httpClient.get<ApiResponse<KnowledgeFeedback>>(
        `/projects/${projectId}/knowledge/sessions/messages/${messageId}/feedback`,
      ),
    )
  },
}
