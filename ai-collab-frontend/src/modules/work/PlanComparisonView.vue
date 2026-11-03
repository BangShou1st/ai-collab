<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
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
const loadError = ref(false)
const project = ref<Project | null>(null)

// 请求令牌：快速切换项目/规划时旧请求不得覆盖新结果
let listRequestSeq = 0
let comparisonRequestSeq = 0

async function loadComparison(planId: string): Promise<void> {
  if (!projectId.value || !planId) return
  const token = ++comparisonRequestSeq
  const pid = projectId.value
  loading.value = true
  comparison.value = null
  try {
    const result = await reportApi.planComparison(pid, planId)
    if (token !== comparisonRequestSeq || pid !== projectId.value) return
    comparison.value = result.data
  } catch (error) {
    if (token !== comparisonRequestSeq) return
    showApiError(error, '规划对比加载')
  } finally {
    if (token === comparisonRequestSeq) loading.value = false
  }
}

async function load(): Promise<void> {
  if (!projectId.value) return
  const token = ++listRequestSeq
  const pid = projectId.value
  loading.value = true
  loadError.value = false
  // 项目切换后清空旧项目的规划选择与对比结果
  selectedPlanId.value = ''
  comparison.value = null
  try {
    const [projectResult, planResult] = await Promise.all([
      projectApi.get(pid),
      planningApi.list(pid),
    ])
    if (token !== listRequestSeq || pid !== projectId.value) return
    project.value = projectResult.data
    plans.value = planResult.data.data
    if (routePlanId.value && plans.value.some(plan => plan.id === routePlanId.value)) {
      selectedPlanId.value = routePlanId.value
      await loadComparison(routePlanId.value)
    }
  } catch (error) {
    if (token !== listRequestSeq) return
    loadError.value = true
    showApiError(error, '规划列表加载')
  } finally {
    if (token === listRequestSeq) loading.value = false
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
    case 'UNLINKED': return '无法关联'
    case 'OTHER_VERSION': return '其他版本'
    case 'AMBIGUOUS': return '来源重复'
    default: return status
  }
}

const planNotApplied = computed(() => comparison.value?.comparisonStatus === 'PLAN_NOT_APPLIED')

const baselineLabel = computed(() => {
  const current = comparison.value
  if (!current) return ''
  if (current.baselineSource === 'APPLIED_VERSION' && current.baselineVersionNo != null) {
    return `基准：实际应用版本（版本 ${current.baselineVersionNo}）`
  }
  return '基准：未知（规划未确认应用，不做任务级对比）'
})

watch(projectId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="规划对比"
      description="对比所选规划版本与项目实际任务；无法可靠关联的任务会明确标注"
      :context="project?.name"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="comparison-container">
      <el-result
        v-if="!loading && loadError && plans.length === 0"
        icon="error"
        title="规划列表加载失败"
        sub-title="网络或服务异常，可能是临时问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </el-result>
      <div v-else class="plan-picker">
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
      <el-alert
        v-else-if="planNotApplied"
        type="warning"
        :closable="false"
        :title="comparison?.message || '该规划尚未确认应用到任务中心，暂无可靠对比基准'"
        show-icon
      />
      <template v-else-if="comparison">
        <div class="baseline-banner" role="status">
          <span>{{ baselineLabel }}</span>
          <span v-if="comparison.appliedAt" class="baseline-applied">应用时间：{{ comparison.appliedAt }}</span>
        </div>
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
          <p v-if="comparison.otherVersionTaskCount || comparison.unlinkedTaskCount || comparison.ambiguousTaskCount" class="baseline-note">
            <template v-if="comparison.otherVersionTaskCount">其他规划版本任务 {{ comparison.otherVersionTaskCount }} 个（不计入额外）；</template>
            <template v-if="comparison.unlinkedTaskCount">无法关联实际任务 {{ comparison.unlinkedTaskCount }} 个；</template>
            <template v-if="comparison.ambiguousTaskCount">来源键重复任务 {{ comparison.ambiguousTaskCount }} 个（不做精确判定）。</template>
          </p>
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

.baseline-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  font-size: 13px;
  font-weight: 500;
}

.baseline-applied {
  color: var(--color-text-muted);
  font-weight: 400;
  font-size: 12px;
}

.baseline-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
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
  background-color: var(--color-surface-raised);
}

.summary-item.success { background-color: #f0f9eb; }
.summary-item.warning { background-color: #fdf6ec; }
.summary-item.danger { background-color: #fef0f0; }
.summary-item.info { background-color: #f4f4f5; }

.summary-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text);
}

.summary-item.success .summary-value { color: var(--color-success); }
.summary-item.warning .summary-value { color: var(--color-warning); }
.summary-item.danger .summary-value { color: var(--color-danger); }

.summary-label {
  font-size: 14px;
  color: var(--color-text-secondary);
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
  color: var(--color-text-secondary);
}

.diff-planned {
  color: var(--color-text-muted);
  text-decoration: line-through;
}

.diff-arrow {
  color: var(--color-text-muted);
}

.diff-actual {
  color: var(--color-primary);
}

.no-diff {
  color: var(--color-text-muted);
  font-style: italic;
}
</style>
