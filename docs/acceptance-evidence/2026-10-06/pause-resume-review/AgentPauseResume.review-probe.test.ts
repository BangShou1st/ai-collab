// @vitest-environment jsdom
// Archived review evidence. Copy beside AgentView.vue to run; intentionally fails until repaired.
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, unknown> },
  replace: vi.fn(),
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runApprovals: vi.fn(),
  runEvents: vi.fn(),
  run: vi.fn(),
  submit: vi.fn(),
  pause: vi.fn(),
  resume: vi.fn(),
  cancel: vi.fn(),
  messages: vi.fn(),
  skills: vi.fn(),
  stream: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
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
    run: mocks.run,
    submit: mocks.submit,
    pause: mocks.pause,
    resume: mocks.resume,
    cancel: mocks.cancel,
    messages: mocks.messages,
    skills: mocks.skills,
  },
}))

vi.mock('./agent-event-stream', () => ({
  streamAgentEvents: mocks.stream,
}))

vi.mock('../../stores/auth-store', () => ({
  useAuthStore: () => ({ currentUser: { id: 'user-1' } }),
}))

vi.mock('../project/project-api', () => ({
  projectApi: { listMembers: vi.fn().mockResolvedValue({ data: [] }) },
}))

const response = <T,>(data: T) => ({ data })

const runBase = {
  id: 'run-1', sessionId: 'session-1', projectId: 'project-1', goal: '检查本周进度',
  stepsUsed: 2, maxSteps: 12, toolCallsUsed: 1, maxToolCalls: 8,
  inputTokensUsed: 100, maxInputTokens: 50000, outputTokensUsed: 20, maxOutputTokens: 20000,
  errorCode: null,
}
const detail = (status: string, pauseRequestedAt: string | null = null) => ({
  run: { ...runBase, status, pauseRequestedAt },
  plan: null, lastEventSequence: 3, pendingApprovalId: null, pauseRequestedAt,
})

