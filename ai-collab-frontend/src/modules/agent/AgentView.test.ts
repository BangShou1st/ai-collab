// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'
import AgentContextChips from './AgentContextChips.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' }, query: {} as Record<string, unknown> },
  replace: vi.fn(),
  sessions: vi.fn(),
  sessionSummaries: vi.fn(),
  latestRun: vi.fn(),
  runApprovals: vi.fn(),
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
