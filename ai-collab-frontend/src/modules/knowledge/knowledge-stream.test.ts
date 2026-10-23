import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../../stores/auth-store'
import { knowledgeApi, KnowledgeStreamError } from './knowledge-api'

beforeEach(() => {
  setActivePinia(createPinia())
  useAuthStore().accessToken = 'token'
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('knowledge stream parser', () => {
  it('refreshes an expired access token once before opening the stream', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: done\ndata: {"type":"done","messageId":"m1"}\n\n'))
        controller.close()
      },
    })
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 401, body: null })
      .mockResolvedValueOnce({ ok: true, status: 200, body })
    vi.stubGlobal('fetch', fetchMock)
    const auth = useAuthStore()
    vi.spyOn(auth, 'refresh').mockImplementation(async () => {
      auth.accessToken = 'refreshed-token'
      return {
        httpStatus: 200,
        code: 'SUCCESS',
        message: '操作成功',
        data: { accessToken: 'refreshed-token', tokenType: 'Bearer', expiresInSeconds: 900 },
      }
    })

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone: vi.fn(),
      },
    )

    expect(auth.refresh).toHaveBeenCalledOnce()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[1]?.[1]?.headers).toMatchObject({
      Authorization: 'Bearer refreshed-token',
    })
  })

  it('keeps the event name when event and data arrive in different chunks', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: token\n'))
        controller.enqueue(encoder.encode('data: {"type":"token","text":"你好"}\n\n'))
        controller.enqueue(encoder.encode('event: done\ndata: {"type":"done","messageId":"m1"}\n\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const tokens: string[] = []
    const done = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: text => tokens.push(text),
        onCitations: vi.fn(),
        onDone: done,
      },
    )

    expect(tokens).toEqual(['你好'])
    expect(done).toHaveBeenCalledWith('m1')
  })

  it('throws when the stream closes without a terminal event', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: token\ndata: {"type":"token","text":"部分"}\n\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))

    // 错误必须以异常抛出：静默返回会让成功续流程照常执行（历史缺陷的根源）。
    await expect(knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      { onToken: vi.fn(), onCitations: vi.fn(), onDone: vi.fn() },
    )).rejects.toMatchObject({ code: 'STREAM_INCOMPLETE', message: '回答连接提前结束，请重试' })
  })

  it('throws a KnowledgeStreamError with backend message on SSE error event', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: error\ndata: {"type":"error","code":"AI_PROVIDER_QUOTA_EXCEEDED","message":"模型服务额度不足"}\n\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))

    await expect(knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      { onToken: vi.fn(), onCitations: vi.fn(), onDone: vi.fn() },
    )).rejects.toBeInstanceOf(KnowledgeStreamError)
  })

  it('throws with the backend message when HTTP responds an error status', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 429,
      body: null,
      text: async () => JSON.stringify({ code: 'AI_RATE_LIMIT_EXCEEDED', message: '问答请求过于频繁' }),
    }))

    await expect(knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      { onToken: vi.fn(), onCitations: vi.fn(), onDone: vi.fn() },
    )).rejects.toMatchObject({ code: 'HTTP_429', message: '问答请求过于频繁' })
  })

  it('processes a terminal event left in the final buffer', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: done\ndata: {"type":"done","messageId":"m1"}'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const onDone = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone,
      },
    )

    expect(onDone).toHaveBeenCalledOnce()
    expect(onDone).toHaveBeenCalledWith('m1')
  })

  it('accepts CRLF and an event type carried by the JSON payload', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"type":"done","messageId":"m2"}\r\n\r\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const onDone = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone,
      },
    )

    expect(onDone).toHaveBeenCalledOnce()
    expect(onDone).toHaveBeenCalledWith('m2')
  })
})
