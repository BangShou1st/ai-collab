<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { userAiApi, type AiPurpose, type ProviderProfile, type UserAiProvider } from './user-ai-api'
import { aiPresetApi, type PresetStatus } from './ai-preset-api'
import { loadProviderProfiles } from './provider-profiles'
import { usePresetConnection } from './use-preset-connection'
import ProviderFormDialog from './ProviderFormDialog.vue'
import CapabilitySuitePanel from './CapabilitySuitePanel.vue'
import {
  AI_PURPOSES,
  READINESS_LABELS,
  purposeReadiness,
  probeResultsFromViews,
  resolvePurposeStatuses,
  type ProbeMode,
  type ProbeResultState,
  type PurposeReadiness,
  type PurposeStatus,
} from './ai-purpose-status'

const auth = useAuthStore()
const providers = ref<UserAiProvider[]>([])
const loading = ref(false)
/** 配置加载失败：状态未知，不得显示为“未配置”。 */
const loadFailed = ref(false)
const zen = ref<PresetStatus | null>(null)
const kilo = ref<PresetStatus | null>(null)
const profiles = ref<ProviderProfile[]>([])
const dialogVisible = ref(false)
const editing = ref<UserAiProvider | null>(null)
const purposesFailed = ref(false)
/** 落库探针结果（providerId → 各模式最新结论，含 STALE 判定）。 */
const persistedProbes = reactive<Record<string, Record<ProbeMode, ProbeResultState | null>>>({})
const overrides = reactive<Record<AiPurpose, string>>({
  KNOWLEDGE_CHAT: '',
  PLANNING: '',
  AGENT: '',
})
/** 递增请求令牌：晚到的加载响应不得覆盖新状态或回填旧用户数据。 */
let loadToken = 0
/** 当前已加载数据的账号；变化时丢弃旧数据并重新加载。 */
let ownerUserId: string | null = null

const customProviders = computed(() => providers.value.filter((p) => !p.presetCode))
const enabledProviderCount = computed(() => providers.value.filter((p) => p.enabled).length)
const zenProvider = computed(() => providers.value.find((p) => p.presetCode === 'OPENCODE_ZEN_FREE') ?? null)
const kiloProvider = computed(() => providers.value.find((p) => p.presetCode === 'KILO_GATEWAY_FREE') ?? null)
const purposeStatuses = computed<PurposeStatus[]>(() => resolvePurposeStatuses(providers.value, overrides))

/** 用途就绪 = 配置解析 × 能力声明 × 探针证据；CONNECTIVITY 通过不外推到任何用途。 */
function readinessOf(status: PurposeStatus): PurposeReadiness | null {
  return purposeReadiness(status.purpose, status.provider, persistedProbes[status.provider?.id ?? ''] ?? probeResultsFromViews([]))
}

function readinessTagType(readiness: PurposeReadiness | null): 'success' | 'danger' | 'warning' | 'info' {
  if (readiness === 'READY') return 'success'
  if (readiness === 'UNAVAILABLE') return 'danger'
  if (readiness === 'UNVERIFIED' || readiness === 'NOT_SUPPORTED') return 'warning'
  return 'info'
}

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

