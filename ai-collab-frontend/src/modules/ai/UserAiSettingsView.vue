<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { userAiApi, type AiProviderType, type AiPurpose, type UserAiProvider } from './user-ai-api'
import { aiPresetApi, type PresetStatus } from './ai-preset-api'

const providers = ref<UserAiProvider[]>([])
const loading = ref(false)
const zen = ref<PresetStatus | null>(null)
const zenModels = ref<string[]>([])
const zenLoading = ref(false)
const zenKey = ref('')
const zenModel = ref('')
const zenEnabled = ref(true)
const zenDefault = ref(false)
const saving = ref(false)
const testingId = ref('')
const dialogVisible = ref(false)
const editing = ref<UserAiProvider | null>(null)
const showAdvanced = ref('')
const purposes: { value: AiPurpose; label: string }[] = [
  { value: 'KNOWLEDGE_CHAT', label: '知识问答' },
  { value: 'PLANNING', label: 'AI 规划' },
  { value: 'AGENT', label: 'Agent' },
]
const overrides = reactive<Record<AiPurpose, string>>({
  KNOWLEDGE_CHAT: '',
  PLANNING: '',
  AGENT: '',
})
const form = reactive({
  name: '',
  providerType: 'OPENAI_COMPATIBLE' as AiProviderType,
  baseUrl: 'https://api.openai.com',
  apiPath: '/v1/chat/completions',
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.2,
  maxOutputTokens: 1200,
  capabilities: ['CHAT'] as string[],
})
const dialogTitle = computed(() => (editing.value ? '编辑 AI 配置' : '添加 AI 配置'))
const customProviders = computed(() => providers.value.filter((p) => !p.presetCode))
const zenProviderId = computed(() => providers.value.find((p) => p.presetCode)?.id ?? '')
const canSave = computed(() => Boolean(form.name.trim() && form.modelName.trim() && (form.apiKey.trim() || editing.value)))

async function load(): Promise<void> {
  loading.value = true
  try {
    const [custom, presetList, purposes] = await Promise.all([userAiApi.list(), aiPresetApi.presets(), userAiApi.listPurposes().catch(() => ({ data: {} as Record<string, string> }))])
    providers.value = custom.data
    zen.value = presetList.data.find((p) => p.code === 'OPENCODE_ZEN_FREE') ?? null
    if (zen.value?.modelName) zenModel.value = zen.value.modelName
    zenEnabled.value = zen.value?.enabled ?? true
    zenDefault.value = !custom.data.some((p) => p.isDefault) || (zen.value?.isDefault ?? false)
    const saved = (purposes as { data: Record<string, string> }).data ?? {}
    overrides.KNOWLEDGE_CHAT = saved.KNOWLEDGE_CHAT ?? ''
    overrides.PLANNING = saved.PLANNING ?? ''
    overrides.AGENT = saved.AGENT ?? ''
    await loadZenModels(false)
  } catch (error) {
    showApiError(error, 'AI 配置加载')
  } finally {
    loading.value = false
  }
}
async function loadZenModels(refresh: boolean): Promise<void> {
  zenLoading.value = true
  try {
    zenModels.value = (await aiPresetApi.models(refresh)).data
    if (!zenModel.value && zenModels.value[0]) zenModel.value = zenModels.value[0]
  } catch (error) {
    showApiError(error, '免费模型加载，可点击重试')
  } finally {
    zenLoading.value = false
  }
}
async function saveZen(): Promise<void> {
  if (!zenModel.value || saving.value) return
  saving.value = true
  try {
    zen.value = (await aiPresetApi.save({ apiKey: zenKey.value || undefined, modelName: zenModel.value, enabled: zenEnabled.value, setDefault: zenDefault.value })).data
    zenKey.value = ''
    await load()
    ElMessage.success('OpenCode Zen Free 已保存并启用')
  } catch (error) {
    showApiError(error, 'Zen 保存')
  } finally {
    saving.value = false
  }
}
async function testZen(): Promise<void> {
  testingId.value = 'zen'
  try {
    await aiPresetApi.test({ apiKey: zenKey.value || undefined, modelName: zenModel.value || undefined })
    ElMessage.success('连接成功')
  } catch (error) {
    showApiError(error, '连接测试')
  } finally {
    testingId.value = ''
  }
}

