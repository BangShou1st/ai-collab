// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'

// 权威状态对账回归（2026-10-31 生产 QUEUED 停滞事件，F 类放大因素）：
// Agent 后端领取即把 Run 置为 RUNNING，但首个模型轮次迟迟不产出事件时
// （辅助摘要请求或慢模型），SSE 打开期间前端只靠事件驱动，状态会一直停留在
// 「等待 Agent 处理（排队中）/模型未读取」。消费流期间必须周期对账权威运行详情，
// 把 QUEUED→RUNNING 单向推进并刷新模型解析快照。

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
  id: 'run-1', sessionId: 'session-1', projectId: 'project-1', goal: '介绍你可以执行的只读操作',
  stepsUsed: 0, maxSteps: 12, toolCallsUsed: 0, maxToolCalls: 8,
  inputTokensUsed: 0, maxInputTokens: 50000, outputTokensUsed: 0, maxOutputTokens: 20000,
  errorCode: null,
}

const detail = (status: string, modelConfiguration?: { provider: string; model: string; mode: string; maxOutputTokens: number; budgetEnforced: boolean }) => ({
  run: { ...runBase, status },
  plan: null, lastEventSequence: 0, pendingApprovalId: null, pauseRequestedAt: null,
  ...(modelConfiguration ? { modelConfiguration } : {}),
})

const stubs = {
  // 渲染 actions 插槽：运行状态标题在页头 actions 内，本组测试直接断言它
  PageHeader: { template: '<header><slot name="actions" /></header>' },
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

let releaseStream: (() => void) | null = null

beforeEach(() => {
  vi.clearAllMocks()
  releaseStream = null
  mocks.sessions.mockResolvedValue(response([{
    id: 'session-1', projectId: 'project-1', creatorId: 'user-1', title: '项目检查',
    status: 'ACTIVE', version: 0, createdAt: '2026-07-30T00:00:00Z', updatedAt: '2026-07-30T00:00:00Z',
  }]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
  mocks.runApprovals.mockResolvedValue(response([]))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.latestRun.mockResolvedValue(response(detail('QUEUED')))
  mocks.run.mockResolvedValue(response(detail('QUEUED')))
  // 流保持打开：模拟首个模型轮次长时间不产出事件的窗口
  mocks.stream.mockImplementation(async () => {
    await new Promise<void>(resolve => { releaseStream = resolve })
  })
  vi.useFakeTimers()
})

afterEach(() => {
  releaseStream?.()
  vi.useRealTimers()
})

const mountView = async () => {
  const wrapper = mount(AgentView, {
    attachTo: document.body,
    global: { directives: { loading: () => undefined }, stubs },
  })
  await flushPromises()
  return wrapper
}

describe('SSE 打开期间的权威状态对账', () => {
  it('QUEUED 运行在无事件窗口内通过轮询推进为 RUNNING，模型名称随详情刷新显示', async () => {
    const wrapper = await mountView()
    try {
      expect(wrapper.text()).toContain('等待 Agent 处理')
      expect(wrapper.text()).not.toContain('Agent 正在处理')
      expect(wrapper.text()).not.toContain('魔搭')

      mocks.run.mockResolvedValue(response(detail('RUNNING', {
        provider: 'OPENAI_COMPATIBLE', model: 'deepseek-ai/DeepSeek-V4.1-Flash', mode: 'NATIVE_TOOLS',
        maxOutputTokens: 1200, budgetEnforced: true,
      })))
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()

      expect(wrapper.text()).toContain('Agent 正在处理')
      expect(wrapper.text()).not.toContain('等待 Agent 处理')
      expect(wrapper.text()).toContain('deepseek-ai/DeepSeek-V4.1-Flash')
    } finally { wrapper.unmount() }
  })

  it('事件已推进或权威状态未变化时不回退，轮询失败静默重试', async () => {
    const wrapper = await mountView()
    try {
      // 权威对账把 QUEUED 推进为 RUNNING
      mocks.run.mockResolvedValue(response(detail('RUNNING')))
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()
      expect(wrapper.text()).toContain('Agent 正在处理')

      // 迟到的旧快照（QUEUED）不得把状态拉回排队
      mocks.run.mockResolvedValue(response(detail('QUEUED')))
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()
      expect(wrapper.text()).toContain('Agent 正在处理')

      // 对账失败不产生用户可见错误，下一轮继续
      mocks.run.mockRejectedValue(new Error('network down'))
      await vi.advanceTimersByTimeAsync(5000)
      await flushPromises()
      expect(wrapper.text()).toContain('Agent 正在处理')
    } finally { wrapper.unmount() }
  })

  it('组件卸载（流被中止）后不再发起权威对账请求', async () => {
    const wrapper = await mountView()
    await vi.advanceTimersByTimeAsync(5000)
    await flushPromises()
    expect(mocks.run.mock.calls.length).toBeGreaterThan(0)

    wrapper.unmount()
    const callsAfterUnmount = mocks.run.mock.calls.length
    await vi.advanceTimersByTimeAsync(20000)
    await flushPromises()
    expect(mocks.run.mock.calls.length).toBe(callsAfterUnmount)
  })
})
