import { apiResultFromResponse } from '../../api/api-result'
import { authenticatedFetch } from '../../api/authenticated-fetch'
import { httpClient } from '../../api/http-client'
import type { ApiResponse, ApiResult } from '../../api/types'
import type {
  KnowledgeCitation,
  KnowledgeFeedback,
  KnowledgeSession,
  KnowledgeSessionDetail,
} from './types'

export interface StreamCallbacks {
  onToken: (text: string) => void
  onCitations: (citations: KnowledgeCitation[]) => void
  onDone: (messageId: string) => void
}

/**
 * 流式问答失败的统一错误：携带后端错误码与可读信息。
 * 所有失败路径（HTTP 错误、SSE error 事件、流提前结束）都抛出该异常，
 * 由调用方决定如何呈现——绝不静默返回，否则成功续流程会照常执行。
 */
export class KnowledgeStreamError extends Error {
  readonly code: string
  constructor(code: string, message: string) {
    super(message)
    this.name = 'KnowledgeStreamError'
    this.code = code
  }
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
      }
    )
    if (!response.ok) {
      // 优先读取后端结构化错误（ApiResponse）；读不到时给出可读的 HTTP 状态。
      let message = `请求失败 (HTTP ${response.status})`
      try {
        const body = await response.text()
        const parsed = JSON.parse(body)
        if (parsed?.message) message = parsed.message
      } catch {
        // 非 JSON 响应体，保持默认文案
      }
      throw new KnowledgeStreamError(`HTTP_${response.status}`, message)
    }
    const reader = response.body?.getReader()
    if (!reader) {
      throw new KnowledgeStreamError('NO_BODY', '无法读取响应流')
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
            throw new KnowledgeStreamError(parsed.code ?? 'UNKNOWN', parsed.message ?? '未知错误')
        }
      } catch (error) {
        // 流错误事件是终止性结论，必须向上传播；只忽略无法解析的单条事件。
        if (error instanceof KnowledgeStreamError) throw error
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
        throw new KnowledgeStreamError('STREAM_INCOMPLETE', '回答连接提前结束，请重试')
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
