// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import AgentView from './AgentView.vue'

const g = globalThis as unknown as Record<string, unknown>

const mocks = vi.hoisted(() => ({
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runApprovals: vi.fn(),
  runEvents: vi.fn(),
  messages: vi.fn(),
  run: vi.fn(),
  skills: vi.fn(),
  retry: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () =>
    (g.__testRoute as unknown) ?? { params: { projectId: 'project-1' }, query: {} },
  useRouter: () => ({ replace: (...args: unknown[]) => (g.__testReplace as ((...a: unknown[]) => unknown) | undefined)?.(...args) }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { prompt: vi.fn(), confirm: vi.fn() },
}))

vi.mock('./agent-api', () => ({
  agentApi: {
    sessions: mocks.sessions,
    sessionSummaries: mocks.sessionSummaries,
    latestRun: mocks.latestRun,
    runApprovals: mocks.runApprovals,
    runEvents: mocks.runEvents,
    messages: mocks.messages,
    run: mocks.run,
    skills: mocks.skills,
    createSession: vi.fn(),
    renameSession: vi.fn(),
    deleteSession: vi.fn(),
    submit: vi.fn(),
    retry: mocks.retry,
    continueRun: vi.fn(),
    cancel: vi.fn(),
    approvals: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    skillsList: vi.fn(),
  },
}))

vi.mock('../../stores/auth-store', () => ({
  useAuthStore: () => ({ currentUser: { id: 'user-1' } }),
}))

vi.mock('../project/project-api', () => ({
  projectApi: { listMembers: vi.fn().mockResolvedValue({ data: [] }) },
}))

vi.mock('../../api/authenticated-fetch', () => ({
  authenticatedFetch: (...args: unknown[]) => {
    const fn = g.__testFetch as ((...a: unknown[]) => Promise<unknown>) | undefined
    return fn ? fn(...args) : Promise.reject(new Error('fetch not mocked'))
  },
}))

const response = <T,>(data: T) => ({ data })

const session = (id: string, title: string, projectId = 'project-1') => ({
  id, projectId, creatorId: 'user-1', title, status: 'ACTIVE', version: 0,
  createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z',
})

const runOf = (id: string, sessionId: string, status: string) => ({
  id, sessionId, projectId: 'project-1', goal: 'goal', status,
  stepsUsed: 1, maxSteps: 12, toolCallsUsed: 1, maxToolCalls: 8,
  inputTokensUsed: 10, maxInputTokens: 50000, outputTokensUsed: 5, maxOutputTokens: 20000,
  errorCode: null,
})

const stubs = {
  PageHeader: { template: '<header />' },
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
  ElTag: { template: '<span><slot /></span>' },
  ElEmpty: { template: '<div><slot /></div>' },
  ElInput: { template: '<textarea />' },
  ElAlert: { template: '<div><slot /></div>' },
  ElSegmented: { template: '<div />' },
  ElCheckTag: { template: '<span><slot /></span>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: {
    inheritAttrs: false,
    template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
  },
}

const mountView = () =>
  mount(AgentView, { global: { directives: { loading: () => undefined }, stubs } })

const sseResponse = (frames: string[]) => {
  const enc = new TextEncoder()
  const body = new ReadableStream({
    start(c) {
      for (const f of frames) c.enqueue(enc.encode(`data: ${f}\n\n`))
      c.close()
    },
  })
  return Promise.resolve({ ok: true, body })
}

const fetchedUrls = () => {
  const urls: string[] = []
  g.__testFetch = (input: unknown) => {
    urls.push(String((input as Request).url ?? input))
    return sseResponse([])
  }
  return urls
}

beforeEach(() => {
  vi.clearAllMocks()
  delete g.__testFetch
  g.__testRoute = reactive({ params: { projectId: 'project-1' }, query: {} as Record<string, unknown> })
  mocks.sessions.mockResolvedValue(response([session('session-1', '会话一')]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.latestRun.mockResolvedValue(response(null))
  mocks.runApprovals.mockResolvedValue(response([]))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
})

describe('AgentView retry', () => {
  it('终态运行点击重试切换到新运行并恢复事件订阅', async () => {
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'session-1', 'FAILED'), plan: null, lastEventSequence: 5, pendingApprovalId: null,
    }))
    const urls = fetchedUrls()
    mocks.retry.mockResolvedValue(response(runOf('run-2', 'session-1', 'QUEUED')))

    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.find('[data-test="agent-retry"]').exists()).toBe(true)

    await wrapper.find('[data-test="agent-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.retry).toHaveBeenCalledWith('project-1', 'run-1')
    // 界面切换到新运行（QUEUED，标题来自运行状态标签），重试按钮随之消失
    expect(wrapper.text()).toContain('运行 · 排队中')
    expect(wrapper.find('[data-test="agent-retry"]').exists()).toBe(false)
    // 消息重新加载，事件订阅切到新运行
    expect(mocks.messages).toHaveBeenCalled()
    expect(urls.some((u) => u.includes('/runs/run-2/events'))).toBe(true)
    wrapper.unmount()
  })

  it('重复点击只发起一次重试请求', async () => {
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'session-1', 'BUDGET_EXCEEDED'), plan: null, lastEventSequence: 5, pendingApprovalId: null,
    }))
    fetchedUrls()
    let resolveRetry: (value: unknown) => void = () => undefined
    mocks.retry.mockReturnValue(new Promise((resolve) => { resolveRetry = resolve }))

    const wrapper = mountView()
    await flushPromises()

    await wrapper.find('[data-test="agent-retry"]').trigger('click')
    await wrapper.find('[data-test="agent-retry"]').trigger('click')
    resolveRetry(response(runOf('run-2', 'session-1', 'QUEUED')))
    await flushPromises()

    expect(mocks.retry).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('运行内自动重试路径返回原运行时原地恢复会话', async () => {
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'session-1', 'FAILED'), plan: null, lastEventSequence: 5, pendingApprovalId: null,
    }))
    fetchedUrls()
    mocks.retry.mockResolvedValue(response(runOf('run-1', 'session-1', 'QUEUED')))

    const wrapper = mountView()
    await flushPromises()
    mocks.runEvents.mockClear()

    await wrapper.find('[data-test="agent-retry"]').trigger('click')
    await flushPromises()

    expect(mocks.retry).toHaveBeenCalledWith('project-1', 'run-1')
    // 原地路径：重新恢复当前运行的会话状态与事件历史
    expect(mocks.latestRun).toHaveBeenCalled()
    expect(mocks.runEvents).toHaveBeenCalledWith('project-1', 'run-1', 0)
    wrapper.unmount()
  })
})
