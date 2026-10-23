<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import {
  DEFAULT_DECLARED_CAPABILITIES,
  profileByType,
} from './provider-profiles'
import { userAiApi, type AiProviderType, type ProviderProfile, type UserAiProvider, type UserAiProviderPayload } from './user-ai-api'

/**
 * 自定义 Provider 表单：新建/编辑共用。
 *
 * 关键交互：
 * - Provider 切换时按接入档案填默认端点，但不覆盖用户已编辑的值
 *   （当前值仍等于旧档案默认值才替换）。
 * - Base URL 是普通字段，API Path 在高级设置并展示档案说明（{model} 占位符语义）。
 * - 能力勾选的候选来自档案；新建默认 CHAT+STREAMING（流式知识问答的最小声明）。
 * - 模型 ID 可手输；编辑已保存配置时可拉取模型列表辅助选择（失败不阻断手输）。
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

const form = reactive({
  name: '',
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

/** 上一次应用默认值的档案类型：用于识别“当前值仍是默认值（用户未改）”。 */
let appliedProfileType: AiProviderType = 'OPENAI_COMPATIBLE'

const dialogTitle = computed(() => (props.editing ? '编辑 AI 配置' : '添加 AI 配置'))
const profile = computed(() => profileByType(props.profiles, form.providerType))
const canSave = computed(() =>
  Boolean(form.name.trim() && form.modelName.trim() && (form.apiKey.trim() || props.editing)))

watch(
  () => props.visible,
  (open) => {
    if (!open) return
    discoveredModels.value = []
    if (props.editing) {
      Object.assign(form, {
        name: props.editing.name,
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
      appliedProfileType = props.editing.providerType
    } else {
      resetForm()
    }
  },
  { immediate: true },
)

function resetForm(): void {
  const defaults = profileByType(props.profiles, 'OPENAI_COMPATIBLE')
  Object.assign(form, {
    name: '',
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
  appliedProfileType = 'OPENAI_COMPATIBLE'
}

/**
 * 切换 Provider：仅当字段仍是旧档案的默认值（未被用户修改）时才跟随新档案，
 * 用户改过的 Base URL / API Path / 能力勾选一律保留。
 */
function onProviderTypeChange(): void {
  const previous = profileByType(props.profiles, appliedProfileType)
  const next = profileByType(props.profiles, form.providerType)
  if (form.baseUrl.trim() === previous.defaultBaseUrl) form.baseUrl = next.defaultBaseUrl
  if (form.apiPath.trim() === previous.defaultApiPath) form.apiPath = next.defaultApiPath
  // 能力候选随协议变化：去掉新档案不支持的勾选项，其余保留（用户勾选不丢）。
  form.capabilities = form.capabilities.filter((capability) => next.candidateCapabilities.includes(capability))
  if (!form.capabilities.includes('CHAT')) form.capabilities.unshift('CHAT')
  appliedProfileType = form.providerType
}

async function discoverModels(): Promise<void> {
  if (!props.editing || discovering.value) return
  discovering.value = true
  try {
    discoveredModels.value = (await userAiApi.models(props.editing.id, true)).data ?? []
    if (discoveredModels.value.length) ElMessage.success(`发现 ${discoveredModels.value.length} 个模型`)
    else ElMessage.warning('模型列表为空，请手动填写模型 ID')
  } catch (error) {
    showApiError(error, '模型列表获取，可手动填写模型 ID')
  } finally {
    discovering.value = false
  }
}

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
      <el-form-item label="Provider">
        <el-select v-model="form.providerType" @change="onProviderTypeChange">
          <el-option v-for="p in profiles" :key="p.providerType" :label="p.label" :value="p.providerType" />
        </el-select>
      </el-form-item>
      <el-form-item label="Base URL">
        <el-input v-model="form.baseUrl" placeholder="服务商端点地址，https:// 开头" />
        <span class="field-hint">已按所选 Provider 预填官方端点，可改为兼容网关地址</span>
      </el-form-item>
      <el-form-item label="API Key">
        <el-input v-model="form.apiKey" type="password" show-password placeholder="只写不读，编辑时留空则不修改" />
      </el-form-item>
      <el-form-item label="模型">
        <div class="model-field">
          <el-select
            v-if="editing && discoveredModels.length"
            v-model="form.modelName"
            filterable
            allow-create
            default-first-option
          >
            <el-option v-for="m in discoveredModels" :key="m" :label="m" :value="m" />
          </el-select>
          <el-input v-else v-model="form.modelName" maxlength="160" placeholder="模型 ID，可手动填写" />
          <el-button
            v-if="editing"
            text
            :icon="Refresh"
            :loading="discovering"
            aria-label="获取模型列表"
            title="获取模型列表（只读请求，失败可手动填写）"
            @click="discoverModels"
          />
        </div>
        <span v-if="!editing" class="field-hint">保存后可点击刷新按钮获取模型列表辅助选择</span>
      </el-form-item>
      <el-form-item label="能力声明">
        <el-checkbox-group v-model="form.capabilities">
          <el-checkbox v-for="capability in profile.candidateCapabilities" :key="capability" :value="capability">
            {{ capability }}
          </el-checkbox>
        </el-checkbox-group>
        <span class="field-hint">勾选 = 声明该模型具备此能力；知识问答（流式）需要 CHAT + STREAMING</span>
      </el-form-item>
      <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      <el-collapse accordion>
        <el-collapse-item title="高级设置" name="advanced">
          <el-form-item label="API Path">
            <el-input v-model="form.apiPath" />
            <span class="field-hint">{{ profile.apiPathHint }}</span>
          </el-form-item>
          <el-form-item label="Temperature"><el-input-number v-model="form.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
          <el-form-item label="Max Output Tokens"><el-input-number v-model="form.maxOutputTokens" :min="1" :max="131072" /></el-form-item>
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
</style>
