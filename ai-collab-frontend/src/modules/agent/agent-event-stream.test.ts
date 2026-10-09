import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { streamAgentEvents } from './agent-event-stream'

afterEach(() => vi.unstubAllGlobals())
beforeEach(() => setActivePinia(createPinia()))

describe('agent event stream', () => {
  it('parses CRLF, comments, and multiline data', async () => {
    const encoder = new TextEncoder()
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(': heartbeat\r\nid: 1\r\nevent: RUN_CREATED\r\n'))
        controller.enqueue(encoder.encode('data: {"id":"e1","projectId":"p","runId":"r",\r\n'))
        controller.enqueue(encoder.encode('data: "sequence":1,"type":"RUN_CREATED","payload":{},"createdAt":"now"}\r\n\r\n'))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const received: number[] = []

    await streamAgentEvents('/events', 0, new AbortController().signal, event => {
      received.push(event.sequence)
    })

    expect(received).toEqual([1])
  })

  it('parses a CRLF frame separator split between byte chunks', async () => {
    const encoder = new TextEncoder()
    const event = 'data: {"id":"e2","projectId":"p","runId":"r","sequence":2,"type":"RUN_CREATED","payload":{},"createdAt":"now"}\r\n\r\n'
    const nextEvent = 'data: {"id":"e3","projectId":"p","runId":"r","sequence":3,"type":"RUN_CREATED","payload":{},"createdAt":"now"}\r\n\r\n'
    const split = event.indexOf('\r\n\r\n') + 3
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(event.slice(0, split)))
        controller.enqueue(encoder.encode(event.slice(split) + nextEvent))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const received: number[] = []

    await streamAgentEvents('/events', 0, new AbortController().signal, value => received.push(value.sequence))

    expect(received).toEqual([2, 3])
  })

  it('routes MODEL_CONTENT frames to onContent without entering the event timeline', async () => {
    const encoder = new TextEncoder()
    const event = 'data: {"id":"e4","projectId":"p","runId":"r","sequence":4,"type":"MODEL_STARTED","payload":{},"createdAt":"now"}\n\n'
    const content = 'event: MODEL_CONTENT\ndata: {"modelCallId":"call-1","revision":1,"text":"正在生成","final":false}\n\n'
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(event + content))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const received: number[] = []
    const frames: Array<{ modelCallId: string; revision: number; text: string; final: boolean }> = []

    await streamAgentEvents('/events', 0, new AbortController().signal,
      value => received.push(value.sequence),
      frame => frames.push(frame))

    expect(received).toEqual([4])
    expect(frames).toEqual([{ modelCallId: 'call-1', revision: 1, text: '正在生成', final: false }])
  })

  it('skips invalid content frames and keeps routing persistent events', async () => {
    const encoder = new TextEncoder()
    const badContent = 'event: MODEL_CONTENT\ndata: {"revision":"x"}\n\n'
    const goodEvent = 'data: {"id":"e5","projectId":"p","runId":"r","sequence":5,"type":"MODEL_COMPLETED","payload":{},"createdAt":"now"}\n\n'
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode(badContent + goodEvent))
        controller.close()
      },
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body }))
    const received: number[] = []
    const frames: unknown[] = []
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})

    try {
      await streamAgentEvents('/events', 0, new AbortController().signal,
        value => received.push(value.sequence),
        frame => frames.push(frame))
    } finally {
      warn.mockRestore()
    }

    expect(received).toEqual([5])
    expect(frames).toEqual([])
  })
})
