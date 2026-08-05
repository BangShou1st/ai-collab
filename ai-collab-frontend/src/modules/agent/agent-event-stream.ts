import { authenticatedFetch } from '../../api/authenticated-fetch'
import type { AgentRunEvent } from './types'

export async function streamAgentEvents(
  url: string,
  afterSequence: number,
  signal: AbortSignal,
  onEvent: (event: AgentRunEvent) => void,
): Promise<void> {
  const separator = url.includes('?') ? '&' : '?'
  const response = await authenticatedFetch(`${url}${separator}afterSequence=${afterSequence}`, {
    headers: { Accept: 'text/event-stream', 'Last-Event-ID': String(afterSequence) },
    signal,
  })
  if (!response.ok || !response.body) throw new Error(`SSE ${response.status}`)

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { value, done } = await reader.read()
    if (done) {
      buffer += decoder.decode()
      buffer = buffer.replaceAll('\r\n', '\n')
      if (buffer.trim()) parseFrame(buffer, onEvent)
      return
    }
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replaceAll('\r\n', '\n')
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) parseFrame(frame, onEvent)
  }
}

function parseFrame(frame: string, onEvent: (event: AgentRunEvent) => void): void {
  const data = frame.split('\n')
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
    .join('\n')
  if (!data) return
  const parsed: unknown = JSON.parse(data)
  if (!isAgentRunEvent(parsed)) throw new Error('Agent SSE 事件格式无效')
  onEvent(parsed)
}

function isAgentRunEvent(value: unknown): value is AgentRunEvent {
  if (typeof value !== 'object' || value === null) return false
  const event = value as Record<string, unknown>
  return typeof event.id === 'string'
    && typeof event.projectId === 'string'
    && typeof event.runId === 'string'
    && typeof event.sequence === 'number'
    && typeof event.type === 'string'
    && typeof event.payload === 'object'
    && typeof event.createdAt === 'string'
}
