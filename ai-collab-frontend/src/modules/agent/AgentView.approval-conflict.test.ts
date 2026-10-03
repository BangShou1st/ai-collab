// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, unknown> },
  replace: vi.fn(),
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runApprovals: vi.fn(),
  runEvents: vi.fn(),
  messages: vi.fn(),
  skills: vi.fn(),
  approve: vi.fn(),
  reject: vi.fn(),
  confirm: vi.fn(),
  prompt: vi.fn(),
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: mocks.messageSuccess, error: mocks.messageError },
  ElMessageBox: {
    prompt: mocks.prompt,
    confirm: mocks.confirm,
  },
}))

vi.mock('./agent-api', () => ({
  agentApi: {
    sessions: mocks.sessions,
    sessionSummaries: mocks.sessionSummaries,
    latestRun: mocks.latestRun,
    runApprovals: mocks.runApprovals,
    runEvents: mocks.runEvents,
    messages: mocks.messages,
    skills: mocks.skills,
    approve: mocks.approve,
    reject: mocks.reject,
  },
}))

vi.mock('../../stores/auth-store', () => ({
  useAuthStore: () => ({ currentUser: { id: 'user-1' } }),
}))

vi.mock('../project/project-api', () => ({
  projectApi: {
    listMembers: vi.fn().mockResolvedValue({ data: [] }),
  },
}))

const response = <T,>(data: T) => ({ data })

const STAMP = '2026-10-03T00:00:00Z'
const SESSION = {
  id: 'session-1', projectId: 'project-1', creatorId: 'user-1', title: '提案审批',
  status: 'ACTIVE', version: 0, createdAt: STAMP, updatedAt: STAMP,
}
const approval = (id: string, revision: number, status: string, title: string) => ({
  id, runId: 'run-1', sessionId: 'session-1',
  toolName: 'create_task_after_approval', arguments: {}, diff: { after: { title } },
  status, nonce: null, expiresAt: STAMP, createdAt: STAMP,
  result: null, rejectionReason: null, proposalFamily: 'TASK_CREATE', subjectKey: 'sub-1',
  revision, updatedAt: STAMP,
})
/** 形如 axios 409 冲突响应的错误，携带后端区分文案。 */
const conflictError = (message: string, code = 'AGENT_APPROVAL_CONFLICT', status = 409) => ({
  isAxiosError: true,
  response: { status, data: { code, message } },
})

