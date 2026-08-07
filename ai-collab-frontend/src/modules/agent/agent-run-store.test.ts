import { describe, expect, it } from 'vitest'
import { applyAgentEvent, emptyAgentTimeline, reconcileAgentRun } from './agent-run-store'
import type { AgentRun, AgentRunEvent } from './types'

const run: AgentRun = {
  id: 'run-1', sessionId: 'session-1', projectId: 'project-1', goal: '检查',
  status: 'RUNNING', stepsUsed: 0, maxSteps: 16, toolCallsUsed: 0,
  maxToolCalls: 12, inputTokensUsed: 0, maxInputTokens: 1000,
  outputTokensUsed: 0, maxOutputTokens: 1000, errorCode: null,
}

const event = (sequence: number, type: AgentRunEvent['type']): AgentRunEvent => ({
  id: `event-${sequence}`, projectId: 'project-1', runId: 'run-1', sequence, type,
  payload: {}, createdAt: '2026-08-05T00:00:00Z',
})

describe('agent timeline state', () => {
  it('moves a queued run to running when model execution starts', () => {
    const state = emptyAgentTimeline({ ...run, status: 'QUEUED' })

    applyAgentEvent(state, event(1, 'MODEL_STARTED'))

    expect(state.run?.status).toBe('RUNNING')
  })

  it('applies each sequence once and ignores another run', () => {
    const state = emptyAgentTimeline(run)
    applyAgentEvent(state, event(1, 'MODEL_STARTED'))
    applyAgentEvent(state, event(1, 'MODEL_STARTED'))
    applyAgentEvent(state, { ...event(2, 'RUN_SUCCEEDED'), runId: 'run-2' })

    expect(state.events).toHaveLength(1)
    expect(state.lastSequence).toBe(1)
    expect(state.run?.status).toBe('RUNNING')
  })

  it('maps approval and terminal events to run state', () => {
    const state = emptyAgentTimeline(run)
    applyAgentEvent(state, event(1, 'APPROVAL_REQUESTED'))
    expect(state.run?.status).toBe('WAITING_FOR_APPROVAL')
    applyAgentEvent(state, event(2, 'RUN_CANCELED'))
    expect(state.run?.status).toBe('CANCELED')
  })

  it('APPROVAL_UPDATED does not set run to waiting state', () => {
    const state = emptyAgentTimeline(run)
    applyAgentEvent(state, event(1, 'MODEL_STARTED'))
    expect(state.run?.status).toBe('RUNNING')

    // APPROVAL_UPDATED 不应该改变 Run 状态
    applyAgentEvent(state, event(2, 'APPROVAL_UPDATED'))
    expect(state.run?.status).toBe('RUNNING')

    // RUN_SUCCEEDED 应该正常结束
    applyAgentEvent(state, event(3, 'RUN_SUCCEEDED'))
    expect(state.run?.status).toBe('SUCCEEDED')
  })

  it('APPROVAL_REQUESTED followed by RUN_SUCCEEDED ends run', () => {
    const state = emptyAgentTimeline(run)
    applyAgentEvent(state, event(1, 'APPROVAL_REQUESTED'))
    expect(state.run?.status).toBe('WAITING_FOR_APPROVAL')

    // 新流程：RUN_SUCCEEDED 后 Run 应该结束，不再等待审批
    applyAgentEvent(state, event(2, 'RUN_SUCCEEDED'))
    expect(state.run?.status).toBe('SUCCEEDED')
  })

  it('reconciles a missed terminal event from the persisted run', () => {
    const state = emptyAgentTimeline({ ...run, status: 'QUEUED' })

    reconcileAgentRun(state, {
      ...run,
      status: 'BUDGET_EXCEEDED',
      stepsUsed: 12,
      errorCode: 'AGENT_BUDGET_EXCEEDED',
    })

    expect(state.run?.status).toBe('BUDGET_EXCEEDED')
    expect(state.run?.stepsUsed).toBe(12)
    expect(state.run?.errorCode).toBe('AGENT_BUDGET_EXCEEDED')
  })
})
