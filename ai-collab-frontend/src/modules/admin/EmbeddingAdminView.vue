<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { embeddingAdminApi, type SystemEmbeddingConfig } from './embedding-admin-api'

const config = ref<SystemEmbeddingConfig | null>(null)
const loading = ref(false)
const saving = ref(false)
const testing = ref(false)
const reindexing = ref(false)
const form = reactive({
  provider: 'OPENAI_COMPATIBLE',
  baseUrl: 'https://api.openai.com',
  apiPath: '/v1/embeddings',
  apiKey: '',
  modelName: '',
  dimensions: 1536,
  batchSize: 16,
})

async function load(): Promise<void> {
  loading.value = true
  try {
    config.value = (await embeddingAdminApi.get()).data
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
    await embeddingAdminApi.test()
    ElMessage.success('连接成功')
  } catch (error) {
    showApiError(error, '连接测试')
  } finally {
    testing.value = false
  }
}

async function reindex(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将使用当前表单配置重建全部知识库索引，旧索引在重建完成前不可用。继续吗？',
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
        <el-button :loading="testing" @click="testConnection">连接测试</el-button>
        <el-button type="warning" :loading="reindexing" @click="reindex">重建知识库索引</el-button>
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
          <el-form-item label="Provider"><el-input v-model="form.provider" /></el-form-item>
          <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
          <el-form-item label="API Path"><el-input v-model="form.apiPath" /></el-form-item>
          <el-form-item label="API Key（只写不读，留空则不修改）">
            <el-input v-model="form.apiKey" type="password" show-password />
          </el-form-item>
          <el-form-item label="模型"><el-input v-model="form.modelName" /></el-form-item>
          <el-form-item label="维度"><el-input-number v-model="form.dimensions" :min="1" :max="4096" /></el-form-item>
          <el-form-item label="批量大小"><el-input-number v-model="form.batchSize" :min="1" :max="256" /></el-form-item>
        </el-form>
        <p class="key-note">API Key 状态：{{ config?.hasApiKey ? '已配置' : '未配置' }}</p>
      </el-card>
    </section>
  </main>
</template>

