// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import AgentView from './AgentView.vue'
import type { AgentRunEvent } from './types'

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
  prompt: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () =>
    (g.__testRoute as unknown) ?? { params: { projectId: 'project-1' }, query: {} },
  useRouter: () => ({ replace: (...args: unknown[]) => (g.__testReplace as ((...a: unknown[]) => unknown) | undefined)?.(...args) }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
  ElMessageBox: { prompt: mocks.prompt, confirm: mocks.confirm },
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
    retry: vi.fn(),
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

const evt = (sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}): AgentRunEvent => ({
  id: `e${sequence}`, projectId: 'project-1', runId: 'run-1', sequence, type, payload,
  createdAt: '2026-09-10T00:00:00Z',
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

describe('AgentView restore', () => {
  it('restores terminal history with plan and activities', async () => {
    const plan = { objective: '检查风险', steps: [{ id: 's1', title: '读取任务', status: 'DONE' }] }
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'session-1', 'SUCCEEDED'), plan, lastEventSequence: 3, pendingApprovalId: null,
    }))
    mocks.runEvents.mockResolvedValue(response([
      evt(1, 'PLAN_CREATED', { plan }),
      evt(2, 'TOOL_CALL_STARTED', { callId: 'c1', toolName: 'list_tasks' }),
      evt(3, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 18 }),
    ]))
    const wrapper = mountView()
    await flushPromises()
    await flushPromises()
    expect(mocks.runEvents).toHaveBeenCalledWith('project-1', 'run-1', 0)
    expect(wrapper.text()).toContain('读取项目任务')
    expect(wrapper.text()).toContain('读取任务')
    wrapper.unmount()
  })

  it('resumes running runs from lastEventSequence without duplicating events', async () => {
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'session-1', 'RUNNING'), plan: null, lastEventSequence: 5, pendingApprovalId: null,
    }))
    const history = [1, 2, 3, 4, 5].map((s) =>
      s % 2 ? evt(s, 'TOOL_CALL_STARTED', { callId: 'c1', toolName: 'list_tasks' }) : evt(s, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks' }),
    )
    mocks.runEvents.mockResolvedValue(response(history))
    const live = evt(6, 'TOOL_CALL_STARTED', { callId: 'c2', toolName: 'list_milestones' })
    const overlap = evt(5, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks' })
    g.__testFetch = () => sseResponse([JSON.stringify(overlap), JSON.stringify(live)])
    mocks.run.mockResolvedValue(response({ run: runOf('run-1', 'session-1', 'SUCCEEDED') }))
    const wrapper = mountView()
    await flushPromises()
    for (let i = 0; i < 6; i++) {
      await new Promise((r) => setTimeout(r, 30))
      await flushPromises()
    }
    const conversationText = wrapper.find('.conversation').text()
    expect(conversationText.match(/读取项目任务/g)?.length).toBe(1)
    expect(conversationText).toContain('检查里程碑')
    const inspectorText = wrapper.find('.agent-inspector').text()
    expect(inspectorText.match(/读取项目任务/g)?.length).toBe(1)
    wrapper.unmount()
  })

  it('drops the old session when switching projects', async () => {
    mocks.sessions.mockImplementation((pid: string) =>
      response(pid === 'project-1' ? [session('session-1', '会话一')] : [session('session-2', '会话二', 'project-2')]),
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('会话一')
    ;(g.__testRoute as { params: { projectId: string } }).params.projectId = 'project-2'
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('会话二')
    expect(wrapper.text()).not.toContain('会话一')
    for (const call of mocks.latestRun.mock.calls as unknown[][]) {
      expect(call).not.toEqual(['project-2', 'session-1'])
    }
    wrapper.unmount()
  })

  it('ignores a stale restore that resolves after a rapid session switch', async () => {
    mocks.sessions.mockResolvedValue(response([session('session-a', '会话A'), session('session-b', '会话B')]))
    let resolveA: ((v: unknown) => void) | null = null
    const gateA = new Promise((resolve) => { resolveA = resolve })
    mocks.messages.mockImplementation((_pid: string, sid: string) =>
      sid === 'session-a' ? gateA.then(() => response([{ id: 'm-a', role: 'USER', content: 'A 的问题', sessionId: 'session-a', runId: null, citations: [], inferences: [], createdAt: '' }])) : Promise.resolve(response([{ id: 'm-b', role: 'USER', content: 'B 的问题', sessionId: 'session-b', runId: null, citations: [], inferences: [], createdAt: '' }])),
    )
    mocks.latestRun.mockImplementation((_pid: string, sid: string) =>
      sid === 'session-a' ? gateA.then(() => response(null)) : Promise.resolve(response(null)),
    )
    const wrapper = mountView()
    await flushPromises()
    const buttons = wrapper.findAll('button.session')
    await buttons[1].trigger('click')
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('B 的问题')
    resolveA!(response([{ id: 'm-a', role: 'USER', content: 'A 的问题', sessionId: 'session-a', runId: null, citations: [], inferences: [], createdAt: '' }]))
    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).toContain('B 的问题')
    expect(wrapper.text()).not.toContain('A 的问题')
    wrapper.unmount()
  })
})
