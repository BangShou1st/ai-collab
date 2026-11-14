// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminAiProviderApi, type AdminSharedAiProvider } from '../../api/admin-ai-provider-api'
import { showApiError } from '../../api/api-result'
import AdminAiProvidersView from './AdminAiProvidersView.vue'

const success = vi.hoisted(() => vi.fn())
const warning = vi.hoisted(() => vi.fn())

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {}, path: '/admin/ai-providers' }),
  useRouter: () => ({ replace: vi.fn() }),
}))
vi.mock('element-plus', () => ({
  ElMessage: { success, warning },
  ElMessageBox: { confirm: vi.fn() },
}))
vi.mock('../../api/api-result', () => ({
  showApiError: vi.fn(),
}))
vi.mock('../../api/admin-ai-provider-api', () => ({
  adminAiProviderApi: {
    list: vi.fn(),
    discoverModels: vi.fn(),
    models: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    test: vi.fn(),
    publish: vi.fn(),
    revoke: vi.fn(),
  },
}))

const provider = (overrides: Partial<AdminSharedAiProvider> = {}): AdminSharedAiProvider => ({
  id: 'provider-1',
  name: 'Magic Provider',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.example.com',
  apiPath: '/v1/chat/completions',
  modelName: 'saved-model',
  enabled: true,
  temperature: 0.7,
  maxOutputTokens: 4096,
  capabilities: ['CHAT', 'STREAMING'],
  hasApiKey: true,
  isShared: true,
  sharedStatus: 'PUBLISHED',
  sharedPublishedAt: '2026-11-15T00:00:00Z',
  createdAt: '2026-11-15T00:00:00Z',
  updatedAt: '2026-11-15T00:00:00Z',
  capabilityConfigVersion: 1,
  ...overrides,
})

const stubs = {
  ElButton: {
    inheritAttrs: false,
    props: ['disabled'],
    emits: ['click'],
    template: '<button :disabled="disabled" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>',
  },
  ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { props: ['label'], template: '<label><slot /></label>' },
  ElInput: {
    inheritAttrs: false,
    props: ['modelValue', 'placeholder', 'type'],
    emits: ['update:modelValue'],
    template: `
      <input
        v-bind="$attrs"
        :value="modelValue"
        :placeholder="placeholder"
        :type="type === 'password' ? 'password' : 'text'"
        @input="$emit('update:modelValue', $event.target.value)"
      />
    `,
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>',
  },
  ElOption: { props: ['label', 'value'], template: '<option :value="value">{{ label }}</option>' },
  ElCheckboxGroup: { template: '<div><slot /></div>' },
  ElCheckbox: { props: ['label', 'value'], template: '<label><slot /></label>' },
  ElSwitch: { template: '<div><slot /></div>' },
  ElTag: { template: '<span><slot /></span>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElTabs: { template: '<div><slot /></div>' },
  ElTabPane: { template: '<section><slot /></section>' },
  EmbeddingAdminContent: true,
  RerankerAdminContent: true,
}

async function renderView(providers: AdminSharedAiProvider[] = []) {
  vi.mocked(adminAiProviderApi.list).mockResolvedValue({ data: providers } as never)
  const wrapper = mount(AdminAiProvidersView, {
    global: {
      directives: { loading: () => undefined },
      stubs,
    },
  })
  await flushPromises()
  return wrapper
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text)
}

async function openCreateWithKey(wrapper: ReturnType<typeof mount>, key = 'sk-test') {
  await buttonByText(wrapper, '添加供应商')!.trigger('click')
  await wrapper.find('input[placeholder="sk-..."]').setValue(key)
}

describe('admin AI provider model discovery', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('discovers models for a new unsaved provider with the typed key', async () => {
    vi.mocked(adminAiProviderApi.discoverModels).mockResolvedValue({ data: ['model-a', 'model-b'] } as never)
    const wrapper = await renderView()
    await openCreateWithKey(wrapper)

    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(adminAiProviderApi.discoverModels).toHaveBeenCalledWith({
      providerType: 'OPENAI_COMPATIBLE',
      baseUrl: 'https://api.deepseek.com',
      apiPath: '/v1/chat/completions',
      apiKey: 'sk-test',
    })
    expect(success).toHaveBeenCalledWith('发现 2 个模型')
    expect(wrapper.text()).toContain('model-a')
  })

  it('refreshes the saved credential path when editing with a blank key', async () => {
    vi.mocked(adminAiProviderApi.models).mockResolvedValue({ data: ['saved-model'] } as never)
    const wrapper = await renderView([provider()])

    await buttonByText(wrapper, '编辑')!.trigger('click')
    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(adminAiProviderApi.models).toHaveBeenCalledWith('provider-1', true)
    expect(adminAiProviderApi.discoverModels).not.toHaveBeenCalled()
  })

  it('keeps manual model entry available when discovery fails', async () => {
    vi.mocked(adminAiProviderApi.discoverModels).mockRejectedValue(new Error('network'))
    const wrapper = await renderView()
    await openCreateWithKey(wrapper)

    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(showApiError).toHaveBeenCalledWith(expect.any(Error), '模型列表获取，可手动填写模型 ID')
    expect(wrapper.find('input[placeholder="如 deepseek-chat 或 gpt-4o"]').exists()).toBe(true)
  })
})
