<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { embeddingAdminApi, type SystemEmbeddingConfig, type IndexGeneration } from './embedding-admin-api'

const config = ref<SystemEmbeddingConfig | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const reindexing = ref(false)
const generations = ref<IndexGeneration[]>([])
const stateLabels = { QUEUED: '已排队', BUILDING: '构建中', READY: '构建完成，待激活', ACTIVE: '当前索引', RETIRED: '旧代际', FAILED: '构建失败' }
const form = reactive({
  provider: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.openai.com',
  apiPath: '/v1/embeddings',
  apiKey: '',
  modelName: '',
  dimensions: 0,
  batchSize: 16,
})

async function load(): Promise<void> {
  loading.value = true
  try {
    config.value = (await embeddingAdminApi.get()).data
    generations.value = (await embeddingAdminApi.generations()).data
    if (config.value?.modelName) {
      Object.assign(form, {
        provider: config.value.provider,
        baseUrl: config.value.baseUrl,
        apiPath: config.value.apiPath,
        apiKey: '',
        modelName: config.value.modelName,
        dimensions: config.value.dimensions,
        batchSize: config.value.batchSize,
      })
    }
  } catch (error) {
    showApiError(error, 'Embedding 配置加载')
  } finally {
    loading.value = false
  }
}

function selectProvider() {
  form.apiKey = ''
  form.dimensions = 0
  form.apiPath = '/v1/embeddings'
  form.baseUrl = form.provider === 'OLLAMA' ? 'http://127.0.0.1:11434' : 'https://api.openai.com'
}

async function activate(item: IndexGeneration) {
  try { await embeddingAdminApi.activate(item.id); ElMessage.success('已激活候选索引'); await load() }
  catch (error) { showApiError(error, '索引激活') }
}
async function discard(item: IndexGeneration) {
  try {
    await ElMessageBox.confirm('将删除该代索引分块及其历史引用链接，保留当前索引和历史回答正文。是否清理？', '清理旧索引', { type: 'warning' })
    await embeddingAdminApi.discard(item.id); await load()
  }
  catch (error) { showApiError(error, '索引清理') }
}

function payload() {
  return {
    provider: form.provider,
    baseUrl: form.baseUrl.trim(),
    apiPath: form.apiPath.trim(),
    apiKey: form.apiKey.trim() || null,
    modelName: form.modelName.trim(),
    dimensions: form.dimensions,
    batchSize: form.batchSize,
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    config.value = (await embeddingAdminApi.update(payload())).data
    form.apiKey = ''
    ElMessage.success('已保存')
  } catch (error) {
    showApiError(error, 'Embedding 配置保存')
  } finally {
    saving.value = false
  }
}

async function testConnection(): Promise<void> {
  testing.value = true
  try {
    const result = await embeddingAdminApi.test(payload())
    form.dimensions = result.data.dimensions
    ElMessage.success(`候选配置可用，实际维度 ${result.data.dimensions}，耗时 ${result.data.latencyMs} ms`)
  } catch (error) {
    showApiError(error, '连接测试')
  } finally {
    testing.value = false
  }
}

async function reindex(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将使用当前表单配置构建候选索引。旧索引继续提供服务，构建完成后需要单独激活。继续吗？',
      '重建知识库索引',
      { confirmButtonText: '开始重建', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  reindexing.value = true
  try {
    const result = await embeddingAdminApi.reindex(payload())
    ElMessage.success(`已触发重建，共 ${result.data.documents} 个文档`)
    await load()
  } catch (error) {
    showApiError(error, '索引重建')
  } finally {
    reindexing.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="管理中心" title="AI Infrastructure" description="系统 Embedding 只有系统管理员可以管理">
      <template #actions>
        <el-button @click="load">刷新状态</el-button>
        <el-button :loading="testing" @click="testConnection">测试当前表单并探测维度</el-button>
        <el-button type="warning" :disabled="form.dimensions < 1" :loading="reindexing" @click="reindex">构建候选索引</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </PageHeader>

    <section v-loading="loading" class="infra-stack">
      <el-alert
        v-if="config && config.staleChunks > 0"
        type="warning"
        show-icon
        :closable="false"
        title="存在旧索引"
        description="当前知识库已有使用旧 Embedding 生成的索引，修改模型前需要重新构建知识库索引。"
      />
      <el-card shadow="never">
        <template #header><strong>系统 Embedding 配置</strong></template>
        <el-form label-position="top">
          <el-form-item label="Provider"><el-select v-model="form.provider" @change="selectProvider"><el-option label="OpenAI 兼容" value="OPENAI_COMPATIBLE" /><el-option label="Ollama 本地" value="OLLAMA" /></el-select></el-form-item>
          <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
          <el-form-item label="API Path"><el-input v-model="form.apiPath" /></el-form-item>
          <el-form-item :label="form.provider === 'OLLAMA' ? 'API Key（本地直连可留空）' : 'API Key（同目标编辑留空保留；切换目标需重新填写）'">
            <el-input v-model="form.apiKey" type="password" show-password />
          </el-form-item>
          <el-form-item label="模型"><el-input v-model="form.modelName" /></el-form-item>
          <el-form-item label="实际维度（请先测试探测）"><el-input-number v-model="form.dimensions" :min="0" :max="4096" /></el-form-item>
          <el-form-item label="批量大小"><el-input-number v-model="form.batchSize" :min="1" :max="256" /></el-form-item>
        </el-form>
        <p class="key-note">API Key 状态：{{ config?.hasApiKey ? '已配置' : '未配置' }}</p>
        <p v-if="form.provider === 'OLLAMA'">请求由后端发起。Docker 内的 localhost 指后端容器；访问宿主机可配置 host.docker.internal:11434，同网络容器使用服务名。内网目标须加入后端精确主机和端口允许项。请使用已安装的向量模型。</p>
      </el-card>
      <el-card shadow="never">
        <template #header><strong>索引代际</strong></template>
        <el-table :data="generations">
          <el-table-column prop="modelName" label="模型" />
          <el-table-column label="状态"><template #default="{ row }">{{ stateLabels[row.state as keyof typeof stateLabels] }}</template></el-table-column>
          <el-table-column label="进度"><template #default="{ row }">完成 {{ row.completed }}/{{ row.total }} · 失败 {{ row.failed }}</template></el-table-column>
          <el-table-column prop="errorCode" label="失败原因" />
          <el-table-column label="操作"><template #default="{ row }"><el-button v-if="row.state === 'READY'" @click="activate(row)">激活</el-button><el-button v-if="['READY', 'FAILED', 'RETIRED'].includes(row.state)" @click="discard(row)">清理</el-button></template></el-table-column>
        </el-table>
      </el-card>
    </section>
  </main>
</template>
