<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import {
  DEFAULT_DECLARED_CAPABILITIES,
  profileByCode,
  profileByType,
} from './provider-profiles'
import {
  duplicateVersionJunction,
  endpointFixSuggestions,
  previewEndpoint,
} from './endpoint-rules'
import { userAiApi, type AiProviderType, type ProviderProfile, type UserAiProvider, type UserAiProviderPayload } from './user-ai-api'

/**
 * 自定义 Provider 表单：新建/编辑共用。
 *
 * 普通接入路径只需要：服务商 → API Key → 模型（可一键发现）→ 保存，
 * 之后在面板点击「检测并配置」由真实探针确定能力。
 * Base URL / API Path / Temperature / Max Output Tokens / 能力声明 / 启用
 * 全部收进可展开的高级设置——能力勾选只表示允许尝试，真实可用性以探针为准。
 *
 * 关键交互：
 * - 服务商切换时按接入档案填默认端点，但不覆盖用户已编辑的值
 *   （当前值仍等于旧档案默认值才替换）。
 * - Base URL 与 API Path 拼接处的重复版本前缀（如 /v1 + /v1/...）在保存前
 *   即给出警告与一键修正（纯本地检查，0 次请求）。
 * - 未保存配置也能一键发现模型（Key 只用于本次只读请求）；发现失败可手输模型 ID。
 */
const props = defineProps<{
  visible: boolean
  editing: UserAiProvider | null
  profiles: ProviderProfile[]
}>()
const emit = defineEmits<{
  'update:visible': [value: boolean]
  saved: []
}>()

const saving = ref(false)
const discoveredModels = ref<string[]>([])
const discovering = ref(false)
const advancedOpen = ref<string[]>([])

const form = reactive({
  name: '',
  profileCode: 'OPENAI_COMPATIBLE',
  providerType: 'OPENAI_COMPATIBLE' as AiProviderType,
  baseUrl: '',
  apiPath: '',
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.2,
  maxOutputTokens: 1200,
  capabilities: [...DEFAULT_DECLARED_CAPABILITIES],
})

/** 上一次应用默认值的档案 code：用于识别“当前值仍是默认值（用户未改）”。 */
let appliedProfileCode = 'OPENAI_COMPATIBLE'

const dialogTitle = computed(() => (props.editing ? '编辑 AI 配置' : '添加 AI 配置'))
const profile = computed(() => profileByCode(props.profiles, form.profileCode) ?? profileByType(props.profiles, form.providerType))
const canSave = computed(() =>
  Boolean(form.name.trim() && form.modelName.trim() && (form.apiKey.trim() || props.editing)))

/** 端点组合预览与重复版本前缀检查（纯本地，0 次请求）。 */
const endpointPreview = computed(() => previewEndpoint(form.baseUrl, form.apiPath))
const duplicateVersion = computed(() => duplicateVersionJunction(form.baseUrl, form.apiPath))
const fixSuggestions = computed(() => endpointFixSuggestions(form.baseUrl, form.apiPath))

function applyFix(kind: 'base' | 'path'): void {
  const suggestion = fixSuggestions.value
  if (kind === 'base' && suggestion.fixedBaseUrl) form.baseUrl = suggestion.fixedBaseUrl
  if (kind === 'path' && suggestion.fixedApiPath) form.apiPath = suggestion.fixedApiPath
}

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    discoveredModels.value = []
    advancedOpen.value = []
    if (props.editing) {
      Object.assign(form, {
        name: props.editing.name,
        profileCode: props.editing.providerType,
        providerType: props.editing.providerType,
        baseUrl: props.editing.baseUrl,
        apiPath: props.editing.apiPath,
        apiKey: '',
        modelName: props.editing.modelName,
        enabled: props.editing.enabled,
        temperature: props.editing.temperature,
        maxOutputTokens: props.editing.maxOutputTokens,
        capabilities: [...props.editing.capabilities],
      })
      appliedProfileCode = props.editing.providerType
    } else {
      resetForm()
    }
  },
  { immediate: true },
)

function resetForm(): void {
  const defaults = profileByCode(props.profiles, 'OPENAI_COMPATIBLE') ?? profileByType(props.profiles, 'OPENAI_COMPATIBLE')
  Object.assign(form, {
    name: '',
    profileCode: 'OPENAI_COMPATIBLE',
    providerType: 'OPENAI_COMPATIBLE',
    baseUrl: defaults.defaultBaseUrl,
    apiPath: defaults.defaultApiPath,
    apiKey: '',
    modelName: '',
    enabled: true,
    temperature: 0.2,
    maxOutputTokens: 1200,
    capabilities: [...DEFAULT_DECLARED_CAPABILITIES],
  })
  appliedProfileCode = 'OPENAI_COMPATIBLE'
}

/**
 * 切换服务商：仅当字段仍是旧档案的默认值（未被用户修改）时才跟随新档案，
 * 用户改过的 Base URL / API Path / 能力勾选一律保留。
 */
function onProfileChange(): void {
  const previous = profileByCode(props.profiles, appliedProfileCode) ?? profileByType(props.profiles, form.providerType)
  const next = profile.value
  if (form.baseUrl.trim() === previous.defaultBaseUrl) form.baseUrl = next.defaultBaseUrl
  if (form.apiPath.trim() === previous.defaultApiPath) form.apiPath = next.defaultApiPath
  form.providerType = next.providerType
  // 能力候选随协议变化：去掉新档案不支持的勾选项，其余保留（用户勾选不丢）。
  form.capabilities = form.capabilities.filter((capability) => next.candidateCapabilities.includes(capability))
  if (!form.capabilities.includes('CHAT')) form.capabilities.unshift('CHAT')
  appliedProfileCode = next.code
}