const mountView = () => mount(AgentView, {
  global: {
    directives: { loading: () => undefined },
    stubs: {
      PageHeader: { template: '<header />' },
      ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
      ElTabs: { template: '<div><slot /></div>' },
      ElTabPane: { template: '<section><slot /></section>' },
      ElButton: {
        inheritAttrs: false,
        emits: ['click'],
        template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
      },
      ElInput: { template: '<textarea />' },
      ElEmpty: { template: '<div><slot /></div>' },
      ElCard: { template: '<section><slot name="header" /><slot /></section>' },
      ElTag: { template: '<span><slot /></span>' },
      ElTable: { template: '<div><slot /></div>' },
      ElTableColumn: { template: '<div />' },
      ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
      ElForm: { template: '<form><slot /></form>' },
      ElFormItem: { template: '<label><slot /></label>' },
      ElSelect: { template: '<div><slot /></div>' },
      ElOption: { template: '<span />' },
      ElInputNumber: { template: '<input />' },
      ElTimePicker: { template: '<input />' },
      ElDropdown: { template: '<div><slot /></div>' },
      ElDropdownMenu: { template: '<div><slot /></div>' },
      ElDropdownItem: {
        inheritAttrs: false,
        template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
      },
      ElSegmented: { template: '<div />' },
    },
  },
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.sessions.mockResolvedValue(response([SESSION]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.latestRun.mockResolvedValue(response({
    run: { id: 'run-1', status: 'SUCCEEDED' },
    plan: null, steps: [], lastEventSequence: 0,
  }))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
  mocks.confirm.mockResolvedValue(true)
  mocks.prompt.mockResolvedValue({ value: '不符合要求' })
})

describe('AgentView 审批冲突刷新', () => {
  it('批准失败(提案已修订)后刷新审批列表：展示最新版本待审批，已冲突记录移出待审批', async () => {
    mocks.runApprovals.mockResolvedValueOnce(response([approval('ap-old', 1, 'PENDING', 'V1-PROPOSAL')]))
    mocks.approve.mockRejectedValueOnce(conflictError('提案已修订，请刷新并确认当前版本'))
    // 服务端刷新结果：旧审批已冲突，最新版本仍是待审批。
    mocks.runApprovals.mockResolvedValueOnce(response([
      approval('ap-old', 1, 'CONFLICTED', 'V1-PROPOSAL'),
      approval('ap-new', 2, 'PENDING', 'V2-PROPOSAL'),
    ]))
    const wrapper = mountView()
    await flushPromises()
    let cards = wrapper.findAllComponents(AgentApprovalCard)
    expect(cards).toHaveLength(1)
    expect(cards[0].props('approval').id).toBe('ap-old')
    const callsBefore = mocks.runApprovals.mock.calls.length

    await cards[0].find('button').trigger('click')
    await flushPromises()

    expect(mocks.messageError).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('提案已修订，请刷新并确认当前版本'),
    }))
    expect(mocks.runApprovals.mock.calls.length).toBeGreaterThan(callsBefore)
    cards = wrapper.findAllComponents(AgentApprovalCard)
    const statuses = cards.map(c => c.props('approval'))
    expect(statuses.map(s => `${s.id}:${s.status}`)).toEqual(['ap-new:PENDING', 'ap-old:CONFLICTED'])
    expect(wrapper.text()).toContain('V2-PROPOSAL')
  })

  it('刷新失败不覆盖原始审批错误', async () => {
    mocks.runApprovals.mockResolvedValueOnce(response([approval('ap-old', 1, 'PENDING', 'V1-PROPOSAL')]))
    mocks.approve.mockRejectedValueOnce(conflictError('提案已修订，请刷新并确认当前版本'))
    mocks.runApprovals.mockRejectedValueOnce({ isAxiosError: true, response: { status: 500, data: { code: 'INTERNAL_ERROR' } } })
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAllComponents(AgentApprovalCard)[0].find('button').trigger('click')
    await flushPromises()

    const texts = mocks.messageError.mock.calls.map(call => call[0]?.message ?? call[0])
    expect(texts.some(t => String(t).includes('提案已修订，请刷新并确认当前版本'))).toBe(true)
    expect(texts.some(t => String(t).includes('Agent 审批状态刷新'))).toBe(true)
  })

  it('提交期间重复点击不再发起请求，结束后恢复可再次操作', async () => {
    let release!: () => void
    mocks.runApprovals.mockResolvedValue(response([approval('ap-1', 1, 'PENDING', 'DUP-PROPOSAL')]))
    mocks.approve.mockImplementation(() => new Promise((_, reject) => { release = () => reject(conflictError('提案已修订，请刷新并确认当前版本')) }))
    const wrapper = mountView()
    await flushPromises()
    const card = wrapper.findAllComponents(AgentApprovalCard)[0]
    const approveButton = card.find('button')

    await approveButton.trigger('click')
    await flushPromises()
    expect(mocks.approve).toHaveBeenCalledTimes(1)
    // 提交期间：两个按钮禁用；loading 只在实际提交的批准按钮上(未提交的按钮 loading 不为 true)。
    const buttons = wrapper.findAllComponents(AgentApprovalCard)[0].findAll('button')
    expect(buttons[0].attributes('disabled')).toBeDefined()
    expect(buttons[0].attributes('loading')).toBe('true')
    expect(buttons[1].attributes('disabled')).toBeDefined()
    expect(String(buttons[1].attributes('loading'))).not.toBe('true')

    await buttons[0].trigger('click')
    await flushPromises()
    expect(mocks.approve).toHaveBeenCalledTimes(1)
    expect(mocks.confirm).toHaveBeenCalledTimes(1)

    // 请求结束(即使失败)后释放忙碌状态：按钮恢复，可再次发起。
    release()
    await flushPromises()
    const recovered = wrapper.findAllComponents(AgentApprovalCard)[0].findAll('button')
    expect(recovered[0].attributes('disabled')).toBeUndefined()
    await recovered[0].trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(2)
    expect(mocks.approve).toHaveBeenCalledTimes(2)
  })

  it('确认弹窗尚未完成时重复触发不进入第二次流程', async () => {
    let releaseConfirm!: () => void
    mocks.runApprovals.mockResolvedValue(response([approval('ap-1', 1, 'PENDING', 'DIALOG-PROPOSAL')]))
    mocks.confirm.mockImplementation(() => new Promise((resolve) => { releaseConfirm = () => resolve(true) }))
    mocks.approve.mockResolvedValue(response(approval('ap-1', 1, 'APPROVED', 'DIALOG-PROPOSAL')))
    const wrapper = mountView()
    await flushPromises()
    const approveButton = wrapper.findAllComponents(AgentApprovalCard)[0].find('button')

    await approveButton.trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    // 弹窗还开着(用户未确认)：第二次点击在入口被忙碌状态拦截。
    await wrapper.findAllComponents(AgentApprovalCard)[0].find('button').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledTimes(1)
    expect(mocks.approve).not.toHaveBeenCalled()

    releaseConfirm()
    await flushPromises()
    expect(mocks.approve).toHaveBeenCalledTimes(1)
  })

  it('拒绝失败(提案已修订)后同样刷新审批列表并展示最新版本', async () => {
    mocks.runApprovals.mockResolvedValueOnce(response([approval('ap-old', 1, 'PENDING', 'V1-PROPOSAL')]))
    mocks.reject.mockRejectedValueOnce(conflictError('提案已修订，请刷新并确认当前版本'))
    mocks.runApprovals.mockResolvedValueOnce(response([
      approval('ap-old', 1, 'CONFLICTED', 'V1-PROPOSAL'),
      approval('ap-new', 2, 'PENDING', 'V2-PROPOSAL'),
    ]))
    mocks.prompt.mockResolvedValue({ value: '不再需要该任务' })
    const wrapper = mountView()
    await flushPromises()
    // 拒绝按钮是卡片上的第二个按钮。
    await wrapper.findAllComponents(AgentApprovalCard)[0].findAll('button')[1].trigger('click')
    await flushPromises()

    expect(mocks.reject).toHaveBeenCalledTimes(1)
    expect(mocks.messageError).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('提案已修订，请刷新并确认当前版本'),
    }))
    const statuses = wrapper.findAllComponents(AgentApprovalCard).map(c => c.props('approval'))
    expect(statuses.map(s => `${s.id}:${s.status}`)).toEqual(['ap-new:PENDING', 'ap-old:CONFLICTED'])
    // 拒绝提交期间 loading 只在拒绝按钮上。
    expect(mocks.runApprovals.mock.calls.length).toBeGreaterThan(1)
  })

  it('审批已过期(AGENT_APPROVAL_EXPIRED)提示过期文案并将过期记录移出待审批', async () => {
    mocks.runApprovals.mockResolvedValueOnce(response([approval('ap-old', 1, 'PENDING', 'EXP-PROPOSAL')]))
    mocks.approve.mockRejectedValueOnce(conflictError('审批已过期，请重新发起提案', 'AGENT_APPROVAL_EXPIRED', 410))
    mocks.runApprovals.mockResolvedValueOnce(response([approval('ap-old', 1, 'EXPIRED', 'EXP-PROPOSAL')]))
    const wrapper = mountView()
    await flushPromises()
    await wrapper.findAllComponents(AgentApprovalCard)[0].find('button').trigger('click')
    await flushPromises()

    expect(mocks.messageError).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining('审批已过期，请重新发起提案'),
    }))
    const statuses = wrapper.findAllComponents(AgentApprovalCard).map(c => c.props('approval'))
    expect(statuses.map(s => `${s.id}:${s.status}`)).toEqual(['ap-old:EXPIRED'])
    expect(wrapper.text()).toContain('已过期')
  })
})
