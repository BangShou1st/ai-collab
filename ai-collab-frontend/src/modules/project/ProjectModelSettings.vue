<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from './project-api'
import type { Project } from './types'
import {
  projectModelApi,
  canToggleMcpConnection,
  mcpEditorValues,
  toMcpConnectionInput,
  type ModelConfiguration,
  type ModelConfigurationInput,
  type ModelAssignment,
  type ModelCapability,
  type ModelProviderType,
  type ModelPurpose,
  type EmbeddingConfig,
  type EmbeddingConfigInput,
  type McpConnection,
  type McpConnectionInput,
  type McpEditorValues,
} from './project-model-api'

const route = useRoute()
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

const project = ref<Project | null>(null)
const models = ref<ModelConfiguration[]>([])
const assignments = ref<ModelAssignment[]>([])
const embeddingConfig = ref<EmbeddingConfig | null>(null)
const mcpConnections = ref<McpConnection[]>([])
const loading = ref(false)
const saving = ref(false)

// ─── Model Dialog ───
const modelDialog = ref(false)
const editingModelId = ref<string | null>(null)
const testingModelId = ref('')
const modelForm = reactive<ModelConfigurationInput>({
  name: '',
  providerType: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.openai.com',
  apiPath: '/v1/chat/completions',
  apiKey: '',
  modelName: '',
  enabled: true,
  temperature: 0.2,
  maxOutputTokens: 1200,
  capabilities: ['CHAT', 'STREAMING', 'STRUCTURED_OUTPUT', 'NATIVE_TOOLS', 'USAGE'],
})

// ─── Embedding Dialog ───
const embeddingDialog = ref(false)
const testingEmbedding = ref(false)
const embeddingForm = reactive<EmbeddingConfigInput>({
  provider: 'OPENAI_COMPATIBLE',
  baseUrl: '',
  apiPath: '/v1/embeddings',
  apiKey: '',
  modelName: '',
  dimensions: 1024,
  batchSize: 10,
})
const embeddingProviderDefaults: Record<ModelProviderType, { baseUrl: string; apiPath: string }> = {
  OPENAI_COMPATIBLE: { baseUrl: 'https://api.openai.com', apiPath: '/v1/embeddings' },
  ANTHROPIC: { baseUrl: 'https://api.anthropic.com', apiPath: '/v1/embeddings' },
  GEMINI: { baseUrl: 'https://generativelanguage.googleapis.com', apiPath: '/v1beta/models/{model}:embedContent' },
}

// ─── MCP Dialog ───
const mcpDialog = ref(false)
const editingMcpId = ref<string | null>(null)
const mcpActionId = ref('')
const mcpForm = reactive<McpEditorValues>({
  code: '', name: '', endpoint: '', authType: 'BEARER',
  credential: '', timeoutMs: 10000, maxResultBytes: 65536,
  toolAllowlist: '', resourceAllowlist: '', version: 0,
})

// ─── Labels ───
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
const providerDefaults: Record<ModelProviderType, { baseUrl: string; apiPath: string }> = {
  OPENAI_COMPATIBLE: { baseUrl: 'https://api.openai.com', apiPath: '/v1/chat/completions' },
  ANTHROPIC: { baseUrl: 'https://api.anthropic.com', apiPath: '/v1/messages' },
  GEMINI: { baseUrl: 'https://generativelanguage.googleapis.com', apiPath: '/v1beta/models/{model}:generateContent' },
}

// ─── Load ───
async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const [projectResult, modelResult, assignmentResult, embeddingResult, mcpResult] = await Promise.all([
      projectApi.get(projectId.value),
      projectModelApi.listModels(projectId.value),
      projectModelApi.listAssignments(projectId.value),
      projectModelApi.getEmbeddingConfig(projectId.value),
      projectModelApi.listMcpConnections(projectId.value),
    ])
    project.value = projectResult.data
    models.value = modelResult.data
    assignments.value = assignmentResult.data
    embeddingConfig.value = embeddingResult.data
    mcpConnections.value = mcpResult.data
  } catch (error) {
    showApiError(error, '模型配置加载')
  } finally {
    loading.value = false
  }
}

// ─── Model Methods ───
function resetModel(): void {
  editingModelId.value = null
  Object.assign(modelForm, {
    name: '',
    providerType: 'OPENAI_COMPATIBLE',
    ...providerDefaults.OPENAI_COMPATIBLE,
    apiKey: '',
    modelName: '',
    enabled: true,
    temperature: 0.2,
    maxOutputTokens: 1200,
    capabilities: [...allCapabilities],
  })
}

