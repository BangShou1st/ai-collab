// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'
import AgentContextChips from './AgentContextChips.vue'
import AgentApprovalCard from './AgentApprovalCard.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, unknown> },
  replace: vi.fn(),
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runApprovals: vi.fn(),
  runEvents: vi.fn(),
  approvals: vi.fn(),
  schedules: vi.fn(),
  mcpBindings: vi.fn(),
  messages: vi.fn(),
  skills: vi.fn(),
  renameSession: vi.fn(),
  deleteSession: vi.fn(),
  prompt: vi.fn(),
  confirm: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), error: vi.fn() },
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
    approvals: mocks.approvals,
    schedules: mocks.schedules,
    mcpBindings: mocks.mcpBindings,
    messages: mocks.messages,
    skills: mocks.skills,
    renameSession: mocks.renameSession,
    deleteSession: mocks.deleteSession,
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

beforeEach(() => {
  vi.clearAllMocks()
  mocks.sessions.mockResolvedValue(response([{
    id: 'session-1',
    projectId: 'project-1',
    creatorId: 'user-1',
    title: '项目检查',
    status: 'ACTIVE',
    version: 0,
    createdAt: '2026-07-30T00:00:00Z',
    updatedAt: '2026-07-30T00:00:00Z',
  }]))
  mocks.sessionSummaries.mockResolvedValue(response([]))
  mocks.latestRun.mockResolvedValue(response(null))
  mocks.runApprovals.mockResolvedValue(response([]))
  mocks.runEvents.mockResolvedValue(response([]))
  mocks.approvals.mockResolvedValue(response([]))
  mocks.schedules.mockResolvedValue(response([]))
  mocks.mcpBindings.mockResolvedValue(response([]))
  mocks.skills.mockResolvedValue(response([]))
  mocks.messages.mockResolvedValue(response([]))
  mocks.renameSession.mockResolvedValue(response({
    id: 'session-1',
    title: '浏览器验收',
  }))
  mocks.deleteSession.mockResolvedValue(undefined)
  mocks.prompt.mockResolvedValue({ value: '浏览器验收' })
  mocks.confirm.mockResolvedValue(true)
})

describe('AgentView session management', () => {
  it('renames and deletes a conversation without exposing internal controls', async () => {
    const wrapper = mount(AgentView, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          PageHeader: { template: '<header />' },
          ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
          ElTabs: { template: '<div><slot /></div>' },
          ElTabPane: { template: '<section><slot /></section>' },
          ElButton: {
            inheritAttrs: false,
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
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElDropdownItem: {
            inheritAttrs: false,
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
          },
        },
      },
    })
    await flushPromises()

    await wrapper.get('[data-test="rename-agent-session"]').trigger('click')
    await flushPromises()
    expect(mocks.renameSession).toHaveBeenCalledWith(
      'project-1',
      'session-1',
      '浏览器验收',
    )

    await wrapper.get('[data-test="delete-agent-session"]').trigger('click')
    await flushPromises()
    expect(mocks.deleteSession).toHaveBeenCalledWith('project-1', 'session-1')
  })
})

describe('AgentView context handoff', () => {
  it('clears all four context query keys with a single replace', async () => {
    mocks.route.query = {
      task: '11111111-1111-4111-8111-111111111111',
      document: '22222222-2222-4222-8222-222222222222',
      plan: '33333333-3333-4333-8333-333333333333',
      milestone: '44444444-4444-4444-8444-444444444444',
      other: 'keep',
    }
    const wrapper = mount(AgentView, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          PageHeader: { template: '<header />' },
          ElButton: {
            inheritAttrs: false,
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
          },
          ElTag: { template: '<span><slot /></span>' },
          ElEmpty: { template: '<div><slot /></div>' },
          ElInput: { template: '<textarea />' },
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElDropdownItem: {
            inheritAttrs: false,
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
          },
        },
      },
    })
    await flushPromises()
    const chips = wrapper.findComponent(AgentContextChips)
    expect(chips.exists()).toBe(true)
    await chips.vm.$emit('clear')
    await flushPromises()
    expect(mocks.replace).toHaveBeenCalledTimes(1)
    expect(mocks.replace).toHaveBeenCalledWith({ query: { other: 'keep' } })
    mocks.route.query = {}
  })
})

describe('AgentView approval race', () => {
  it('drops a late approvals response from a previous session', async () => {
    const stamp = '2026-07-30T00:00:00Z'
    const session = (id: string, title: string) => ({
      id, projectId: 'project-1', creatorId: 'user-1', title,
      status: 'ACTIVE', version: 0, createdAt: stamp, updatedAt: stamp,
    })
    const approval = (tag: string, runId: string, sessionId: string) => ({
      id: 'ap-' + tag, runId, sessionId,
      toolName: 'create_task_after_approval', arguments: {}, diff: { after: { title: tag + '-PROPOSAL' } },
      status: 'PENDING', nonce: null, expiresAt: stamp, createdAt: stamp,
      result: null, rejectionReason: null, proposalFamily: 'TASK_CREATE', subjectKey: 'sub-' + tag,
      revision: 1, updatedAt: stamp,
    })
    mocks.sessions.mockResolvedValue(response([session('session-A', '会话A'), session('session-B', '会话B')]))
    mocks.latestRun.mockImplementation((_pid: string, sid: string) =>
      Promise.resolve(response({
        run: { id: sid === 'session-A' ? 'run-A' : 'run-B', status: 'SUCCEEDED' },
        plan: null, steps: [], lastEventSequence: 0,
      })))
    let resolveA!: (value: unknown) => void
    const pendingA = new Promise((resolve) => { resolveA = resolve })
    mocks.runApprovals.mockImplementation((_pid: string, rid: string) =>
      rid === 'run-A' ? pendingA : Promise.resolve(response([approval('B', 'run-B', 'session-B')])))
    const wrapper = mount(AgentView, {
      global: {
        directives: { loading: () => undefined },
        stubs: {
          PageHeader: { template: '<header />' },
          ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
          ElButton: {
            inheritAttrs: false,
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
          },
          ElTag: { template: '<span><slot /></span>' },
          ElEmpty: { template: '<div><slot /></div>' },
          ElInput: { template: '<textarea />' },
          ElDropdown: { template: '<div><slot /><slot name="dropdown" /></div>' },
          ElDropdownMenu: { template: '<div><slot /></div>' },
          ElDropdownItem: {
            inheritAttrs: false,
            template: '<button v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
          },
        },
      },
    })
    await flushPromises()
    // Switch to B while A's approvals request is still pending.
    await wrapper.findAll('.session')[1].trigger('click')
    await flushPromises()
    let cards = wrapper.findAllComponents(AgentApprovalCard)
    expect(cards).toHaveLength(1)
    expect(cards[0].props('approval').id).toBe('ap-B')
    // A's response arrives late: it must not overwrite B.
    resolveA(response([approval('A', 'run-A', 'session-A')]))
    await flushPromises()
    cards = wrapper.findAllComponents(AgentApprovalCard)
    expect(cards).toHaveLength(1)
    expect(cards[0].props('approval').id).toBe('ap-B')
    expect(wrapper.text()).toContain('B-PROPOSAL')
    expect(wrapper.text()).not.toContain('A-PROPOSAL')
  })
})
