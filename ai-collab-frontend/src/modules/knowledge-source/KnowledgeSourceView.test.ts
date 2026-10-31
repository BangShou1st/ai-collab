// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgeSourceView from './KnowledgeSourceView.vue'
import { connectorApi } from '../../api/connector-api'
import { projectApi } from '../project/project-api'
import { ElMessage, ElMessageBox } from 'element-plus'

vi.mock('vue-router', () => ({ useRoute: () => ({ params: { projectId: 'project-one' } }) }))
vi.mock('../../stores/auth-store', () => ({ useAuthStore: () => ({ currentUser: { systemAdmin: false } }) }))
vi.mock('../../api/connector-api', () => ({ connectorApi: {
  listSources: vi.fn(), availableConnections: vi.fn(), authorizations: vi.fn(), startAuthorization: vi.fn(),
  disconnectAuthorization: vi.fn(), createSource: vi.fn(), syncSource: vi.fn(), eventStatus: vi.fn(),
  verifyConnection: vi.fn(), configureEvents: vi.fn(), listConnections: vi.fn(), createConnection: vi.fn(),
} }))
vi.mock('../project/project-api', () => ({ projectApi: {
  get: vi.fn(),
} }))
vi.mock('element-plus', () => ({
  ElMessage: { info: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn() },
}))

const response = <T,>(data: T) => ({ data }) as never
const stubs = {
  PageHeader: { template: '<header><slot /><slot name="actions" /></header>' },
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
  vi.mocked(projectApi.get).mockResolvedValue(
    response({ id: 'project-one', name: '演示项目', role: 'OWNER' }) as never)
  vi.mocked(connectorApi.listSources).mockResolvedValue(response([]))
  vi.mocked(connectorApi.availableConnections).mockResolvedValue(
    response([{ id: 'connection-one', name: '飞书连接', status: 'ACTIVE', visibility: 'SYSTEM' }]))
  vi.mocked(connectorApi.authorizations).mockResolvedValue(response([
    { id: 'grant-one', connectionId: 'connection-one', status: 'ACTIVE', accessExpiresAt: null },
  ]))
})

describe('飞书授权', () => {
  it('shows the owner a disconnect action and loads project connections on mount', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    expect(wrapper.text()).toContain('我的飞书授权')
    expect(wrapper.findAll('button').some(button => button.text() === '断开并清理')).toBe(true)
    expect(connectorApi.availableConnections).toHaveBeenCalledWith('project-one')
    wrapper.unmount()
  })

  it('does not query connection scope APIs for a plain project member', async () => {
    const projectModule = await import('../project/project-api')
    vi.mocked(projectModule.projectApi.get).mockResolvedValue(
      response({ id: 'project-one', name: '演示项目', role: 'MEMBER' }) as never)
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    expect(connectorApi.availableConnections).not.toHaveBeenCalled()
    expect(connectorApi.eventStatus).not.toHaveBeenCalled()
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

  it('parses a pasted folder URL and submits the extracted token', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      createForm: { connectionId: string; folderToken: string; name: string; authorizationConfirmed: boolean }
      parsedFolder: { ok: boolean; token?: string }
      submitCreate: () => Promise<void>
    }
    vm.createForm.connectionId = 'connection-one'
    vm.createForm.folderToken = 'https://example.feishu.cn/drive/folder/fldcnABC123'
    vm.createForm.name = '赛事资料'
    vm.createForm.authorizationConfirmed = true
    expect(vm.parsedFolder.ok).toBe(true)
    expect(vm.parsedFolder.token).toBe('fldcnABC123')
    vi.mocked(connectorApi.createSource).mockResolvedValue(response({ id: 'source-one' }))
    vi.mocked(connectorApi.syncSource).mockResolvedValue(response({ jobId: 'job-one', status: 'RUNNING' }))
    await vm.submitCreate()
    expect(connectorApi.createSource).toHaveBeenCalledWith('project-one', expect.objectContaining({
      folderToken: 'fldcnABC123',
    }))
    expect(connectorApi.syncSource).toHaveBeenCalledWith('project-one', 'source-one')
    expect(ElMessage.success).toHaveBeenCalledWith('知识源已添加，首次同步已开始')
    wrapper.unmount()
  })

  it('warns but keeps the created source when the first sync fails to start', async () => {
    const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      createForm: { connectionId: string; folderToken: string; name: string; authorizationConfirmed: boolean }
      submitCreate: () => Promise<void>
    }
    vm.createForm.connectionId = 'connection-one'
    vm.createForm.folderToken = 'fldcnRAW123'
    vm.createForm.name = '赛事资料'
    vm.createForm.authorizationConfirmed = true
    vi.mocked(connectorApi.createSource).mockResolvedValue(response({ id: 'source-one' }))
    vi.mocked(connectorApi.syncSource).mockRejectedValue(new Error('sync rejected'))
    await vm.submitCreate()
    expect(connectorApi.createSource).toHaveBeenCalledTimes(1)
    expect(ElMessage.warning).toHaveBeenCalledWith('知识源已添加，但首次同步启动失败，可在列表中点击“同步”重试')
    wrapper.unmount()
  })

  it('auto-detects a fresh authorization while waiting after opening the OAuth popup', async () => {
    vi.useFakeTimers()
    try {
      const windowOpen = vi.spyOn(window, 'open').mockReturnValue({
        opener: null, location: { assign: vi.fn() }, close: vi.fn(),
      } as unknown as Window)
      vi.mocked(connectorApi.startAuthorization).mockResolvedValue(
        response({ authorizationUrl: 'https://accounts.feishu.cn/open-apis/authen/v1/authorize?x=1', expiresAt: '2026-01-01T00:05:00Z' }))
      const wrapper = mount(KnowledgeSourceView, { global: { stubs, directives: { loading: () => {} } } })
      await flushPromises()
      const vm = wrapper.vm as unknown as { createForm: { connectionId: string }; startAuthorization: () => Promise<void> }
      vm.createForm.connectionId = 'connection-other'
      await vm.startAuthorization()
      expect(windowOpen).toHaveBeenCalled()
      vi.mocked(connectorApi.authorizations).mockResolvedValue(response([
        { id: 'grant-two', connectionId: 'connection-other', status: 'ACTIVE', accessExpiresAt: null },
      ]))
      await vi.advanceTimersByTimeAsync(3100)
      await flushPromises()
      expect(ElMessage.success).toHaveBeenCalledWith('飞书授权成功，可以继续添加知识源')
      expect((wrapper.vm as unknown as { createForm: { userAuthorizationId: string } }).createForm.userAuthorizationId)
        .toBe('grant-two')
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
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