function openCreateModel(): void {
  resetModel()
  modelDialog.value = true
}

function openEditModel(model: ModelConfiguration): void {
  editingModelId.value = model.id
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
    if (editingModelId.value) await projectModelApi.updateModel(projectId.value, editingModelId.value, input)
    else await projectModelApi.createModel(projectId.value, input)
    modelDialog.value = false
    ElMessage.success('模型配置已保存')
    await load()
  } catch (error) {
    showApiError(error, '模型配置保存')
  } finally {
    saving.value = false
  }
}

async function testModel(model: ModelConfiguration): Promise<void> {
  testingModelId.value = model.id
  try {
    await projectModelApi.testModel(projectId.value, model.id)
    ElMessage.success(`${model.name} 连接成功`)
  } catch (error) {
    showApiError(error, '模型连接测试')
  } finally {
    testingModelId.value = ''
  }
}

async function removeModel(model: ModelConfiguration): Promise<void> {
  try {
    await ElMessageBox.confirm(`删除模型配置"${model.name}"？`, '删除模型', { type: 'warning' })
    await projectModelApi.deleteModel(projectId.value, model.id)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '模型配置删除')
  }
}

function assignedModel(purpose: ModelPurpose): string {
  return assignments.value.find(item => item.purpose === purpose)?.configurationId ?? ''
}

async function assignModel(purpose: ModelPurpose, configurationId: string): Promise<void> {
  if (!configurationId) return
  try {
    await projectModelApi.assign(projectId.value, purpose, configurationId)
    ElMessage.success(`${purposeLabels[purpose]}模型已更新`)
    await load()
  } catch (error) {
    showApiError(error, `${purposeLabels[purpose]}模型分配`)
  }
}

// ─── Embedding Methods ───
function openEditEmbedding(): void {
  if (embeddingConfig.value) {
    Object.assign(embeddingForm, {
      provider: embeddingConfig.value.provider || 'OPENAI_COMPATIBLE',
      baseUrl: embeddingConfig.value.baseUrl || '',
      apiPath: embeddingConfig.value.apiPath || '/v1/embeddings',
      apiKey: '',
      modelName: embeddingConfig.value.modelName || '',
      dimensions: embeddingConfig.value.dimensions,
      batchSize: embeddingConfig.value.batchSize,
    })
  }
  embeddingDialog.value = true
}

function applyEmbeddingProviderDefaults(): void {
  Object.assign(embeddingForm, embeddingProviderDefaults[embeddingForm.provider])
}

async function saveEmbedding(): Promise<void> {
  if (!embeddingForm.baseUrl.trim() || !embeddingForm.modelName.trim()) return
  saving.value = true
  try {
    const input = { ...embeddingForm, apiKey: embeddingForm.apiKey?.trim() || null }
    await projectModelApi.saveEmbeddingConfig(projectId.value, input)
    embeddingDialog.value = false
    ElMessage.success('嵌入模型配置已保存')
    await load()
  } catch (error) {
    showApiError(error, '嵌入模型配置保存')
  } finally {
    saving.value = false
  }
}

async function testEmbedding(): Promise<void> {
  testingEmbedding.value = true
  try {
    await projectModelApi.testEmbeddingConfig(projectId.value)
    ElMessage.success('嵌入模型连接成功')
  } catch (error) {
    showApiError(error, '嵌入模型连接测试')
  } finally {
    testingEmbedding.value = false
  }
}

// ─── MCP Methods ───
function openCreateMcp(): void {
  editingMcpId.value = null
  Object.assign(mcpForm, {
    code: '', name: '', endpoint: '', authType: 'BEARER', credential: '',
    timeoutMs: 10000, maxResultBytes: 65536, toolAllowlist: '',
    resourceAllowlist: '', version: 0,
  })
  mcpDialog.value = true
}

function openEditMcp(item: McpConnection): void {
  editingMcpId.value = item.id
  Object.assign(mcpForm, mcpEditorValues(item))
  mcpDialog.value = true
}

