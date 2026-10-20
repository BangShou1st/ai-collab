<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { userAiApi, type AiProviderType, type AiPurpose, type UserAiProvider } from './user-ai-api'
import { aiPresetApi, type PresetStatus } from './ai-preset-api'
import {
  AI_PURPOSES,
  PROBE_MODES,
  probeKey,
  resolvePurposeStatuses,
  type ProbeMode,
  type ProbeOutcome,
  type PurposeStatus,
} from './ai-purpose-status'

const auth = useAuthStore()
const providers = ref<UserAiProvider[]>([])
const loading = ref(false)
/** 配置加载失败：状态未知，不得显示为“未配置”。 */
const loadFailed = ref(false)
const zen = ref<PresetStatus | null>(null)
const zenModels = ref<string[]>([])
const zenLoading = ref(false)
const zenKey = ref('')
const zenModel = ref('')
const zenEnabled = ref(true)
const zenDefault = ref(false)
const kilo = ref<PresetStatus | null>(null)
const kiloModels = ref<string[]>([])
const kiloLoading = ref(false)
const kiloModel = ref('')
const kiloEnabled = ref(true)
const kiloDefault = ref(false)
const saving = ref(false)
const savingKilo = ref(false)
const disconnecting = ref(false)
const disconnectingKilo = ref(false)
const testingId = ref('')
const dialogVisible = ref(false)
const purposesFailed = ref(false)
const editing = ref<UserAiProvider | null>(null)
const showAdvanced = ref('')
const overrides = reactive<Record<AiPurpose, string>>({
  KNOWLEDGE_CHAT: '',
  PLANNING: '',
  AGENT: '',
})
/** 每次测试按钮对应的能力，按 Provider 独立保存（不同能力不互相替代）。 */
const probeModes = reactive<Record<string, ProbeMode>>({})
/** 仅当前会话内的测试结果，不落库、不显示历史成功，避免被当成持续健康。 */
const probeOutcomes = reactive<Record<string, ProbeOutcome>>({})
/** 递增请求令牌：晚到的加载响应不得覆盖新状态或回填旧用户数据。 */
let loadToken = 0

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
const enabledProviderCount = computed(() => providers.value.filter((p) => p.enabled).length)
const zenProviderId = computed(() => providers.value.find((p) => p.presetCode === 'OPENCODE_ZEN_FREE')?.id ?? '')
const kiloProviderId = computed(() => providers.value.find((p) => p.presetCode === 'KILO_GATEWAY_FREE')?.id ?? '')
const canSave = computed(() => Boolean(form.name.trim() && form.modelName.trim() && (form.apiKey.trim() || editing.value)))
const purposeStatuses = computed<PurposeStatus[]>(() => resolvePurposeStatuses(providers.value, overrides))

/**
 * 覆盖指向的 Provider 若已停用或已从可选列表移除，选择器会回退成原始 ID；
 * 这里补一个只读（不可重选）选项，显示可读名称与“已停用”，并允许用户清除覆盖。
 */
const overrideExtras = computed<Record<AiPurpose, { id: string; label: string } | null>>(() => {
  const out = {} as Record<AiPurpose, { id: string; label: string } | null>
  for (const status of purposeStatuses.value) {
    const provider = status.provider
    out[status.purpose] = provider && !isTargetListed(provider)
      ? { id: provider.id, label: `${provider.name}${provider.enabled ? '' : '（已停用）'}` }
      : null
  }
  return out
})

function isTargetListed(provider: UserAiProvider): boolean {
  if (provider.presetCode === 'OPENCODE_ZEN_FREE') return Boolean(zen.value?.connected)
  if (provider.presetCode === 'KILO_GATEWAY_FREE') return Boolean(kilo.value?.connected)
  return Boolean(provider.enabled)
}

function purposeLabel(purpose: AiPurpose): string {
  return AI_PURPOSES.find((item) => item.value === purpose)?.label ?? purpose
}

function modeFor(id: string): ProbeMode {
  return probeModes[id] ?? 'CONNECTIVITY'
}

function outcomeFor(id: string): ProbeOutcome | null {
  return probeOutcomes[probeKey(id, modeFor(id))] ?? null
}

function probeLabel(id: string): string {
  const outcome = outcomeFor(id)
  if (!outcome) return '未测试'
  const label = PROBE_MODES.find((item) => item.value === outcome.mode)?.label ?? outcome.mode
  return outcome.ok ? `${label}：本次会话测试通过` : `${label}：本次会话测试失败`
}

