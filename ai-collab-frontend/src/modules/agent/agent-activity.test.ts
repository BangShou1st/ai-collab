import { describe, expect, it } from 'vitest'
import { reduceAgentActivities } from './agent-activity'
import type { AgentRunEvent } from './types'

function evt(seq: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent {
  return { id: `e${seq}`, projectId: 'p', runId: 'r', sequence: seq, type, payload, createdAt: new Date().toISOString() }
}

describe('reduceAgentActivities', () => {
  it('aggregates same callId lifecycle into one activity', () => {
    const list = reduceAgentActivities([
      evt(1, 'TOOL_CALL_STARTED', { callId: 'c1', toolName: 'list_tasks' }),
      evt(2, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 18, durationMs: 400 }),
    ])
    expect(list).toHaveLength(1)
    expect(list[0].title).toBe('读取项目任务')
    expect(list[0].status).toBe('done')
    expect(list[0].count).toBe(18)
  })
  it('shows running verb while in progress', () => {
    const list = reduceAgentActivities([evt(1, 'TOOL_CALL_STARTED', { callId: 'c1', toolName: 'list_tasks' })])
    expect(list[0].status).toBe('running')
    expect(list[0].title).toContain('正在读取')
  })
  it('falls back safely for unknown tools without snake_case titles', () => {
    const list = reduceAgentActivities([evt(1, 'TOOL_CALL_COMPLETED', { callId: 'c9', toolName: 'mystery_tool' })])
    expect(list[0].title).toBe('工具调用')
    expect(list[0].title).not.toContain('_')
  })
  it('never shows unknown for tool calls without a name', () => {
    const list = reduceAgentActivities([evt(1, 'TOOL_CALL_STARTED', { callId: 'c9' })])
    expect(list).toHaveLength(1)
    expect(list[0].title).not.toContain('unknown')
    expect(list[0].title).not.toMatch(/_/)
  })
  it('marks failures with detail', () => {
    const list = reduceAgentActivities([evt(1, 'TOOL_CALL_FAILED', { callId: 'c1', toolName: 'search_project_knowledge', error: '暂时不可访问' })])
    expect(list[0].status).toBe('failed')
    expect(list[0].detail).toBe('暂时不可访问')
  })
})
