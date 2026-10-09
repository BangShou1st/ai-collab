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
}))

vi.mock('vue-router', () => ({
  useRoute: () => (g.__testRoute as unknown) ?? { params: { projectId: 'project-1' }, query: {} },
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

const session = (id: string, title: string) => ({
  id, projectId: 'project-1', creatorId: 'user-1', title, status: 'ACTIVE', version: 0,
  createdAt: '2026-09-10T00:00:00Z', updatedAt: '2026-09-10T00:00:00Z',
})

const runOf = (id: string, status: string, sessionId = 'session-1') => ({
  id, sessionId, projectId: 'project-1', goal: '检查本周任务是否影响交付', status,
  stepsUsed: 2, maxSteps: 12, toolCallsUsed: 2, maxToolCalls: 8,
  inputTokensUsed: 10, maxInputTokens: 50000, outputTokensUsed: 5, maxOutputTokens: 20000,
  errorCode: null,
})

const evt = (sequence: number, type: AgentRunEvent['type'], payload: Record<string, unknown> = {}, runId = 'run-1'): AgentRunEvent => ({
  id: `e${sequence}`, projectId: 'project-1', runId, sequence, type, payload,
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
  ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
  ElDropdownMenu: { template: '<div><slot /></div>' },
  ElDropdownItem: { inheritAttrs: false, template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
}

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

let fetchCalls = 0
let fetchBatches: AgentRunEvent[][] = []

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
  fetchCalls = 0
  fetchBatches = []
})

/** 每次 SSE 连接返回下一批事件，模拟运行过程中持续到达的新事件。 */
const installGrowingStream = (batches: AgentRunEvent[]) => {
  fetchBatches = batches.map(b => [b])
  g.__testFetch = () => {
    const batch = fetchBatches[Math.min(fetchCalls, fetchBatches.length - 1)] ?? []
    fetchCalls += 1
    return sseResponse(batch.map(e => JSON.stringify(e)))
  }
}

async function settle(rounds = 4) {
  for (let i = 0; i < rounds; i++) {
    await new Promise(r => setTimeout(r, 30))
    await flushPromises()
  }
}

describe('AgentView activity block identity', () => {
  it('keeps expanded technical details, focus and scroll when new events arrive', async () => {
    // 运行中的历史：两条已完成工具 → 可展开的技术详情
    mocks.latestRun.mockResolvedValue(response({
      run: runOf('run-1', 'RUNNING'), plan: null, lastEventSequence: 4, pendingApprovalId: null,
    }))
    mocks.runEvents.mockResolvedValue(response([
      evt(1, 'MODEL_STARTED', { model: 'acceptance-a' }),
      evt(2, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 12 }),
      evt(3, 'TOOL_CALL_COMPLETED', { callId: 'c2', toolName: 'list_milestones', count: 4 }),
    ]))
    mocks.run.mockResolvedValue(response({ run: runOf('run-1', 'RUNNING') }))
    // 连接 1：老工具的活动更新；连接 2：全新的工具行
    installGrowingStream([
      evt(4, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 18 }),
      evt(5, 'TOOL_CALL_STARTED', { callId: 'c3', toolName: 'get_task' }),
    ])

    const wrapper = mount(AgentView, { attachTo: document.body, global: { directives: { loading: () => undefined }, stubs } })
    await settle(6)

    const block = wrapper.get('[aria-label="执行过程"]')
    const toolRow = block.find('details.activity.done')
    expect(toolRow.exists(), '应存在已完成的工具活动行').toBe(true)
    const summary = toolRow.find('summary')
    ;(toolRow.element as HTMLDetailsElement).open = true
    ;(summary.element as HTMLElement).focus()
    expect(document.activeElement).toBe(summary.element)

    // 向上浏览历史：记录滚动位置。jsdom 没有布局（scrollHeight/clientHeight 恒为 0），
    // 回弹与否无法在此判断；由浏览器验收覆盖真实滚动行为，这里断言节点未被重挂载。
    const messages = wrapper.get('.messages').element as HTMLElement
    await wrapper.get('.messages').trigger('scroll')
    messages.scrollTop = 120

    // 记录活动块内节点是否被替换：整块重挂载会移除并重建原节点
    const replaced: string[] = []
    const observer = new MutationObserver(records => {
      for (const record of records) {
        for (const node of Array.from(record.removedNodes)) {
          if (node === summary.element) replaced.push('summary')
        }
      }
    })
    observer.observe(block.element, { childList: true, subtree: true })

    // 追加新事件（含一条全新工具行）：活动块不得整块重新挂载
    await settle(10)
    observer.disconnect()

    const after = wrapper.find('[aria-label="执行过程"]')
    expect(after.exists()).toBe(true)
    expect(after.text(), '新事件应已渲染到活动块中').toContain('读取任务详情')
    expect(replaced, '追加事件不得移除原有活动行节点（即整块重挂载）').toEqual([])
    const stillOpen = after.findAll('details.activity')
      .some(row => (row.element as HTMLDetailsElement).open)
    expect(stillOpen, '追加事件后已展开的技术详情必须保持展开').toBe(true)
    expect(document.activeElement, '追加事件后键盘焦点必须留在原元素上').toBe(summary.element)
    expect(summary.element.isConnected, '原 DOM 节点必须仍在文档中').toBe(true)
    // scrollTop 未被强制归零：节点没有重建，滚动容器保持同一元素
    expect(messages.isConnected).toBe(true)

    wrapper.unmount()
  })

  it('does not carry expanded details across a run switch', async () => {
    mocks.sessions.mockResolvedValue(response([session('session-1', '会话一'), session('session-2', '会话二')]))
    mocks.latestRun.mockImplementation((_pid: string, sid: string) => Promise.resolve(response(
      sid === 'session-1'
        ? { run: runOf('run-1', 'RUNNING'), plan: null, lastEventSequence: 3, pendingApprovalId: null }
        : { run: runOf('run-2', 'RUNNING', 'session-2'), plan: null, lastEventSequence: 1, pendingApprovalId: null },
    )))
    mocks.runEvents.mockImplementation((_pid: string, rid: string) => Promise.resolve(response(
      rid === 'run-1'
        ? [
          evt(1, 'MODEL_STARTED', { model: 'acceptance-a' }),
          evt(2, 'TOOL_CALL_COMPLETED', { callId: 'c1', toolName: 'list_tasks', count: 12 }),
          evt(3, 'TOOL_CALL_COMPLETED', { callId: 'c2', toolName: 'list_milestones', count: 4 }),
        ]
        : [evt(1, 'TOOL_CALL_STARTED', { callId: 'c9', toolName: 'get_task' }, 'run-2')],
    )))
    mocks.run.mockResolvedValue(response({ run: runOf('run-1', 'RUNNING') }))
    installGrowingStream([evt(4, 'TOOL_CALL_STARTED', { callId: 'c3', toolName: 'get_task' }, 'run-2')])

    const wrapper = mount(AgentView, { attachTo: document.body, global: { directives: { loading: () => undefined }, stubs } })
    await settle(6)
    const first = wrapper.find('details.activity.done')
    expect(first.exists()).toBe(true)
    ;(first.element as HTMLDetailsElement).open = true
    await settle(2)
    expect(wrapper.findAll('details.activity').some(r => (r.element as HTMLDetailsElement).open)).toBe(true)

    // 切换到另一个会话（不同运行）：旧展开状态不能串到新运行的活动块
    const buttons = wrapper.findAll('button.session')
    expect(buttons.length).toBe(2)
    await buttons[1].trigger('click')
    await settle(8)

    expect(wrapper.get('[aria-label="会话历史"]').text()).toContain('会话二')
    const block = wrapper.find('[aria-label="执行过程"]')
    expect(block.exists(), '新运行的活动块应正常渲染').toBe(true)
    expect(block.findAll('details.activity').some(r => (r.element as HTMLDetailsElement).open),
      '切换运行后不得沿用旧运行的展开状态').toBe(false)
    wrapper.unmount()
  })
})