function probeTagType(id: string): 'success' | 'danger' | 'info' {
  const outcome = outcomeFor(id)
  if (!outcome) return 'info'
  return outcome.ok ? 'success' : 'danger'
}

/** 测试结论只覆盖被测试的单项能力，不外推到规划 JSON、流式或原生工具。 */
function probeSuccessMessage(mode: ProbeMode): string {
  const label = PROBE_MODES.find((item) => item.value === mode)?.label ?? mode
  return `${label}能力测试通过（仅该能力，其他能力需分别测试）`
}

function purposeStatusText(status: PurposeStatus): string {
  const provider = status.provider
  const name = provider ? `${provider.name} · ${provider.modelName}` : ''
  if (status.blocked === 'OVERRIDE_DISABLED') {
    return `用途覆盖指向已停用的模型“${provider?.name ?? ''}”，该用途当前不可用`
  }
  if (status.blocked === 'DEFAULT_DISABLED') {
    return `默认模型“${provider?.name ?? ''}”已停用，该用途当前不可用`
  }
  if (status.blocked === 'NO_DEFAULT') {
    return '没有默认模型，也没有该用途的覆盖，当前不可用'
  }
  const stale = status.staleOverride ? '用途覆盖已失效（对应模型已删除），已回退到默认模型 · ' : ''
  const source = status.resolution === 'OVERRIDE' ? '用途覆盖' : '默认模型'
  return `${stale}${source}：${name} · 已启用 · 可用性未验证`
}

async function loadPurposes(): Promise<void> {
  try {
    const saved = (await userAiApi.listPurposes()).data ?? {}
    overrides.KNOWLEDGE_CHAT = saved.KNOWLEDGE_CHAT ?? ''
    overrides.PLANNING = saved.PLANNING ?? ''
    overrides.AGENT = saved.AGENT ?? ''
    purposesFailed.value = false
  } catch (error) {
    // 失败不是“无覆盖”：保留旧值并禁用选择器，避免把失败伪装成“使用默认”。
    purposesFailed.value = true
    showApiError(error, '用途覆盖加载，可点击重试')
  }
}

async function load(): Promise<void> {
  const token = ++loadToken
  loading.value = true
  loadFailed.value = false
  try {
    const [custom, presetList] = await Promise.all([userAiApi.list(), aiPresetApi.presets()])
    if (token !== loadToken) return
    providers.value = custom.data
    zen.value = presetList.data.find((p) => p.code === 'OPENCODE_ZEN_FREE') ?? null
    kilo.value = presetList.data.find((p) => p.code === 'KILO_GATEWAY_FREE') ?? null
    if (zen.value?.modelName) zenModel.value = zen.value.modelName
    if (kilo.value?.modelName) kiloModel.value = kilo.value.modelName
    zenEnabled.value = zen.value?.enabled ?? true
    kiloEnabled.value = kilo.value?.enabled ?? true
    zenDefault.value = !custom.data.some((p) => p.isDefault) || (zen.value?.isDefault ?? false)
    kiloDefault.value = !custom.data.some((p) => p.isDefault) || (kilo.value?.isDefault ?? false)
    await loadPurposes()
  } catch (error) {
    if (token !== loadToken) return
    loadFailed.value = true
    showApiError(error, 'AI 配置加载')
  } finally {
    if (token === loadToken) loading.value = false
  }
  if (token !== loadToken) return
  // 免费模型列表走独立 skeleton，不阻塞整页：先让预设配置/模型/覆盖可见
  await Promise.all([loadZenModels(false), loadKiloModels(false)])
}

async function loadZenModels(refresh: boolean): Promise<void> {
  zenLoading.value = true
  try {
    zenModels.value = (await aiPresetApi.models('OPENCODE_ZEN_FREE', refresh)).data
    if (!zenModel.value && zenModels.value[0]) zenModel.value = zenModels.value[0]
  } catch (error) {
    showApiError(error, '免费模型加载，可点击重试')
  } finally {
    zenLoading.value = false
  }
}

async function loadKiloModels(refresh: boolean): Promise<void> {
  kiloLoading.value = true
  try {
    kiloModels.value = (await aiPresetApi.models('KILO_GATEWAY_FREE', refresh)).data
    if (!kiloModel.value && kiloModels.value[0]) kiloModel.value = kiloModels.value[0]
  } catch (error) {
    showApiError(error, 'Kilo 免费模型加载，可点击重试')
  } finally {
    kiloLoading.value = false
  }
}