/** 用途选择器的选项组：两张卡片（解析状态/覆盖）共用同一组装配逻辑。 */
function purposeOptions(purpose: AiPurpose) {
  const extra = overrideExtras.value[purpose]
  return {
    extras: extra ? [{ label: extra.label, value: extra.id }] : [],
    zen: zen.value?.connected && zenProvider.value
      ? [{ label: `OpenCode Zen Free · ${zen.value?.modelName ?? ''}`, value: zenProvider.value.id }]
      : [],
    kilo: kilo.value?.connected && kiloProvider.value
      ? [{ label: `Kilo Gateway Free · ${kilo.value?.modelName ?? ''}`, value: kiloProvider.value.id }]
      : [],
    custom: customProviders.value.filter((c) => c.enabled).map((c) => ({ label: c.name, value: c.id })),
  }
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
  return `${stale}${source}：${name} · 已启用`
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

/** 拉取全部 Provider 的落库探针结果（只读查询，不触发任何模型请求）。 */
async function loadPersistedProbes(): Promise<void> {
  await Promise.all(providers.value.map(async (provider) => {
    try {
      const result = await userAiApi.probeResults(provider.id)
      persistedProbes[provider.id] = probeResultsFromViews(result.data ?? [])
    } catch {
      // 单个 Provider 的历史结果读取失败不阻塞整页
    }
  }))
}

async function load(): Promise<void> {
  const token = ++loadToken
  loading.value = true
  loadFailed.value = false
  try {
    const [custom, presetList, profileList] = await Promise.all([
      userAiApi.list(),
      aiPresetApi.presets(),
      loadProviderProfiles(),
    ])
    if (token !== loadToken) return
    providers.value = custom.data
    zen.value = presetList.data.find((p) => p.code === 'OPENCODE_ZEN_FREE') ?? null
    kilo.value = presetList.data.find((p) => p.code === 'KILO_GATEWAY_FREE') ?? null
    profiles.value = profileList
    zenConnection.syncFromStatus(zen.value, '')
    kiloConnection.syncFromStatus(kilo.value, '')
    await loadPurposes()
    await loadPersistedProbes()
  } catch (error) {
    if (token !== loadToken) return
    loadFailed.value = true
    showApiError(error, 'AI 配置加载')
  } finally {
    if (token === loadToken) loading.value = false
  }
  if (token !== loadToken) return
  // 免费模型列表走独立 skeleton，不阻塞整页：先让预设配置/模型/覆盖可见
  await Promise.all([zenConnection.loadModels(false), kiloConnection.loadModels(false)])
}

/** 能力套件完成后刷新落库结论（用途就绪随之更新）。 */
async function onSuiteCompleted(): Promise<void> {
  await loadPersistedProbes()
}

const zenConnection = usePresetConnection({
  code: 'OPENCODE_ZEN_FREE',
  displayName: 'OpenCode Zen Free',
  supportsApiKey: true,
  disconnectWarning: '断开后将删除已保存的 Zen API Key，用途覆盖会回退到默认模型。确定断开吗？',
  onSaved: load,
})

const kiloConnection = usePresetConnection({
  code: 'KILO_GATEWAY_FREE',
  displayName: 'Kilo Gateway Free',
  supportsApiKey: false,
  disconnectWarning: '断开后将删除已保存的 Kilo Gateway 连接，用途覆盖会回退到默认模型。确定断开吗？',
  onSaved: load,
})

function openCreate(): void {
  editing.value = null
  dialogVisible.value = true
}

function openEdit(provider: UserAiProvider): void {
  editing.value = provider
  dialogVisible.value = true
}

async function onFormSaved(): Promise<void> {
  await load()
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
  } catch (error) {
    showApiError(error, 'AI 配置删除')
  }
}

async function setDefault(provider: UserAiProvider): Promise<void> {
  try {
    await userAiApi.setDefault(provider.id)
    await load()
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
  } catch (error) {
    showApiError(error, '用途覆盖保存')
  }
}

onMounted(() => {
  ownerUserId = auth.currentUser?.id ?? null
  void load()
})