async function saveMcp(): Promise<void> {
  if (!mcpForm.code.trim() || !mcpForm.name.trim() || !mcpForm.endpoint.trim()) return
  const input: McpConnectionInput = toMcpConnectionInput(mcpForm)
  saving.value = true
  try {
    if (editingMcpId.value) await projectModelApi.updateMcpConnection(projectId.value, editingMcpId.value, input)
    else await projectModelApi.createMcpConnection(projectId.value, input)
    mcpDialog.value = false
    ElMessage.success('MCP 连接已保存；请重新发现并确认 Schema 后启用')
    await load()
  } catch (error) {
    showApiError(error, 'MCP 连接保存')
  } finally {
    saving.value = false
  }
}

async function runMcpAction(item: McpConnection, action: 'test' | 'discover' | 'toggle'): Promise<void> {
  mcpActionId.value = `${item.id}:${action}`
  try {
    if (action === 'test') await projectModelApi.testMcpConnection(projectId.value, item.id)
    else if (action === 'discover') await projectModelApi.discoverMcpConnection(projectId.value, item.id)
    else await projectModelApi.setMcpConnectionEnabled(projectId.value, item, !item.enabled)
    await load()
  } catch (error) {
    const actionLabel = action === 'test' ? 'MCP 连接测试'
      : action === 'discover' ? 'MCP 工具发现'
        : item.enabled ? 'MCP 连接停用' : 'MCP 连接启用'
    showApiError(error, actionLabel)
  } finally {
    mcpActionId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目设置"
      title="模型配置"
      :context="project?.name"
    />

    <section v-loading="loading" class="settings-stack">
      <!-- Purpose Assignment -->
      <el-card>
        <template #header><strong>用途分配</strong></template>
        <div class="assignment-grid">
          <label v-for="purpose in purposes" :key="purpose">
            <span>{{ purposeLabels[purpose] }}</span>
            <el-select
              :model-value="assignedModel(purpose)"
              placeholder="未分配"
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

      <!-- Embedding Config -->
      <el-card>
        <template #header>
          <div class="card-header">
            <strong>嵌入模型配置</strong>
            <div>
              <el-button :loading="testingEmbedding" @click="testEmbedding" :disabled="!embeddingConfig?.hasApiKey">测试</el-button>
              <el-button type="primary" @click="openEditEmbedding">配置</el-button>
            </div>
          </div>
        </template>
        <el-descriptions v-if="embeddingConfig" :column="2" border size="small">
          <el-descriptions-item label="Provider">{{ embeddingConfig.provider || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="模型">{{ embeddingConfig.modelName || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="URL">{{ embeddingConfig.baseUrl || '未配置' }}</el-descriptions-item>
          <el-descriptions-item label="维度">{{ embeddingConfig.dimensions }}</el-descriptions-item>
          <el-descriptions-item label="批大小">{{ embeddingConfig.batchSize }}</el-descriptions-item>
          <el-descriptions-item label="API Key">
            <el-tag :type="embeddingConfig.hasApiKey ? 'success' : 'info'" size="small">
              {{ embeddingConfig.hasApiKey ? '已保存' : '未设置' }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <el-empty v-else description="尚未配置嵌入模型" :image-size="64" />
      </el-card>

      <!-- Model Config -->
      <el-card>
        <template #header>
          <div class="card-header">
            <strong>模型配置</strong>
            <el-button type="primary" @click="openCreateModel">添加模型</el-button>
          </div>
        </template>
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
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="密钥" width="90">
            <template #default="{ row }">{{ row.hasApiKey ? '已保存' : '未设置' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="240" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openEditModel(row)">编辑</el-button>
              <el-button text :loading="testingModelId === row.id" @click="testModel(row)">测试</el-button>
              <el-button text type="danger" @click="removeModel(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <!-- MCP Connections -->
      <el-card>
        <template #header>
          <div class="card-header">
            <strong>MCP 连接</strong>
            <el-button type="primary" @click="openCreateMcp">添加 MCP 连接</el-button>
          </div>
        </template>
        <el-table :data="mcpConnections" empty-text="还没有 MCP 连接">
          <el-table-column label="连接" min-width="190">
            <template #default="{ row }">
              <strong>{{ row.name }}</strong>
              <small class="cell-subtitle">{{ row.code }}</small>
              <small class="cell-subtitle">{{ row.endpoint }}</small>
            </template>
          </el-table-column>
          <el-table-column label="健康" width="110">
            <template #default="{ row }">{{ row.lastHealthStatus || '未测试' }}</template>
          </el-table-column>
          <el-table-column label="Schema Hash" min-width="180">
            <template #default="{ row }">
              <code>{{ row.schemaHash || '尚未发现' }}</code>
              <small v-if="row.schemaHash && !row.schemaConfirmed" class="cell-warning">待确认</small>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300" fixed="right">
            <template #default="{ row }">
              <el-button text type="primary" @click="openEditMcp(row)">编辑/轮换凭据</el-button>
              <el-button text :loading="mcpActionId === `${row.id}:test`" @click="runMcpAction(row, 'test')">测试</el-button>
              <el-button text :loading="mcpActionId === `${row.id}:discover`" @click="runMcpAction(row, 'discover')">发现</el-button>
              <el-button text :type="row.enabled ? 'danger' : 'success'" :disabled="!canToggleMcpConnection(row)"
                :loading="mcpActionId === `${row.id}:toggle`" @click="runMcpAction(row, 'toggle')">
                {{ row.enabled ? '停用' : '确认并启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>

    <!-- Model Dialog -->
    <el-dialog v-model="modelDialog" :title="editingModelId ? '编辑模型' : '添加模型'" width="640px">
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
          <el-form-item :label="editingModelId ? 'API Key（留空则保持不变）' : 'API Key'">
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

    <!-- Embedding Dialog -->
    <el-dialog v-model="embeddingDialog" title="嵌入模型配置" width="560px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="Provider">
            <el-select v-model="embeddingForm.provider" @change="applyEmbeddingProviderDefaults">
              <el-option v-for="(label, value) in providerLabels" :key="value" :label="label" :value="value" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型名称"><el-input v-model="embeddingForm.modelName" placeholder="text-embedding-v4" /></el-form-item>
          <el-form-item label="接口地址"><el-input v-model="embeddingForm.baseUrl" placeholder="https://api.openai.com" /></el-form-item>
          <el-form-item label="接口路径"><el-input v-model="embeddingForm.apiPath" placeholder="/v1/embeddings" /></el-form-item>
          <el-form-item :label="embeddingConfig?.hasApiKey ? 'API Key（留空保持不变）' : 'API Key'">
            <el-input v-model="embeddingForm.apiKey" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="向量维度"><el-input-number v-model="embeddingForm.dimensions" :min="1" :max="4096" /></el-form-item>
          <el-form-item label="批处理大小"><el-input-number v-model="embeddingForm.batchSize" :min="1" :max="100" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="embeddingDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEmbedding">保存</el-button>
      </template>
    </el-dialog>

    <!-- MCP Dialog -->
    <el-dialog v-model="mcpDialog" :title="editingMcpId ? '编辑 MCP 连接' : '添加 MCP 连接'" width="680px">
      <el-alert title="当前后端仅允许 HTTPS 的 STREAMABLE_HTTP；保存更新会停用连接，需重新发现并确认 Schema。" type="info" show-icon />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="连接代码"><el-input v-model="mcpForm.code" placeholder="github-readonly" /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="mcpForm.name" /></el-form-item>
          <el-form-item label="HTTPS Endpoint"><el-input v-model="mcpForm.endpoint" placeholder="https://mcp.example.com/mcp" /></el-form-item>
          <el-form-item label="认证方式"><el-select v-model="mcpForm.authType"><el-option label="无认证" value="NONE" /><el-option label="Bearer Token" value="BEARER" /></el-select></el-form-item>
          <el-form-item :label="editingMcpId ? '凭据（留空保持不变）' : '凭据'"><el-input v-model="mcpForm.credential" type="password" show-password autocomplete="new-password" /></el-form-item>
          <el-form-item label="超时（毫秒）"><el-input-number v-model="mcpForm.timeoutMs" :min="1000" :max="60000" /></el-form-item>
          <el-form-item label="最大结果字节数"><el-input-number v-model="mcpForm.maxResultBytes" :min="1024" :max="262144" /></el-form-item>
          <el-form-item label="确认只读工具白名单（逗号分隔）"><el-input v-model="mcpForm.toolAllowlist" /></el-form-item>
          <el-form-item label="连接级资源白名单（逗号分隔）"><el-input v-model="mcpForm.resourceAllowlist" /></el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="mcpDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveMcp">保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.settings-stack { display: grid; gap: 18px; }
.card-header { display: flex; align-items: center; justify-content: space-between; }
.assignment-grid, .form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.assignment-grid label { display: grid; gap: 7px; font-weight: 650; }
.cell-subtitle { display: block; color: var(--color-text-muted); }
.cell-warning { color: var(--el-color-warning); margin-left: 6px; }
@media (max-width: 720px) {
  .assignment-grid, .form-grid { grid-template-columns: 1fr; }
}
</style>
