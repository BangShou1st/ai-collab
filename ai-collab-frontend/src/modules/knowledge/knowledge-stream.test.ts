import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../../stores/auth-store'
import { knowledgeApi } from './knowledge-api'

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
        onError: vi.fn(),
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
        onError: vi.fn(),
      },
    )

    expect(tokens).toEqual(['你好'])
    expect(done).toHaveBeenCalledWith('m1')
  })

  it('reports a stream that closes without a terminal event', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('event: token\ndata: {"type":"token","text":"部分"}\n\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const onError = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone: vi.fn(),
        onError,
      },
    )

    expect(onError).toHaveBeenCalledWith('STREAM_INCOMPLETE', '回答连接提前结束，请重试')
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
    const onError = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone,
        onError,
      },
    )

    expect(onDone).toHaveBeenCalledOnce()
    expect(onDone).toHaveBeenCalledWith('m1')
    expect(onError).not.toHaveBeenCalled()
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
    const onError = vi.fn()

    await knowledgeApi.askStream(
      'project-1',
      'session-1',
      '问题',
      [],
      {
        onToken: vi.fn(),
        onCitations: vi.fn(),
        onDone,
        onError,
      },
    )

    expect(onDone).toHaveBeenCalledOnce()
    expect(onDone).toHaveBeenCalledWith('m2')
    expect(onError).not.toHaveBeenCalled()
  })
})
