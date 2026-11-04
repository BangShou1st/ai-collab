<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { showApiError } from '../../../api/api-result'
import { embeddingAdminApi, type SystemEmbeddingConfig, type IndexGeneration } from '../embedding-admin-api'

const config = ref<SystemEmbeddingConfig | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const reindexing = ref(false)
const generations = ref<IndexGeneration[]>([])

const stateLabels = {
  QUEUED: '已排队',
  BUILDING: '构建中',
  READY: '构建完成（待激活）',
  ACTIVE: '当前在线索引',
  RETIRED: '历史旧代际',
  FAILED: '构建失败',
}

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
    const [cfgRes, genRes] = await Promise.all([
      embeddingAdminApi.get(),
      embeddingAdminApi.generations(),
    ])
    config.value = cfgRes.data
    generations.value = genRes.data
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

function selectProvider(): void {
  form.apiKey = ''
  form.dimensions = 0
  form.apiPath = '/v1/embeddings'
  form.baseUrl = form.provider === 'OLLAMA' ? 'http://127.0.0.1:11434' : 'https://api.openai.com'
}

async function activate(item: IndexGeneration): Promise<void> {
  try {
    await embeddingAdminApi.activate(item.id)
    ElMessage.success('已切换并激活候选索引')
    await load()
  } catch (error) {
    showApiError(error, '索引激活')
  }
}

async function discard(item: IndexGeneration): Promise<void> {
  try {
    await ElMessageBox.confirm('确认清理该代索引分块记录吗？当前在线索引不受影响。', '清理旧索引', { type: 'warning' })
    await embeddingAdminApi.discard(item.id)
    ElMessage.success('已清理')
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '索引清理')
  }
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
    ElMessage.success('Embedding 配置已保存')
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
    ElMessage.success(`探测成功！向量维度：${result.data.dimensions}，耗时：${result.data.latencyMs} ms`)
  } catch (error) {
    showApiError(error, '测试并探测维度')
  } finally {
    testing.value = false
  }
}

async function reindex(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将使用当前配置在后台构建候选向量索引。构建期间原索引正常提供问答服务，构建完成后需手动激活。确认开始吗？',
      '重建知识库索引',
      { confirmButtonText: '开始构建', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  reindexing.value = true
  try {
    const result = await embeddingAdminApi.reindex(payload())
    ElMessage.success(`已触发重建任务，包含 ${result.data.documents} 个文档`)
    await load()
  } catch (error) {
    showApiError(error, '触发索引重建')
  } finally {
    reindexing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="embedding-wrapper">
    <!-- 警告栏 -->
    <el-alert
      v-if="config && config.staleChunks > 0"
      type="warning"
      show-icon
      :closable="false"
      title="存在旧模型索引"
      description="知识库包含使用历史 Embedding 模型构建的分块，若需使新模型生效，请先探测维度并构建候选索引。"
      class="alert-margin"
    />

    <div class="action-topbar">
      <el-button :icon="Refresh" @click="load">刷新状态</el-button>
      <el-button :loading="testing" :icon="VideoPlay" @click="testConnection">测试连接与探测维度</el-button>
      <el-button type="warning" :disabled="form.dimensions < 1" :loading="reindexing" @click="reindex">
        构建候选索引
      </el-button>
      <el-button type="primary" :loading="saving" @click="save">保存配置</el-button>
    </div>

    <div class="embedding-cards">
      <el-card class="box-card" shadow="never">
        <template #header><strong>系统 Embedding 配置</strong></template>
        <el-form label-position="top">
          <div class="form-grid">
            <el-form-item label="Provider 类型">
              <el-select v-model="form.provider" @change="selectProvider">
                <el-option label="OpenAI 兼容协议" value="OPENAI_COMPATIBLE" />
                <el-option label="Ollama 本地部署" value="OLLAMA" />
              </el-select>
            </el-form-item>

            <el-form-item label="模型名称（Model ID）">
              <el-input v-model="form.modelName" placeholder="如 text-embedding-3-small 或 bge-m3" />
            </el-form-item>

            <el-form-item label="Base URL">
              <el-input v-model="form.baseUrl" placeholder="https://api.openai.com" />
            </el-form-item>

            <el-form-item label="API Path">
              <el-input v-model="form.apiPath" placeholder="/v1/embeddings" />
            </el-form-item>

            <el-form-item label="API Key（留空保持不变）">
              <el-input v-model="form.apiKey" type="password" show-password placeholder="加密存储，不回显明文" />
            </el-form-item>

            <el-form-item label="向量维度（通过测试自动探测）">
              <el-input-number v-model="form.dimensions" :min="0" :max="4096" style="width: 100%" />
            </el-form-item>
          </div>
        </el-form>
      </el-card>

      <el-card class="box-card" shadow="never">
        <template #header><strong>知识库索引代际</strong></template>
        <el-table :data="generations" empty-text="暂无历史代际记录">
          <el-table-column prop="modelName" label="模型" min-width="140" />
          <el-table-column label="状态" width="160">
            <template #default="{ row }">
              <el-tag :type="row.state === 'ACTIVE' ? 'success' : (row.state === 'READY' ? 'warning' : 'info')" size="small">
                {{ stateLabels[row.state as keyof typeof stateLabels] || row.state }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="构建进度" min-width="160">
            <template #default="{ row }">
              <span>完成 {{ row.completed }}/{{ row.total }}（失败 {{ row.failed }}）</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.state === 'READY'" type="primary" size="small" @click="activate(row)">激活</el-button>
              <el-button v-if="['READY', 'FAILED', 'RETIRED'].includes(row.state)" text type="danger" size="small" @click="discard(row)">
                清理
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.embedding-wrapper {
  padding-top: 12px;
}
.alert-margin {
  margin-bottom: 16px;
}
.action-topbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.embedding-cards {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.box-card {
  border-radius: 12px;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
}
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
@media (max-width: 768px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
