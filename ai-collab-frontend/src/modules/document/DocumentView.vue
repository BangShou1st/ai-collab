<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import {
  documentStatusLabel,
  documentStatusType,
  formatDateTime,
} from '../../shared/display-labels'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { documentApi } from './document-api'
import {
  createUploadCandidates,
  uploadCandidatesSequentially,
  validateUploadCandidate,
  type UploadCandidate,
} from './document-upload'
import type { ProjectDocument } from './types'

const route = useRoute()
const projectId = String(route.params.projectId)
const project = ref<Project | null>(null)
const documents = ref<ProjectDocument[]>([])
const selected = ref<ProjectDocument | null>(null)
const loading = ref(false)
const drawerVisible = ref(false)
const uploadVisible = ref(false)
const uploading = ref(false)
const operationId = ref('')
const uploadCandidates = ref<UploadCandidate[]>([])
const fileInput = ref<HTMLInputElement | null>(null)
const dragging = ref(false)
let pollTimer: number | null = null
let active = true

const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const readyDocuments = computed(() => documents.value.filter(d => d.status === 'READY'))
const batchReindexing = ref(false)
const reindexProgress = ref<{ total: number; completed: number; failed: number; inProgress: number } | null>(null)
const processingStatuses = new Set(['UPLOADED', 'PARSING', 'INDEXING', 'DELETING'])
const hasProcessing = computed(() => documents.value.some(item => processingStatuses.has(item.status)))
const uploadValidationMessage = computed(() => {
  if (uploadCandidates.value.length === 0) return '请选择要上传的文件'
  return uploadCandidates.value
    .map(validateUploadCandidate)
    .find(Boolean) ?? ''
})

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function load(showLoading = true): Promise<void> {
  if (showLoading) loading.value = true
  try {
    const [projectResult, documentResult] = await Promise.allSettled([
      projectApi.get(projectId),
      documentApi.list(projectId),
    ])
    if (!active) return
    if (projectResult.status === 'fulfilled') {
      project.value = projectResult.value.data
    } else {
      showApiError(projectResult.reason, '项目信息加载')
    }
    if (documentResult.status === 'fulfilled') {
      documents.value = documentResult.value.data
    } else {
      documents.value = []
      showApiError(documentResult.reason, '文档列表加载')
    }
    if (selected.value) {
      selected.value = documents.value.find(item => item.id === selected.value?.id) ?? null
      if (!selected.value) drawerVisible.value = false
    }
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
  uploadCandidates.value = createUploadCandidates(files ? Array.from(files) : [])
}

function onFileChange(event: Event): void {
  chooseFiles((event.target as HTMLInputElement).files)
}

function onDrop(event: DragEvent): void {
  dragging.value = false
  chooseFiles(event.dataTransfer?.files ?? null)
}

function resetUpload(): void {
  uploadCandidates.value = []
  if (fileInput.value) fileInput.value.value = ''
}

async function upload(): Promise<void> {
  if (uploadCandidates.value.length === 0 || uploadValidationMessage.value || uploading.value) return
  uploading.value = true
  try {
    const results = await uploadCandidatesSequentially(
      uploadCandidates.value,
      candidate => documentApi.upload(projectId, candidate.file, candidate.displayName),
    )
    await load(false)
    const succeeded = results.filter(item => item.status === 'success').length
    const failed = results.length - succeeded
    if (failed === 0) {
      uploadVisible.value = false
      resetUpload()
      ElMessage.success(`已上传 ${succeeded} 个文档，正在后台处理`)
    } else {
      ElMessage.warning(`已上传 ${succeeded} 个文档，${failed} 个失败；请查看文件列表中的具体原因`)
    }
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
    showApiError(error, '文档详情加载')
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
    showApiError(error, '文档下载')
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
    showApiError(error, '文档处理重试')
  } finally {
    operationId.value = ''
  }
}

async function reindex(item: ProjectDocument): Promise<void> {
  if (!canManage.value || item.status !== 'READY' || operationId.value) return
  try {
    await ElMessageBox.confirm(
      `将重新解析和索引文档"${item.displayName}"，期间文档状态会变为处理中。`,
      '重新索引文档',
      { confirmButtonText: '确认重新索引', cancelButtonText: '取消', type: 'info' },
    )
    operationId.value = item.id
    await documentApi.reindex(projectId, item.id)
    await load(false)
    ElMessage.success('已提交重新索引')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '文档重新索引')
  } finally {
    operationId.value = ''
  }
}

