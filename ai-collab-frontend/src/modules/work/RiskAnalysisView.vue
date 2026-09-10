<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import { formatDate } from '../../shared/display-labels'
import EmptyState from '../../shared/EmptyState.vue'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { reportApi, type RiskAnalysis, type RiskItem } from './report-api'

const route = useRoute()
const projectId = route.params.projectId as string

const analysis = ref<RiskAnalysis | null>(null)
const loading = ref(false)

const project = ref<Project | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [result, projectResult] = await Promise.all([
      reportApi.riskAnalysis(projectId),
      projectApi.get(projectId),
    ])
    analysis.value = result.data
    project.value = projectResult.data
  } catch (error) {
    showApiError(error, '延期风险分析加载')
  } finally {
    loading.value = false
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
    default: return type
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="延期风险分析"
      :context="project?.name"
    />
    <section v-loading="loading" class="risk-stack">
      <EmptyState v-if="!loading && !analysis" compact title="暂无风险数据" description="任务数据充足后会自动生成风险评估" />
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
        </div>
        <div class="inline-notice" role="status">
          <span class="status-dot" :class="analysis.summary.highRiskCount > 0 ? 'wait' : 'done'" />
          <span>{{ analysis.summary.overallAssessment }}</span>
        </div>

        <section v-if="analysis.risks.length > 0" class="risk-list-section">
          <h3 class="section-title">风险列表</h3>
          <el-table :data="analysis.risks" style="width: 100%">
            <el-table-column prop="taskTitle" label="任务" min-width="200" />
            <el-table-column label="风险类型" width="100">
              <template #default="{ row }">
                <span>{{ getRiskTypeLabel(row.riskType) }}</span>
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
.risk-list-section { display: grid; gap: 10px; }
.summary-label { font-size: 13px; color: var(--color-text-secondary); }
.risk-danger { color: var(--color-danger); }
.risk-warn { color: var(--color-warning); }
.severity { font-weight: 600; font-size: 13px; }
.severity.high { color: var(--color-danger); }
.severity.medium { color: var(--color-warning); }
.severity.low { color: var(--color-text-secondary); }
</style>
