import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('../../api/http-client', () => ({ httpClient: { get: mocks.get } }))

import { agentApi } from './agent-api'

const approval = {
  id: 'approval-1', runId: 'run-1', sessionId: 'session-1',
  toolName: 'create_task_after_approval', arguments: {}, diff: {},
  status: 'PENDING' as const, nonce: 'n', expiresAt: '2026-11-15T01:00:00Z',
  createdAt: '2026-11-14T01:00:00Z', result: null, rejectionReason: null,
  proposalFamily: 'TASK_CREATE' as const, subjectKey: 'subject-1',
  revision: 1, updatedAt: '2026-11-14T01:00:00Z',
}

describe('agentApi.approvalInbox', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sends incremental query and ETag headers', async () => {
    mocks.get.mockResolvedValue({
      status: 200,
      headers: { etag: '"abc"' },
      data: {
        code: 'SUCCESS', message: 'success',
        data: {
          items: [approval],
          serverTime: '2026-11-14T01:00:05Z',
          maxUpdatedAt: '2026-11-14T01:00:05Z',
        },
      },
    })

    const result = await agentApi.approvalInbox(
      'project-1',
      { status: 'PENDING', updatedSince: '2026-11-14T01:00:00Z' },
      '"prev"',
    )

    expect(mocks.get).toHaveBeenCalledWith(
      '/projects/project-1/agent/approvals',
      expect.objectContaining({
        params: { status: 'PENDING', updatedSince: '2026-11-14T01:00:00Z' },
        headers: { 'If-None-Match': '"prev"' },
      }),
    )
    expect(result).toMatchObject({
      notModified: false,
      etag: '"abc"',
      items: [approval],
      maxUpdatedAt: '2026-11-14T01:00:05Z',
    })
  })

  it('treats 304 as a successful no-change response', async () => {
    mocks.get.mockResolvedValue({ status: 304, headers: { etag: '"abc"' }, data: undefined })

    await expect(agentApi.approvalInbox('project-1', {}, '"abc"')).resolves.toEqual({
      notModified: true,
      etag: '"abc"',
      items: [],
      serverTime: '',
      maxUpdatedAt: null,
    })
  })
})
