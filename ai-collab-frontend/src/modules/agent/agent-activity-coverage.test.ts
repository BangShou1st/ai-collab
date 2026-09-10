import { describe, expect, it } from 'vitest'
import { reduceAgentActivities } from './agent-activity'
import type { AgentEventType, AgentRunEvent } from './types'

function evt(seq: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent {
  return { id: `e${seq}`, projectId: 'p', runId: 'r', sequence: seq, type, payload, createdAt: new Date().toISOString() }
}

const NON_ROW_TYPES: AgentRunEvent['type'][] = [
  'RUN_CREATED', 'CONTEXT_CAPTURED', 'SKILL_SELECTED', 'PLAN_CREATED', 'PLAN_UPDATED',
  'MODEL_COMPLETED', 'APPROVAL_UPDATED', 'RESULT_VERIFIED', 'RUN_RETRY_SCHEDULED',
  'RUN_CANCELED', 'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_BUDGET_EXCEEDED',
  'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED',
]

describe('agent activity presentation policy', () => {
  it('never emits unknown, snake_case or raw enum rows', () => {
    const events = NON_ROW_TYPES.map((t, i) => evt(i + 1, t, { callId: 'c1', toolName: 'list_tasks' }))
    const rows = reduceAgentActivities(events)
    for (const r of rows) {
      expect(r.title).not.toContain('unknown')
      expect(r.title).not.toContain('UNKNOWN')
      expect(r.title).not.toMatch(/_/)
      expect(r.title).not.toMatch(/^[A-Z_]+$/)
    }
  })
  it('covers every AgentEventType without crashing', () => {
    const all: AgentEventType[] = ['RUN_CREATED', 'CONTEXT_CAPTURED', 'SKILL_SELECTED', 'PLAN_CREATED', 'PLAN_UPDATED', 'MODEL_STARTED', 'MODEL_COMPLETED', 'TOOL_CALL_PROPOSED', 'TOOL_CALL_STARTED', 'TOOL_CALL_COMPLETED', 'TOOL_CALL_FAILED', 'APPROVAL_REQUESTED', 'APPROVAL_APPROVED', 'APPROVAL_REJECTED', 'APPROVAL_EXPIRED', 'APPROVAL_UPDATED', 'RESULT_VERIFIED', 'RUN_RETRY_SCHEDULED', 'RUN_CANCELED', 'RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_BUDGET_EXCEEDED', 'WAITING_FOR_USER_INPUT']
    const events = all.map((t, i) => evt(i + 1, t, { callId: 'cov', toolName: 'list_tasks', approvalId: 'a1' }))
    const rows = reduceAgentActivities(events)
    for (const r of rows) {
      expect(r.title.length).toBeGreaterThan(0)
      expect(r.title).not.toContain('unknown')
      expect(r.title).not.toMatch(/_/)
    }
  })
  it('state-only events produce no rows', () => {
    const rows = reduceAgentActivities([
      evt(1, 'RUN_CREATED'), evt(2, 'CONTEXT_CAPTURED'), evt(3, 'PLAN_CREATED'),
      evt(4, 'SKILL_SELECTED'), evt(5, 'RESULT_VERIFIED'), evt(6, 'RUN_SUCCEEDED'),
    ])
    expect(rows).toHaveLength(0)
  })
  it('model started shows one analyzing row, completed clears it', () => {
    expect(reduceAgentActivities([evt(1, 'MODEL_STARTED')])).toHaveLength(1)
    expect(reduceAgentActivities([evt(1, 'MODEL_STARTED')])[0].title).toBe('正在分析')
    expect(reduceAgentActivities([evt(1, 'MODEL_STARTED'), evt(2, 'MODEL_COMPLETED')])).toHaveLength(0)
  })
  it('approval requested becomes approval activity, not tool row', () => {
    const rows = reduceAgentActivities([evt(1, 'APPROVAL_REQUESTED', { approvalId: 'a1' })])
    expect(rows).toHaveLength(1)
    expect(rows[0].kind).toBe('approval')
    expect(rows[0].status).toBe('waiting')
  })
})

