<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { adminApi } from './admin-api'
import type {
  AdminUser,
  ModelAssignment,
  ModelCapability,
  ModelConfiguration,
  ModelConfigurationInput,
  ModelProviderType,
  ModelPurpose,
} from './types'

const models = ref<ModelConfiguration[]>([])
const users = ref<AdminUser[]>([])
const assignments = ref<ModelAssignment[]>([])
const loading = ref(false)
const errorMessage = ref('')
const modelDialog = ref(false)
const userDialog = ref(false)
const editingId = ref<string | null>(null)
const saving = ref(false)
const testingId = ref('')

const providerDefaults: Record<ModelProviderType, { baseUrl: string; apiPath: string }> = {
  OPENAI_COMPATIBLE: { baseUrl: 'https://api.openai.com', apiPath: '/v1/chat/completions' },
  ANTHROPIC: { baseUrl: 'https://api.anthropic.com', apiPath: '/v1/messages' },
  GEMINI: {
    baseUrl: 'https://generativelanguage.googleapis.com',
    apiPath: '/v1beta/models/{model}:generateContent',
  },
}

const modelForm = reactive<ModelConfigurationInput>({
  name: '',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: providerDefaults.OPENAI_COMPATIBLE.baseUrl,
  apiPath: providerDefaults.OPENAI_COMPATIBLE.apiPath,
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.2,
  maxOutputTokens: 2000,
  capabilities: ['CHAT', 'STREAMING', 'STRUCTURED_OUTPUT', 'NATIVE_TOOLS', 'USAGE'],
})
const userForm = reactive({ username: '', displayName: '', password: '' })

const purposeLabels: Record<ModelPurpose, string> = {
  KNOWLEDGE_CHAT: '知识问答',
  AGENT: '协作 Agent',
  PLANNING: '任务规划',
}
const providerLabels: Record<ModelProviderType, string> = {
  OPENAI_COMPATIBLE: 'OpenAI 兼容',
  ANTHROPIC: 'Claude',
  GEMINI: 'Gemini',
}
const capabilityLabels: Record<ModelCapability, string> = {
  CHAT: '问答',
  STREAMING: '流式',
  STRUCTURED_OUTPUT: '结构化输出',
  NATIVE_TOOLS: '工具调用',
  USAGE: '用量统计',
}
const allCapabilities = Object.keys(capabilityLabels) as ModelCapability[]
const purposes = Object.keys(purposeLabels) as ModelPurpose[]

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [modelResult, assignmentResult, userResult] = await Promise.all([
      adminApi.models(), adminApi.assignments(), adminApi.users(),
    ])
    models.value = modelResult.data
    assignments.value = assignmentResult.data
    users.value = userResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

function resetModel(): void {
  editingId.value = null
  Object.assign(modelForm, {
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    ...providerDefaults.OPENAI_COMPATIBLE,
    apiKey: '',
    modelName: '',
    enabled: true,
    temperature: 0.2,
    maxOutputTokens: 2000,
    capabilities: [...allCapabilities],
  })
}

function openCreateModel(): void {
  resetModel()
  modelDialog.value = true
}

function openEditModel(model: ModelConfiguration): void {
  editingId.value = model.id
  Object.assign(modelForm, {
    name: model.name,
    providerType: model.providerType,
    baseUrl: model.baseUrl,
    apiPath: model.apiPath,
    apiKey: '',
    modelName: model.modelName,
    enabled: model.enabled,
    temperature: model.temperature,
    maxOutputTokens: model.maxOutputTokens,
    capabilities: [...model.capabilities],
  })
  modelDialog.value = true
}

function applyProviderDefaults(): void {
  Object.assign(modelForm, providerDefaults[modelForm.providerType])
}

async function saveModel(): Promise<void> {
  if (!modelForm.name.trim() || !modelForm.modelName.trim() || !modelForm.baseUrl.trim()) return
  saving.value = true
  try {
    const input = { ...modelForm, apiKey: modelForm.apiKey?.trim() || null }
    if (editingId.value) await adminApi.updateModel(editingId.value, input)
    else await adminApi.createModel(input)
    modelDialog.value = false
    ElMessage.success('模型配置已保存')
    await load()
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  } finally {
    saving.value = false
  }
}

async function testModel(model: ModelConfiguration): Promise<void> {
  testingId.value = model.id
  try {
    await adminApi.testModel(model.id)
    ElMessage.success(`${model.name} 连接成功`)
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  } finally {
    testingId.value = ''
  }
}

function assignedModel(purpose: ModelPurpose): string {
  return assignments.value.find(item => item.purpose === purpose)?.configurationId ?? ''
}

async function assignModel(purpose: ModelPurpose, configurationId: string): Promise<void> {
  if (!configurationId) return
  try {
    await adminApi.assign(purpose, configurationId)
    ElMessage.success(`${purposeLabels[purpose]}模型已更新`)
    await load()
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  }
}