function resetForm(): void {
  editing.value = null
  showAdvanced.value = ''
  Object.assign(form, {
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.openai.com',
    apiPath: '/v1/chat/completions',
    apiKey: '',
    modelName: '',
    enabled: true,
    temperature: 0.2,
    maxOutputTokens: 1200,
    capabilities: ['CHAT'],
  })
}

function openCreate(): void {
  resetForm()
  dialogVisible.value = true
}

function openEdit(provider: UserAiProvider): void {
  editing.value = provider
  showAdvanced.value = ''
  Object.assign(form, {
    name: provider.name,
    providerType: provider.providerType,
    baseUrl: provider.baseUrl,
    apiPath: provider.apiPath,
    apiKey: '',
    modelName: provider.modelName,
    enabled: provider.enabled,
    temperature: provider.temperature,
    maxOutputTokens: provider.maxOutputTokens,
    capabilities: [...provider.capabilities],
  })
  dialogVisible.value = true
}

async function save(): Promise<void> {
  if (!canSave.value || saving.value) return
  saving.value = true
  try {
    const payload = {
      name: form.name.trim(),
      providerType: form.providerType,
      baseUrl: form.baseUrl.trim(),
      apiPath: form.apiPath.trim(),
      apiKey: form.apiKey.trim() || null,
      modelName: form.modelName.trim(),
      enabled: form.enabled,
      temperature: form.temperature,
      maxOutputTokens: form.maxOutputTokens,
      capabilities: form.capabilities,
    }
    if (editing.value) await userAiApi.update(editing.value.id, payload)
    else await userAiApi.create(payload)
    dialogVisible.value = false
    resetForm()
    await load()
    ElMessage.success('已保存')
  } catch (error) {
    showApiError(error, 'AI 配置保存')
  } finally {
    saving.value = false
  }
}

