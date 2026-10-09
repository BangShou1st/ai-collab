import { authenticatedFetch } from '../../api/authenticated-fetch'
import type { AgentContentFrame, AgentRunEvent } from './types'

/** 临时正文帧的 SSE 事件名；与后端 AgentEventStreamService.publishContentDelta 对应。 */
const CONTENT_EVENT_NAME = 'MODEL_CONTENT'

export async function streamAgentEvents(
  url: string,
  afterSequence: number,
  signal: AbortSignal,
  onEvent: (event: AgentRunEvent) => void,
  onContent?: (frame: AgentContentFrame) => void,
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
      if (buffer.trim()) parseFrame(buffer, onEvent, onContent)
      return
    }
    buffer += decoder.decode(value, { stream: true })
    buffer = buffer.replaceAll('\r\n', '\n')
    const frames = buffer.split('\n\n')
    buffer = frames.pop() ?? ''
    for (const frame of frames) parseFrame(frame, onEvent, onContent)
  }
}

function parseFrame(
  frame: string,
  onEvent: (event: AgentRunEvent) => void,
  onContent?: (frame: AgentContentFrame) => void,
): void {
  const lines = frame.split('\n')
  const data = lines
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
    .join('\n')
  if (!data) return
  // 临时正文帧与持久事件按 SSE 事件名区分：正文帧不进入事件时间线
  const eventName = lines
    .filter(line => line.startsWith('event:'))
    .map(line => line.slice(6).trim())
    .at(-1)
  if (onContent && eventName === CONTENT_EVENT_NAME) {
    try {
      const parsed: unknown = JSON.parse(data)
      if (!isAgentContentFrame(parsed)) {
        console.warn('Agent SSE 正文帧格式无效，跳过')
        return
      }
      onContent(parsed)
    } catch (e) {
      console.warn('Agent SSE 正文帧解析失败，跳过:', e)
    }
    return
  }
  try {
    const parsed: unknown = JSON.parse(data)
    if (!isAgentRunEvent(parsed)) {
      console.warn('Agent SSE 事件格式无效，跳过')
      return
    }
    onEvent(parsed)
  } catch (e) {
    console.warn('Agent SSE 帧解析失败，跳过:', e)
  }
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

function isAgentContentFrame(value: unknown): value is AgentContentFrame {
  if (typeof value !== 'object' || value === null) return false
  const frame = value as Record<string, unknown>
  return typeof frame.modelCallId === 'string'
    && typeof frame.revision === 'number'
    && typeof frame.text === 'string'
    && typeof frame.final === 'boolean'
}
