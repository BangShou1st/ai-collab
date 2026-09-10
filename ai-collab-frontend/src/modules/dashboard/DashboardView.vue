<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { dashboardApi } from './dashboard-api'
import { useProjectContextStore } from '../../stores/project-context-store'
import {
  formatDate,
  formatDateTime,
  projectStatusLabel,
  roleLabel,
  taskStatusLabel,
  taskPriorityLabel,
  documentStatusLabel,
  milestoneStatusLabel,
  auditEntityLabel,
} from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import type { DashboardView } from './types'

const route = useRoute()
const projectCtx = useProjectContextStore()
const projectId = route.params.projectId as string

const dashboard = ref<DashboardView | null>(null)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    dashboard.value = (await dashboardApi.get(projectId)).data
    await projectCtx.loadProject(projectId)
  } catch (error) {
    showApiError(error, '项目概览加载')
  } finally {
    loading.value = false
  }
}

function taskStatusType(status: string): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'DONE') return 'success'
  if (status === 'BLOCKED') return 'danger'
  if (status === 'IN_PROGRESS') return 'warning'
  return 'info'
}

function priorityType(priority: string): 'info' | 'warning' | 'success' | 'danger' {
  if (priority === 'URGENT') return 'danger'
  if (priority === 'HIGH') return 'warning'
  return 'info'
}

function milestoneStatusType(status: string): 'info' | 'warning' | 'success' | 'danger' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'ACTIVE') return 'warning'
  if (status === 'CANCELED') return 'info'
  return 'info'
}

function completionPercent(rate: number): number {
  return Math.round(rate * 100)
}

const healthState = computed(() => {
  if (!dashboard.value) return { tone: 'info' as const, text: '' }
  const risks: string[] = []
  if (dashboard.value.tasks.overdue > 0) risks.push(`${dashboard.value.tasks.overdue} 个逾期任务`)
  if (dashboard.value.tasks.blocked > 0) risks.push(`${dashboard.value.tasks.blocked} 个阻塞任务`)
  const overdueMilestones = dashboard.value.milestones.filter((m) => m.overdue).length
  if (overdueMilestones > 0) risks.push(`${overdueMilestones} 个逾期里程碑`)
  if (!risks.length) return { tone: 'success' as const, text: '项目健康：暂无逾期与阻塞' }
  return { tone: 'warning' as const, text: `需要注意：${risks.join('、')}` }
})

