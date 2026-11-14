// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import RerankerAdminContent from './RerankerAdminContent.vue'
import { adminCapabilityApi } from '../../../api/admin-capability-api'
import type { ApiResult } from '../../../api/types'

vi.mock('../../../api/admin-capability-api', () => ({
  adminCapabilityApi: {
    listProfiles: vi.fn(),
    listAssignments: vi.fn(),
    testProfile: vi.fn(),
    publish: vi.fn(),
    rollback: vi.fn(),
    disable: vi.fn(),
    bindGlobal: vi.fn(),
    deleteAssignment: vi.fn(),
  },
}))

vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(undefined) },
}))

const rerankProfile = {
  id: 'profile-rerank',
  modelProfileId: 'model-rerank',
  modelKey: 'qwen3-reranker',
  modelDisplayName: 'Qwen3 Reranker 0.6B',
  providerId: 'provider-1',
  providerName: 'Local llama.cpp',
  capability: 'RERANK',
  protocol: 'LLAMACPP_RERANK',
  settings: { candidateLimit: 8, timeoutMs: 3000 },
  status: 'PUBLISHED',
  version: 3,
  source: 'LOCAL_FILE',
  verifiedAt: '2026-11-13T10:00:00+08:00',
  verifiedBy: 'admin',
  createdAt: '2026-11-13T09:00:00+08:00',
  updatedAt: '2026-11-13T10:00:00+08:00',
} as const

const chatProfile = {
  ...rerankProfile,
  id: 'profile-chat',
  modelKey: 'chat-model',
  modelDisplayName: 'Chat Model',
  capability: 'CHAT',
} as const

const globalAssignment = {
  id: 'assignment-1',
  capability: 'RERANK',
  purpose: 'KNOWLEDGE_RERANK',
  scope: 'GLOBAL',
  projectId: null,
  profileId: 'profile-rerank',
  profileStatus: 'PUBLISHED',
  profileVersion: 3,
  enabled: true,
  updatedAt: '2026-11-13T10:00:00+08:00',
} as const

function apiResult<T>(data: T): ApiResult<T> {
  return { httpStatus: 200, code: 'SUCCESS', message: 'ok', data }
}

function mountView() {
  return mount(RerankerAdminContent, {
    global: {
      directives: { loading: () => {} },
      stubs: {
        'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
        'el-tag': { template: '<span class="el-tag"><slot /></span>' },
        'el-button': { template: '<button><slot /></button>' },
        'el-dialog': { template: '<div class="el-dialog"><slot /><slot name="footer" /></div>' },
        'el-form': { template: '<form><slot /></form>' },
        'el-form-item': { template: '<div><slot /></div>' },
        'el-input': { template: '<input />' },
        'el-input-number': { template: '<input type="number" />' },
        'el-select': { template: '<select><slot /></select>' },
        'el-option': { template: '<option><slot /></option>' },
        'el-icon': { template: '<span><slot /></span>' },
      },
    },
  })
}

describe('RerankerAdminContent', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(adminCapabilityApi.listProfiles).mockResolvedValue(
      apiResult([rerankProfile, chatProfile]) as never,
    )
    vi.mocked(adminCapabilityApi.listAssignments).mockResolvedValue(
      apiResult([globalAssignment]) as never,
    )
    vi.mocked(adminCapabilityApi.publish).mockResolvedValue(
      apiResult({ ...rerankProfile, status: 'PUBLISHED' }) as never,
    )
  })

  it('loads only RERANK profiles and shows the global binding', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(adminCapabilityApi.listProfiles).toHaveBeenCalled()
    expect(adminCapabilityApi.listAssignments).toHaveBeenCalled()
    expect(wrapper.text()).toContain('Qwen3 Reranker 0.6B')
    expect(wrapper.text()).toContain('全局使用中')
    expect(wrapper.text()).toContain('已绑定')
    expect(wrapper.text()).not.toContain('Chat Model')
  })

  it('publishes a selected rerank profile after confirmation', async () => {
    vi.mocked(adminCapabilityApi.listProfiles).mockResolvedValue(
      apiResult([{ ...rerankProfile, status: 'TESTED' }, chatProfile]) as never,
    )
    const wrapper = mountView()
    await flushPromises()

    const publishButton = wrapper.findAll('button').find((item) => item.text().includes('发布'))
    expect(publishButton).toBeTruthy()
    await publishButton!.trigger('click')
    await flushPromises()

    expect(adminCapabilityApi.publish).toHaveBeenCalledWith(
      'profile-rerank',
      '管理员从 Reranker 管理页发布',
    )
  })
})
