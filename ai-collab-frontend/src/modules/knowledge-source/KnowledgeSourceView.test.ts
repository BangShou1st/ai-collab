// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeSourceView from './KnowledgeSourceView.vue'
import { connectorApi } from '../../api/connector-api'
import { ElMessageBox } from 'element-plus'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { projectId: 'project-one' } }) }))
vi.mock('../../stores/auth-store', () => ({ useAuthStore: () => ({ currentUser: { systemAdmin: false } }) }))
vi.mock('../../api/connector-api', () => ({ connectorApi: {
  listSources: vi.fn(), availableConnections: vi.fn(), authorizations: vi.fn(), startAuthorization: vi.fn(),
  disconnectAuthorization: vi.fn(), createSource: vi.fn(),
} }))
vi.mock('element-plus', () => ({
  ElMessage: { info: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

const response = <T,>(data: T) => ({ data }) as never
const stubs = {
  PageHeader: { template: '<header><slot /></header>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElDialog: { props: ['modelValue'], template: '<div v-if="modelValue"><slot /><slot name="footer" /></div>' },
  ElDrawer: { template: '<div><slot /></div>' },
  ElAlert: { template: '<div><slot /></div>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<div><slot /></div>' },
  ElSelect: { props: ['modelValue'], emits: ['update:modelValue', 'change'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value); $emit(\'change\', $event.target.value)"><slot /></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElInput: true, ElCheckbox: true, ElTable: true, ElTableColumn: true, ElTag: true, ElEmpty: true,
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(connectorApi.listSources).mockResolvedValue(response([]))
  vi.mocked(connectorApi.availableConnections).mockResolvedValue(response([{ id: 'connection-one', name: '飞书连接' }]))
  vi.mocked(connectorApi.authorizations).mockResolvedValue(response([
    { id: 'grant-one', connectionId: 'connection-one', status: 'ACTIVE', accessExpiresAt: null },
  ]))
})

describe('飞书授权', () => {
  it('shows the owner a disconnect action without requiring permission to add a source', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    expect(wrapper.text()).toContain('我的飞书授权')
    expect(wrapper.findAll('button').some(button => button.text() === '断开并清理')).toBe(true)
    expect(connectorApi.availableConnections).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('allows a project admin to select a user grant without exposing app credentials', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '添加飞书文件夹')!.trigger('click')
    await flushPromises()
    expect(connectorApi.availableConnections).toHaveBeenCalledWith('project-one')
    expect(wrapper.text()).not.toContain('暂无可用连接')
    await wrapper.findAll('select')[0]!.setValue('connection-one')
    expect(wrapper.text()).toContain('授权状态：正常')
    expect(wrapper.text()).toContain('我的飞书用户授权')
    wrapper.unmount()
  })

  it('asks for confirmation before disconnecting and schedules cleanup only after confirmation', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === '添加飞书文件夹')!.trigger('click')
    await flushPromises()
    await wrapper.findAll('select')[0]!.setValue('connection-one')
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
    await wrapper.findAll('button').find(button => button.text() === '断开并清理')!.trigger('click')
    await flushPromises()
    expect(connectorApi.disconnectAuthorization).not.toHaveBeenCalled()
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as never)
    await wrapper.findAll('button').find(button => button.text() === '断开并清理')!.trigger('click')
    await flushPromises()
    expect(connectorApi.disconnectAuthorization).toHaveBeenCalledWith('project-one', 'grant-one')
    wrapper.unmount()
  })
})
