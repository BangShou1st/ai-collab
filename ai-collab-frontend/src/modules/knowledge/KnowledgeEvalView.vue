<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { knowledgeApi } from './knowledge-api'
import {
  toEvalRequest,
  validateEvalRows,
  type KnowledgeEvalFormRow,
} from './knowledge-eval-form'
import type { KnowledgeEvalRun, KnowledgeEvalRunDetail } from './types'

const route = useRoute()
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

const project = ref<Project | null>(null)
const documents = ref<ProjectDocument[]>([])
const runs = ref<KnowledgeEvalRun[]>([])
const selectedRun = ref<KnowledgeEvalRunDetail | null>(null)
const loading = ref(true)
const running = ref(false)
const errorMessage = ref('')
let rowSequence = 1
const testCases = ref<KnowledgeEvalFormRow[]>([{
  id: 'eval-row-1',
  question: '',
  expectedDocumentIds: [],
}])

const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const readyDocuments = computed(() => documents.value.filter(document => document.status === 'READY'))

onMounted(async () => {
  if (!projectId.value) return
  try {
    const [projectResult, runsResult, documentsResult] = await Promise.all([
      projectApi.get(projectId.value),
      knowledgeApi.listEvalRuns(projectId.value),
      documentApi.list(projectId.value),
    ])
    project.value = projectResult.data
    runs.value = runsResult.data
    documents.value = documentsResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
})

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

function formatMetric(value: number | null): string {
  if (value === null || value === undefined) return '—'
  return `${(value * 100).toFixed(1)}%`
}

function statusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'COMPLETED': return 'success'
    case 'RUNNING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'COMPLETED': return '已完成'
    case 'RUNNING': return '运行中'
    case 'FAILED': return '失败'
    default: return status
  }
}

async function startEval(): Promise<void> {
  if (!projectId.value || running.value) return
  const validation = validateEvalRows(testCases.value)
  if (!validation.valid) {
    errorMessage.value = validation.message
    return
  }
  const requestCases = toEvalRequest(testCases.value)
  try {
    await ElMessageBox.confirm(
      `将运行 ${requestCases.length} 个问题的评测，可能需要一些时间。`,
      '确认运行评测',
      { confirmButtonText: '开始评测', cancelButtonText: '取消', type: 'info' },
    )
    running.value = true
    await knowledgeApi.startEval(projectId.value, requestCases)
    ElMessage.success('评测已提交，请稍后查看结果')
    testCases.value = [{
      id: `eval-row-${++rowSequence}`,
      question: '',
      expectedDocumentIds: [],
    }]
    // Refresh runs
    const runsResult = await knowledgeApi.listEvalRuns(projectId.value)
    runs.value = runsResult.data
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
  } finally {
    running.value = false
  }
}

function addTestCase(): void {
  testCases.value.push({
    id: `eval-row-${++rowSequence}`,
    question: '',
    expectedDocumentIds: [],
  })
}

function removeTestCase(id: string): void {
  if (testCases.value.length === 1) return
  testCases.value = testCases.value.filter(row => row.id !== id)
}

