import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { projectApi } from './project-api'
import type { Project } from './types'
import {
  projectModelApi,
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

/**
 * 项目模型配置页的状态与业务逻辑。
 * 从 ProjectModelSettings.vue 下沉：模型/用途分配、Embedding 与 MCP 连接管理都在这里，
 * 视图层只负责渲染与编排。
 */
export function useProjectModelSettings() {
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

  return {
    projectId, project, models, assignments, embeddingConfig, mcpConnections,
    loading, saving,
    modelDialog, editingModelId, testingModelId, modelForm,
    embeddingDialog, testingEmbedding, embeddingForm, embeddingProviderDefaults,
    mcpDialog, editingMcpId, mcpActionId, mcpForm,
    purposeLabels, providerLabels, capabilityLabels, allCapabilities, purposes, providerDefaults,
    openCreateModel, openEditModel, applyProviderDefaults, saveModel, testModel, removeModel,
    assignedModel, assignModel,
    openEditEmbedding, applyEmbeddingProviderDefaults, saveEmbedding, testEmbedding,
    openCreateMcp, openEditMcp, saveMcp, runMcpAction,
  }
}
