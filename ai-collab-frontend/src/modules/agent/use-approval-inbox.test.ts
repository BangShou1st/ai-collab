// @vitest-environment jsdom
import { effectScope, nextTick, ref } from 'vue'
import type { EffectScope } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { shouldPollApprovalInbox, useApprovalInbox } from './use-approval-inbox'
import type { AgentApproval, AgentRunStatus } from './types'

const mocks = vi.hoisted(() => ({ approvalInbox: vi.fn() }))

vi.mock('./agent-api', () => ({ agentApi: { approvalInbox: mocks.approvalInbox } }))

const STAMP = '2026-11-14T01:00:00Z'
const scopes: EffectScope[] = []
const approval = (overrides: Partial<AgentApproval> = {}): AgentApproval => ({
  id: 'approval-1', runId: 'run-1', sessionId: 'session-1',
  toolName: 'create_task_after_approval', arguments: {}, diff: {},
  status: 'PENDING', nonce: 'n', expiresAt: '2026-11-15T01:00:00Z',
  createdAt: STAMP, result: null, rejectionReason: null,
  proposalFamily: 'TASK_CREATE', subjectKey: 'subject-1', revision: 1, updatedAt: STAMP,
  ...overrides,
})

function visibility(value: DocumentVisibilityState): void {
  Object.defineProperty(document, 'visibilityState', { configurable: true, value })
}

function install(options: {
  status?: AgentRunStatus
  approvals?: AgentApproval[]
  merge?: (items: AgentApproval[]) => void
}) {
  const projectId = ref('project-1')
  const runId = ref<string | null>('run-1')
  const status = ref<AgentRunStatus | null>(options.status ?? 'SUCCEEDED')
  const approvals = ref(options.approvals ?? [approval()])
  const merge = options.merge ?? vi.fn((items: AgentApproval[]) => {
    const byId = new Map(approvals.value.map(item => [item.id, item]))
    for (const item of items) byId.set(item.id, item)
    approvals.value = [...byId.values()]
  })
  const scope = effectScope()
  scopes.push(scope)
  let inbox: ReturnType<typeof useApprovalInbox>
  scope.run(() => {
    inbox = useApprovalInbox({
      projectId: () => projectId.value,
      runId: () => runId.value,
      enabled: () => shouldPollApprovalInbox(status.value, approvals.value),
      approvals: () => approvals.value,
      merge,
    })
  })
  return { scope, inbox: inbox!, approvals, status, runId, projectId, merge }
}

describe('useApprovalInbox', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    visibility('visible')
    mocks.approvalInbox.mockResolvedValue({
      notModified: false,
      etag: '"etag-1"',
      items: [approval({ status: 'PENDING', revision: 2, updatedAt: '2026-11-14T01:00:05Z' })],
      serverTime: '2026-11-14T01:00:05Z',
      maxUpdatedAt: '2026-11-14T01:00:05Z',
    })
  })

  afterEach(() => {
    for (const scope of scopes.splice(0)) scope.stop()
    vi.useRealTimers()
  })

  it('polls every five seconds only for terminal runs with pending approvals', async () => {
    const inbox = install({ status: 'RUNNING' })
    await nextTick()
    expect(mocks.approvalInbox).not.toHaveBeenCalled()

    inbox.status.value = 'SUCCEEDED'
    await nextTick()
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(5_000)
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(2)
    expect(mocks.approvalInbox).toHaveBeenLastCalledWith(
      'project-1',
      { updatedSince: '2026-11-14T01:00:05Z' },
      '"etag-1"',
    )
    inbox.scope.stop()
  })

  it('stops requests while hidden and refreshes immediately on focus', async () => {
    const inbox = install({})
    await nextTick()
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(1)

    visibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(10_000)
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(1)

    visibility('visible')
    window.dispatchEvent(new Event('focus'))
    await nextTick()
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(2)
    inbox.scope.stop()
  })

  it('does not render an unchanged 304 response', async () => {
    const merge = vi.fn()
    mocks.approvalInbox.mockResolvedValue({
      notModified: true,
      etag: '"etag-1"',
      items: [],
      serverTime: '',
      maxUpdatedAt: null,
    })
    const inbox = install({ merge })
    await nextTick()
    expect(merge).not.toHaveBeenCalled()
    inbox.scope.stop()
  })

  it('stops all timers and listeners when its scope is disposed', async () => {
    const inbox = install({})
    await nextTick()
    inbox.scope.stop()
    await vi.advanceTimersByTimeAsync(10_000)
    window.dispatchEvent(new Event('focus'))
    await nextTick()
    expect(mocks.approvalInbox).toHaveBeenCalledTimes(1)
  })

  it('supports an immediate full refresh after an approval action', async () => {
    const inbox = install({})
    await nextTick()
    await inbox.inbox.refreshNow(true)
    expect(mocks.approvalInbox).toHaveBeenLastCalledWith(
      'project-1',
      { updatedSince: null },
      null,
    )
    inbox.scope.stop()
  })
})

describe('shouldPollApprovalInbox', () => {
  it('requires both a terminal run and a pending approval', () => {
    expect(shouldPollApprovalInbox('SUCCEEDED', [approval()])).toBe(true)
    expect(shouldPollApprovalInbox('RUNNING', [approval()])).toBe(false)
    expect(shouldPollApprovalInbox('SUCCEEDED', [approval({ status: 'APPROVED' })])).toBe(false)
  })
})