async function remove(provider: UserAiProvider): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除“${provider.name}”吗？`, '删除 AI 配置', {
      confirmButtonText: '确认删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await userAiApi.remove(provider.id)
    await load()
    ElMessage.success('已删除')
  } catch (error) {
    showApiError(error, 'AI 配置删除')
  }
}

async function testConnection(provider: UserAiProvider): Promise<void> {
  testingId.value = provider.id
  try {
    await userAiApi.test(provider.id)
    ElMessage.success('连接成功')
  } catch (error) {
    showApiError(error, '连接测试')
  } finally {
    testingId.value = ''
  }
}

async function setDefault(provider: UserAiProvider): Promise<void> {
  try {
    await userAiApi.setDefault(provider.id)
    await load()
    ElMessage.success('已设为默认')
  } catch (error) {
    showApiError(error, '设置默认')
  }
}

function overrideName(purpose: AiPurpose): string {
  const id = overrides[purpose]
  if (!id) return '使用默认'
  if (id === zenProviderId.value) return `OpenCode Zen · ${zen.value?.modelName ?? ''}`
  return customProviders.value.find((p) => p.id === id)?.name ?? '使用默认'
}

async function saveOverride(purpose: AiPurpose): Promise<void> {
  const providerId = overrides[purpose]
  try {
    if (providerId) await userAiApi.assignPurpose(purpose, providerId)
    else await userAiApi.unassignPurpose(purpose)
    ElMessage.success('已保存覆盖')
  } catch (error) {
    showApiError(error, '用途覆盖保存')
  }
}

onMounted(load)
</script>
<template>
  <main class="workspace-page">
    <PageHeader eyebrow="个人设置" title="AI 设置" description="连接自己的模型，知识问答、AI 规划与 Agent 都由此驱动">
      <template #actions>
        <el-button type="primary" @click="openCreate">添加 AI 配置</el-button>
      </template>
    </PageHeader>

    <section v-loading="loading" class="ai-settings-stack ai-settings-narrow">
      <div class="section-title"><h2>推荐连接</h2></div>
      <div class="provider-tile zen">
        <div class="zen-two-col">
          <div class="zen-brand">
            <div><strong>OpenCode Zen</strong><span v-if="zen?.connected" class="status-dot done" /> <span v-if="zen?.isDefault">默认</span></div>
            <p class="provider-tile__sub">官方免费模型通道，开箱即用。连接后知识问答、AI 规划与 Agent 可直接使用。</p>
            <ul class="zen-points">
              <li>免费模型 · 自动发现</li>
              <li>OpenCode 官方接口</li>
              <li>应用层 Direct</li>
            </ul>
          </div>
          <div>
            <div v-if="zen?.connected" class="provider-tile__current">{{ zen?.modelName }}</div>
            <el-skeleton v-if="zenLoading" :rows="2" animated />
            <div v-else class="zen-form">
              <el-input v-model="zenKey" type="password" show-password placeholder="API Key（留空则保留已保存）" />
              <el-select v-model="zenModel" placeholder="选择免费模型" filterable>
                <el-option v-for="m in zenModels" :key="m" :label="m" :value="m" />
              </el-select>
              <div class="zen-actions">
                <el-checkbox v-model="zenDefault">设为默认模型</el-checkbox>
                <el-button :loading="testingId === 'zen'" @click="testZen">测试连接</el-button>
                <el-button type="primary" :loading="saving" :disabled="!zenModel" @click="saveZen">保存</el-button>
                <el-button text aria-label="刷新模型" title="刷新模型" @click="loadZenModels(true)">刷新</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="section-title"><h2>你的模型</h2><el-button text type="primary" @click="openCreate">+ 自定义 Provider</el-button></div>
      <EmptyState
        v-if="!customProviders.length"
        compact
        title="还没有自定义模型"
        description="上面的 Zen 连接已可直接使用，这里按需添加自己的网关"
      />
      <div v-if="customProviders.length" class="model-rows">
        <div v-for="provider in customProviders" :key="provider.id" class="model-row">
          <div class="model-main">
            <strong>{{ provider.name }}</strong>
            <span class="provider-meta">{{ provider.modelName }} · {{ provider.providerType }} · API Key {{ provider.hasApiKey ? '已配置' : '未配置' }}</span>
          </div>
          <span class="provider-tags">
            <el-tag v-if="provider.isDefault" type="success" size="small">默认</el-tag>
            <el-tag :type="provider.enabled ? '' : 'info'" size="small">{{ provider.enabled ? '启用' : '停用' }}</el-tag>
          </span>
          <div class="provider-actions">
            <el-button size="small" :loading="testingId === provider.id" @click="testConnection(provider)">测试</el-button>
            <el-button v-if="!provider.isDefault" size="small" @click="setDefault(provider)">设为默认</el-button>
            <el-button size="small" @click="openEdit(provider)">编辑</el-button>
            <el-button size="small" type="danger" text @click="remove(provider)">删除</el-button>
          </div>
        </div>
      </div>

      <el-card v-if="customProviders.length || zen?.connected" shadow="never">
        <template #header><strong>按用途覆盖默认模型（可选）</strong></template>
        <div v-for="purpose in purposes" :key="purpose.value" class="override-row setting-row">
          <span class="override-label">{{ purpose.label }}</span>
          <span class="override-current">{{ overrideName(purpose.value) }}</span>
          <el-select v-model="overrides[purpose.value]" placeholder="使用默认" clearable @change="saveOverride(purpose.value)">
            <el-option v-if="zen?.connected" :key="'zen'" :label="`OpenCode Zen Free · ${zen?.modelName ?? ''}`" :value="zenProviderId" />
            <el-option v-for="p in customProviders.filter((c) => c.enabled)" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </div>
      </el-card>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px">
      <el-form label-position="top">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="80" /></el-form-item>
        <el-form-item label="Provider">
          <el-select v-model="form.providerType">
            <el-option label="OpenAI 兼容" value="OPENAI_COMPATIBLE" />
            <el-option label="Anthropic" value="ANTHROPIC" />
            <el-option label="Gemini" value="GEMINI" />
          </el-select>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="只写不读，编辑时留空则不修改" />
        </el-form-item>
        <el-form-item label="模型"><el-input v-model="form.modelName" maxlength="160" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-collapse v-model="showAdvanced" accordion>
          <el-collapse-item title="高级设置" name="advanced">
            <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
            <el-form-item label="API Path"><el-input v-model="form.apiPath" /></el-form-item>
            <el-form-item label="Temperature"><el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
            <el-form-item label="Max Output Tokens"><el-input-number v-model="form.maxOutputTokens" :min="1" :max="131072" /></el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>
