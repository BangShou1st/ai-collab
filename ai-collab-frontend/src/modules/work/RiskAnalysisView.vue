<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showApiError } from '../../api/api-result'
import { formatDate } from '../../shared/display-labels'
import EmptyState from '../../shared/EmptyState.vue'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { reportApi, type RiskAnalysis, type RiskItem } from './report-api'

const route = useRoute()
const router = useRouter()
const projectId = computed(() => String(route.params.projectId ?? ''))

const analysis = ref<RiskAnalysis | null>(null)
const loading = ref(false)
const loadError = ref(false)
const project = ref<Project | null>(null)

// 请求令牌：快速切换项目时旧请求不得覆盖新项目的数据
let requestSeq = 0

async function load(): Promise<void> {
  const token = ++requestSeq
  const pid = projectId.value
  if (!pid) return
  loading.value = true
  loadError.value = false
  try {
    const [result, projectResult] = await Promise.all([
      reportApi.riskAnalysis(pid),
      projectApi.get(pid),
    ])
    if (token !== requestSeq || pid !== projectId.value) return
    analysis.value = result.data
    project.value = projectResult.data
  } catch (error) {
    if (token !== requestSeq) return
    loadError.value = true
    showApiError(error, '延期风险分析加载')
  } finally {
    if (token === requestSeq) loading.value = false
  }
}

function getSeverityLabel(severity: string): string {
  switch (severity) {
    case 'HIGH': return '高'
    case 'MEDIUM': return '中'
    default: return '低'
  }
}

function getRiskTypeLabel(type: string): string {
  switch (type) {
    case 'OVERDUE': return '逾期'
    case 'BLOCKED': return '阻塞'
    case 'UNASSIGNED': return '未分配'
    case 'DUE_SOON': return '即将到期'
    default: return type
  }
}

// 口径优先用后端去重结果；旧响应回退到前端按任务去重
const riskTaskCount = computed(() => {
  if (!analysis.value) return 0
  return analysis.value.summary.affectedTaskCount
    ?? new Set(analysis.value.risks.map(risk => risk.taskId ?? risk.taskTitle)).size
})

function openTask(row: RiskItem): void {
  if (!row.taskId || !projectId.value) return
  void router.push(`/projects/${projectId.value}/tasks?view=list&taskId=${row.taskId}`)
}

watch(projectId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="延期风险分析"
      description="基于规则的当前风险检测（逾期 / 阻塞 / 未分配 / 即将到期），非 AI 预测"
      :context="project?.name"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="risk-stack">
      <el-result
        v-if="!loading && loadError && !analysis"
        icon="error"
        title="风险分析加载失败"
        sub-title="网络或服务异常，可能是临时问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </el-result>
      <EmptyState v-else-if="!loading && !analysis" compact title="暂无风险数据" description="任务数据充足后会自动生成风险评估" />
      <template v-else-if="analysis">
        <div class="risk-metrics">
          <div class="risk-tile">
            <strong class="risk-danger">{{ analysis.summary.highRiskCount }}</strong>
            <span class="summary-label">高风险</span>
          </div>
          <div class="risk-tile">
            <strong class="risk-warn">{{ analysis.summary.mediumRiskCount }}</strong>
            <span class="summary-label">中风险</span>
          </div>
          <div class="risk-tile">
            <strong>{{ analysis.summary.lowRiskCount }}</strong>
            <span class="summary-label">低风险</span>
          </div>
          <div class="risk-tile">
            <strong>{{ riskTaskCount }}</strong>
            <span class="summary-label">涉及任务数</span>
          </div>
          <div v-if="analysis.summary.checkedTaskCount !== undefined" class="risk-tile">
            <strong>{{ analysis.summary.checkedTaskCount }}</strong>
            <span class="summary-label">检查的任务总数</span>
          </div>
        </div>
        <div v-if="analysis.rules && analysis.rules.length > 0" class="rules-note" role="note">
          检测规则：{{ analysis.rules.map(rule => rule.name).join('；') }}
        </div>
        <div class="inline-notice" role="status">
          <span class="status-dot" :class="analysis.summary.highRiskCount > 0 ? 'wait' : 'done'" />
          <span>{{ analysis.summary.overallAssessment }}</span>
        </div>

        <section v-if="analysis.risks.length > 0" class="risk-list-section">
          <h3 class="section-title">风险列表</h3>
          <el-table :data="analysis.risks" style="width: 100%">
            <el-table-column label="任务" min-width="200">
              <template #default="{ row }">
                <el-button
                  v-if="row.taskId"
                  link
                  type="primary"
                  class="task-link"
                  @click="openTask(row)"
                >{{ row.taskTitle }}</el-button>
                <span v-else>{{ row.taskTitle }}</span>
              </template>
            </el-table-column>
            <el-table-column label="风险类型" width="100">
              <template #default="{ row }">
                <span>{{ getRiskTypeLabel(row.riskType) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="涉及任务" width="90">
              <template #default="{ row }">
                <span v-if="row.affectedTaskCount">{{ row.affectedTaskCount }} 个</span>
                <span v-else>1 个</span>
              </template>
            </el-table-column>
            <el-table-column label="严重程度" width="100">
              <template #default="{ row }">
                <span class="severity" :class="String(row.severity).toLowerCase()">
                  {{ getSeverityLabel(row.severity) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="250" />
            <el-table-column label="截止日期" width="120">
              <template #default="{ row }">
                {{ formatDate(row.dueDate) }}
              </template>
            </el-table-column>
            <el-table-column prop="assigneeName" label="负责人" width="120" />
          </el-table>
        </section>
      </template>
    </section>
  </main>
</template>

<style scoped>
.risk-stack { display: grid; gap: 16px; }
.risk-metrics { grid-template-columns: repeat(5, minmax(0, 1fr)); }
.risk-list-section { display: grid; gap: 10px; }
.summary-label { font-size: 13px; color: var(--color-text-secondary); }
.risk-danger { color: var(--color-danger); }
.risk-warn { color: var(--color-warning); }
.severity { font-weight: 600; font-size: 13px; }
.severity.high { color: var(--color-danger); }
.severity.medium { color: var(--color-warning); }
.severity.low { color: var(--color-text-secondary); }
@media (max-width: 760px) { .risk-metrics { grid-template-columns: 1fr; } }
</style>
