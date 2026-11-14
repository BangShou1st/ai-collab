import { beforeEach, describe, expect, it, vi } from 'vitest'
import type {
  AgentMemory,
  AgentMemoryCandidate,
  AgentMemoryJob,
} from './agent-memory-api'

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  patch: vi.fn(),
  delete: vi.fn(),
}))

vi.mock('../../api/http-client', () => ({ httpClient: mocks }))

import { agentMemoryApi } from './agent-memory-api'

const memory: AgentMemory = {
  id: 'memory-1',
  projectId: 'project-1',
  type: 'DECISION',
  title: '采用灰度发布',
  content: '新版本先在小范围项目验证。',
  sourceType: 'AGENT_RUN',
  sourceId: 'run-1',
  status: 'ACTIVE',
  createdBy: 'user-1',
  updatedBy: 'user-1',
  version: 2,
  createdAt: '2026-11-14T01:00:00Z',
  updatedAt: '2026-11-14T02:00:00Z',
  verificationState: 'VERIFIED',
  verifiedAt: '2026-11-14T02:00:00Z',
  verifiedBy: 'user-1',
  supersededBy: null,
}

const candidate: AgentMemoryCandidate = {
  id: 'candidate-1',
  jobId: 'job-1',
  attemptNo: 1,
  kind: 'DECISION',
  title: '候选记忆',
  content: '候选正文',
  confidence: 0.91,
  sourceRef: 'run:run-1#event:12',
  reviewStatus: 'PENDING_REVIEW',
  createdAt: '2026-11-14T03:00:00Z',
}

const job: AgentMemoryJob = {
  id: 'job-1',
  projectId: 'project-1',
  jobType: 'MEMORY_EXTRACTION',
  status: 'QUEUED',
  attemptCount: 0,
  maxAttempts: 3,
  leaseEpoch: 0,
  leaseExpiresAt: null,
  nextAttemptAt: null,
  deadlineAt: null,
  lastErrorCode: null,
  createdAt: '2026-11-14T03:00:00Z',
  updatedAt: '2026-11-14T03:00:00Z',
}

function response<T>(data: T) {
  return {
    status: 200,
    data: { code: 'SUCCESS', message: '操作成功', data },
  }
}

describe('agentMemoryApi', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('reads active memories and keeps activeOnly explicit', async () => {
    mocks.get.mockResolvedValue(response([memory]))

    const result = await agentMemoryApi.listMemories('project-1')

    expect(mocks.get).toHaveBeenCalledWith(
      '/projects/project-1/agent/memories',
      { params: { activeOnly: true } },
    )
    expect(result.data).toEqual([memory])
  })

  it('uses the existing create, update and optimistic-disable contract', async () => {
    mocks.post.mockResolvedValue(response(memory))
    mocks.patch.mockResolvedValue(response({ ...memory, version: 3 }))
    mocks.delete.mockResolvedValue(response({ ...memory, status: 'DISABLED' }))
    const input = {
      type: 'DECISION' as const,
      title: memory.title,
      content: memory.content,
      sourceType: memory.sourceType,
      sourceId: memory.sourceId,
      version: 1,
    }

    await agentMemoryApi.createMemory('project-1', input)
    await agentMemoryApi.updateMemory('project-1', 'memory-1', { ...input, version: 2 })
    await agentMemoryApi.disableMemory('project-1', 'memory-1', 2, '  已失效  ')

    expect(mocks.post).toHaveBeenCalledWith('/projects/project-1/agent/memories', input)
    expect(mocks.patch).toHaveBeenCalledWith(
      '/projects/project-1/agent/memories/memory-1',
      { ...input, version: 2 },
    )
    expect(mocks.delete).toHaveBeenCalledWith(
      '/projects/project-1/agent/memories/memory-1',
      { params: { version: 2, reason: '已失效' } },
    )
  })

  it('wires candidate review and memory job endpoints', async () => {
    mocks.get
      .mockResolvedValueOnce(response([candidate]))
      .mockResolvedValueOnce(response(job))
    mocks.post
      .mockResolvedValueOnce(response(memory))
      .mockResolvedValueOnce(response({ ...candidate, reviewStatus: 'REJECTED' }))
      .mockResolvedValueOnce(response(job))

    const candidates = await agentMemoryApi.listCandidates('project-1')
    await agentMemoryApi.approveCandidate('project-1', 'candidate-1', ' 来源可信 ')
    await agentMemoryApi.rejectCandidate('project-1', 'candidate-1', ' 证据不足 ')
    const created = await agentMemoryApi.createJob('project-1')
    const fetched = await agentMemoryApi.getJob('project-1', 'job-1')

    expect(candidates.data).toEqual([candidate])
    expect(mocks.get).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/agent/memory-candidates',
      { params: { reviewStatus: 'PENDING_REVIEW' } },
    )
    expect(mocks.post).toHaveBeenNthCalledWith(
      1,
      '/projects/project-1/agent/memory-candidates/candidate-1/approve',
      { reason: '来源可信' },
    )
    expect(mocks.post).toHaveBeenNthCalledWith(
      2,
      '/projects/project-1/agent/memory-candidates/candidate-1/reject',
      { reason: '证据不足' },
    )
    expect(mocks.post).toHaveBeenNthCalledWith(3, '/projects/project-1/agent/memory-jobs')
    expect(mocks.get).toHaveBeenNthCalledWith(2, '/projects/project-1/agent/memory-jobs/job-1')
    expect(created.data).toEqual(job)
    expect(fetched.data).toEqual(job)
  })
})
