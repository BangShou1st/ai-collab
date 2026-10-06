import { describe, expect, it } from 'vitest'
import { groupAgentActivities, reduceAgentActivities } from './agent-activity'
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
  it('emits turn narration only for tool-carrying turns with content', () => {
    const list = reduceAgentActivities([
      evt(1, 'MODEL_COMPLETED', { stepSequence: 1, toolCallCount: 2, content: '我先检查未完成任务，再核对里程碑。' }),
      evt(2, 'TOOL_CALL_STARTED', { callId: 'c1', toolName: 'list_tasks' }),
      evt(3, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 12 }),
    ])
    expect(list).toHaveLength(2)
    expect(list[0].kind).toBe('narration')
    expect(list[0].detail).toBe('我先检查未完成任务，再核对里程碑。')
    expect(list[1].title).toBe('读取项目任务')
  })
  it('does not narrate text-only turns: the final answer owns that content', () => {
    expect(reduceAgentActivities([evt(1, 'MODEL_COMPLETED', { toolCallCount: 0, content: '最终结论' })])).toHaveLength(0)
  })
  it('does not narrate tool turns without prose', () => {
    const list = reduceAgentActivities([
      evt(1, 'MODEL_COMPLETED', { stepSequence: 1, toolCallCount: 1, content: '' }),
      evt(2, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks' }),
    ])
    expect(list).toHaveLength(1)
    expect(list[0].kind).not.toBe('narration')
  })
  it('sorts narration and tool rows chronologically by sequence', () => {
    const list = reduceAgentActivities([
      evt(1, 'MODEL_COMPLETED', { stepSequence: 1, toolCallCount: 1, content: '先查任务' }),
      evt(2, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks' }),
      evt(3, 'MODEL_COMPLETED', { stepSequence: 3, toolCallCount: 1, content: '发现延期，再核对负责人' }),
      evt(4, 'TOOL_CALL_COMPLETED', { callId: 'c2', toolName: 'get_task' }),
    ])
    expect(list.map((r) => r.kind)).toEqual(['narration', 'read', 'narration', 'read'])
  })
})

describe('analyzing status across rounds', () => {
  const analyzing = (events: AgentRunEvent[]) => reduceAgentActivities(events).filter((r) => r.key === 'model:analyzing')

  it('keeps showing analyzing while a later round is in flight', () => {
    const events = [
      evt(1, 'MODEL_STARTED', { model: 'acceptance-a' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '先查任务' }),
      evt(3, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks' }),
      evt(4, 'MODEL_STARTED', { model: 'acceptance-b' }),
    ]
    const rows = analyzing(events)
    expect(rows).toHaveLength(1)
    expect(rows[0].title).toBe('正在分析')
    expect(rows[0].detail).toBe('使用 acceptance-b')
    expect(rows[0].status).toBe('running')
  })

  it('keeps showing analyzing for a third round after two completed rounds', () => {
    const events = [
      evt(1, 'MODEL_STARTED', { model: 'm1' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第一轮' }),
      evt(3, 'MODEL_STARTED', { model: 'm1' }),
      evt(4, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第二轮' }),
      evt(5, 'MODEL_STARTED', { model: 'm2' }),
    ]
    expect(analyzing(events)).toHaveLength(1)
    expect(analyzing(events)[0].detail).toBe('使用 m2')
  })

  it('clears analyzing once the in-flight round completes', () => {
    const events = [
      evt(1, 'MODEL_STARTED', { model: 'm1' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第一轮' }),
      evt(3, 'MODEL_STARTED', { model: 'm1' }),
      evt(4, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第二轮' }),
    ]
    expect(analyzing(events)).toHaveLength(0)
  })

  it('shows analyzing again after a retry schedules a new request', () => {
    const events = [
      evt(1, 'MODEL_STARTED', { model: 'm1' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第一轮' }),
      evt(3, 'RUN_RETRY_SCHEDULED', { retryCount: 1 }),
      evt(4, 'MODEL_STARTED', { model: 'm1' }),
    ]
    expect(analyzing(events)).toHaveLength(1)
  })

  it('never shows analyzing for terminal runs', () => {
    for (const terminal of ['RUN_SUCCEEDED', 'RUN_FAILED', 'RUN_CANCELED', 'RUN_BUDGET_EXCEEDED'] as const) {
      const events = [evt(1, 'MODEL_STARTED', { model: 'm1' }), evt(2, terminal)]
      expect(analyzing(events)).toHaveLength(0)
    }
  })

  it('is independent of replay order and ignores duplicate completions', () => {
    const events = [
      evt(4, 'MODEL_STARTED', { model: 'm2' }),
      evt(1, 'MODEL_STARTED', { model: 'm1' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第一轮' }),
      evt(2, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第一轮' }),
      evt(3, 'MODEL_COMPLETED', { toolCallCount: 1, content: '第二轮' }),
    ]
    const rows = analyzing(events)
    expect(rows).toHaveLength(1)
    expect(rows[0].detail).toBe('使用 m2')
  })
})

describe('groupAgentActivities', () => {
  const done = (seq: number, tool: string, title: string): AgentActivityRow => ({
    key: `tool:c${seq}`, tool, kind: 'read', status: 'done', title, detail: null, durationMs: null, count: null, raw: [evt(seq, 'TOOL_CALL_COMPLETED', { callId: `c${seq}`, toolName: tool })],
  })
  type AgentActivityRow = ReturnType<typeof reduceAgentActivities>[number]
  it('merges three or more consecutive completed reads into one group', () => {
    const rows = [done(1, 'list_tasks', '读取项目任务'), done(2, 'list_milestones', '检查里程碑'), done(3, 'get_task', '读取任务详情')]
    const grouped = groupAgentActivities(rows)
    expect(grouped).toHaveLength(1)
    expect(grouped[0].kind).toBe('group')
    if (grouped[0].kind === 'group') {
      expect(grouped[0].count).toBe(3)
      expect(grouped[0].items).toHaveLength(3)
    }
  })
  it('keeps narration, failures and running rows out of groups', () => {
    const rows: AgentActivityRow[] = [
      { key: 'narration:1', tool: 'narration', kind: 'narration', status: 'done', title: 'Agent', detail: '先查任务', durationMs: null, count: null, raw: [evt(1, 'MODEL_COMPLETED', {})] },
      done(2, 'list_tasks', '读取项目任务'),
      done(3, 'list_milestones', '检查里程碑'),
      done(4, 'get_task', '读取任务详情'),
      { key: 'tool:f1', tool: 'get_task', kind: 'failure', status: 'failed', title: '读取任务详情', detail: '执行失败', durationMs: null, count: null, raw: [evt(5, 'TOOL_CALL_FAILED', { callId: 'f1' })] },
    ]
    const grouped = groupAgentActivities(rows)
    expect(grouped).toHaveLength(3)
    expect(grouped[0].kind).toBe('narration')
    expect(grouped[1].kind).toBe('group')
    expect(grouped[2].status).toBe('failed')
  })
  it('leaves one or two reads as individual rows', () => {
    const rows = [done(1, 'list_tasks', '读取项目任务'), done(2, 'list_milestones', '检查里程碑')]
    expect(groupAgentActivities(rows)).toHaveLength(2)
  })
})
