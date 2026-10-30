// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SystemConfigAdminView from './SystemConfigAdminView.vue'
import { systemConfigApi } from './system-config-api'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '../../api/types'

vi.mock('./system-config-api', () => ({
  systemConfigApi: {
    getConfig: vi.fn(),
    updateConfig: vi.fn(),
    validateConfig: vi.fn(),
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

describe('SystemConfigAdminView', () => {
  const mockConfig = {
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

  const mockRuntimeStatus = {
    agentEnabled: false,
    chatEnabled: false,
    agentRuntimeAllowed: true,
    configuredWorkerConcurrency: 2,
    activeWorkerThreads: 0,
    queuedAgentRuns: 0,
    runningAgentRuns: 0,
    recentFailedAgentRuns: 0,
    lastExecutionError: null,
    maintenanceMode: false,
    refreshedAt: new Date().toISOString(),
  }

  const mockAudits = [
    {
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
    },
  ]

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(systemConfigApi.getConfig).mockResolvedValue({ data: { ...mockConfig } } as unknown as ApiResult<typeof mockConfig>)
    vi.mocked(systemConfigApi.getRuntimeStatus).mockResolvedValue({ data: { ...mockRuntimeStatus } } as unknown as ApiResult<typeof mockRuntimeStatus>)
    vi.mocked(systemConfigApi.getAuditLogs).mockResolvedValue({ data: [...mockAudits] } as unknown as ApiResult<typeof mockAudits>)
    vi.mocked(systemConfigApi.updateConfig).mockResolvedValue({
      data: { ...mockConfig, version: 2, agentEnabled: true },
    } as unknown as ApiResult<typeof mockConfig>)
    vi.mocked(systemConfigApi.restoreConfig).mockResolvedValue({
      data: { ...mockConfig, version: 3 },
    } as unknown as ApiResult<typeof mockConfig>)
  })

  it('loads configuration and runtime overview on mount', async () => {
    const wrapper = mount(SystemConfigAdminView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          'router-link': { template: '<a><slot /></a>' },
          'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
          'el-tag': { template: '<span class="el-tag"><slot /></span>' },
          'el-alert': { template: '<div class="el-alert" />' },
          'el-tabs': { template: '<div class="el-tabs"><slot /></div>' },
          'el-tab-pane': { template: '<div class="el-tab-pane"><slot /></div>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div class="el-form-item"><slot /></div>' },
          'el-row': { template: '<div class="el-row"><slot /></div>' },
          'el-col': { template: '<div class="el-col"><slot /></div>' },
          'el-switch': { template: '<input type="checkbox" />' },
          'el-slider': { template: '<input type="range" />' },
          'el-input-number': { template: '<input type="number" />' },
          'el-input': { template: '<input />' },
          'el-divider': { template: '<hr />' },
          'el-table': { template: '<table><slot /></table>' },
          'el-table-column': { template: '<th />' },
          'el-button': { template: '<button><slot /></button>' },
        },
      },
    })

    await flushPromises()

    expect(systemConfigApi.getConfig).toHaveBeenCalledTimes(1)
    expect(systemConfigApi.getRuntimeStatus).toHaveBeenCalledTimes(1)
    expect(systemConfigApi.getAuditLogs).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('Agent 协作状态')
    expect(wrapper.text()).toContain('AI Chat 问答状态')
  })

  it('updates configuration when saved', async () => {
    const wrapper = mount(SystemConfigAdminView, {
      global: {
        directives: { loading: () => {} },
        stubs: {
          PageHeader: { template: '<header><slot name="actions" /></header>' },
          'router-link': { template: '<a><slot /></a>' },
          'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
          'el-tag': { template: '<span class="el-tag"><slot /></span>' },
          'el-alert': { template: '<div class="el-alert" />' },
          'el-tabs': { template: '<div class="el-tabs"><slot /></div>' },
          'el-tab-pane': { template: '<div class="el-tab-pane"><slot /></div>' },
          'el-form': { template: '<form><slot /></form>' },
          'el-form-item': { template: '<div class="el-form-item"><slot /></div>' },
          'el-row': { template: '<div class="el-row"><slot /></div>' },
          'el-col': { template: '<div class="el-col"><slot /></div>' },
          'el-switch': { template: '<input type="checkbox" />' },
          'el-slider': { template: '<input type="range" />' },
          'el-input-number': { template: '<input type="number" />' },
          'el-input': { template: '<input />' },
          'el-divider': { template: '<hr />' },
          'el-table': { template: '<table><slot /></table>' },
          'el-table-column': { template: '<th />' },
          'el-button': {
            template: '<button><slot /></button>',
          },
        },
      },
    })

    await flushPromises()

    const saveBtn = wrapper.findAll('button').find((b) => b.text().includes('保存全部配置'))
    expect(saveBtn).toBeDefined()
    await saveBtn?.trigger('click')
    await flushPromises()

    expect(systemConfigApi.updateConfig).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('配置已保存'))
  })
})
