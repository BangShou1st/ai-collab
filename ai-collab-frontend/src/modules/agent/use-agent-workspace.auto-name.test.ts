// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { useAgentWorkspace } from './use-agent-workspace'
import type { AgentRun, AgentRunStatus } from './types'

const g = globalThis as unknown as Record<string, unknown>

const mocks = vi.hoisted(() => ({
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runEvents: vi.fn(),
  messages: vi.fn(),
  skills: vi.fn(),
  submit: vi.fn(),
  renameSession: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => reactive({ params: { projectId: 'project-1' }, query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
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
    runEvents: mocks.runEvents,
    messages: mocks.messages,
    skills: mocks.skills,
    submit: mocks.submit,
    renameSession: mocks.renameSession,
    createSession: vi.fn(),
    deleteSession: vi.fn(),
    cancel: vi.fn(),
    approvals: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
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

const session = (id: string, title: string) => ({
  id, projectId: 'project-1', creatorId: 'user-1', title, status: 'ACTIVE', version: 0,
  createdAt: '2026-11-03T00:00:00Z', updatedAt: '2026-11-03T00:00:00Z',
})

const runOf = (id: string, status: AgentRunStatus): AgentRun => ({
  id, sessionId: 'session-1', projectId: 'project-1', goal: '检查风险', status,
  stepsUsed: 0, maxSteps: 12, toolCallsUsed: 0, maxToolCalls: 8,
  inputTokensUsed: 0, maxInputTokens: null, outputTokensUsed: 0, maxOutputTokens: null,
  errorCode: null,
})

const emptySse = () => {
  const body = new ReadableStream({ start(c) { c.close() } })
  return Promise.resolve({ ok: true, body })
}

const Harness = defineComponent({
  setup(_, { expose }) {
    const workspace = useAgentWorkspace()
    expose({ workspace })
    return () => null
  },
})

function workspaceOf(wrapper: ReturnType<typeof mount>): ReturnType<typeof useAgentWorkspace> {
  const exposed = (wrapper.vm.$ as unknown as { exposed: { workspace: ReturnType<typeof useAgentWorkspace> } }).exposed
  return exposed.workspace
}

beforeEach(() => {
  vi.clearAllMocks()
  delete g.__testFetch
  mocks.sessions.mockResolvedValue(response([session('session-1', '项目协作 2026/11/3')]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.latestRun.mockResolvedValue(response(null))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
  mocks.submit.mockResolvedValue(response(runOf('run-1', 'QUEUED')))
  mocks.renameSession.mockImplementation((_pid: string, sid: string, title: string) =>
    Promise.resolve(response(session(sid, title))))
  g.__testFetch = () => emptySse()
})

describe('Agent session auto naming', () => {
  it('renames a default-titled session once from the first question, without overwriting later', async () => {
    const wrapper = mount(Harness)
    await flushPromises()
    const workspace = workspaceOf(wrapper)

    workspace.question.value = '检查本周风险，并给出来源'
    await workspace.send()
    await flushPromises()

    expect(mocks.renameSession).toHaveBeenCalledTimes(1)
    expect(mocks.renameSession).toHaveBeenCalledWith('project-1', 'session-1', '检查本周风险，并给出来源')
    expect(workspace.sessions.value[0]?.title).toBe('检查本周风险，并给出来源')

    workspace.question.value = '第二个问题，不应再次触发命名'
    await workspace.send()
    await flushPromises()

    expect(mocks.renameSession, '自动命名只发生一次').toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('truncates long questions at 30 code points and keeps manual titles untouched', async () => {
    const longQuestion = Array.from({ length: 40 }, (_, i) => `字${i}`).join('')
    const manualSession = session('session-1', '我的手动命名')
    mocks.sessions.mockResolvedValue(response([manualSession]))

    const wrapper = mount(Harness)
    await flushPromises()
    const workspace = workspaceOf(wrapper)

    workspace.question.value = longQuestion
    await workspace.send()
    await flushPromises()
    expect(mocks.renameSession, '手动命名的会话不得被自动改名').not.toHaveBeenCalled()

    const defaulted = session('session-1', '项目协作 2026/11/3')
    mocks.sessions.mockResolvedValue(response([defaulted]))
    workspace.sessions.value = [defaulted]
    workspace.question.value = longQuestion
    await workspace.send()
    await flushPromises()

    expect(mocks.renameSession).toHaveBeenCalledTimes(1)
    const title = mocks.renameSession.mock.calls[0]?.[2] as string
    expect(Array.from(title)).toHaveLength(31)
    expect(title.endsWith('…')).toBe(true)
    wrapper.unmount()
  })

  it('keeps the conversation alive when the rename request fails', async () => {
    mocks.renameSession.mockRejectedValue(new Error('network down'))
    const wrapper = mount(Harness)
    await flushPromises()
    const workspace = workspaceOf(wrapper)

    workspace.question.value = '检查本周风险'
    await expect(workspace.send()).resolves.toBeUndefined()
    await flushPromises()

    expect(mocks.submit, '命名失败不影响消息发送').toHaveBeenCalledTimes(1)
    expect(workspace.sessions.value[0]?.title, '命名失败保留默认标题，等待下次发送重试').toBe('项目协作 2026/11/3')
    wrapper.unmount()
  })
})