// 账号切换（登录/退出/换人）时丢弃上一个用户的配置、覆盖与测试结果，绝不显示他人的 Provider。
auth.$subscribe(() => {
  const currentId = auth.currentUser?.id ?? null
  if (currentId === ownerUserId) return
  ownerUserId = currentId
  loadToken += 1
  providers.value = []
  zen.value = null
  kilo.value = null
  overrides.KNOWLEDGE_CHAT = ''
  overrides.PLANNING = ''
  overrides.AGENT = ''
  for (const key of Object.keys(persistedProbes)) delete persistedProbes[key]
  if (currentId) void load()
})
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
            <strong>按用途的解析与就绪状态</strong>
            <el-button v-if="purposesFailed" text type="primary" size="small" @click="loadPurposes">重试</el-button>
          </div>
        </template>
        <p v-if="purposesFailed" class="purpose-failure" role="alert">用途覆盖加载失败，暂无法确认各用途解析结果。请点击“重试”后再判断。</p>
        <template v-else>
          <p v-if="!providers.length" class="purpose-empty">还没有保存任何模型配置，下列用途均不可用。</p>
          <div v-for="status in purposeStatuses" :key="status.purpose" class="purpose-row">
            <span class="override-label">{{ purposeLabel(status.purpose) }}</span>
            <el-select v-model="overrides[status.purpose]" placeholder="使用默认" clearable @change="saveOverride(status.purpose)">
              <el-option
                v-for="option in purposeOptions(status.purpose).extras"
                :key="`extra-${option.value}`" :label="`${option.label}（已停用）`" :value="option.value" disabled
              />
              <el-option
                v-for="option in purposeOptions(status.purpose).zen"
                :key="`zen-${option.value}`" :label="option.label" :value="option.value"
              />
              <el-option
                v-for="option in purposeOptions(status.purpose).kilo"
                :key="`kilo-${option.value}`" :label="option.label" :value="option.value"
              />
              <el-option
                v-for="option in purposeOptions(status.purpose).custom"
                :key="option.value" :label="option.label" :value="option.value"
              />
            </el-select>
            <span class="purpose-status" :class="{ 'purpose-status--blocked': !status.resolvable }">
              {{ purposeStatusText(status) }}
              <el-tag
                v-if="status.resolvable"
                :type="readinessTagType(readinessOf(status))"
                size="small"
                class="purpose-readiness"
              >
                {{ readinessOf(status) ? READINESS_LABELS[readinessOf(status)!] : '状态未知' }}
              </el-tag>
            </span>
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
              <el-button text type="danger" size="small" :loading="zenConnection.disconnecting.value" @click="zenConnection.disconnect()">断开连接</el-button>
            </div>
            <el-skeleton v-if="zenConnection.modelsLoading.value" :rows="2" animated />
            <div v-else class="zen-form">
              <el-input v-model="zenConnection.apiKey.value" type="password" show-password placeholder="API Key（免费模型可留空；留空则保留已保存 Key）" />
              <div class="model-select-row">
                <el-select v-model="zenConnection.model.value" placeholder="选择免费模型" filterable>
                  <el-option v-for="m in zenConnection.models.value" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button text :icon="Refresh" aria-label="刷新模型" title="刷新模型" @click="zenConnection.loadModels(true)" />
              </div>
              <div class="zen-actions">
                <el-checkbox v-model="zenConnection.setDefault.value">设为默认模型</el-checkbox>
                <el-button type="primary" :loading="zenConnection.saving.value" :disabled="!zenConnection.model.value" @click="zenConnection.save()">保存连接</el-button>
              </div>
              <CapabilitySuitePanel
                v-if="zenProvider && zen?.connected"
                :provider="zenProvider"
                :admitted-models="zenConnection.models.value"
                @completed="onSuiteCompleted"
              />
              <p v-else class="preset-suite-hint">保存连接后可执行统一能力测试（真实生成 + 流式）</p>
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
              <el-button text type="danger" size="small" :loading="kiloConnection.disconnecting.value" @click="kiloConnection.disconnect()">断开连接</el-button>
            </div>
            <el-skeleton v-if="kiloConnection.modelsLoading.value" :rows="2" animated />
            <div v-else class="zen-form">
              <div class="model-select-row">
                <el-select v-model="kiloConnection.model.value" placeholder="选择免费模型" filterable>
                  <el-option v-for="m in kiloConnection.models.value" :key="m" :label="m" :value="m" />
                </el-select>
                <el-button text :icon="Refresh" aria-label="刷新模型" title="刷新模型" @click="kiloConnection.loadModels(true)" />
              </div>
              <div class="zen-actions">
                <el-checkbox v-model="kiloConnection.setDefault.value">设为默认模型</el-checkbox>
                <el-button type="primary" :loading="kiloConnection.saving.value" :disabled="!kiloConnection.model.value" @click="kiloConnection.save()">保存连接</el-button>
              </div>
              <CapabilitySuitePanel
                v-if="kiloProvider && kilo?.connected"
                :provider="kiloProvider"
                :admitted-models="kiloConnection.models.value"
                @completed="onSuiteCompleted"
              />
              <p v-else class="preset-suite-hint">保存连接后可执行统一能力测试（真实生成 + 流式）</p>
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
          <div class="provider-actions">
            <el-button v-if="!provider.isDefault" size="small" @click="setDefault(provider)">设为默认</el-button>
            <el-button size="small" @click="openEdit(provider)">编辑</el-button>
            <el-button size="small" type="danger" text @click="remove(provider)">删除</el-button>
          </div>
          <CapabilitySuitePanel :provider="provider" @completed="onSuiteCompleted" />
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
            <el-option
              v-for="option in purposeOptions(purpose.value).extras"
              :key="`extra-${option.value}`" :label="`${option.label}（已停用）`" :value="option.value" disabled
            />
            <el-option
              v-for="option in purposeOptions(purpose.value).zen"
              :key="`zen-${option.value}`" :label="option.label" :value="option.value"
            />
            <el-option
              v-for="option in purposeOptions(purpose.value).kilo"
              :key="`kilo-${option.value}`" :label="option.label" :value="option.value"
            />
            <el-option
              v-for="option in purposeOptions(purpose.value).custom"
              :key="option.value" :label="option.label" :value="option.value"
            />
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

    <ProviderFormDialog
      v-model:visible="dialogVisible"
      :editing="editing"
      :profiles="profiles"
      @saved="onFormSaved"
    />
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
.purpose-status { grid-column: 2; color: var(--color-text-secondary); font-size: 12px; display: inline-flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.purpose-status--blocked { color: var(--color-danger); }
.purpose-readiness { flex: none; }
.purpose-failure { margin: 0 0 8px; color: var(--color-danger); font-size: 13px; }
.purpose-empty { margin: 0; color: var(--color-text-secondary); font-size: 13px; }
.preset-suite-hint { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 12px; }
.admin-link { margin: 0; font-size: 13px; }
@media (max-width: 760px) {
  .purpose-row { grid-template-columns: 1fr; }
  .purpose-status { grid-column: 1; }
}
</style>