const nextTasks = computed(() => {
  if (!dashboard.value) return []
  const rank = (status: string, overdue: boolean): number =>
    overdue ? 0 : status === 'BLOCKED' ? 1 : status === 'IN_PROGRESS' ? 2 : 3
  return [...dashboard.value.recentTasks]
    .sort((a, b) => rank(a.status, a.overdue) - rank(b.status, b.overdue))
    .slice(0, 5)
})

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目中心"
      title="项目概览"
    >
      <template #actions>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </template>
    </PageHeader>

    <div v-loading="loading">
      <template v-if="dashboard">
        <div class="inline-notice" role="status">
          <span class="status-dot" :class="healthState.tone === 'warning' ? 'wait' : healthState.tone === 'info' ? 'run' : 'done'" />
          <span>{{ healthState.text }}</span>
        </div>
        <el-card v-if="nextTasks.length > 0" class="dashboard-section" shadow="never">
          <template #header>
            <span>接下来要做什么</span>
          </template>
          <div v-for="task in nextTasks" :key="task.id" class="next-task-row">
            <StatusBadge :label="taskStatusLabel(task.status)" />
            <span class="next-task-title">{{ task.title }}</span>
            <span v-if="task.overdue" class="overdue-tag">已逾期</span>
            <span class="next-task-due">{{ formatDate(task.dueDate) }}</span>
          </div>
        </el-card>
        <!-- 项目基本信息 -->
        <el-card class="dashboard-section" shadow="never">
          <template #header>
            <div class="section-header">
              <span>项目信息</span>
              <el-tag>{{ projectStatusLabel(dashboard.project.status) }}</el-tag>
            </div>
          </template>
          <p class="project-desc">{{ dashboard.project.description || '暂无项目描述' }}</p>
          <div class="info-grid">
            <div class="info-item">
              <span class="info-label">项目周期</span>
              <span>{{ formatDate(dashboard.project.startDate) }} 至 {{ formatDate(dashboard.project.dueDate) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">我的角色</span>
              <span>{{ roleLabel(dashboard.project.currentUserRole) }}</span>
            </div>
            <div class="info-item">
              <span class="info-label">成员数量</span>
              <span>{{ dashboard.project.memberCount ?? 0 }} 人</span>
            </div>
          </div>
        </el-card>

        <!-- 任务统计卡片 -->
        <div class="stats-grid">
          <el-card shadow="never" class="stat-card">
            <div class="stat-value">{{ dashboard.tasks.total }}</div>
            <div class="stat-label">总任务</div>
          </el-card>
          <el-card shadow="never" class="stat-card">
            <div class="stat-value stat-in-progress">{{ dashboard.tasks.inProgress }}</div>
            <div class="stat-label">进行中</div>
          </el-card>
          <el-card shadow="never" class="stat-card">
            <div class="stat-value stat-done">{{ dashboard.tasks.done }}</div>
            <div class="stat-label">已完成</div>
          </el-card>
          <el-card shadow="never" class="stat-card">
            <div class="stat-value stat-overdue">{{ dashboard.tasks.overdue }}</div>
            <div class="stat-label">逾期</div>
          </el-card>
          <el-card shadow="never" class="stat-card">
            <div class="stat-value">{{ completionPercent(dashboard.tasks.completionRate) }}%</div>
            <div class="stat-label">完成率</div>
          </el-card>
          <el-card shadow="never" class="stat-card">
            <div class="stat-value stat-blocked">{{ dashboard.tasks.blocked }}</div>
            <div class="stat-label">已阻塞</div>
          </el-card>
        </div>

        <!-- 里程碑进度 -->
        <el-card v-if="dashboard.milestones.length > 0" class="dashboard-section" shadow="never">
          <template #header>
            <span>里程碑进度</span>
          </template>
          <div v-for="m in dashboard.milestones" :key="m.id" class="milestone-item">
            <div class="milestone-header">
              <span class="milestone-name">{{ m.name }}</span>
              <el-tag :type="milestoneStatusType(m.status)" size="small">
                {{ milestoneStatusLabel(m.status) }}
              </el-tag>
              <span v-if="m.overdue" class="overdue-tag">已逾期</span>
            </div>
            <div class="milestone-meta">
              <span>目标：{{ formatDate(m.targetDate) }}</span>
              <span>{{ m.completedTasks }}/{{ m.totalTasks }} 任务完成</span>
              <span>{{ completionPercent(m.completionRate) }}%</span>
            </div>
            <el-progress :percentage="completionPercent(m.completionRate)" :stroke-width="8" />
          </div>
        </el-card>

        <!-- 最近任务 -->
        <el-card class="dashboard-section" shadow="never">
          <template #header>
            <span>最近更新的任务</span>
          </template>
          <el-table v-if="dashboard.recentTasks.length > 0" :data="dashboard.recentTasks" size="small" stripe>
            <el-table-column prop="title" label="任务名称" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="taskStatusType(row.status)" size="small">{{ taskStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="优先级" width="80">
              <template #default="{ row }">
                <el-tag :type="priorityType(row.priority)" size="small">{{ taskPriorityLabel(row.priority) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="assigneeDisplayName" label="负责人" width="100" show-overflow-tooltip />
            <el-table-column label="截止日期" width="120">
              <template #default="{ row }">
                <span :class="{ 'overdue-text': row.overdue }">{{ formatDate(row.dueDate) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="120">
              <template #default="{ row }">
                {{ formatDateTime(row.updatedAt) }}
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无任务" :image-size="60" />
        </el-card>

        <!-- 最近文档 -->
        <el-card class="dashboard-section" shadow="never">
          <template #header>
            <span>最近上传的文档</span>
          </template>
          <el-table v-if="dashboard.recentDocuments.length > 0" :data="dashboard.recentDocuments" size="small" stripe>
            <el-table-column prop="originalFilename" label="文件名" min-width="200" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small">{{ documentStatusLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="uploaderDisplayName" label="上传人" width="100" />
            <el-table-column label="上传时间" width="120">
              <template #default="{ row }">
                {{ formatDateTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无文档" :image-size="60" />
        </el-card>

        <!-- 最近动态 -->
        <el-card class="dashboard-section" shadow="never">
          <template #header>
            <span>最近动态</span>
          </template>
          <el-table v-if="dashboard.recentActivities.length > 0" :data="dashboard.recentActivities" size="small" stripe>
            <el-table-column prop="userDisplayName" label="操作人" width="100" />
            <el-table-column prop="summary" label="操作摘要" min-width="300" show-overflow-tooltip />
            <el-table-column label="对象类型" width="120">
              <template #default="{ row }">
                {{ auditEntityLabel(row.entityType) }}
              </template>
            </el-table-column>
            <el-table-column label="时间" width="120">
              <template #default="{ row }">
                {{ formatDateTime(row.createdAt) }}
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="暂无动态" :image-size="60" />
        </el-card>
      </template>
    </div>
  </main>
</template>

<style scoped>
.dashboard-section {
  margin-bottom: 16px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.project-desc {
  color: var(--el-text-color-secondary);
  margin-bottom: 12px;
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 8px;
}

.info-item {
  display: flex;
  gap: 8px;
}

.info-label {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.stat-card {
  text-align: center;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-top: 4px;
}

.stat-in-progress {
  color: var(--el-color-warning);
}

.stat-done {
  color: var(--el-color-success);
}

.stat-overdue {
  color: var(--el-color-danger);
}

.stat-blocked {
  color: var(--el-color-danger);
}

.milestone-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.milestone-item:last-child {
  border-bottom: none;
}

.milestone-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.milestone-name {
  font-weight: 500;
}

.overdue-tag {
  color: var(--el-color-danger);
  font-size: 12px;
}

.milestone-meta {
  display: flex;
  gap: 16px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  margin-bottom: 8px;
}

.overdue-text {
  color: var(--el-color-danger);
}
</style>
