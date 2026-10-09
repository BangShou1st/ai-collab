import { describe, expect, it } from 'vitest'
import { reduceAgentActivities } from './agent-activity'
import type { AgentRunEvent } from './types'

function event(sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent {
  return { id: `review-close-${sequence}`, runId: 'r', projectId: 'p', sequence, type, payload, createdAt: '2026-10-06T11:00:00Z' }
}
const failedRequest = [
  event(1, 'MODEL_STARTED', { model: 'configured-model' }),
  event(2, 'RUN_FAILED', { status: 'FAILED_RETRYABLE', errorCode: 'AI_MODEL_TIMEOUT', retryable: true }),
]

describe('review probes: end-of-request presentation', () => {
  it('has no model request in flight while waiting to retry a failed request', () => {
    const rows = reduceAgentActivities(failedRequest)
    expect(rows.find(row => row.key === 'retry:2')?.detail).toContain('等待自动重试')
    expect(rows.filter(row => row.key === 'model:analyzing')).toHaveLength(0)
  })

  it('does not show a failed model request as analyzing while the run is paused', () => {
    const rows = reduceAgentActivities([...failedRequest, event(3, 'RUN_PAUSED')])
    expect(rows.find(row => row.key === 'retry:2')?.status).toBe('waiting')
    expect(rows.filter(row => row.key === 'model:analyzing')).toHaveLength(0)
  })

  it('does not revive analyzing on resume until a new model request starts', () => {
    const rows = reduceAgentActivities([...failedRequest, event(3, 'RUN_PAUSED'), event(4, 'RUN_RESUMED')])
    expect(rows.find(row => row.key === 'retry:2')?.detail).toContain('等待自动重试')
    expect(rows.filter(row => row.key === 'model:analyzing')).toHaveLength(0)
  })

  it('does not keep a completed retry request running while waiting for user clarification', () => {
    const rows = reduceAgentActivities([
      ...failedRequest, event(3, 'MODEL_STARTED'),
      event(4, 'MODEL_COMPLETED', { toolCallCount: 0, content: '[QUESTIONS]请确认交付范围' }),
      event(5, 'WAITING_FOR_USER_INPUT'),
    ])
    expect(rows.some(row => row.key === 'waiting:input')).toBe(true)
    expect(rows.find(row => row.key === 'retry:2')?.status).not.toBe('running')
  })
})