async function reindexAll(): Promise<void> {
  if (!canManage.value || batchReindexing.value || readyDocuments.value.length === 0) return
  try {
    await ElMessageBox.confirm(
      `将重新解析和索引全部 ${readyDocuments.value.length} 个已就绪文档，期间文档状态会变为处理中。`,
      '批量重建索引',
      { confirmButtonText: '确认重建', cancelButtonText: '取消', type: 'warning' },
    )
    batchReindexing.value = true
    await documentApi.reindexAll(projectId)
    await load(false)
    await pollReindexProgress()
    ElMessage.success('已提交批量重建索引')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '文档批量重新索引')
  } finally {
    batchReindexing.value = false
  }
}

async function pollReindexProgress(): Promise<void> {
  try {
    const result = await documentApi.getReindexProgress(projectId)
    reindexProgress.value = result.data
    if (result.data.inProgress > 0) {
      setTimeout(() => { void pollReindexProgress() }, 2000)
    } else {
      await load(false)
      setTimeout(() => { reindexProgress.value = null }, 3000)
    }
  } catch (error) {
    showApiError(error, '重建索引进度加载')
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
    showApiError(error, '文档删除')
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
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="uploadVisible = true">
          上传文档
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="project?.role === 'MEMBER'"
      title="你可以查看和下载项目文档；上传、重试和删除需要管理员权限。"
      type="info"
      :closable="false"
      show-icon
    />

    <el-card v-loading="loading" class="document-list-card">
      <template #header>
        <div class="document-card-header">
          <span>项目文档</span>
          <el-button
            v-if="canManage && readyDocuments.length > 0"
            type="warning"
            size="small"
            :loading="batchReindexing"
            @click="reindexAll"
          >
            重建全部索引
          </el-button>
        </div>
      </template>
      <el-alert
        v-if="reindexProgress && reindexProgress.total > 0"
        :title="`批量重建索引进度：${reindexProgress.completed}/${reindexProgress.total} 完成，${reindexProgress.failed} 失败`"
        type="info"
        show-icon
        :closable="false"
        class="reindex-progress"
      />
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
        <el-table-column label="版本" width="80">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
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
              v-if="canManage && row.status === 'READY'"
              text
              aria-label="重新索引文档"
              @click.stop="reindex(row)"
            >
              重新索引
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
            <strong>{{ uploadCandidates.length ? `已选择 ${uploadCandidates.length} 个文件` : '将文件拖到这里' }}</strong>
            <span>或点击一次选择多个文件；支持 PDF、DOCX、Markdown、TXT，单个最大 20MB</span>
            <input
              ref="fileInput"
              class="visually-hidden"
              type="file"
              multiple
              accept=".pdf,.docx,.md,.markdown,.txt"
              @change="onFileChange"
            >
          </div>
        </el-form-item>
        <div v-if="uploadCandidates.length" class="upload-candidate-list">
          <div v-for="candidate in uploadCandidates" :key="candidate.id" class="upload-candidate">
            <div class="upload-candidate__heading">
              <span>{{ candidate.file.name }}</span>
              <el-tag v-if="candidate.status !== 'pending'" :type="candidate.status === 'success' ? 'success' : candidate.status === 'failed' ? 'danger' : 'info'">
                {{ candidate.status === 'success' ? '上传成功' : candidate.status === 'failed' ? '上传失败' : '上传中' }}
              </el-tag>
            </div>
            <el-input v-model="candidate.displayName" :maxlength="180" placeholder="显示名称" />
            <small v-if="candidate.error" class="upload-candidate__error">
              {{ normalizeApiError(candidate.error).message }}
            </small>
          </div>
        </div>
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
            上传 {{ uploadCandidates.length }} 个文件
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
