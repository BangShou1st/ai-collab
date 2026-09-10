<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import { formatDate } from '../../shared/display-labels'
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

function getSeverityType(severity: string): 'danger' | 'warning' | 'info' {
  switch (severity) {
    case 'HIGH': return 'danger'
    case 'MEDIUM': return 'warning'
    default: return 'info'
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
    <section v-loading="loading" class="risk-container">
      <el-empty v-if="!loading && !analysis" description="暂无风险数据" :image-size="64" />
      <template v-else-if="analysis">
        <el-card class="summary-card">
          <div class="summary-grid">
            <div class="summary-item danger">
              <span class="summary-count">{{ analysis.summary.highRiskCount }}</span>
              <span class="summary-label">高风险</span>
            </div>
            <div class="summary-item warning">
              <span class="summary-count">{{ analysis.summary.mediumRiskCount }}</span>
              <span class="summary-label">中风险</span>
            </div>
            <div class="summary-item info">
              <span class="summary-count">{{ analysis.summary.lowRiskCount }}</span>
              <span class="summary-label">低风险</span>
            </div>
          </div>
          <div class="inline-notice" role="status">
            <span class="status-dot" :class="analysis.summary.highRiskCount > 0 ? 'wait' : 'done'" />
            <span>{{ analysis.summary.overallAssessment }}</span>
          </div>
        </el-card>

        <el-card v-if="analysis.risks.length > 0" class="risks-card">
          <template #header>
            <h3>风险列表</h3>
          </template>
          <el-table :data="analysis.risks" style="width: 100%">
            <el-table-column prop="taskTitle" label="任务" min-width="200" />
            <el-table-column label="风险类型" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ getRiskTypeLabel(row.riskType) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="严重程度" width="100">
              <template #default="{ row }">
                <el-tag :type="getSeverityType(row.severity)" size="small">
                  {{ getSeverityLabel(row.severity) }}
                </el-tag>
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
        </el-card>
      </template>
    </section>
  </main>
</template>

<style scoped>
.risk-container {
  padding: 0 24px;
}

.summary-card {
  margin-bottom: 16px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px;
  border-radius: 8px;
}

.summary-item.danger { background-color: #fef0f0; }
.summary-item.warning { background-color: #fdf6ec; }
.summary-item.info { background-color: #f4f4f5; }

.summary-count {
  font-size: 32px;
  font-weight: 600;
}

.summary-item.danger .summary-count { color: #f56c6c; }
.summary-item.warning .summary-count { color: #e6a23c; }
.summary-item.info .summary-count { color: #909399; }

.summary-label {
  font-size: 14px;
  color: #606266;
  margin-top: 4px;
}

.assessment-alert {
  margin-top: 8px;
}

.risks-card h3 {
  margin: 0;
}
</style>
