<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { planningApi } from '../planning/planning-api'
import type { TaskPlan } from '../planning/types'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { reportApi, type PlanComparison } from './report-api'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))
const routePlanId = computed(() =>
  typeof route.query.planId === 'string' ? route.query.planId : '',
)

const comparison = ref<PlanComparison | null>(null)
const plans = ref<TaskPlan[]>([])
const selectedPlanId = ref('')
const loading = ref(false)

const project = ref<Project | null>(null)

async function loadComparison(planId: string): Promise<void> {
  if (!projectId.value || !planId) return
  loading.value = true
  comparison.value = null
  try {
    const result = await reportApi.planComparison(projectId.value, planId)
    comparison.value = result.data
  } catch (error) {
    showApiError(error, '规划对比加载')
  } finally {
    loading.value = false
  }
}

async function load(): Promise<void> {
  if (!projectId.value) return
  loading.value = true
  try {
    const [projectResult, planResult] = await Promise.all([
      projectApi.get(projectId.value),
      planningApi.list(projectId.value),
    ])
    project.value = projectResult.data
    plans.value = planResult.data.data
    if (routePlanId.value && plans.value.some(plan => plan.id === routePlanId.value)) {
      selectedPlanId.value = routePlanId.value
      await loadComparison(routePlanId.value)
    }
  } catch (error) {
    showApiError(error, '规划列表加载')
  } finally {
    loading.value = false
  }
}

function selectPlan(planId: string): void {
  selectedPlanId.value = planId
  void loadComparison(planId)
}

function getStatusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'MATCHED': return 'success'
    case 'MODIFIED': return 'warning'
    case 'MISSING': return 'danger'
    case 'EXTRA': return 'info'
    default: return 'info'
  }
}

function getStatusLabel(status: string): string {
  switch (status) {
    case 'MATCHED': return '匹配'
    case 'MODIFIED': return '已修改'
    case 'MISSING': return '缺失'
    case 'EXTRA': return '额外'
    default: return status
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="规划对比"
      :context="project?.name"
    />
    <section v-loading="loading" class="comparison-container">
      <div class="plan-picker">
        <label for="comparison-plan">选择任务规划</label>
        <el-select
          id="comparison-plan"
          v-model="selectedPlanId"
          placeholder="请选择要对比的任务规划"
          filterable
          @change="selectPlan"
        >
          <el-option
            v-for="plan in plans"
            :key="plan.id"
            :label="`${plan.title} · 版本 ${plan.latestVersionNo}`"
            :value="plan.id"
          />
        </el-select>
      </div>
      <el-empty
        v-if="!loading && !comparison"
        :description="plans.length ? '请选择要对比的任务规划' : '当前项目还没有可对比的任务规划'"
        :image-size="64"
      />
      <template v-else-if="comparison">
        <el-card class="summary-card">
          <template #header>
            <h3>{{ comparison.planName }} - 对比摘要</h3>
          </template>
          <div class="summary-grid">
            <div class="summary-item">
              <span class="summary-value">{{ comparison.summary.totalPlanned }}</span>
              <span class="summary-label">规划任务数</span>
            </div>
            <div class="summary-item success">
              <span class="summary-value">{{ comparison.summary.matchedTasks }}</span>
              <span class="summary-label">匹配任务</span>
            </div>
            <div class="summary-item warning">
              <span class="summary-value">{{ comparison.summary.modifiedTasks }}</span>
              <span class="summary-label">已修改</span>
            </div>
            <div class="summary-item danger">
              <span class="summary-value">{{ comparison.summary.missingTasks }}</span>
              <span class="summary-label">缺失</span>
            </div>
            <div class="summary-item info">
              <span class="summary-value">{{ comparison.summary.extraTasks }}</span>
              <span class="summary-label">额外</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="comparison.taskComparisons.length > 0" class="tasks-card">
          <template #header>
            <h3>任务对比详情</h3>
          </template>
          <el-table :data="comparison.taskComparisons" style="width: 100%">
            <el-table-column prop="actualTitle" label="任务名称" min-width="200" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="getStatusType(row.status)" size="small">
                  {{ getStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="差异" min-width="300">
              <template #default="{ row }">
                <div v-if="row.diffs.length > 0" class="diffs-list">
                  <div v-for="diff in row.diffs" :key="diff.fieldName" class="diff-item">
                    <span class="diff-field">{{ diff.fieldName }}:</span>
                    <span class="diff-planned">{{ diff.plannedValue }}</span>
                    <span class="diff-arrow">→</span>
                    <span class="diff-actual">{{ diff.actualValue }}</span>
                  </div>
                </div>
                <span v-else class="no-diff">无差异</span>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </template>
    </section>
  </main>
</template>

<style scoped>
.comparison-container {
  padding: 0 24px;
}

.plan-picker {
  display: grid;
  grid-template-columns: 120px minmax(260px, 520px);
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.plan-picker label {
  font-weight: 650;
}

.summary-card {
  margin-bottom: 16px;
}

.summary-card h3 {
  margin: 0;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}

.summary-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 16px;
  border-radius: 8px;
  background-color: #f5f7fa;
}

.summary-item.success { background-color: #f0f9eb; }
.summary-item.warning { background-color: #fdf6ec; }
.summary-item.danger { background-color: #fef0f0; }
.summary-item.info { background-color: #f4f4f5; }

.summary-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.summary-item.success .summary-value { color: #67c23a; }
.summary-item.warning .summary-value { color: #e6a23c; }
.summary-item.danger .summary-value { color: #f56c6c; }

.summary-label {
  font-size: 14px;
  color: #606266;
  margin-top: 4px;
}

.tasks-card h3 {
  margin: 0;
}

.diffs-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.diff-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}

.diff-field {
  font-weight: 500;
  color: #606266;
}

.diff-planned {
  color: #909399;
  text-decoration: line-through;
}

.diff-arrow {
  color: #909399;
}

.diff-actual {
  color: #409eff;
}

.no-diff {
  color: #909399;
  font-style: italic;
}
</style>
