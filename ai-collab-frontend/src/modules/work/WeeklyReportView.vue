<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { reportApi, type WeeklyReport } from './report-api'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))

const report = ref<WeeklyReport | null>(null)
const loading = ref(false)
const loadError = ref(false)
const project = ref<Project | null>(null)

// 报告周期：默认本周（周一起始，含今天），支持上/下周切换
const periodStart = ref('')
const periodEnd = ref('')

function toIsoDate(date: Date): string {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

function setPeriodFor(anchor: Date): void {
  const day = anchor.getDay()
  const mondayOffset = day === 0 ? -6 : 1 - day
  const monday = new Date(anchor)
  monday.setDate(anchor.getDate() + mondayOffset)
  const sunday = new Date(monday)
  sunday.setDate(monday.getDate() + 6)
  periodStart.value = toIsoDate(monday)
  periodEnd.value = toIsoDate(sunday)
}

function shiftPeriod(weeks: number): void {
  const anchor = new Date(`${periodStart.value}T00:00:00`)
  anchor.setDate(anchor.getDate() + weeks * 7)
  setPeriodFor(anchor)
  void load()
}

setPeriodFor(new Date())

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
      reportApi.weeklyReport(pid, periodStart.value, periodEnd.value),
      projectApi.get(pid),
    ])
    if (token !== requestSeq || pid !== projectId.value) return
    report.value = result.data
    project.value = projectResult.data
  } catch (error) {
    if (token !== requestSeq) return
    loadError.value = true
    showApiError(error, '项目周报加载')
  } finally {
    if (token === requestSeq) loading.value = false
  }
}

function formatRate(rate: number | null): string {
  if (rate === null) return '—'
  return (rate * 100).toFixed(1) + '%'
}

function formatDateRange(start?: string, end?: string): string {
  if (start && end) return `${start} ~ ${end}`
  if (start) return `${start} 起`
  if (end) return `截至 ${end}`
  return ''
}

watch(projectId, () => { void load() })
onMounted(() => { void load() })
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目报告"
      title="项目周报"
      description="确定性周度统计：数字来自数据库实时查询；AI 生成的周报文字请使用协作 Agent 的周报技能"
      :context="project?.name"
    >
      <template #actions>
        <div class="period-controls">
          <el-button :disabled="loading" @click="shiftPeriod(-1)">上一周</el-button>
          <el-button :disabled="loading" @click="shiftPeriod(1)">下一周</el-button>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </template>
    </PageHeader>
    <section v-loading="loading" class="report-container">
      <el-result
        v-if="!loading && loadError && !report"
        icon="error"
        title="项目周报加载失败"
        sub-title="网络或服务异常，可能是临时问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </el-result>
      <el-empty v-else-if="!loading && !report" description="暂无报告数据" :image-size="64" />
      <template v-else-if="report">
        <div v-if="report.startDate || report.endDate" class="period-banner" role="status">
          报告周期：{{ formatDateRange(report.startDate, report.endDate) }}
          <span v-if="report.dataSource" class="period-source">{{ report.dataSource }}</span>
        </div>

        <el-card class="stats-card">
          <template #header>
            <h3>本期变化</h3>
          </template>
          <div class="stats-grid">
            <div class="stat-item">
              <span class="stat-value primary">{{ report.periodStats?.createdInPeriod ?? '—' }}</span>
              <span class="stat-label">本期新建任务</span>
            </div>
            <div class="stat-item">
              <span class="stat-value success">{{ report.periodStats?.completedInPeriod ?? '—' }}</span>
              <span class="stat-label">本期完成任务</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ report.periodStats?.milestoneCreatedInPeriod ?? '—' }}</span>
              <span class="stat-label">本期新建里程碑</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ report.periodStats?.milestoneCompletedInPeriod ?? '—' }}</span>
              <span class="stat-label">本期完成里程碑</span>
            </div>
          </div>
          <p class="period-note">
            本期变化基于任务创建/完成事件与里程碑审计记录还原；无法可靠还原的历史行为会如实标注在下方说明中。
          </p>
        </el-card>

        <el-card class="stats-card">
          <template #header>
            <h3>累计状态快照（截至报告生成时刻）</h3>
          </template>
          <div class="stats-grid">
            <div class="stat-item">
              <span class="stat-value">{{ report.taskStats.totalTasks }}</span>
              <span class="stat-label">任务总数（不含取消）</span>
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
            <div v-if="report.taskStats.canceledTasks !== undefined" class="stat-item">
              <span class="stat-value">{{ report.taskStats.canceledTasks }}</span>
              <span class="stat-label">已取消（不计完成率）</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ formatRate(report.taskStats.completionRate) }}</span>
              <span class="stat-label">完成率（分母不含取消）</span>
            </div>
          </div>
        </el-card>

        <el-card class="stats-card">
          <template #header>
            <h3>里程碑状态</h3>
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
              <span class="stat-label">即将到期{{ report.milestoneStats.dueSoonWindowDays ? `（${report.milestoneStats.dueSoonWindowDays} 天内）` : '' }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-value danger">{{ report.milestoneStats.overdueMilestones }}</span>
              <span class="stat-label">已逾期</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="report.topContributors.length > 0" class="stats-card">
          <template #header>
            <h3>贡献者（仅本期有活动者）</h3>
          </template>
          <div class="contributors-list">
            <div v-for="contributor in report.topContributors" :key="contributor.displayName" class="contributor-item">
              <span class="contributor-name">{{ contributor.displayName }}</span>
              <span class="contributor-stats">本期新建 {{ contributor.createdInPeriod ?? 0 }} · 本期完成 {{ contributor.completedInPeriod ?? 0 }}</span>
            </div>
          </div>
        </el-card>

        <el-card v-if="report.highlights.length > 0" class="stats-card">
          <template #header>
            <h3>✅ 亮点（基于本期事实）</h3>
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

        <el-card v-if="report.notes && report.notes.length > 0" class="stats-card notes-card">
          <template #header>
            <h3>数据说明</h3>
          </template>
          <ul class="list-items notes-list">
            <li v-for="(note, index) in report.notes" :key="index" class="note-item">
              {{ note }}
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

.period-controls {
  display: flex;
  gap: 8px;
}

.period-banner {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 12px;
  margin-bottom: 16px;
  padding: 10px 14px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-control);
  background: var(--color-surface);
  font-size: 14px;
  font-weight: 500;
}

.period-source {
  color: var(--color-text-muted);
  font-weight: 400;
  font-size: 12px;
}

.period-note {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}

.notes-card .notes-list .note-item {
  color: var(--color-text-secondary);
  margin-bottom: 6px;
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
  color: var(--color-text);
}

.stat-value.success { color: var(--color-success); }
.stat-value.primary { color: var(--color-primary); }
.stat-value.danger { color: var(--color-danger); }

.stat-label {
  font-size: 14px;
  color: var(--color-text-muted);
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
  border-bottom: 1px solid var(--color-surface-raised);
}

.contributor-name {
  font-weight: 500;
}

.contributor-stats {
  color: var(--color-text-muted);
}

.list-items {
  margin: 0;
  padding-left: 20px;
}

.highlight-item {
  color: var(--color-success);
  margin-bottom: 8px;
}

.risk-item {
  color: var(--color-warning);
  margin-bottom: 8px;
}
</style>
