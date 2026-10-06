// @vitest-environment jsdom
// Archived closure review evidence. Copy beside AgentView.vue to run; the final case exposes F2.
import { flushPromises, mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'
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
  continueRun: vi.fn(),
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
    continueRun: mocks.continueRun,
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
  mocks.continueRun.mockResolvedValue(response({ ...runBase, status: 'QUEUED' }))
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
})

// ========== 五项限定补修（2026-10-06 审查复现转正式回归） ==========

const sessionOf = (id: string, title: string) => ({
  id, projectId: 'project-1', creatorId: 'user-1', title, status: 'ACTIVE', version: 0,
  createdAt: '2026-07-30T00:00:00Z', updatedAt: '2026-07-30T00:00:00Z',
})
const messageOf = (id: string, role: 'USER' | 'ASSISTANT', content: string, sessionId: string) => ({
  id, role, content, sessionId, runId: null, citations: [], inferences: [],
  createdAt: '2026-10-06T08:00:00Z',
})

describe('Ctrl+Enter 键盘路由（F3）', () => {
  it('普通输入态：调用 send 且恰一次', async () => {
    mocks.latestRun.mockResolvedValue(response(null))
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('检查本周风险')
      await input.trigger('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true })
      await flushPromises()
      expect(mocks.submit).toHaveBeenCalledTimes(1)
      expect(mocks.submit).toHaveBeenCalledWith('project-1', 'session-1', expect.objectContaining({
        content: '检查本周风险', pausedRunId: null,
      }))
      expect(mocks.continueRun).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('暂停态：经 send 恢复同一运行且恰一次', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await input.trigger('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true })
      await flushPromises()
      expect(mocks.submit).toHaveBeenCalledTimes(1)
      expect(mocks.submit).toHaveBeenCalledWith('project-1', 'session-1', expect.objectContaining({
        content: '继续', pausedRunId: 'run-1',
      }))
      expect(mocks.continueRun).not.toHaveBeenCalled()
      expect((input.element as HTMLTextAreaElement).value).toBe('')
    } finally { wrapper.unmount() }
  })

  it('等待澄清态：调用现有澄清入口且恰一次，不走普通提交', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('WAITING_FOR_USER_INPUT')))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('我的回复')
      await input.trigger('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true })
      await flushPromises()
      expect(mocks.continueRun).toHaveBeenCalledTimes(1)
      expect(mocks.continueRun).toHaveBeenCalledWith('project-1', 'run-1', '我的回复')
      expect(mocks.submit).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })
})