async function removeModel(model: ModelConfiguration): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除模型配置“${model.name}”？`, '删除模型', { type: 'warning' })
    await adminApi.removeModel(model.id)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(normalizeApiError(error).message)
  }
}

async function createUser(): Promise<void> {
  saving.value = true
  try {
    await adminApi.createUser({ ...userForm })
    userDialog.value = false
    Object.assign(userForm, { username: '', displayName: '', password: '' })
    ElMessage.success('账号已创建')
    await load()
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  } finally {
    saving.value = false
  }
}

async function toggleUser(user: AdminUser): Promise<void> {
  try {
    await adminApi.setUserEnabled(user.id, user.status !== 'ACTIVE')
    await load()
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="系统设置" title="管理中心">
      <template #actions>
        <el-button @click="userDialog = true">新建账号</el-button>
        <el-button type="primary" @click="openCreateModel">添加模型</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />

    <section v-loading="loading" class="admin-stack">
      <el-card>
        <template #header><strong>用途分配</strong></template>
        <div class="assignment-grid">
          <label v-for="purpose in purposes" :key="purpose">
            <span>{{ purposeLabels[purpose] }}</span>
            <el-select
              :model-value="assignedModel(purpose)"
              placeholder="使用旧环境配置"
              @change="assignModel(purpose, $event)"
            >
              <el-option
                v-for="model in models.filter(item => item.enabled)"
                :key="model.id"
                :label="`${model.name} · ${model.modelName}`"
                :value="model.id"
              />
            </el-select>
          </label>
        </div>
      </el-card>

      <el-card>
        <template #header><strong>模型配置</strong></template>
        <el-table :data="models" empty-text="还没有模型配置">
          <el-table-column label="名称" min-width="180">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <small class="cell-subtitle">{{ row.modelName }}</small>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="130">
            <template #default="{ row }">{{ providerLabels[row.providerType as ModelProviderType] }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="密钥" width="90">
            <template #default="{ row }">{{ row.hasApiKey ? '已保存' : '未设置' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openEditModel(row)">编辑</el-button>
              <el-button text :loading="testingId === row.id" @click="testModel(row)">测试</el-button>
              <el-button text type="danger" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card>
        <template #header><strong>账号管理</strong></template>
        <el-table :data="users" empty-text="暂无账号">
          <el-table-column prop="username" label="用户名" min-width="140" />
          <el-table-column prop="displayName" label="显示名称" min-width="160" />
          <el-table-column prop="email" label="邮箱" min-width="180" />
          <el-table-column label="角色" width="110">
            <template #default="{ row }">{{ row.systemAdmin ? '管理员' : '成员' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button
                v-if="!row.systemAdmin"
                text
                :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
                @click="toggleUser(row)"
              >
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>

    <el-dialog v-model="modelDialog" :title="editingId ? '编辑模型' : '添加模型'" width="640px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="配置名称"><el-input v-model="modelForm.name" /></el-form-item>
          <el-form-item label="模型类型">
            <el-select v-model="modelForm.providerType" @change="applyProviderDefaults">
              <el-option v-for="(label, value) in providerLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="接口地址"><el-input v-model="modelForm.baseUrl" /></el-form-item>
          <el-form-item label="接口路径"><el-input v-model="modelForm.apiPath" /></el-form-item>
          <el-form-item label="模型名称"><el-input v-model="modelForm.modelName" /></el-form-item>
          <el-form-item :label="editingId ? 'API Key（留空则保持不变）' : 'API Key'">
            <el-input v-model="modelForm.apiKey" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="温度"><el-input-number v-model="modelForm.temperature" :min="0" :max="2" :step="0.1" /></el-form-item>
          <el-form-item label="最大输出 Token"><el-input-number v-model="modelForm.maxOutputTokens" :min="1" :max="131072" /></el-form-item>
        </div>
        <el-form-item label="能力">
          <el-checkbox-group v-model="modelForm.capabilities">
            <el-checkbox v-for="capability in allCapabilities" :key="capability" :value="capability">
              {{ capabilityLabels[capability] }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-switch v-model="modelForm.enabled" active-text="启用此配置" />
      </el-form>
      <template #footer>
        <el-button @click="modelDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveModel">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="userDialog" title="新建账号" width="460px">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="userForm.username" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="userForm.displayName" /></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="userForm.password" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createUser">创建</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-stack { display: grid; gap: 18px; }
.assignment-grid, .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.assignment-grid label { display: grid; gap: 7px; font-weight: 650; }
.cell-subtitle { display: block; color: var(--color-text-muted); }
@media (max-width: 720px) {
  .assignment-grid, .form-grid { grid-template-columns: 1fr; }
}
</style>
