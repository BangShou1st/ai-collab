import type { AxiosAdapter, AxiosResponse } from 'axios'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { httpClient } from '../../api/http-client'
import { useAuthStore } from '../../stores/auth-store'
import { isFeedbackEligible } from './knowledge-feedback'
import { knowledgeApi } from './knowledge-api'
import type { KnowledgeMessage } from './types'

const originalAdapter = httpClient.defaults.adapter

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
  vi.unstubAllGlobals()
})

const message = (role: KnowledgeMessage['role']): KnowledgeMessage => ({
  id: 'message-1',
  role,
  content: '内容',
  insufficientEvidence: false,
  model: null,
  citations: [],
  createdAt: '2026-07-29T15:00:00Z',
})

describe('knowledge feedback', () => {
  it('allows feedback only for persisted assistant answers', () => {
    expect(isFeedbackEligible(message('USER'))).toBe(false)
    expect(isFeedbackEligible(message('ASSISTANT'))).toBe(true)
    expect(isFeedbackEligible({ ...message('ASSISTANT'), id: '' })).toBe(false)
  })

  it('uses the backend feedback endpoint under knowledge sessions', async () => {
    let requestedUrl = ''
    httpClient.defaults.adapter = (async (config) => {
      requestedUrl = config.url ?? ''
      return {
        data: {
          code: 'SUCCESS',
          message: '操作成功',
          data: { myFeedback: true, helpfulCount: 1, unhelpfulCount: 0 },
        },
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
      } as AxiosResponse
    }) as AxiosAdapter

    await knowledgeApi.submitFeedback('project-1', 'message-1', true)

    expect(requestedUrl).toBe(
      '/projects/project-1/knowledge/sessions/messages/message-1/feedback',
    )
  })

  it('authenticates streaming questions with the active Pinia access token', async () => {
    useAuthStore().accessToken = 'active-token'
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
    })
    vi.stubGlobal('fetch', fetchMock)

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '项目进度如何',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone: vi.fn(),
        onError: vi.fn(),
      },
    )

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/projects/project-1/knowledge/sessions/session-1/questions/stream',
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: 'Bearer active-token',
        }),
      }),
    )
  })
})
