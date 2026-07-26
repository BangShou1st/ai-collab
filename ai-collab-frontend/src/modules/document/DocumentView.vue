<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import {
  documentStatusLabel,
  documentStatusType,
  formatDateTime,
} from '../../shared/display-labels'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { documentApi } from './document-api'
import type { ProjectDocument } from './types'

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<Project | null>(null)
const documents = ref<ProjectDocument[]>([])
const selected = ref<ProjectDocument | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const drawerVisible = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const operationId = ref('')
const chosenFile = ref<File | null>(null)
const displayName = ref('')
const fileInput = ref<HTMLInputElement | null>(null)
const dragging = ref(false)
let pollTimer: number | null = null
let active = true

const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const processingStatuses = new Set(['UPLOADED', 'PARSING', 'INDEXING', 'DELETING'])
const hasProcessing = computed(() => documents.value.some(item => processingStatuses.has(item.status)))
const uploadValidationMessage = computed(() => {
  if (!chosenFile.value) return '请选择要上传的文件'
  if (chosenFile.value.size <= 0) return '不能上传空文件'
  if (chosenFile.value.size > 20 * 1024 * 1024) return '文件大小不能超过 20MB'
  if (chosenFile.value.name.length > 180) return '文件名不能超过 180 个字符'
  if (displayName.value.length > 180) return '显示名称不能超过 180 个字符'
  if (!/\.(pdf|docx|md|markdown|txt)$/i.test(chosenFile.value.name)) {
    return '仅支持 PDF、DOCX、Markdown 和 TXT 文件'
  }
  return ''
})

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function load(showLoading = true): Promise<void> {
  if (showLoading) loading.value = true
  errorMessage.value = ''
  try {
    const [projectResult, documentResult] = await Promise.all([
      projectApi.get(projectId),
      documentApi.list(projectId),
    ])
    if (!active) return
    project.value = projectResult.data
    documents.value = documentResult.data
    if (selected.value) {
      selected.value = documents.value.find(item => item.id === selected.value?.id) ?? null
      if (!selected.value) drawerVisible.value = false
    }
  } catch (error) {
    if (active) errorMessage.value = normalizeApiError(error).message
  } finally {
    if (active) {
      loading.value = false
      schedulePolling()
    }
  }
}

function schedulePolling(): void {
  stopPolling()
  if (!active || !hasProcessing.value) return
  pollTimer = window.setTimeout(async () => {
    pollTimer = null
    await load(false)
  }, 3000)
}

function stopPolling(): void {
  if (pollTimer !== null) {
    window.clearTimeout(pollTimer)
    pollTimer = null
  }
}

function chooseFiles(files: FileList | null): void {
  const file = files?.item(0) ?? null
  chosenFile.value = file
  if (file && !displayName.value) displayName.value = file.name.replace(/\.[^.]+$/, '')
}

function onFileChange(event: Event): void {
  chooseFiles((event.target as HTMLInputElement).files)
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  chooseFiles(event.dataTransfer?.files ?? null)
}

