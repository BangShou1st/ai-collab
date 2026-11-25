<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Close, Cpu, Delete, Edit, Plus, Refresh, Share, VideoPlay,
} from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import {
  adminAiProviderApi,
  adminModelProfileApi,
  type AdminModelProfile,
  type AdminSharedAiProvider,
  type DiscoveredModelMetadata,
  type ModelProfileSource,
} from '../../api/admin-ai-provider-api'
import type { UserAiProviderPayload } from '../ai/user-ai-api'
import {
  DEFAULT_MAX_OUTPUT_TOKENS,
  formatDiscoveredModelOption,
  resolveAutoMaxOutputTokens,
  shouldAutoFillMaxOutputTokens,
} from '../ai/model-output-defaults'
import EmbeddingAdminContent from './components/EmbeddingAdminContent.vue'
import RerankerAdminContent from './components/RerankerAdminContent.vue'

const route = useRoute()
const router = useRouter()

const activeTab = ref<'shared' | 'embedding' | 'reranker'>('shared')
const loading = ref(false)
const providers = ref<AdminSharedAiProvider[]>([])
const modelProfiles = ref<AdminModelProfile[]>([])

// 弹窗表单状态
const dialogVisible = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<string | null>(null)
const submitting = ref(false)
const testingId = ref<string | null>(null)
const editingHasApiKey = ref(false)
const discovering = ref(false)
const discoveredModels = ref<DiscoveredModelMetadata[]>([])
let maxOutputManual = false
let initialEditingModel = ''

function markMaxOutputManual(): void {
  maxOutputManual = true
}

const form = reactive<UserAiProviderPayload>({
  name: '',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: '',
  apiPath: '/v1/chat/completions',
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.7,
  maxOutputTokens: DEFAULT_MAX_OUTPUT_TOKENS,
  capabilities: ['CHAT', 'STREAMING'],
})

const profileForm = reactive<{
  contextWindowTokens: number | null
  source: ModelProfileSource
}>({
  contextWindowTokens: null,
  source: 'ADMIN_VERIFIED',
})

watch(
  () => route.query.tab,
  (tab) => {
    if (tab === 'embedding') activeTab.value = 'embedding'
    else if (tab === 'reranker') activeTab.value = 'reranker'
    else activeTab.value = 'shared'
  },
  { immediate: true },
)

function onTabChange(tabName: string): void {
  router.replace({ query: { ...route.query, tab: tabName } })
}

async function loadProviders(): Promise<void> {
  loading.value = true
  try {
    const [providerRes, profileRes] = await Promise.all([
      adminAiProviderApi.list(),
      adminModelProfileApi.list().catch(() => ({ data: [] as AdminModelProfile[] })),
    ])
    providers.value = providerRes.data
    modelProfiles.value = profileRes.data ?? []
  } catch (err) {
    showApiError(err, 'AI 供应商列表加载')
  } finally {
    loading.value = false
  }
}

function openCreateDialog(): void {
  dialogMode.value = 'create'
  editingId.value = null
  editingHasApiKey.value = false
  discoveredModels.value = []
  maxOutputManual = false
  initialEditingModel = ''
  Object.assign(form, {
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    baseUrl: 'https://api.deepseek.com',
    apiPath: '/v1/chat/completions',
    apiKey: '',
    modelName: 'deepseek-chat',
    enabled: true,
    temperature: 0.7,
    maxOutputTokens: DEFAULT_MAX_OUTPUT_TOKENS,
    capabilities: ['CHAT', 'STREAMING'],
  })
  Object.assign(profileForm, {
    contextWindowTokens: null,
    source: 'ADMIN_VERIFIED',
  })
  dialogVisible.value = true
}

