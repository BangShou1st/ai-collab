import { describe, expect, it } from 'vitest'
import { applyAgentEvent, emptyAgentTimeline } from './agent-run-store'
import { agentRunPresentation } from './agent-run-state'
import { reduceAgentActivities } from './agent-activity'
import { RUN_STATUS_LABEL } from './agent-labels'
import type { AgentRun, AgentRunEvent } from './types'

const run = (overrides: Partial<AgentRun> = {}): AgentRun => ({
  id: 'run-1', sessionId: 'session-1', projectId: 'project-1', goal: '检查进度',
  status: 'RUNNING', stepsUsed: 1, maxSteps: 12, toolCallsUsed: 0, maxToolCalls: 8,
  inputTokensUsed: 0, maxInputTokens: 50000, outputTokensUsed: 0, maxOutputTokens: 20000,
  errorCode: null, ...overrides,
})

const event = (sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent => ({
  id: `e${sequence}`, projectId: 'project-1', runId: 'run-1', sequence,
  type, payload, createdAt: '2026-10-06T08:00:00Z',
})

describe('暂停/继续事件归约', () => {
  it('RUN_PAUSE_REQUESTED 标记正在暂停但不改变 RUNNING 状态', () => {
    const state = emptyAgentTimeline(run())
    applyAgentEvent(state, event(1, 'RUN_PAUSE_REQUESTED'))
    expect(state.run?.status).toBe('RUNNING')
    expect(state.run?.pauseRequestedAt).toBe('2026-10-06T08:00:00Z')
  })

  it('RUN_PAUSED 进入 PAUSED 且非终态', () => {
    const state = emptyAgentTimeline(run())
    applyAgentEvent(state, event(2, 'RUN_PAUSED'))
    expect(state.run?.status).toBe('PAUSED')
    expect(agentRunPresentation(state.run!).terminal).toBe(false)
  })

  it('RUN_RESUMED 回到 QUEUED 并清除暂停意图', () => {
    const state = emptyAgentTimeline(run({ status: 'PAUSED', pauseRequestedAt: '2026-10-06T07:00:00Z' }))
    applyAgentEvent(state, event(3, 'RUN_RESUMED'))
    expect(state.run?.status).toBe('QUEUED')
    expect(state.run?.pauseRequestedAt).toBeNull()
  })

  it('PAUSED 展示为已暂停、进度保留，且不在活跃判断中伪装成运行中', () => {
    const presentation = agentRunPresentation(run({ status: 'PAUSED' }))
    expect(presentation.title).toContain('已暂停')
    expect(presentation.terminal).toBe(false)
    expect(RUN_STATUS_LABEL.PAUSED).toBe('已暂停')
  })
})

describe('暂停/继续活动行', () => {
  it('控制事件渲染为一行状态说明，不伪造用户任务或最终回答', () => {
    const state = emptyAgentTimeline(run())
    applyAgentEvent(state, event(1, 'RUN_PAUSED'))
    applyAgentEvent(state, event(2, 'RUN_RESUMED'))
    const rows = reduceAgentActivities(state.events)
    const titles = rows.map((r) => r.title)
    expect(titles).toContain('已暂停，进度已保留')
    expect(titles).toContain('已继续')
    expect(rows.every((r) => r.raw[0].type !== 'RUN_PAUSED' || r.kind === 'info')).toBe(true)
  })
})