function resetUpload(): void {
  chosenFile.value = null
  displayName.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function upload(): Promise<void> {
  if (!chosenFile.value || uploadValidationMessage.value || uploading.value) return
  uploading.value = true
  errorMessage.value = ''
  try {
    await documentApi.upload(projectId, chosenFile.value, displayName.value)
    uploadVisible.value = false
    resetUpload()
    await load(false)
    ElMessage.success('文档已上传，正在后台处理')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    uploading.value = false
  }
}

async function openDetail(item: ProjectDocument): Promise<void> {
  if (operationId.value) return
  operationId.value = item.id
  try {
    selected.value = (await documentApi.get(projectId, item.id)).data
    drawerVisible.value = true
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    operationId.value = ''
  }
}

async function download(item: ProjectDocument): Promise<void> {
  if (operationId.value) return
  operationId.value = item.id
  try {
    const result = await documentApi.downloadUrl(projectId, item.id)
    window.location.assign(result.data.url)
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    operationId.value = ''
  }
}

async function retry(item: ProjectDocument): Promise<void> {
  if (!canManage.value || item.status !== 'FAILED' || operationId.value) return
  operationId.value = item.id
  try {
    await documentApi.retry(projectId, item.id)
    await load(false)
    ElMessage.success('已重新提交处理')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    operationId.value = ''
  }
}

async function remove(item: ProjectDocument): Promise<void> {
  if (!canManage.value || operationId.value) return
  try {
    await ElMessageBox.confirm(
      '删除后将移除原文件和知识库索引，无法恢复。',
      `删除文档“${item.displayName}”`,
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    operationId.value = item.id
    await documentApi.delete(projectId, item.id)
    if (selected.value?.id === item.id) {
      selected.value = null
      drawerVisible.value = false
    }
    await load(false)
    ElMessage.success('文档已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
  } finally {
    operationId.value = ''
  }
}

onMounted(load)
onUnmounted(() => {
  active = false
  stopPolling()
})
</script>

<template>
  <main class="workspace-page document-page">
    <PageHeader
      eyebrow="项目知识资产"
      title="项目文档"
      description="集中管理竞赛材料，并将可提取文本转换为后续知识检索所需的向量索引。"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="uploadVisible = true">
          上传文档
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <el-alert
      v-if="project?.role === 'MEMBER'"
      title="你可以查看和下载项目文档；上传、重试和删除需要管理员权限。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-card v-loading="loading" class="document-list-card">
      <el-table :data="documents" empty-text="暂无项目文档" @row-click="openDetail">
        <el-table-column label="文档名称" min-width="220">
          <template #default="{ row }">
            <div class="document-name">
              <strong>{{ row.displayName }}</strong>
              <small>{{ row.originalFilename }}</small>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="documentStatusType(row.status)">
              {{ documentStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column label="分块数" width="100" prop="chunkCount" />
        <el-table-column label="上传者" min-width="120">
          <template #default="{ row }">{{ row.uploadedByDisplayName || '未知成员' }}</template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="190">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="230">
          <template #default="{ row }">
            <el-button
              text
              :loading="operationId === row.id"
              aria-label="下载文档"
              @click.stop="download(row)"
            >
              下载
            </el-button>
            <el-button
              v-if="canManage && row.status === 'FAILED'"
              text
              aria-label="重试文档处理"
              @click.stop="retry(row)"
            >
              重试
            </el-button>
            <el-button
              v-if="canManage"
              text
              type="danger"
              aria-label="删除文档"
              @click.stop="remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="uploadVisible" title="上传项目文档" width="560px" @closed="resetUpload">
      <el-form label-position="top" @submit.prevent="upload">
        <el-form-item label="文档文件">
          <div
            class="document-dropzone"
            :class="{ 'is-dragging': dragging }"
            tabindex="0"
            role="button"
            aria-label="选择或拖入文档文件"
            @click="fileInput?.click()"
            @keydown.enter="fileInput?.click()"
            @dragenter.prevent="dragging = true"
            @dragover.prevent="dragging = true"
            @dragleave.prevent="dragging = false"
            @drop.prevent="onDrop"
          >
            <strong>{{ chosenFile?.name || '将文件拖到这里' }}</strong>
            <span>或点击选择文件；支持 PDF、DOCX、Markdown、TXT，最大 20MB</span>
            <input
              ref="fileInput"
              class="visually-hidden"
              type="file"
              accept=".pdf,.docx,.md,.markdown,.txt"
              @change="onFileChange"
            >
          </div>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="displayName" :maxlength="180" show-word-limit />
        </el-form-item>
        <el-alert
          v-if="uploadValidationMessage"
          :title="uploadValidationMessage"
          type="warning"
          :closable="false"
        />
        <div class="actions">
          <el-button @click="uploadVisible = false">取消</el-button>
          <el-button
            type="primary"
            native-type="submit"
            :loading="uploading"
            :disabled="Boolean(uploadValidationMessage) || uploading"
          >
            上传
          </el-button>
        </div>
      </el-form>
    </el-dialog>

    <el-drawer v-model="drawerVisible" title="文档详情" size="520px">
      <template v-if="selected">
        <dl class="document-detail">
          <dt>显示名称</dt><dd>{{ selected.displayName }}</dd>
          <dt>原始文件名</dt><dd>{{ selected.originalFilename }}</dd>
          <dt>处理状态</dt>
          <dd>
            <el-tag :type="documentStatusType(selected.status)">
              {{ documentStatusLabel(selected.status) }}
            </el-tag>
          </dd>
          <dt>文件类型</dt><dd>{{ selected.mimeType }}</dd>
          <dt>文件大小</dt><dd>{{ formatBytes(selected.sizeBytes) }}</dd>
          <dt>解析器</dt><dd>{{ selected.parserType || '尚未解析' }}</dd>
          <dt>文本分块</dt><dd>{{ selected.chunkCount }}</dd>
          <dt>向量提供方</dt><dd>{{ selected.embeddingProvider || '尚未生成' }}</dd>
          <dt>向量模型</dt><dd>{{ selected.embeddingModel || '尚未生成' }}</dd>
          <dt>向量维度</dt><dd>{{ selected.embeddingDimension || '尚未生成' }}</dd>
          <dt>上传者</dt><dd>{{ selected.uploadedByDisplayName || '未知成员' }}</dd>
          <dt>上传时间</dt><dd>{{ formatDateTime(selected.createdAt) }}</dd>
          <dt>索引完成时间</dt><dd>{{ formatDateTime(selected.indexedAt) }}</dd>
        </dl>
        <el-alert
          v-if="selected.errorMessage"
          :title="selected.errorMessage"
          type="error"
          :closable="false"
          show-icon
        />
        <div class="actions">
          <el-button @click="download(selected)">下载原文件</el-button>
          <el-button
            v-if="canManage && selected.status === 'FAILED'"
            type="warning"
            @click="retry(selected)"
          >
            重新处理
          </el-button>
          <el-button v-if="canManage" type="danger" @click="remove(selected)">
            删除文档
          </el-button>
        </div>
      </template>
    </el-drawer>
  </main>
</template>