/** 未保存配置也能发现模型（输入 Key 后）；编辑时留空 Key 则使用已保存凭据。 */
async function discoverModels(): Promise<void> {
  if (discovering.value) return
  discovering.value = true
  try {
    if (props.editing && !form.apiKey.trim()) {
      discoveredModels.value = (await userAiApi.models(props.editing.id, true)).data ?? []
    } else {
      if (!form.apiKey.trim()) {
        ElMessage.warning('请先填写 API Key 再获取模型列表')
        return
      }
      discoveredModels.value = (await userAiApi.discoverUnsavedModels({
        providerType: form.providerType,
        baseUrl: form.baseUrl.trim(),
        apiPath: form.apiPath.trim(),
        apiKey: form.apiKey.trim(),
      })).data ?? []
    }
    if (discoveredModels.value.length) ElMessage.success(`发现 ${discoveredModels.value.length} 个模型`)
    else ElMessage.warning('模型列表为空，请手动填写模型 ID')
  } catch (error) {
    showApiError(error, '模型列表获取，可手动填写模型 ID')
  } finally {
    discovering.value = false
  }
}

const canDiscover = computed(() => Boolean(form.apiKey.trim() || (props.editing && props.editing.hasApiKey)))

async function save(): Promise<void> {
  if (!canSave.value || saving.value) return
  saving.value = true
  try {
    const payload: UserAiProviderPayload = {
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
    if (props.editing) await userAiApi.update(props.editing.id, payload)
    else await userAiApi.create(payload)
    emit('update:visible', false)
    emit('saved')
  } catch (error) {
    showApiError(error, 'AI 配置保存')
  } finally {
    saving.value = false
  }
}

function close(): void {
  emit('update:visible', false)
}
</script>

<template>
  <el-dialog :model-value="visible" :title="dialogTitle" width="600px" @update:model-value="close">
    <el-form label-position="top">
      <el-form-item label="名称"><el-input v-model="form.name" maxlength="80" /></el-form-item>
      <el-form-item label="服务商">
        <el-select v-model="form.profileCode" @change="onProfileChange">
          <el-option v-for="p in profiles" :key="p.code" :label="p.label" :value="p.code" />
        </el-select>
      </el-form-item>
      <el-alert
        v-if="duplicateVersion"
        type="warning"
        :closable="false"
        show-icon
        title="Base URL 与 API Path 拼接后会重复版本前缀"
        :description="`最终端点将是 ${endpointPreview.inferenceUrl}。请选择一键修正：`"
      >
        <template #default>
          <div class="fix-row">
            <el-button v-if="fixSuggestions.fixedBaseUrl" size="small" @click="applyFix('base')">
              改为 Base URL 不带 /{{ duplicateVersion }}
            </el-button>
            <el-button v-if="fixSuggestions.fixedApiPath" size="small" @click="applyFix('path')">
              改为 API Path 不带 /{{ duplicateVersion }}
            </el-button>
          </div>
        </template>
      </el-alert>
      <el-form-item label="API Key">
        <el-input v-model="form.apiKey" type="password" show-password placeholder="只写不读，编辑时留空则不修改" />
      </el-form-item>
      <el-form-item label="模型">
        <div class="model-field">
          <el-select
            v-if="discoveredModels.length"
            v-model="form.modelName"
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入模型 ID"
          >
            <el-option v-for="m in discoveredModels" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input v-else v-model="form.modelName" maxlength="160" placeholder="模型 ID，可手动填写" />
          <el-button
            text
            :icon="Refresh"
            :loading="discovering"
            :disabled="!canDiscover"
            aria-label="获取模型列表"
            title="获取模型列表（只读请求，失败可手动填写）"
            @click="discoverModels"
          />
        </div>
        <span class="field-hint">填写 API Key 后可一键获取模型列表；失败时直接手输模型 ID 即可</span>
      </el-form-item>
      <el-collapse v-model="advancedOpen">
        <el-collapse-item title="高级设置" name="advanced">
          <el-form-item label="Base URL">
            <el-input v-model="form.baseUrl" placeholder="服务商端点地址，https:// 开头" />
            <span class="field-hint">已按所选服务商预填官方端点，可改为兼容网关地址</span>
          </el-form-item>
          <el-form-item label="API Path">
            <el-input v-model="form.apiPath" />
            <span class="field-hint">{{ profile.apiPathHint }}</span>
            <span class="field-hint" data-test="endpoint-preview">
              推理端点：{{ endpointPreview.inferenceUrl }} · 模型发现：{{ endpointPreview.discoveryUrl }}
            </span>
          </el-form-item>
          <el-form-item label="能力声明">
            <el-checkbox-group v-model="form.capabilities">
              <el-checkbox v-for="capability in profile.candidateCapabilities" :key="capability" :value="capability">
                {{ capability }}
              </el-checkbox>
            </el-checkbox-group>
            <span class="field-hint">
              勾选 = 允许尝试调用该能力，不代表已验证；保存后点击「检测并配置」，通过真实探针的能力会自动启用
            </span>
          </el-form-item>
          <el-form-item label="Temperature"><el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
          <el-form-item label="Max Output Tokens"><el-input-number v-model="form.maxOutputTokens" :min="1" :max="131072" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint { display: block; color: var(--color-text-secondary); font-size: 12px; margin-top: 2px; }
.model-field { display: flex; align-items: center; gap: 8px; width: 100%; }
.fix-row { display: flex; gap: 8px; margin-top: 4px; }
</style>
