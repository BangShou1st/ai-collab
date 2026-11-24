// @vitest-environment jsdom
/* eslint-disable vue/one-component-per-file -- Test-only Element Plus stubs are colocated with their tests. */
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminSettingsView from './AdminSettingsView.vue'
import { systemConfigApi } from './system-config-api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ApiResult } from '../../api/types'
import type { SystemConfig, SystemConfigAudit } from './system-config-api'

vi.mock('./system-config-api', () => ({
  systemConfigApi: {
    getConfig: vi.fn(),
    updateConfig: vi.fn(),
    getAuditLogs: vi.fn(),
    restoreConfig: vi.fn(),
    getRuntimeStatus: vi.fn(),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    success: vi.fn(),
    error: vi.fn(),
  },
  ElMessageBox: {
    confirm: vi.fn(),
  },
}))

const ButtonStub = defineComponent({
  name: 'ElButton',
  props: { disabled: Boolean },
  emits: ['click'],
  setup(props, { attrs, emit, slots }) {
    return () => h('button', {
      ...attrs,
      disabled: props.disabled,
      onClick: () => {
        if (!props.disabled) emit('click')
      },
    }, slots.default?.())
  },
})

const SwitchStub = defineComponent({
  name: 'ElSwitch',
  props: { modelValue: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, attrs }) {
    return () => h('input', {
      ...attrs,
      type: 'checkbox',
      checked: props.modelValue,
      onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).checked),
    })
  },
})

const stubs = {
  'el-button': ButtonStub,
  'el-card': { template: '<section><slot name="header" /><slot /></section>' },
  'el-form': { template: '<form><slot /></form>' },
  'el-form-item': { template: '<label><slot /></label>' },
  'el-switch': SwitchStub,
  'el-input': { template: '<input />' },
  'el-input-number': { template: '<input type="number" />' },
  'el-collapse': { template: '<div><slot /></div>' },
  'el-collapse-item': {
    props: ['title'],
    template: '<section><h2>{{ title }}</h2><slot /></section>',
  },
  'el-table': {
    props: ['data'],
    template: '<table><slot /></table>',
  },
  'el-table-column': {
    props: ['prop', 'label'],
    template: '<td :data-prop="prop" :data-label="label"><slot :row="{ id: \'audit-old\', configVersion: 0, changeSummary: \'旧配置\' }" /></td>',
  },
}

function apiResult<T>(data: T): ApiResult<T> {
  return { httpStatus: 200, code: 'SUCCESS', message: '', data }
}