function openEditDialog(provider: AdminSharedAiProvider): void {
  dialogMode.value = 'edit'
  editingId.value = provider.id
  editingHasApiKey.value = provider.hasApiKey
  discoveredModels.value = []
  maxOutputManual = false
  initialEditingModel = provider.modelName
  Object.assign(form, {
    name: provider.name,
    providerType: provider.providerType,
    baseUrl: provider.baseUrl,
    apiPath: provider.apiPath,
    apiKey: '', // 留空表示保持原有密钥
    modelName: provider.modelName,
    enabled: provider.enabled,
    temperature: provider.temperature,
    maxOutputTokens: provider.maxOutputTokens,
    capabilities: [...provider.capabilities],
  })
  Object.assign(profileForm, {
    contextWindowTokens: provider.contextWindowTokens ?? null,
    source: (provider.contextWindowSource as ModelProfileSource | undefined) ?? 'ADMIN_VERIFIED',
  })
  dialogVisible.value = true
}

const canDiscoverModels = computed(() => Boolean(
  (form.apiKey ?? '').trim() || (dialogMode.value === 'edit' && editingHasApiKey.value),
))

const selectedModelMetadata = computed(() =>
  discoveredModels.value.find((m) => m.id === form.modelName.trim()) ?? null,
)
const recommendedMaxOutputTokens = computed(() => resolveAutoMaxOutputTokens(selectedModelMetadata.value))

function applyRecommendedMaxOutput(): void {
  form.maxOutputTokens = recommendedMaxOutputTokens.value
  maxOutputManual = false
}

function applyDiscoveredMetadata(meta?: DiscoveredModelMetadata | null, forceApplyOutput = false): void {
  if (meta?.contextWindowTokens && meta.contextWindowTokens > 0) {
    profileForm.contextWindowTokens = meta.contextWindowTokens
    profileForm.source = 'PROVIDER_DOCUMENT'
  }
  const modelChanged = dialogMode.value === 'edit' && form.modelName.trim() !== initialEditingModel
  if (forceApplyOutput) {
    form.maxOutputTokens = resolveAutoMaxOutputTokens(meta)
    maxOutputManual = false
  } else if (shouldAutoFillMaxOutputTokens({
    mode: dialogMode.value,
    manuallyEdited: maxOutputManual,
    currentValue: form.maxOutputTokens,
    modelChanged,
    meta,
  })) {
    form.maxOutputTokens = resolveAutoMaxOutputTokens(meta)
  }
  if (meta?.supportsTools && !form.capabilities.includes('NATIVE_TOOLS')) {
    if (dialogMode.value === 'create' || modelChanged) {
      form.capabilities = [...form.capabilities, 'NATIVE_TOOLS']
    }
  }
}

watch(
  () => form.modelName,
  (nextName) => {
    const matched = discoveredModels.value.find((m) => m.id === nextName.trim()) ?? null
    if (matched || (dialogMode.value === 'edit' && nextName.trim() !== initialEditingModel)) {
      applyDiscoveredMetadata(matched, false)
    }
  },
)

async function discoverModels(): Promise<void> {
  if (discovering.value || !canDiscoverModels.value) return
  discovering.value = true
  try {
    let list: DiscoveredModelMetadata[]
    if (dialogMode.value === 'edit' && editingId.value && !(form.apiKey ?? '').trim()) {
      list = (await adminAiProviderApi.modelMetadata(editingId.value, true)).data ?? []
    } else {
      list = (await adminAiProviderApi.discoverModelMetadata({
        providerType: form.providerType,
        baseUrl: form.baseUrl.trim(),
        apiPath: form.apiPath.trim(),
        apiKey: (form.apiKey ?? '').trim(),
      })).data ?? []
    }
    discoveredModels.value = list
    if (list.length) {
      const matched = list.find((m) => m.id === form.modelName.trim()) ?? null
      if (matched) applyDiscoveredMetadata(matched, false)
      ElMessage.success(`发现 ${list.length} 个模型`)
    } else {
      ElMessage.warning('模型列表为空，请手动填写模型 ID')
    }
  } catch (err) {
    showApiError(err, '模型列表获取，可手动填写模型 ID')
  } finally {
    discovering.value = false
  }
}