const stubs = {
  PageHeader: { template: '<header />' },
  ElButton: { inheritAttrs: false, template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
  ElTag: { template: '<span><slot /></span>' },
  ElEmpty: { template: '<div><slot /></div>' },
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `<textarea :value="modelValue ?? ''" @input="$emit('update:modelValue', $event.target.value)" />`,
  },
  ElAlert: { props: ['title'], template: '<div class="alert">{{ title }}<slot /></div>' },
  ElSegmented: { template: '<div />' },
  ElCheckTag: { template: '<span><slot /></span>' },
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { inheritAttrs: false, template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.sessions.mockResolvedValue(response([{
    id: 'session-1', projectId: 'project-1', creatorId: 'user-1', title: '项目检查',
    status: 'ACTIVE', version: 0, createdAt: '2026-07-30T00:00:00Z', updatedAt: '2026-07-30T00:00:00Z',
  }]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
  mocks.runApprovals.mockResolvedValue(response([]))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.run.mockResolvedValue(response(detail('RUNNING')))
  mocks.stream.mockResolvedValue(undefined)
})

const mountView = async () => {
  const wrapper = mount(AgentView, {
    attachTo: document.body,
    global: { directives: { loading: () => undefined }, stubs },
  })
  await flushPromises()
  return wrapper
}

describe('暂停与输入续跑交互', () => {
  it('恢复暂停运行显示真实暂停状态与"输入继续"提示，且不重连执行流', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    mocks.run.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="agent-paused-banner"]').text()).toContain('已暂停，进度已保留')
    expect(wrapper.find('[data-test="agent-pause"]').exists()).toBe(false)
    // 暂停后 SSE 不活跃是正常状态：不发起无意义重连
    expect(mocks.stream).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('运行中提供暂停按钮；确认后展示已暂停并停止执行流重连', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('RUNNING')))
    mocks.pause.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="agent-pause"]').exists()).toBe(true)
    const streamsBeforePause = mocks.stream.mock.calls.length
    await wrapper.find('[data-test="agent-pause"]').trigger('click')
    await flushPromises()

    expect(mocks.pause).toHaveBeenCalledWith('project-1', 'run-1')
    expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(true)
    // 已确认暂停后不再新增执行流订阅
    expect(mocks.stream.mock.calls.length).toBe(streamsBeforePause)
    wrapper.unmount()
  })

  it('正在暂停（意图已受理、尚未确认）显示区分中的状态，不提供第二个暂停', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('RUNNING', '2026-10-06T08:00:00Z')))
    mocks.run.mockResolvedValue(response(detail('RUNNING', '2026-10-06T08:00:00Z')))
    const wrapper = await mountView()

    expect(wrapper.find('[data-test="agent-pausing-banner"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="agent-pausing-banner"]').text()).toContain('正在暂停，当前步骤完成后保留进度')
    expect(wrapper.find('[data-test="agent-pause"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('输入"继续"分流到同一运行：不重建时间线、重新订阅原 run', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()

    const input = wrapper.find('textarea')
    await input.setValue('继续')
    const sendButton = wrapper.findAll('button').find((b) => b.text().includes('发送'))
    await sendButton!.trigger('click')
    await flushPromises()

    // 显式绑定当前 runId；由提交入口分流，不直接调用独立 resume 端点
    expect(mocks.submit).toHaveBeenCalledTimes(1)
    expect(mocks.submit).toHaveBeenCalledWith('project-1', 'session-1', expect.objectContaining({
      content: '继续',
      pausedRunId: 'run-1',
    }))
    expect(mocks.resume).not.toHaveBeenCalled()
    // 同一运行恢复：清空已受理输入并重新订阅原 run 的事件流
    expect((input.element as HTMLTextAreaElement).value).toBe('')
    expect(mocks.stream).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('REVIEW: Ctrl+Enter 在暂停态应通过现有输入框恢复', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await input.trigger('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true })
      await flushPromises()
      expect(mocks.submit).toHaveBeenCalledTimes(1)
    } finally { wrapper.unmount() }
  })

  it('REVIEW: 会话切换后的迟到续跑响应不能清空新会话草稿或重订阅', async () => {
    const session = (id: string, title: string) => ({
      id, projectId: 'project-1', creatorId: 'user-1', title, status: 'ACTIVE', version: 0,
      createdAt: '2026-07-30T00:00:00Z', updatedAt: '2026-07-30T00:00:00Z',
    })
    mocks.sessions.mockResolvedValue(response([session('session-1', '会话一'), session('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    let resolveSubmit!: (value: unknown) => void
    mocks.submit.mockReturnValue(new Promise(resolve => { resolveSubmit = resolve }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      const second = { ...detail('SUCCEEDED'), run: { ...runBase, id: 'run-b', sessionId: 'session-2', status: 'SUCCEEDED' } }
      mocks.latestRun.mockResolvedValue(response(second))
      mocks.run.mockResolvedValue(response(second))
      await wrapper.findAll('button.session')[1].trigger('click')
      await flushPromises()
      await input.setValue('B会话的新问题')
      const streamCount = mocks.stream.mock.calls.length
      resolveSubmit(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
      await flushPromises()
      expect((input.element as HTMLTextAreaElement).value).toBe('B会话的新问题')
      expect(mocks.stream.mock.calls.length).toBe(streamCount)
    } finally { wrapper.unmount() }
  })

  it('REVIEW: 迟到暂停回包不能回退已收到完成事件的同一运行', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('RUNNING')))
    let consume!: (event: unknown) => void
    mocks.stream.mockImplementation((_url, _sequence, _signal, callback) => {
      consume = callback
      return new Promise(() => undefined)
    })
    let resolvePause!: (value: unknown) => void
    mocks.pause.mockReturnValue(new Promise(resolve => { resolvePause = resolve }))
    const wrapper = await mountView()
    try {
      await wrapper.find('[data-test="agent-pause"]').trigger('click')
      consume({ runId: 'run-1', sequence: 4, type: 'RUN_PAUSE_REQUESTED', createdAt: '2026-10-06T08:00:01Z', payload: {} })
      consume({ runId: 'run-1', sequence: 5, type: 'RUN_SUCCEEDED', createdAt: '2026-10-06T08:00:02Z', payload: {} })
      await flushPromises()
      expect(wrapper.text()).toContain('运行 · 已完成')
      resolvePause(response(detail('RUNNING', '2026-10-06T08:00:00Z')))
      await flushPromises()
      expect(wrapper.text()).toContain('运行 · 已完成')
      expect(wrapper.find('[data-test="agent-pausing-banner"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })
})


