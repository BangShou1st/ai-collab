import { describe, expect, it } from 'vitest'
import { reduceAgentActivities } from './agent-activity'
import type { AgentRunEvent } from './types'

function event(sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent {
  return { id: `review-${sequence}`, runId: 'r', projectId: 'p', sequence, type, payload, createdAt: '2026-10-06T10:00:00Z' }
}
const transient = (sequence: number) => event(sequence, 'RUN_FAILED', { status: 'FAILED_RETRYABLE', errorCode: 'AI_MODEL_TIMEOUT', retryable: true })

describe('review probes: production automatic-retry event sequences', () => {
  it('shows analyzing when an automatic retry starts after retryable failure', () => {
    const rows = reduceAgentActivities([
      event(1, 'MODEL_STARTED', { model: 'configured-model' }), transient(2),
      event(3, 'MODEL_STARTED', { model: 'configured-model' }),
    ])
    expect(rows.filter(row => row.key === 'model:analyzing')).toHaveLength(1)
  })

  it('keeps the current tool running after the model retry has recovered', () => {
    const rows = reduceAgentActivities([
      event(1, 'MODEL_STARTED'), transient(2), event(3, 'MODEL_STARTED'),
      event(4, 'MODEL_COMPLETED', { toolCallCount: 1, content: '继续读取任务' }),
      event(5, 'TOOL_CALL_STARTED', { callId: 'new-call', toolName: 'list_tasks' }),
    ])
    expect(rows.find(row => row.key === 'tool:new-call')?.status).toBe('running')
  })

  it('shows the user-input wait after a recovered request asks for clarification', () => {
    const rows = reduceAgentActivities([
      event(1, 'MODEL_STARTED'), transient(2), event(3, 'MODEL_STARTED'),
      event(4, 'MODEL_COMPLETED', { toolCallCount: 0, content: '[QUESTIONS]请确认交付范围' }),
      event(5, 'WAITING_FOR_USER_INPUT'),
    ])
    expect(rows.some(row => row.key === 'waiting:input')).toBe(true)
  })

  it('does not keep the earlier retry spinning when a later attempt failed and is waiting', () => {
    const rows = reduceAgentActivities([
      event(1, 'MODEL_STARTED'), transient(2), event(3, 'MODEL_STARTED'), transient(4),
    ])
    expect(rows.find(row => row.key === 'retry:2')?.status).not.toBe('running')
  })

  it('does not promise automatic retry while the user has paused the run', () => {
    const rows = reduceAgentActivities([event(1, 'MODEL_STARTED'), transient(2), event(3, 'RUN_PAUSED')])
    expect(rows.find(row => row.key === 'retry:2')?.detail).not.toContain('等待自动重试')
  })

  it('does not claim the budget stopped retry before a request that actually started', () => {
    const rows = reduceAgentActivities([
      event(1, 'MODEL_STARTED'), transient(2), event(3, 'MODEL_STARTED'),
      event(4, 'MODEL_COMPLETED', { toolCallCount: 0, content: '已完成部分分析' }),
      event(5, 'RUN_BUDGET_EXCEEDED'),
    ])
    expect(rows.find(row => row.key === 'retry:2')?.detail).not.toContain('重试前')
  })
})