async function submitForm(): Promise<void> {
  if (!form.name || !form.baseUrl || !form.modelName) {
    ElMessage.warning('请填写供应商名称、Base URL 与模型名称')
    return
  }
  if (dialogMode.value === 'create' && !form.apiKey) {
    ElMessage.warning('新建供应商必须填写 API Key')
    return
  }
  submitting.value = true
  try {
    const payload: UserAiProviderPayload = {
      ...form,
      name: form.name.trim(),
      baseUrl: form.baseUrl.trim(),
      apiPath: form.apiPath.trim(),
      modelName: form.modelName.trim(),
      maxOutputTokens: Math.min(131072, Math.max(1, Math.round(form.maxOutputTokens || DEFAULT_MAX_OUTPUT_TOKENS))),
    }
    if (dialogMode.value === 'create') {
      await adminAiProviderApi.create(payload)
      ElMessage.success('AI 供应商已创建')
    } else if (editingId.value) {
      await adminAiProviderApi.update(editingId.value, payload)
      ElMessage.success('供应商配置已更新')
    }
    if (profileForm.contextWindowTokens && profileForm.contextWindowTokens > 0) {
      await adminModelProfileApi.upsert({
        providerType: payload.providerType,
        baseUrl: payload.baseUrl,
        apiPath: payload.apiPath,
        modelName: payload.modelName,
        contextWindowTokens: Math.round(profileForm.contextWindowTokens),
        maxOutputTokens: payload.maxOutputTokens,
        supportsUsage: true,
        source: profileForm.source,
      })
    }
    dialogVisible.value = false
    await loadProviders()
  } catch (err) {
    showApiError(err, '保存供应商配置')
  } finally {
    submitting.value = false
  }
}

async function deleteModelProfile(profile: AdminModelProfile): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除模型「${profile.modelName}」的上下文窗口档案吗？删除后该模型将回退至 50,000 token 保守模式。`,
      '删除模型窗口档案',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await adminModelProfileApi.remove(profile.id)
    ElMessage.success('模型窗口档案已删除')
    await loadProviders()
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '删除模型窗口档案')
  }
}

async function testConnection(provider: AdminSharedAiProvider): Promise<void> {
  testingId.value = provider.id
  try {
    const res = await adminAiProviderApi.test(provider.id)
    ElMessage.success({
      message: `连接成功！模型响应：${res.data?.content || '通信正常'}`,
      duration: 3500,
    })
  } catch (err) {
    showApiError(err, `测试「${provider.name}」连接`)
  } finally {
    testingId.value = null
  }
}

async function publishShared(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await adminAiProviderApi.publish(provider.id)
    ElMessage.success(`供应商「${provider.name}」已发布为全站共享`)
    await loadProviders()
  } catch (err) {
    showApiError(err, '发布全站共享')
  }
}

async function revokeShared(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认撤销供应商「${provider.name}」的全站共享吗？普通用户将无法继续以此共享模型发起新请求。`,
      '撤销全站共享',
      { confirmButtonText: '确认撤销', cancelButtonText: '取消', type: 'warning' },
    )
    await adminAiProviderApi.revoke(provider.id)
    ElMessage.success(`供应商「${provider.name}」的全站共享已撤销`)
    await loadProviders()
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '撤销全站共享')
  }
}

