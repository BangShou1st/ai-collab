import { describe, expect, it } from 'vitest'
import { buildConversationBlocks } from './conversation-blocks'
import type { AgentActivity } from './agent-activity'
import type { AgentMessage } from './types'

const msg = (id: string, role: 'USER' | 'ASSISTANT', runId: string | null, content = id): AgentMessage => ({
  id, role, content, sessionId: 'session-1', runId, citations: [], inferences: [], createdAt: '2026-09-10T00:00:00Z',
})
const act = (key: string): AgentActivity => ({
  key, tool: 'list_tasks', kind: 'read', status: 'done', title: `activity ${key}`,
  detail: null, durationMs: null, count: null, raw: [],
})

describe('buildConversationBlocks', () => {
  it('inserts current run activities between user goal and assistant result', () => {
    const blocks = buildConversationBlocks(
      [msg('old-u', 'USER', 'run-old', 'old goal'), msg('old-a', 'ASSISTANT', 'run-old', 'old answer'), msg('u', 'USER', 'run-new', 'new goal'), msg('a', 'ASSISTANT', 'run-new', 'new answer')],
      'run-new',
      [act('c1')],
    )
    const kinds = blocks.map((b) => (b.kind === 'message' ? `msg:${b.message.id}` : 'activities'))
    expect(kinds).toEqual(['msg:old-u', 'msg:old-a', 'msg:u', 'activities', 'msg:a'])
  })

  it('keeps activities after the user goal when no assistant message exists yet', () => {
    const blocks = buildConversationBlocks([msg('u', 'USER', 'run-new', 'goal')], 'run-new', [act('c1')])
    expect(blocks.map((b) => b.kind)).toEqual(['message', 'activities'])
  })

  it('falls back to the last user message when run ids are missing', () => {
    const blocks = buildConversationBlocks(
      [msg('u1', 'USER', null), msg('a1', 'ASSISTANT', null), msg('u2', 'USER', null)],
      'run-new',
      [act('c1')],
    )
    const kinds = blocks.map((b) => (b.kind === 'message' ? `msg:${b.message.id}` : 'activities'))
    expect(kinds).toEqual(['msg:u1', 'msg:a1', 'msg:u2', 'activities'])
  })

  it('renders plain messages when there are no activities', () => {
    const blocks = buildConversationBlocks([msg('u', 'USER', 'run-new')], 'run-new', [])
    expect(blocks.map((b) => b.kind)).toEqual(['message'])
  })
})