/**
 * 真实模型探测只在此函数被用户点击时触发；页面初始化、刷新和用途切换都不会调用它。
 * 结果仅记录在当前会话，用于区分“未测试 / 测试失败 / 测试通过”，不表示持续健康。
 */
async function runProbe(id: string, mode: ProbeMode, action: () => Promise<unknown>): Promise<void> {
  testingId.value = id
  try {
    await action()
    probeOutcomes[probeKey(id, mode)] = { mode, ok: true, at: Date.now() }
    ElMessage.success(probeSuccessMessage(mode))
  } catch (error) {
    const detail = showApiError(error, '连接测试')
    probeOutcomes[probeKey(id, mode)] = { mode, ok: false, at: Date.now(), detail }
  } finally {
    testingId.value = ''
  }
}

async function saveZen(): Promise<void> {
  if (!zenModel.value || saving.value) return
  saving.value = true
  try {
    zen.value = (await aiPresetApi.save('OPENCODE_ZEN_FREE', { apiKey: zenKey.value || undefined, modelName: zenModel.value, enabled: zenEnabled.value, setDefault: zenDefault.value })).data
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
  await runProbe('zen', 'CONNECTIVITY', () => aiPresetApi.test('OPENCODE_ZEN_FREE', { apiKey: zenKey.value || undefined, modelName: zenModel.value || undefined }))
}

async function disconnectZen(): Promise<void> {
  if (disconnecting.value) return
  try {
    await ElMessageBox.confirm('断开后将删除已保存的 Zen API Key，用途覆盖会回退到默认模型。确定断开吗？', '断开 OpenCode Zen', {
      confirmButtonText: '断开连接', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  disconnecting.value = true
  try {
    await aiPresetApi.disconnect('OPENCODE_ZEN_FREE')
    zenKey.value = ''
    delete probeOutcomes[probeKey('zen', 'CONNECTIVITY')]
    await load()
    ElMessage.success('已断开 OpenCode Zen 连接')
  } catch (error) {
    showApiError(error, '断开连接')
  } finally {
    disconnecting.value = false
  }
}

async function saveKilo(): Promise<void> {
  if (!kiloModel.value || savingKilo.value) return
  savingKilo.value = true
  try {
    kilo.value = (await aiPresetApi.save('KILO_GATEWAY_FREE', { modelName: kiloModel.value, enabled: kiloEnabled.value, setDefault: kiloDefault.value })).data
    await load()
    ElMessage.success('Kilo Gateway Free 已保存并启用')
  } catch (error) {
    showApiError(error, 'Kilo 保存')
  } finally {
    savingKilo.value = false
  }
}

async function testKilo(): Promise<void> {
  await runProbe('kilo', 'CONNECTIVITY', () => aiPresetApi.test('KILO_GATEWAY_FREE', { modelName: kiloModel.value || undefined }))
}

async function disconnectKilo(): Promise<void> {
  if (disconnectingKilo.value) return
  try {
    await ElMessageBox.confirm('断开后将删除已保存的 Kilo Gateway 连接，用途覆盖会回退到默认模型。确定断开吗？', '断开 Kilo Gateway', {
      confirmButtonText: '断开连接', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  disconnectingKilo.value = true
  try {
    await aiPresetApi.disconnect('KILO_GATEWAY_FREE')
    delete probeOutcomes[probeKey('kilo', 'CONNECTIVITY')]
    await load()
    ElMessage.success('已断开 Kilo Gateway 连接')
  } catch (error) {
    showApiError(error, '断开连接')
  } finally {
    disconnectingKilo.value = false
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
  const mode = modeFor(provider.id)
  await runProbe(provider.id, mode,
    () => (mode === 'CONNECTIVITY' ? userAiApi.test(provider.id) : userAiApi.testCapability(provider.id, mode)))
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

/**
 * 用途覆盖只改变解析目标，不触发模型探测；切换后立即按新目标重新展示解析状态。
 */
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
      <div v-if="loadFailed" class="ai-load-error" role="alert">
        <div>
          <strong>AI 配置加载失败，当前状态未知</strong>
          <p>无法确认已保存的模型与用途覆盖，请重试后再判断。</p>
        </div>
        <el-button @click="load">重试</el-button>
      </div>

      <p v-if="!loadFailed" class="ai-config-summary">
        已保存 {{ providers.length }} 个模型配置（已启用 {{ enabledProviderCount }} 个）· 配置存在不代表可调用，可用性需按能力测试确认
      </p>

      <el-card v-if="!loadFailed" shadow="never">
        <template #header>
          <div class="header-row">
            <strong>按用途的解析状态</strong>
            <el-button v-if="purposesFailed" text type="primary" size="small" @click="loadPurposes">重试</el-button>
          </div>
        </template>
        <p v-if="purposesFailed" class="purpose-failure" role="alert">用途覆盖加载失败，暂无法确认各用途解析结果。请点击“重试”后再判断。</p>
        <template v-else>
          <p v-if="!providers.length" class="purpose-empty">还没有保存任何模型配置，下列用途均不可用。</p>
          <div v-for="status in purposeStatuses" :key="status.purpose" class="purpose-row">
            <span class="override-label">{{ purposeLabel(status.purpose) }}</span>
            <el-select v-model="overrides[status.purpose]" placeholder="使用默认" clearable @change="saveOverride(status.purpose)">
              <el-option v-if="overrideExtras[status.purpose]" :key="'assigned'" :label="overrideExtras[status.purpose]!.label" :value="overrideExtras[status.purpose]!.id" disabled />
              <el-option v-if="zen?.connected" :key="'zen'" :label="`OpenCode Zen Free · ${zen?.modelName ?? ''}`" :value="zenProviderId" />
              <el-option v-if="kilo?.connected" :key="'kilo'" :label="`Kilo Gateway Free · ${kilo?.modelName ?? ''}`" :value="kiloProviderId" />
              <el-option v-for="p in customProviders.filter((c) => c.enabled)" :key="p.id" :label="p.name" :value="p.id" />
            </el-select>
            <span class="purpose-status" :class="{ 'purpose-status--blocked': !status.resolvable }">{{ purposeStatusText(status) }}</span>
          </div>
        </template>
      </el-card>

      <div v-if="!loadFailed" class="section-title"><h2>推荐连接</h2></div>
      <div v-if="!loadFailed" class="provider-tile zen">
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
            <div v-if="zen?.connected" class="provider-tile__current-row">
              <span class="provider-tile__current">{{ zen?.modelName }}</span>
              <el-button text type="danger" size="small" :loading="disconnecting" @click="disconnectZen">断开连接</el-button>
            </div>
            <el-skeleton v-if="zenLoading" :rows="2" animated />
            <div v-else class="zen-form">
              <el-input v-model="zenKey" type="password" show-password placeholder="API Key（免费模型可留空；留空则保留已保存 Key）" />
              <div class="model-select-row">
                <el-select v-model="zenModel" placeholder="选择免费模型" filterable>
                  <el-option v-for="m in zenModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button text :icon="Refresh" aria-label="刷新模型" title="刷新模型" @click="loadZenModels(true)" />
              </div>
              <div class="zen-actions">
                <el-checkbox v-model="zenDefault">设为默认模型</el-checkbox>
                <el-button :loading="testingId === 'zen'" @click="testZen">测试连接</el-button>
                <el-tag :type="probeTagType('zen')" size="small" class="probe-status">{{ probeLabel('zen') }}</el-tag>
                <el-button type="primary" :loading="saving" :disabled="!zenModel" @click="saveZen">保存</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-if="!loadFailed" class="provider-tile kilo">
        <div class="zen-two-col">
          <div class="zen-brand">
            <div><strong>Kilo Gateway</strong><span v-if="kilo?.connected" class="status-dot done" /> <span v-if="kilo?.isDefault">默认</span></div>
            <p class="provider-tile__sub">免费模型聚合网关，匿名可用。连接后知识问答、AI 规划与 Agent 可直接使用。</p>
            <ul class="zen-points">
              <li>免费模型 · 自动发现</li>
              <li>ai-gateway.kilo.ai 官方接口</li>
              <li>匿名调用 · 无需 Key</li>
            </ul>
          </div>
          <div>
            <div v-if="kilo?.connected" class="provider-tile__current-row">
              <span class="provider-tile__current">{{ kilo?.modelName }}</span>
              <el-button text type="danger" size="small" :loading="disconnectingKilo" @click="disconnectKilo">断开连接</el-button>
            </div>
            <el-skeleton v-if="kiloLoading" :rows="2" animated />
            <div v-else class="zen-form">
              <div class="model-select-row">
                <el-select v-model="kiloModel" placeholder="选择免费模型" filterable>
                  <el-option v-for="m in kiloModels" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button text :icon="Refresh" aria-label="刷新模型" title="刷新模型" @click="loadKiloModels(true)" />
              </div>
              <div class="zen-actions">
                <el-checkbox v-model="kiloDefault">设为默认模型</el-checkbox>
                <el-button :loading="testingId === 'kilo'" @click="testKilo">测试连接</el-button>
                <el-tag :type="probeTagType('kilo')" size="small" class="probe-status">{{ probeLabel('kilo') }}</el-tag>
                <el-button type="primary" :loading="savingKilo" :disabled="!kiloModel" @click="saveKilo">保存</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div v-if="!loadFailed" class="section-title"><h2>你的模型</h2><el-button text type="primary" @click="openCreate">+ 自定义 Provider</el-button></div>
      <EmptyState
        v-if="!customProviders.length && !loadFailed"
        compact
        title="还没有自定义模型"
        description="上面的预设连接已可直接使用，这里按需添加自己的网关"
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
          <span class="provider-probe">
            <el-select v-model="probeModes[provider.id]" size="small" style="width: 130px" aria-label="测试能力">
              <el-option v-for="mode in PROBE_MODES" :key="mode.value" :label="mode.label" :value="mode.value" />
            </el-select>
            <el-button size="small" :loading="testingId === provider.id" @click="testConnection(provider)">测试</el-button>
            <el-tag :type="probeTagType(provider.id)" size="small" class="probe-status">{{ probeLabel(provider.id) }}</el-tag>
          </span>
          <div class="provider-actions">
            <el-button v-if="!provider.isDefault" size="small" @click="setDefault(provider)">设为默认</el-button>
            <el-button size="small" @click="openEdit(provider)">编辑</el-button>
            <el-button size="small" type="danger" text @click="remove(provider)">删除</el-button>
          </div>
        </div>
      </div>

      <el-card v-if="!loadFailed && providers.length" shadow="never">
        <template #header>
          <div class="header-row">
            <strong>按用途覆盖默认模型（可选）</strong>
            <el-button v-if="purposesFailed" text type="primary" size="small" @click="loadPurposes">重试</el-button>
          </div>
        </template>
        <div v-for="purpose in AI_PURPOSES" :key="purpose.value" class="override-row setting-row">
          <span class="override-label">{{ purpose.label }}</span>
          <el-select v-model="overrides[purpose.value]" placeholder="使用默认" clearable :disabled="purposesFailed" @change="saveOverride(purpose.value)">
            <el-option v-if="overrideExtras[purpose.value]" :key="'assigned'" :label="overrideExtras[purpose.value]!.label" :value="overrideExtras[purpose.value]!.id" disabled />
            <el-option v-if="zen?.connected" :key="'zen'" :label="`OpenCode Zen Free · ${zen?.modelName ?? ''}`" :value="zenProviderId" />
            <el-option v-if="kilo?.connected" :key="'kilo'" :label="`Kilo Gateway Free · ${kilo?.modelName ?? ''}`" :value="kiloProviderId" />
            <el-option v-for="p in customProviders.filter((c) => c.enabled)" :key="p.id" :label="p.name" :value="p.id" />
          </el-select>
        </div>
      </el-card>

      <el-alert
        type="info"
        show-icon
        :closable="false"
        title="个人聊天模型与系统 Embedding 是两套配置"
        description="此页只管理你个人用于知识问答、规划与 Agent 的聊天模型。知识库向量检索使用的 Embedding 属于系统级配置，由系统管理员单独管理，与本页的个人 Provider 状态无关。"
      />
      <p v-if="auth.currentUser?.systemAdmin" class="admin-link">
        <router-link to="/admin/embedding">前往系统 Embedding 管理</router-link>
      </p>
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

<style scoped>
.ai-load-error {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  background: var(--color-danger-soft); border: 1px solid var(--color-danger);
  border-radius: var(--radius-card); padding: 16px 20px;
}
.ai-load-error strong { font-size: 14px; }
.ai-load-error p { margin: 2px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.ai-config-summary { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.purpose-row { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 4px 12px; align-items: center; padding: 8px 0; }
.purpose-row + .purpose-row { border-top: 1px solid var(--color-border); }
.purpose-status { grid-column: 2; color: var(--color-text-secondary); font-size: 12px; }
.purpose-status--blocked { color: var(--color-danger); }
.purpose-failure { margin: 0 0 8px; color: var(--color-danger); font-size: 13px; }
.purpose-empty { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.provider-probe { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.probe-status { flex: none; }
.admin-link { margin: 0; font-size: 13px; }
@media (max-width: 760px) {
  .purpose-row { grid-template-columns: 1fr; }
  .purpose-status { grid-column: 1; }
}
</style>