async function deleteProvider(provider: AdminSharedAiProvider): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除供应商「${provider.name}」吗？若已发布全站共享将同步撤销。`,
      '删除供应商',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await adminAiProviderApi.delete(provider.id)
    ElMessage.success('供应商已删除')
    await loadProviders()
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '删除供应商')
  }
}

function formatTokens(value: number | null | undefined): string {
  if (!value) return '未设置'
  return value.toLocaleString('zh-CN')
}

function profileSourceLabel(source: string | null | undefined): string {
  if (source === 'PROVIDER_DOCUMENT') return '服务商元数据'
  if (source === 'ADMIN_VERIFIED') return '管理员核实'
  if (source === 'LOCAL_FILE') return '本地模型文件'
  if (source === 'UNVERIFIED') return '未核实'
  return '保守回退'
}

onMounted(loadProviders)
</script>

<template>
  <div class="providers-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">AI 供应商</h1>
        <p class="page-desc">添加、验证及发布全站共享的大模型，统一管理上下文窗口与输出上限</p>
      </div>
      <div v-if="activeTab === 'shared'" class="header-actions">
        <el-button :icon="Refresh" @click="loadProviders">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreateDialog">添加供应商</el-button>
      </div>
    </header>

    <!-- Tab 切换 -->
    <el-tabs v-model="activeTab" class="admin-tabs" @tab-change="onTabChange">
      <el-tab-pane label="全站 AI 供应商" name="shared">
        <div v-loading="loading" class="provider-list-area">
          <div v-if="providers.length === 0" class="empty-box">
            <el-icon class="empty-icon"><Cpu /></el-icon>
            <p>暂未添加任何 AI 供应商</p>
            <el-button type="primary" :icon="Plus" @click="openCreateDialog">添加首个供应商</el-button>
          </div>

          <div v-else class="provider-grid">
            <div v-for="item in providers" :key="item.id" class="provider-card" :class="{ 'is-shared': item.isShared }">
              <div class="card-main">
                <div class="card-top">
                  <div class="title-group">
                    <strong class="provider-title">{{ item.name }}</strong>
                    <el-tag size="small" effect="plain">{{ item.providerType }}</el-tag>
                    <el-tag v-if="item.isShared" type="success" size="small" effect="dark">全站共享</el-tag>
                    <el-tag v-else-if="item.sharedStatus === 'REVOKED'" type="info" size="small">已撤销共享</el-tag>
                    <el-tag v-else type="warning" size="small">仅私有</el-tag>
                  </div>
                  <el-tag :type="item.enabled ? 'success' : 'info'" size="small">
                    {{ item.enabled ? '已启用' : '已停用' }}
                  </el-tag>
                </div>

                <div class="meta-rows">
                  <div class="meta-row">
                    <span class="meta-label">模型名称</span>
                    <code class="meta-code">{{ item.modelName }}</code>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">端点地址</span>
                    <span class="meta-text text-truncate" :title="item.baseUrl">{{ item.baseUrl }}</span>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">能力声明</span>
                    <div class="cap-tags">
                      <el-tag v-for="cap in item.capabilities" :key="cap" size="small" type="info" effect="plain">
                        {{ cap === 'NATIVE_TOOLS' ? '工具调用' : (cap === 'STREAMING' ? '流式' : '对话') }}
                      </el-tag>
                    </div>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">单次最大输出 / 采样温度</span>
                    <div class="inline-meta">
                      <span class="meta-text">{{ formatTokens(item.maxOutputTokens) }} tokens</span>
                      <el-tag v-if="item.maxOutputTokens < 2048" size="small" type="warning">偏低易截断</el-tag>
                      <span class="meta-sub">· T={{ item.temperature }}</span>
                    </div>
                  </div>
                  <div class="meta-row">
                    <span class="meta-label">上下文窗口档案</span>
                    <div class="inline-meta">
                      <template v-if="item.contextWindowTokens">
                        <span class="meta-text">{{ formatTokens(item.contextWindowTokens) }} tokens</span>
                        <el-tag size="small" :type="item.contextWindowVerified ? 'success' : 'warning'" effect="plain">
                          {{ profileSourceLabel(item.contextWindowSource) }}
                        </el-tag>
                      </template>
                      <template v-else>
                        <el-tag size="small" type="warning" effect="plain">未知（50,000 保守估算）</el-tag>
                      </template>
                    </div>
                  </div>
                </div>
              </div>

              <!-- 操作按钮栏 -->
              <div class="card-actions">
                <el-button
                  size="small"
                  :icon="VideoPlay"
                  :loading="testingId === item.id"
                  @click="testConnection(item)"
                >
                  测试连接
                </el-button>

                <el-button
                  v-if="!item.isShared"
                  size="small"
                  type="success"
                  plain
                  :icon="Share"
                  @click="publishShared(item)"
                >
                  发布为全站共享
                </el-button>
                <el-button
                  v-else
                  size="small"
                  type="warning"
                  plain
                  :icon="Close"
                  @click="revokeShared(item)"
                >
                  撤销共享
                </el-button>

                <el-button size="small" :icon="Edit" @click="openEditDialog(item)">编辑</el-button>
                <el-button size="small" type="danger" text :icon="Delete" @click="deleteProvider(item)">删除</el-button>
              </div>
            </div>
          </div>

          <section class="profile-section" aria-label="模型上下文窗口档案">
            <div class="profile-header">
              <div>
                <h2 class="profile-title">模型上下文窗口档案（ai_model_profile）</h2>
                <p class="profile-desc">
                  Agent 运行时按「协议 + Base URL + API Path + 模型 ID」精确读取对应窗口容量；未配置档案将按 50,000 保守模式运行。
                </p>
              </div>
            </div>
            <div v-if="modelProfiles.length === 0" class="profile-empty">
              暂无已登记的模型上下文窗口档案，可在上方编辑供应商并从模型列表自动获取或手动填写。
            </div>
            <div v-else class="profile-list">
              <div v-for="profile in modelProfiles" :key="profile.id" class="profile-row">
                <div class="profile-main">
                  <code class="meta-code">{{ profile.modelName }}</code>
                  <el-tag size="small" effect="plain">{{ profile.providerType }}</el-tag>
                  <el-tag size="small" :type="profile.verified ? 'success' : 'warning'" effect="plain">
                    {{ profileSourceLabel(profile.source) }}
                  </el-tag>
                  <span class="meta-text">窗口 {{ formatTokens(profile.contextWindowTokens) }} tokens</span>
                  <span v-if="profile.maxOutputTokens" class="meta-sub">· 最大输出 {{ formatTokens(profile.maxOutputTokens) }}</span>
                  <span class="meta-sub text-truncate" :title="`${profile.baseUrl}${profile.apiPath}`">
                    · {{ profile.baseUrl }}
                  </span>
                </div>
                <el-button
                  size="small"
                  type="danger"
                  text
                  :icon="Delete"
                  @click="deleteModelProfile(profile)"
                >
                  移除
                </el-button>
              </div>
            </div>
          </section>
        </div>
      </el-tab-pane>

      <el-tab-pane label="系统 Embedding 与索引" name="embedding">
        <EmbeddingAdminContent />
      </el-tab-pane>

      <el-tab-pane label="Reranker 管理" name="reranker">
        <RerankerAdminContent />
      </el-tab-pane>
    </el-tabs>

    <!-- 添加 / 编辑配置弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? '添加 AI 供应商' : '编辑供应商配置'"
      width="580px"
      destroy-on-close
    >
      <el-form label-position="top">
        <el-form-item label="供应商显示名称" required>
          <el-input v-model="form.name" placeholder="如 DeepSeek V3 官方" />
        </el-form-item>

        <el-form-item label="供应商协议类型" required>
          <el-select v-model="form.providerType" style="width: 100%">
            <el-option label="OpenAI 兼容协议（DeepSeek, Moonshot, Qwen 等）" value="OPENAI_COMPATIBLE" />
            <el-option label="Anthropic Claude" value="ANTHROPIC" />
            <el-option label="Google Gemini" value="GEMINI" />
          </el-select>
        </el-form-item>

        <el-form-item label="Base URL（必须为公网 HTTPS）" required>
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>

        <el-form-item label="API Path">
          <el-input v-model="form.apiPath" placeholder="/v1/chat/completions" />
        </el-form-item>

        <el-form-item :label="dialogMode === 'create' ? 'API Key（密钥加密存储）' : '更新 API Key（留空保持不变）'" :required="dialogMode === 'create'">
          <el-input v-model="form.apiKey" type="password" show-password placeholder="sk-..." />
        </el-form-item>

        <el-form-item label="模型名称（Model ID）" required>
          <div class="model-field">
            <el-select
              v-if="discoveredModels.length"
              v-model="form.modelName"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入模型 ID"
            >
              <el-option
                v-for="model in discoveredModels"
                :key="model.id"
                :label="formatDiscoveredModelOption(model)"
                :value="model.id"
              />
            </el-select>
            <el-input v-else v-model="form.modelName" placeholder="如 deepseek-chat 或 gpt-4o" />
            <el-button
              text
              :icon="Refresh"
              :loading="discovering"
              :disabled="!canDiscoverModels"
              aria-label="获取模型列表"
              title="获取模型列表及窗口元数据"
              @click="discoverModels"
            />
          </div>
          <span class="field-hint">
            填写 API Key 后可获取模型列表并自动识别上下文窗口；编辑时 Key 留空将使用已保存凭据。
          </span>
          <div v-if="selectedModelMetadata" class="metadata-banner" data-test="discovered-model-metadata">
            <template v-if="selectedModelMetadata.metadataAvailable">
              <span>
                发现元数据：
                {{ selectedModelMetadata.contextWindowTokens ? `上下文 ${formatTokens(selectedModelMetadata.contextWindowTokens)} tokens` : '上下文未上报' }}
                ·
                {{ selectedModelMetadata.maxOutputTokens ? `建议输出上限 ${formatTokens(selectedModelMetadata.maxOutputTokens)}` : '输出上限未上报（按 8,192 兜底）' }}
                <template v-if="selectedModelMetadata.supportsTools"> · 支持工具调用</template>
                <template v-if="selectedModelMetadata.supportsReasoning"> · 支持推理</template>
              </span>
              <el-button
                v-if="selectedModelMetadata.maxOutputTokens && selectedModelMetadata.maxOutputTokens !== form.maxOutputTokens"
                size="small"
                text
                type="primary"
                @click="applyDiscoveredMetadata(selectedModelMetadata, true)"
              >
                应用推荐输出上限
              </el-button>
            </template>
            <span v-else>该上游 `/models` 未返回上下文窗口与输出上限，输出上限已按 {{ DEFAULT_MAX_OUTPUT_TOKENS.toLocaleString('zh-CN') }} 自动兜底，可在下方手动调整。</span>
          </div>
        </el-form-item>

        <el-alert
          v-if="form.maxOutputTokens > 0 && form.maxOutputTokens < 2048"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          :title="`当前单次最大输出 (${form.maxOutputTokens}) 偏低，长回答或工具归纳极易被截断`"
        >
          <template #default>
            <el-button size="small" type="warning" plain @click="applyRecommendedMaxOutput">
              一键更新为 {{ recommendedMaxOutputTokens.toLocaleString('zh-CN') }}
            </el-button>
          </template>
        </el-alert>

        <div class="form-row-2">
          <el-form-item label="单次最大输出 (Max Output Tokens)" required>
            <el-input-number
              v-model="form.maxOutputTokens"
              :min="1"
              :max="131072"
              :step="256"
              style="width: 100%"
              @update:model-value="markMaxOutputManual"
            />
            <span class="field-hint">默认 {{ DEFAULT_MAX_OUTPUT_TOKENS }}；支持探测时按模型自动填充，手动修改后保持自定值</span>
          </el-form-item>

          <el-form-item label="采样温度 (Temperature)">
            <el-input-number
              v-model="form.temperature"
              :min="0"
              :max="2"
              :step="0.1"
              :precision="2"
              style="width: 100%"
            />
          </el-form-item>
        </div>

        <div class="form-row-2">
          <el-form-item label="上下文窗口档案 (Context Window Tokens，可选)">
            <el-input-number
              v-model="profileForm.contextWindowTokens"
              :min="0"
              :max="10485760"
              :step="1024"
              placeholder="留空或 0 表示暂不绑定"
              style="width: 100%"
            />
            <span class="field-hint">配置后将写入模型窗口档案，不再回落至 50,000 保守模式</span>
          </el-form-item>

          <el-form-item label="窗口核实来源">
            <el-select v-model="profileForm.source" style="width: 100%">
              <el-option label="管理员核实 (ADMIN_VERIFIED)" value="ADMIN_VERIFIED" />
              <el-option label="服务商自动发现 (PROVIDER_DOCUMENT)" value="PROVIDER_DOCUMENT" />
              <el-option label="待核实估算 (UNVERIFIED)" value="UNVERIFIED" />
            </el-select>
          </el-form-item>
        </div>

        <el-form-item label="支持的能力">
          <el-checkbox-group v-model="form.capabilities">
            <el-checkbox value="CHAT">基础对话 (CHAT)</el-checkbox>
            <el-checkbox value="STREAMING">流式输出 (STREAMING)</el-checkbox>
            <el-checkbox value="NATIVE_TOOLS">工具调用 (NATIVE_TOOLS)</el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="启用状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitForm">确认保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.providers-container {
  max-width: 1200px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  margin: 0 0 4px;
}
.page-desc {
  font-size: 13px;
  color: #6b7280;
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 10px;
}

.provider-list-area {
  padding-top: 12px;
}
.empty-box {
  background: #ffffff;
  border: 1px dashed #d1d5db;
  border-radius: 12px;
  padding: 48px 24px;
  text-align: center;
  color: #6b7280;
}
.empty-icon {
  font-size: 40px;
  color: #9ca3af;
  margin-bottom: 12px;
}

.provider-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.provider-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 12px;
  overflow: hidden;
  transition: all 0.2s ease;
}
.provider-card.is-shared {
  border-left: 4px solid #16a34a;
}
.card-main {
  padding: 18px 20px 14px;
}
.card-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 14px;
}
.title-group {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.provider-title {
  font-size: 16px;
  font-weight: 600;
  color: #111827;
}

.meta-rows {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}
.meta-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.meta-label {
  font-size: 11px;
  color: #6b7280;
  font-weight: 500;
}
.meta-code {
  font-family: monospace;
  font-size: 13px;
  color: #1f2937;
  background: #f3f4f6;
  padding: 2px 6px;
  border-radius: 4px;
  width: fit-content;
}
.meta-text {
  font-size: 13px;
  color: #374151;
}
.meta-sub {
  font-size: 12px;
  color: #6b7280;
}
.inline-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.text-truncate {
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cap-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.model-field {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}
.model-field :deep(.el-select),
.model-field :deep(.el-input) {
  flex: 1;
  min-width: 0;
}
.model-field :deep(.el-button) {
  flex-shrink: 0;
}
.field-hint {
  display: block;
  margin-top: 4px;
  color: var(--el-text-color-secondary, #909399);
  font-size: 12px;
  line-height: 1.45;
}
.metadata-banner {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  font-size: 12px;
  color: #0369a1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}
.form-row-2 {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.card-actions {
  background: #f9fafb;
  border-top: 1px solid #f3f4f6;
  padding: 10px 20px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.profile-section {
  margin-top: 24px;
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 12px;
  padding: 16px 20px;
}
.profile-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  color: #111827;
}
.profile-desc {
  margin: 0 0 12px;
  font-size: 12px;
  color: #6b7280;
}
.profile-empty {
  font-size: 13px;
  color: #6b7280;
  padding: 12px 0;
}
.profile-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.profile-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid #f3f4f6;
  border-radius: 8px;
  background: #f9fafb;
}
.profile-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  min-width: 0;
}

@media (max-width: 768px) {
  .meta-rows,
  .form-row-2 {
    grid-template-columns: 1fr;
    gap: 8px;
  }
  .card-actions {
    justify-content: flex-start;
  }
}
</style>