describe('AdminSettingsView', () => {
  const mockConfig: SystemConfig = {
    id: '00000000-0000-0000-0000-000000000001',
    agentEnabled: false,
    chatEnabled: false,
    workerConcurrency: 2,
    workerDelayMs: 1000,
    scheduleDelayMs: 30000,
    systemMaintenanceMode: false,
    systemMaintenanceMessage: '',
    publicRegistrationEnabled: true,
    defaultChatModel: '',
    defaultAgentModel: '',
    ragRetrievalThreshold: 0.55,
    ragMaxSources: 12,
    version: 1,
    updatedBy: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }

  const mockAudits: SystemConfigAudit[] = [{
    id: '00000000-0000-0000-0000-000000000002',
    configVersion: 1,
    action: 'INITIALIZE',
    changedBy: null,
    changedByName: '系统管理员',
    changeSummary: '初始化默认配置',
    configSnapshot: '{}',
    appliedStatus: 'APPLIED',
    errorMessage: null,
    createdAt: new Date().toISOString(),
  }]

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(systemConfigApi.getConfig).mockResolvedValue(apiResult({ ...mockConfig }))
    vi.mocked(systemConfigApi.getAuditLogs).mockResolvedValue(apiResult([...mockAudits]))
    vi.mocked(systemConfigApi.updateConfig).mockResolvedValue({
      ...apiResult({ ...mockConfig, version: 2 }),
    })
  })

  function mountSettings() {
    return mount(AdminSettingsView, {
      global: {
        directives: { loading: () => {} },
        stubs,
      },
    })
  }

  it('loads current settings and audit history on mount', async () => {
    const wrapper = mountSettings()
    await flushPromises()

    expect(systemConfigApi.getConfig).toHaveBeenCalledTimes(1)
    expect(systemConfigApi.getAuditLogs).toHaveBeenCalledWith(10)
    expect(wrapper.text()).toContain('系统设置')
    expect(wrapper.text()).toContain('公开注册')
    expect(wrapper.text()).toContain('协作 Agent 引擎')
  })

  it('saves settings through the current page and reloads the saved config', async () => {
    const savedConfig = { ...mockConfig, agentEnabled: true, version: 2 }
    vi.mocked(systemConfigApi.getConfig)
      .mockResolvedValueOnce(apiResult({ ...mockConfig }))
      .mockResolvedValue(apiResult(savedConfig))
    vi.mocked(systemConfigApi.updateConfig).mockResolvedValue(apiResult(savedConfig))

    const wrapper = mountSettings()
    await flushPromises()

    const agentSwitchCard = wrapper.findAll('.switch-card').find((card) => card.text().includes('协作 Agent 引擎'))
    expect(agentSwitchCard).toBeDefined()
    await agentSwitchCard!.get('input[type="checkbox"]').setValue(true)

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存生效'))
    expect(saveButton).toBeDefined()
    await saveButton!.trigger('click')
    await flushPromises()

    expect(systemConfigApi.updateConfig).toHaveBeenCalledWith(expect.objectContaining({
      agentEnabled: true,
      publicRegistrationEnabled: true,
      expectedVersion: 1,
    }))
    expect(systemConfigApi.getConfig).toHaveBeenCalledTimes(2)
    expect(ElMessage.success).toHaveBeenCalledWith('系统设置已更新并即时生效')
  })

  it('requires confirmation with runtime status before enabling maintenance mode', async () => {
    vi.mocked(systemConfigApi.getRuntimeStatus).mockResolvedValue(apiResult({
      agentEnabled: true,
      chatEnabled: true,
      agentRuntimeAllowed: true,
      configuredWorkerConcurrency: 2,
      activeWorkerThreads: 1,
      queuedAgentRuns: 2,
      runningAgentRuns: 1,
      recentFailedAgentRuns: 0,
      lastExecutionError: null,
      maintenanceMode: false,
      refreshedAt: new Date().toISOString(),
    }))
    vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')

    const wrapper = mountSettings()
    await flushPromises()

    const maintenanceCard = wrapper.findAll('.switch-card').find((card) => card.text().includes('系统维护模式'))
    await maintenanceCard!.get('input[type="checkbox"]').setValue(true)

    const saveButton = wrapper.findAll('button').find((button) => button.text().includes('保存生效'))
    await saveButton!.trigger('click')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledTimes(1)
    expect(systemConfigApi.updateConfig).not.toHaveBeenCalled()

    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as never)
    vi.mocked(systemConfigApi.updateConfig).mockResolvedValue(apiResult({
      ...mockConfig,
      systemMaintenanceMode: true,
      version: 2,
    }))
    await saveButton!.trigger('click')
    await flushPromises()
    expect(systemConfigApi.updateConfig).toHaveBeenCalledWith(expect.objectContaining({
      systemMaintenanceMode: true,
    }))
  })

  it('binds correct audit props and restores historical version', async () => {
    vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm' as never)
    vi.mocked(systemConfigApi.restoreConfig).mockResolvedValue(apiResult({ ...mockConfig, version: 2 }))

    const wrapper = mountSettings()
    await flushPromises()

    const cols = wrapper.findAll('td[data-prop]').map((el) => el.attributes('data-prop'))
    expect(cols).toContain('configVersion')
    expect(cols).toContain('changedByName')
    expect(cols).not.toContain('version')
    expect(cols).not.toContain('operatorUsername')

    const restoreBtn = wrapper.find('[data-test="restore-config-btn"]')
    await restoreBtn.trigger('click')
    await flushPromises()
    expect(systemConfigApi.restoreConfig).toHaveBeenCalledWith('audit-old')
  })
})
