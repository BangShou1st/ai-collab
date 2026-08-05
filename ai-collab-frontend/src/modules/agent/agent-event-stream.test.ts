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
})
