// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AgentView from './AgentView.vue'

const mocks = vi.hoisted(() => ({
  route: { params: { projectId: 'project-1' } },
  sessions: vi.fn(),
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
    approvals: mocks.approvals,
    schedules: mocks.schedules,
    mcpBindings: mocks.mcpBindings,
    messages: mocks.messages,
    skills: mocks.skills,
    renameSession: mocks.renameSession,
    deleteSession: mocks.deleteSession,
  },
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