describe('输入续跑响应的页面作用域（F2）', () => {
  it('会话切换后的迟到续跑响应不能清空新会话草稿或重订阅', async () => {
    mocks.sessions.mockResolvedValue(response([sessionOf('session-1', '会话一'), sessionOf('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    let resolveSubmit!: (value: unknown) => void
    mocks.submit.mockReturnValue(new Promise(resolve => { resolveSubmit = resolve }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      mocks.latestRun.mockResolvedValue(response({
        ...detail('SUCCEEDED'),
        run: { ...runBase, id: 'run-b', sessionId: 'session-2', status: 'SUCCEEDED' },
      }))
      mocks.run.mockResolvedValue(response({
        ...detail('SUCCEEDED'),
        run: { ...runBase, id: 'run-b', sessionId: 'session-2', status: 'SUCCEEDED' },
      }))
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

  it('切回原会话后（恢复代次已变化）迟到续跑响应仍不生效', async () => {
    mocks.sessions.mockResolvedValue(response([sessionOf('session-1', '会话一'), sessionOf('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    let resolveSubmit!: (value: unknown) => void
    mocks.submit.mockReturnValue(new Promise(resolve => { resolveSubmit = resolve }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      await wrapper.findAll('button.session')[1].trigger('click')
      await flushPromises()
      await input.setValue('B里输入的草稿')
      await wrapper.findAll('button.session')[0].trigger('click')
      await flushPromises()
      expect((input.element as HTMLTextAreaElement).value).toBe('B里输入的草稿')
      resolveSubmit(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
      await flushPromises()
      // 响应已过期：草稿保留、不重启订阅、不把已暂停视图改成恢复中
      expect((input.element as HTMLTextAreaElement).value).toBe('B里输入的草稿')
      expect(mocks.stream).not.toHaveBeenCalled()
      expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(true)
    } finally { wrapper.unmount() }
  })

  it('切换会话后旧请求失败不显示错误、不误清新页面草稿', async () => {
    mocks.sessions.mockResolvedValue(response([sessionOf('session-1', '会话一'), sessionOf('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    let rejectSubmit!: (reason: unknown) => void
    mocks.submit.mockReturnValue(new Promise((_resolve, reject) => { rejectSubmit = reject }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      await wrapper.findAll('button.session')[1].trigger('click')
      await flushPromises()
      await input.setValue('B会话的新问题')
      rejectSubmit(new Error('network down'))
      await flushPromises()
      expect((input.element as HTMLTextAreaElement).value).toBe('B会话的新问题')
      expect(ElMessage.error).not.toHaveBeenCalled()
    } finally { wrapper.unmount() }
  })

  it('延迟的消息加载不覆盖已切换的新会话', async () => {
    mocks.sessions.mockResolvedValue(response([sessionOf('session-1', '会话一'), sessionOf('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    let resolveStaleMessages!: (value: unknown) => void
    let messagesCalls = 0
    mocks.messages.mockImplementation(() => {
      messagesCalls += 1
      if (messagesCalls === 2) return new Promise(resolve => { resolveStaleMessages = resolve })
      if (messagesCalls === 3) return Promise.resolve(response([messageOf('m-b', 'USER', 'B会话的消息', 'session-2')]))
      return Promise.resolve(response([]))
    })
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()
    try {
      const input = wrapper.find('textarea')
      await input.setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      // 续跑响应已应用（重新订阅原 run），其消息加载挂起期间切换到 B
      expect(mocks.stream).toHaveBeenCalledTimes(1)
      await wrapper.findAll('button.session')[1].trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('B会话的消息')
      resolveStaleMessages(response([messageOf('m-stale', 'USER', 'A的旧消息', 'session-1')]))
      await flushPromises()
      expect(wrapper.text()).toContain('B会话的消息')
      expect(wrapper.text()).not.toContain('A的旧消息')
    } finally { wrapper.unmount() }
  })
})

describe('同一运行旧控制回包的顺序核对（F4）', () => {
  it('迟到暂停回包不能回退已收到完成事件的同一运行', async () => {
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
      // 旧 RUNNING 快照（事件序号 3，落后于已应用的 RUN_SUCCEEDED=5）
      resolvePause(response(detail('RUNNING', '2026-10-06T08:00:00Z')))
      await flushPromises()
      expect(wrapper.text()).toContain('运行 · 已完成')
      expect(wrapper.find('[data-test="agent-pausing-banner"]').exists()).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('旧 RUNNING 快照不能覆盖已确认的 PAUSED，也不得替事件流做订阅决定', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('RUNNING')))
    const signals: AbortSignal[] = []
    let consume!: (event: unknown) => void
    mocks.stream.mockImplementation((_url, _sequence, signal, callback) => {
      signals.push(signal)
      consume = callback
      return new Promise(() => undefined)
    })
    let resolvePause!: (value: unknown) => void
    mocks.pause.mockReturnValue(new Promise(resolve => { resolvePause = resolve }))
    const wrapper = await mountView()
    try {
      await wrapper.find('[data-test="agent-pause"]').trigger('click')
      consume({ runId: 'run-1', sequence: 4, type: 'RUN_PAUSE_REQUESTED', createdAt: '2026-10-06T08:00:01Z', payload: {} })
      consume({ runId: 'run-1', sequence: 5, type: 'RUN_PAUSED', createdAt: '2026-10-06T08:00:02Z', payload: {} })
      await flushPromises()
      expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(true)
      resolvePause(response(detail('RUNNING', '2026-10-06T08:00:00Z')))
      await flushPromises()
      expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(true)
      expect(wrapper.find('[data-test="agent-pausing-banner"]').exists()).toBe(false)
      expect(signals[0]?.aborted).toBe(false)
    } finally { wrapper.unmount() }
  })

  it('旧 PAUSED 快照不能覆盖已恢复的运行，也不得停止事件流', async () => {
    mocks.latestRun.mockResolvedValue(response(detail('RUNNING')))
    const signals: AbortSignal[] = []
    let consume!: (event: unknown) => void
    mocks.stream.mockImplementation((_url, _sequence, signal, callback) => {
      signals.push(signal)
      consume = callback
      return new Promise(() => undefined)
    })
    let resolvePause!: (value: unknown) => void
    mocks.pause.mockReturnValue(new Promise(resolve => { resolvePause = resolve }))
    const wrapper = await mountView()
    try {
      await wrapper.find('[data-test="agent-pause"]').trigger('click')
      // 暂停/恢复竞争：RUN_RESUMED 已先于旧暂停回包到达
      consume({ runId: 'run-1', sequence: 4, type: 'RUN_RESUMED', createdAt: '2026-10-06T08:00:01Z', payload: {} })
      await flushPromises()
      expect(wrapper.text()).toContain('运行 · 排队中')
      resolvePause(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
      await flushPromises()
      expect(wrapper.text()).toContain('运行 · 排队中')
      expect(wrapper.find('[data-test="agent-paused-banner"]').exists()).toBe(false)
      expect(signals[0]?.aborted).toBe(false)
    } finally { wrapper.unmount() }
  })
})

describe('F2 收口探针：消息加载的恢复代次', () => {
  it('A 的旧消息加载在 A→B→A 后不能覆盖重新恢复的 A 消息', async () => {
    mocks.sessions.mockResolvedValue(response([sessionOf('session-1', '会话一'), sessionOf('session-2', '会话二')]))
    mocks.latestRun.mockResolvedValue(response(detail('PAUSED', '2026-10-06T08:00:00Z')))
    // Keep the resumed stream in flight so the message loads are controlled independently.
    mocks.stream.mockImplementation(() => new Promise(() => undefined))
    let resolveOldMessages!: (value: unknown) => void
    let aLoads = 0
    mocks.messages.mockImplementation((_pid: string, sid: string) => {
      if (sid === 'session-2') return Promise.resolve(response([messageOf('m-b', 'USER', 'B的消息', 'session-2')]))
      aLoads += 1
      if (aLoads === 2) return new Promise(resolve => { resolveOldMessages = resolve })
      if (aLoads >= 3) return Promise.resolve(response([messageOf('m-a-new', 'USER', 'A重新恢复后的最新消息', 'session-1')]))
      return Promise.resolve(response([]))
    })
    mocks.submit.mockResolvedValue(response({ ...runBase, status: 'QUEUED', pauseRequestedAt: null }))
    const wrapper = await mountView()
    try {
      await wrapper.find('textarea').setValue('继续')
      await wrapper.findAll('button').find(b => b.text().includes('发送'))!.trigger('click')
      expect(aLoads).toBe(2)
      mocks.latestRun.mockImplementation((_pid: string, sid: string) => Promise.resolve(response({
        ...detail('SUCCEEDED'), run: { ...runBase, id: sid === 'session-2' ? 'run-b' : 'run-1', sessionId: sid, status: 'SUCCEEDED' },
      })))
      await wrapper.findAll('button.session')[1].trigger('click')
      await flushPromises()
      await wrapper.findAll('button.session')[0].trigger('click')
      await flushPromises()
      expect(wrapper.text()).toContain('A重新恢复后的最新消息')
      resolveOldMessages(response([messageOf('m-a-old', 'USER', 'A过期请求的旧消息', 'session-1')]))
      await flushPromises()
      expect(wrapper.text()).toContain('A重新恢复后的最新消息')
      expect(wrapper.text()).not.toContain('A过期请求的旧消息')
    } finally { wrapper.unmount() }
  })
})