async function viewRun(run: KnowledgeEvalRun): Promise<void> {
  if (!projectId.value) return
  try {
    const result = await knowledgeApi.getEvalRun(projectId.value, run.id)
    selectedRun.value = result.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="检索质量评测"
      title="知识库评测"
      :context="project?.name"
    />

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />

    <section v-loading="loading" class="eval-content">
      <el-card v-if="canManage" class="eval-create">
        <template #header>
          <div class="eval-create-header">
            <strong>创建知识库评测</strong>
            <el-button @click="addTestCase">添加问题</el-button>
          </div>
        </template>
        <el-alert
          title="使用方法：输入一个能从项目资料中回答的问题，再选择应该被检索到的文档。系统会检查正确文档是否排在检索结果前面。"
          type="info"
          :closable="false"
          show-icon
        />
        <div class="metric-guide">
          <span><strong>Recall@3</strong>：预期文档是否出现在前 3 个结果中</span>
          <span><strong>Recall@5</strong>：预期文档是否出现在前 5 个结果中</span>
          <span><strong>MRR</strong>：正确文档越靠前，分数越高</span>
        </div>
        <div class="eval-case-list">
          <section v-for="(row, index) in testCases" :key="row.id" class="eval-case">
            <header>
              <strong>问题 {{ index + 1 }}</strong>
              <el-button
                text
                type="danger"
                :disabled="testCases.length === 1"
                @click="removeTestCase(row.id)"
              >
                删除
              </el-button>
            </header>
            <el-input
              v-model="row.question"
              placeholder="例如：项目最终验收需要提交哪些材料？"
            />
            <el-select
              v-model="row.expectedDocumentIds"
              multiple
              filterable
              placeholder="选择应该包含答案的项目文档"
            >
              <el-option
                v-for="document in readyDocuments"
                :key="document.id"
                :label="document.displayName"
                :value="document.id"
              />
            </el-select>
          </section>
        </div>
        <el-button
          type="primary"
          :loading="running"
          style="margin-top: 12px"
          @click="startEval"
        >
          运行评测
        </el-button>
      </el-card>

      <el-card>
        <template #header>
          <strong>评测历史</strong>
        </template>
        <el-table :data="runs" empty-text="暂无评测记录" @row-click="viewRun">
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="问题数" width="100" prop="totalQuestions" />
          <el-table-column label="Recall@3" width="110">
            <template #default="{ row }">{{ formatMetric(row.recallAt3) }}</template>
          </el-table-column>
          <el-table-column label="Recall@5" width="110">
            <template #default="{ row }">{{ formatMetric(row.recallAt5) }}</template>
          </el-table-column>
          <el-table-column label="MRR" width="100">
            <template #default="{ row }">{{ formatMetric(row.mrr) }}</template>
          </el-table-column>
          <el-table-column label="平均相似度" width="110">
            <template #default="{ row }">{{ formatMetric(row.avgSimilarity) }}</template>
          </el-table-column>
          <el-table-column label="创建时间" min-width="140">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card v-if="selectedRun" class="eval-detail">
        <template #header>
          <div class="eval-detail-header">
            <strong>评测详情</strong>
            <el-button text @click="selectedRun = null">关闭</el-button>
          </div>
        </template>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="Recall@3">{{ formatMetric(selectedRun.run.recallAt3) }}</el-descriptions-item>
          <el-descriptions-item label="Recall@5">{{ formatMetric(selectedRun.run.recallAt5) }}</el-descriptions-item>
          <el-descriptions-item label="MRR">{{ formatMetric(selectedRun.run.mrr) }}</el-descriptions-item>
          <el-descriptions-item label="平均相似度">{{ formatMetric(selectedRun.run.avgSimilarity) }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="selectedRun.results" empty-text="无结果" style="margin-top: 12px">
          <el-table-column label="问题" min-width="250" prop="question" />
          <el-table-column label="Recall@3" width="100">
            <template #default="{ row }">{{ formatMetric(row.recallAt3) }}</template>
          </el-table-column>
          <el-table-column label="Recall@5" width="100">
            <template #default="{ row }">{{ formatMetric(row.recallAt5) }}</template>
          </el-table-column>
          <el-table-column label="MRR" width="100">
            <template #default="{ row }">{{ formatMetric(row.mrr) }}</template>
          </el-table-column>
          <el-table-column label="预期文档" width="120">
            <template #default="{ row }">{{ row.expectedDocumentIds.length }} 个</template>
          </el-table-column>
          <el-table-column label="检索到" width="120">
            <template #default="{ row }">{{ row.retrievedDocumentIds.length }} 个</template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>
  </main>
</template>

<style scoped>
.eval-content { display: grid; gap: 16px; }
.eval-create { max-width: 920px; }
.eval-create-header, .eval-case header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.metric-guide {
  display: grid;
  gap: 6px;
  margin: 14px 0;
  color: var(--el-text-color-secondary);
}
.eval-case-list { display: grid; gap: 12px; }
.eval-case {
  display: grid;
  gap: 10px;
  padding: 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
}
.eval-detail-header { display: flex; justify-content: space-between; align-items: center; }
</style>
