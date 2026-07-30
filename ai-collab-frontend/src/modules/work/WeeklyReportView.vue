<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { reportApi, type WeeklyReport } from './report-api'

const route = useRoute()
const projectId = route.params.projectId as string

const report = ref<WeeklyReport | null>(null)
const loading = ref(false)
const errorMessage = ref('')

const project = ref<Project | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [result, projectResult] = await Promise.all([
      reportApi.weeklyReport(projectId),
      projectApi.get(projectId),
    ])
    report.value = result.data
    project.value = projectResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

function formatRate(rate: number | null): string {
  if (rate === null) return '0%'
  return (rate * 100).toFixed(1) + '%'
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="AI 周报"
      :context="project?.name"
    />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="report-container">
      <el-empty v-if="!loading && !report" description="暂无报告数据" :image-size="64" />
      <template v-else-if="report">
        <el-card class="stats-card">
          <template #header>
            <h3>任务统计</h3>
          </template>
          <div class="stats-grid">
            <div class="stat-item">
              <span class="stat-value">{{ report.taskStats.totalTasks }}</span>
              <span class="stat-label">总任务</span>
            </div>
            <div class="stat-item">
              <span class="stat-value success">{{ report.taskStats.completedTasks }}</span>
              <span class="stat-label">已完成</span>
            </div>
            <div class="stat-item">
              <span class="stat-value primary">{{ report.taskStats.inProgressTasks }}</span>
              <span class="stat-label">进行中</span>
            </div>
            <div class="stat-item">
              <span class="stat-value danger">{{ report.taskStats.overdueTasks }}</span>
              <span class="stat-label">逾期</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ formatRate(report.taskStats.completionRate) }}</span>
              <span class="stat-label">完成率</span>
            </div>
          </div>
        </el-card>

        <el-card class="stats-card">
          <template #header>
            <h3>里程碑统计</h3>
          </template>
          <div class="stats-grid">
            <div class="stat-item">
              <span class="stat-value">{{ report.milestoneStats.totalMilestones }}</span>
              <span class="stat-label">总里程碑</span>
            </div>
            <div class="stat-item">
              <span class="stat-value success">{{ report.milestoneStats.completedMilestones }}</span>
              <span class="stat-label">已完成</span>
            </div>
            <div class="stat-item">
              <span class="stat-value primary">{{ report.milestoneStats.upcomingMilestones }}</span>
              <span class="stat-label">即将到期</span>
            </div>
            <div class="stat-item">
              <span class="stat-value danger">{{ report.milestoneStats.overdueMilestones }}</span>
              <span class="stat-label">已逾期</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="report.topContributors.length > 0" class="stats-card">
          <template #header>
            <h3>贡献者排行</h3>
          </template>
          <div class="contributors-list">
            <div v-for="contributor in report.topContributors" :key="contributor.displayName" class="contributor-item">
              <span class="contributor-name">{{ contributor.displayName }}</span>
              <span class="contributor-stats">{{ contributor.completedTasks }}/{{ contributor.totalTasks }} 完成</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="report.highlights.length > 0" class="stats-card">
          <template #header>
            <h3>✅ 亮点</h3>
          </template>
          <ul class="list-items">
            <li v-for="(highlight, index) in report.highlights" :key="index" class="highlight-item">
              {{ highlight }}
            </li>
          </ul>
        </el-card>

        <el-card v-if="report.risks.length > 0" class="stats-card">
          <template #header>
            <h3>⚠️ 风险</h3>
          </template>
          <ul class="list-items">
            <li v-for="(risk, index) in report.risks" :key="index" class="risk-item">
              {{ risk }}
            </li>
          </ul>
        </el-card>
      </template>
    </section>
  </main>
</template>

<style scoped>
.report-container {
  padding: 0 24px;
}

.stats-card {
  margin-bottom: 16px;
}

.stats-card h3 {
  margin: 0;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-value {
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.stat-value.success { color: #67c23a; }
.stat-value.primary { color: #409eff; }
.stat-value.danger { color: #f56c6c; }

.stat-label {
  font-size: 14px;
  color: #909399;
  margin-top: 4px;
}

.contributors-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.contributor-item {
  display: flex;
  justify-content: space-between;
  padding: 8px 0;
  border-bottom: 1px solid #f0f0f0;
}

.contributor-name {
  font-weight: 500;
}

.contributor-stats {
  color: #909399;
}

.list-items {
  margin: 0;
  padding-left: 20px;
}

.highlight-item {
  color: #67c23a;
  margin-bottom: 8px;
}

.risk-item {
  color: #e6a23c;
  margin-bottom: 8px;
}
</style>
