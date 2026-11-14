// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ApiResult } from '../../api/types'
import type {
  AgentMemory,
  AgentMemoryCandidate,
  AgentMemoryJob,
} from './agent-memory-api'

const apiMocks = vi.hoisted(() => ({
  listMemories: vi.fn(),
  listCandidates: vi.fn(),
  disableMemory: vi.fn(),
  createJob: vi.fn(),
  getJob: vi.fn(),
  approveCandidate: vi.fn(),
  rejectCandidate: vi.fn(),
}))
const elementMocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  prompt: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
const showApiErrorMock = vi.hoisted(() => vi.fn(
  (_error: unknown, action: string) => `${action}失败`,
))

vi.mock('./agent-memory-api', () => ({ agentMemoryApi: apiMocks }))
vi.mock('element-plus', () => ({
  ElMessage: {
    success: elementMocks.success,
    error: elementMocks.error,
  },
  ElMessageBox: {
    confirm: elementMocks.confirm,
    prompt: elementMocks.prompt,
  },
}))
vi.mock('../../api/api-result', () => ({ showApiError: showApiErrorMock }))

import AgentMemoryDrawer from './AgentMemoryDrawer.vue'

const now = '2026-11-14T03:00:00Z'
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
  createdAt: now,
  updatedAt: now,
  verificationState: 'VERIFIED',
  verifiedAt: now,
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
  createdAt: now,
}
const job = (status: AgentMemoryJob['status']): AgentMemoryJob => ({
  id: 'job-1',
  projectId: 'project-1',
  jobType: 'MEMORY_EXTRACTION',
  status,
  attemptCount: status === 'QUEUED' ? 0 : 1,
  maxAttempts: 3,
  leaseEpoch: 1,
  leaseExpiresAt: null,
  nextAttemptAt: null,
  deadlineAt: null,
  lastErrorCode: null,
  createdAt: now,
  updatedAt: now,
})

function ok<T>(data: T): ApiResult<T> {
  return { httpStatus: 200, code: 'SUCCESS', message: '操作成功', data }
}

const stubs = {
  ElDrawer: {
    props: ['modelValue', 'title'],
    template: '<aside v-if="modelValue" role="dialog"><slot /></aside>',
  },
  ElTabs: { template: '<div><slot /></div>' },
  ElTabPane: { template: '<section><slot /></section>' },
  ElButton: {
    inheritAttrs: false,
    emits: ['click'],
    props: ['loading', 'disabled'],
    template: '<button :disabled="disabled" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
  },
  ElTag: { template: '<span><slot /></span>' },
  ElAlert: { props: ['title'], template: '<div role="alert">{{ title }}</div>' },
  EmptyState: { template: '<div><slot /></div>' },
}

function mountDrawer(modelValue = true, canManage = true) {
  return mount(AgentMemoryDrawer, {
    props: { modelValue, projectId: 'project-1', canManage },
    global: { stubs },
  })
}

describe('AgentMemoryDrawer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiMocks.listMemories.mockResolvedValue(ok([memory]))
    apiMocks.listCandidates.mockResolvedValue(ok([candidate]))
    apiMocks.disableMemory.mockResolvedValue(ok({ ...memory, status: 'DISABLED' }))
    apiMocks.approveCandidate.mockResolvedValue(ok(memory))
    apiMocks.rejectCandidate.mockResolvedValue(ok({ ...candidate, reviewStatus: 'REJECTED' }))
    elementMocks.confirm.mockResolvedValue('confirm')
    elementMocks.prompt.mockResolvedValue({ value: ' 证据不足 ' })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('does not call the API while closed', async () => {
    const wrapper = mountDrawer(false)
    await flushPromises()

    expect(apiMocks.listMemories).not.toHaveBeenCalled()
    expect(apiMocks.listCandidates).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('loads published memories and pending candidates for an administrator', async () => {
    const wrapper = mountDrawer()
    await flushPromises()

    expect(apiMocks.listMemories).toHaveBeenCalledWith('project-1', true)
    expect(apiMocks.listCandidates).toHaveBeenCalledWith('project-1')
    expect(wrapper.text()).toContain('采用灰度发布')
    expect(wrapper.text()).toContain('候选记忆')
    expect(wrapper.text()).toContain('来源：run:run-1#event:12')
    wrapper.unmount()
  })

  it('does not request administrator-only candidates for a member', async () => {
    const wrapper = mountDrawer(true, false)
    await flushPromises()

    expect(apiMocks.listMemories).toHaveBeenCalledTimes(1)
    expect(apiMocks.listCandidates).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('仅项目管理员可查看和审批候选')
    expect(wrapper.find('[data-test="memory-disable"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('disables a memory with its optimistic version', async () => {
    const wrapper = mountDrawer()
    await flushPromises()

    await wrapper.get('[data-test="memory-disable"]').trigger('click')
    await flushPromises()

    expect(elementMocks.confirm).toHaveBeenCalled()
    expect(apiMocks.disableMemory).toHaveBeenCalledWith('project-1', 'memory-1', 2)
    expect(apiMocks.listMemories).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('approves and rejects candidates with explicit reasons', async () => {
    const wrapper = mountDrawer()
    await flushPromises()

    await wrapper.get('[data-test="candidate-approve"]').trigger('click')
    await flushPromises()
    expect(apiMocks.approveCandidate).toHaveBeenCalledWith('project-1', 'candidate-1')

    await wrapper.get('[data-test="candidate-reject"]').trigger('click')
    await flushPromises()
    expect(apiMocks.rejectCandidate).toHaveBeenCalledWith(
      'project-1',
      'candidate-1',
      '证据不足',
    )

    expect(apiMocks.listCandidates).toHaveBeenCalledTimes(3)
    wrapper.unmount()
  })

  it('polls a memory job to completion and refreshes review data', async () => {
    vi.useFakeTimers()
    apiMocks.createJob.mockResolvedValue(ok(job('QUEUED')))
    apiMocks.getJob
      .mockResolvedValueOnce(ok(job('RUNNING')))
      .mockResolvedValueOnce(ok(job('SUCCEEDED')))
    const wrapper = mountDrawer()
    await flushPromises()

    await wrapper.get('[data-test="memory-job-start"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="memory-job-status"]').text()).toBe('排队中')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.get('[data-test="memory-job-status"]').text()).toBe('提取中')

    await vi.advanceTimersByTimeAsync(1500)
    await flushPromises()
    expect(wrapper.get('[data-test="memory-job-status"]').text()).toBe('已完成')
    expect(apiMocks.getJob).toHaveBeenCalledTimes(2)
    expect(apiMocks.listCandidates).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('stops polling after unmount', async () => {
    vi.useFakeTimers()
    apiMocks.createJob.mockResolvedValue(ok(job('QUEUED')))
    const wrapper = mountDrawer()
    await flushPromises()

    await wrapper.get('[data-test="memory-job-start"]').trigger('click')
    await flushPromises()
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(5000)

    expect(apiMocks.getJob).not.toHaveBeenCalled()
  })

  it('converges loading failures into an inline error', async () => {
    apiMocks.listMemories.mockRejectedValue(new Error('offline'))
    const wrapper = mountDrawer()
    await flushPromises()

    expect(showApiErrorMock).toHaveBeenCalledWith(expect.any(Error), '项目记忆加载')
    expect(wrapper.get('[role="alert"]').text()).toContain('项目记忆加载失败')
    wrapper.unmount()
  })
})
