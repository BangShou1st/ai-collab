// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  adminAiProviderApi,
  adminModelProfileApi,
  type AdminSharedAiProvider,
  type DiscoveredModelMetadata,
} from '../../api/admin-ai-provider-api'
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
    discoverModelMetadata: vi.fn(),
    modelMetadata: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    test: vi.fn(),
    publish: vi.fn(),
    revoke: vi.fn(),
  },
  adminModelProfileApi: {
    list: vi.fn(),
    upsert: vi.fn(),
    remove: vi.fn(),
  },
}))

const metaItem = (id: string, overrides: Partial<DiscoveredModelMetadata> = {}): DiscoveredModelMetadata => ({
  id,
  displayName: id,
  ownedBy: 'test',
  contextWindowTokens: null,
  maxInputTokens: null,
  maxOutputTokens: null,
  supportsTools: false,
  supportsReasoning: false,
  supportsStreaming: true,
  supportsStructuredOutput: true,
  metadataAvailable: false,
  reasoningLevels: [],
  ...overrides,
})

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
  modelProfileId: null,
  contextWindowTokens: null,
  contextWindowSource: null,
  contextWindowVerified: null,
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
  ElInputNumber: {
    inheritAttrs: false,
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: `
      <input
        v-bind="$attrs"
        type="number"
        :value="modelValue ?? ''"
        @input="$emit('update:modelValue', $event.target.value === '' ? null : Number($event.target.value))"
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
  ElAlert: { props: ['title'], template: '<div class="el-alert-stub">{{ title }}<slot /></div>' },
  ElTag: { template: '<span><slot /></span>' },
  ElIcon: { template: '<span><slot /></span>' },
  ElTabs: { template: '<div><slot /></div>' },
  ElTabPane: { template: '<section><slot /></section>' },
  EmbeddingAdminContent: true,
  RerankerAdminContent: true,
}

async function renderView(providers: AdminSharedAiProvider[] = []) {
  vi.mocked(adminAiProviderApi.list).mockResolvedValue({ data: providers } as never)
  vi.mocked(adminModelProfileApi.list).mockResolvedValue({ data: [] } as never)
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

describe('admin AI provider model discovery and configuration', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('discovers models and rich metadata for a new unsaved provider with the typed key', async () => {
    vi.mocked(adminAiProviderApi.discoverModelMetadata).mockResolvedValue({
      data: [
        metaItem('model-a', { contextWindowTokens: 256000, maxOutputTokens: 16384, supportsTools: true, metadataAvailable: true }),
        metaItem('model-b'),
      ],
    } as never)
    const wrapper = await renderView()
    await openCreateWithKey(wrapper)

    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(adminAiProviderApi.discoverModelMetadata).toHaveBeenCalledWith({
      providerType: 'OPENAI_COMPATIBLE',
      baseUrl: 'https://api.deepseek.com',
      apiPath: '/v1/chat/completions',
      apiKey: 'sk-test',
    })
    expect(success).toHaveBeenCalledWith('发现 2 个模型')
    expect(wrapper.text()).toContain('model-a')
    expect(wrapper.text()).toContain('256,000')

    // 选中上报了 maxOutputTokens 的模型时自动填入；切到未上报模型时自动兜底 8192
    const selects = wrapper.findAll('select')
    const modelSelect = selects[1]!
    await modelSelect.setValue('model-a')
    await flushPromises()
    expect((wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value).toBe('16384')

    await modelSelect.setValue('model-b')
    await flushPromises()
    expect((wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value).toBe('8192')
  })

  it('refreshes the saved credential path when editing with a blank key and allows editing maxOutputTokens and window profile', async () => {
    vi.mocked(adminAiProviderApi.modelMetadata).mockResolvedValue({
      data: [metaItem('saved-model', { contextWindowTokens: 983040, maxOutputTokens: 65536, metadataAvailable: true })],
    } as never)
    vi.mocked(adminAiProviderApi.update).mockResolvedValue({ data: provider({ maxOutputTokens: 8192 }) } as never)
    vi.mocked(adminModelProfileApi.upsert).mockResolvedValue({ data: {} } as never)

    const wrapper = await renderView([provider({ maxOutputTokens: 1200 })])
    expect(wrapper.text()).toContain('1,200 tokens')
    expect(wrapper.text()).toContain('偏低易截断')

    await buttonByText(wrapper, '编辑')!.trigger('click')
    await flushPromises()
    // 偏低旧配置（1200）打开编辑即高亮告警，获取模型后也会自动升级为上报值 65536
    expect(wrapper.find('.el-alert-stub').exists()).toBe(true)
    expect(wrapper.find('.el-alert-stub').text()).toContain('1200')

    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(adminAiProviderApi.modelMetadata).toHaveBeenCalledWith('provider-1', true)
    expect(adminAiProviderApi.discoverModelMetadata).not.toHaveBeenCalled()
    expect((wrapper.findAll('input[type="number"]')[0]!.element as HTMLInputElement).value).toBe('65536')
    expect(wrapper.find('.el-alert-stub').exists()).toBe(false)

    const numberInputs = wrapper.findAll('input[type="number"]')
    await numberInputs[0]!.setValue('8192')
    await buttonByText(wrapper, '确认保存')!.trigger('click')
    await flushPromises()

    expect(adminAiProviderApi.update).toHaveBeenCalledWith('provider-1', expect.objectContaining({
      maxOutputTokens: 8192,
    }))
    expect(adminModelProfileApi.upsert).toHaveBeenCalledWith(expect.objectContaining({
      modelName: 'saved-model',
      contextWindowTokens: 983040,
      source: 'PROVIDER_DOCUMENT',
    }))
  })

  it('keeps manual model entry available when discovery fails', async () => {
    vi.mocked(adminAiProviderApi.discoverModelMetadata).mockRejectedValue(new Error('network'))
    const wrapper = await renderView()
    await openCreateWithKey(wrapper)

    await wrapper.find('button[aria-label="获取模型列表"]').trigger('click')
    await flushPromises()

    expect(showApiError).toHaveBeenCalledWith(expect.any(Error), '模型列表获取，可手动填写模型 ID')
    expect(wrapper.find('input[placeholder="如 deepseek-chat 或 gpt-4o"]').exists()).toBe(true)
  })
})
