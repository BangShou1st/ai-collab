<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import { taskStatusLabel, formatDate } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { visualizationApi, type GanttTask, type GanttMilestone, type GanttDependency } from './visualization-api'

const route = useRoute()
const projectId = route.params.projectId as string

const tasks = ref<GanttTask[]>([])
const milestones = ref<GanttMilestone[]>([])
const dependencies = ref<GanttDependency[]>([])
const loading = ref(false)

const project = ref<Project | null>(null)

async function load(): Promise<void> {
  loading.value = true
  try {
    const [result, projectResult] = await Promise.all([
      visualizationApi.gantt(projectId),
      projectApi.get(projectId),
    ])
    tasks.value = result.data.tasks
    milestones.value = result.data.milestones
    dependencies.value = result.data.dependencies
    project.value = projectResult.data
  } catch (error) {
    showApiError(error, '甘特图加载')
  } finally {
    loading.value = false
  }
}

const sortedTasks = computed(() => {
  return [...tasks.value].sort((a, b) => {
    const aDate = a.startDate || a.dueDate || ''
    const bDate = b.startDate || b.dueDate || ''
    return aDate.localeCompare(bDate)
  })
})

const DAY_MS = 86_400_000

function day(date: string | null | undefined): Date | null {
  if (!date) return null
  const value = new Date(`${date}T00:00:00`)
  return Number.isNaN(value.getTime()) ? null : value
}

const timeline = computed(() => {
  const dates = [
    ...tasks.value.flatMap(task => [day(task.startDate), day(task.dueDate)]),
    ...milestones.value.map(item => day(item.targetDate)),
    day(project.value?.startDate),
    day(project.value?.dueDate),
  ].filter((value): value is Date => Boolean(value))
  const now = new Date()
  now.setHours(0, 0, 0, 0)
  const start = dates.length ? new Date(Math.min(...dates.map(value => value.getTime()))) : now
  const end = dates.length ? new Date(Math.max(...dates.map(value => value.getTime()))) : now
  if (end.getTime() === start.getTime()) end.setDate(end.getDate() + 6)
  const values: Date[] = []
  for (let cursor = start.getTime(); cursor <= end.getTime(); cursor += DAY_MS) {
    values.push(new Date(cursor))
  }
  return { start, end, days: values }
})

const timelineStyle = computed(() => ({
  gridTemplateColumns: `240px repeat(${timeline.value.days.length}, 28px)`,
}))

function columnFor(value: string | null | undefined): number {
  const parsed = day(value)
  if (!parsed) return 2
  return Math.max(2, Math.round((parsed.getTime() - timeline.value.start.getTime()) / DAY_MS) + 2)
}

function taskBarStyle(task: GanttTask) {
  const start = task.startDate || task.dueDate
  const end = task.dueDate || task.startDate
  const startColumn = columnFor(start)
  const endColumn = Math.max(startColumn + 1, columnFor(end) + 1)
  return {
    gridColumn: `${startColumn} / ${endColumn}`,
    backgroundColor: getStatusColor(task.status),
  }
}

function isToday(value: Date): boolean {
  const today = new Date()
  return value.getFullYear() === today.getFullYear()
    && value.getMonth() === today.getMonth()
    && value.getDate() === today.getDate()
}

function dayLabel(value: Date): string {
  return value.getDate() === 1
    ? `${value.getMonth() + 1}月`
    : String(value.getDate())
}

function getStatusColor(status: string): string {
  const colors: Record<string, string> = {
    'TODO': '#909399',
    'IN_PROGRESS': '#409eff',
    'BLOCKED': '#e6a23c',
    'DONE': '#67c23a',
    'CANCELED': '#f56c6c',
  }
  return colors[status] || '#909399'
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目执行"
      title="甘特图"
      :context="project?.name"
    />
    <section v-loading="loading" class="gantt-container">
      <el-empty v-if="!loading && sortedTasks.length === 0" description="暂无任务数据" :image-size="64" />
      <div v-else class="gantt-chart" :style="{ minWidth: `${240 + timeline.days.length * 28}px` }">
        <div class="gantt-grid gantt-header" :style="timelineStyle">
          <div class="gantt-label-col">任务 / 负责人</div>
          <span
            v-for="value in timeline.days"
            :key="value.toISOString()"
            class="gantt-day"
            :class="{ today: isToday(value), weekend: [0, 6].includes(value.getDay()) }"
          >{{ dayLabel(value) }}</span>
        </div>
        <div v-for="task in sortedTasks" :key="task.id" class="gantt-grid gantt-row" :style="timelineStyle">
          <div class="gantt-label-col">
            <span class="task-title">{{ task.title }}</span>
            <span class="task-assignee">
              {{ task.assigneeName || '未分配' }} · {{ formatDate(task.startDate) }}—{{ formatDate(task.dueDate) }}
            </span>
          </div>
          <span
            v-for="value in timeline.days"
            :key="value.toISOString()"
            class="day-cell"
            :class="{ today: isToday(value), weekend: [0, 6].includes(value.getDay()) }"
          />
          <div
            class="gantt-bar"
            :style="taskBarStyle(task)"
            :title="`${task.title} · ${taskStatusLabel(task.status)}`"
          >
            <span>{{ taskStatusLabel(task.status) }}</span>
          </div>
        </div>
        <div v-for="milestone in milestones" :key="milestone.id" class="gantt-grid gantt-row milestone-row" :style="timelineStyle">
          <div class="gantt-label-col">
            <span class="milestone-title">◆ {{ milestone.name }}</span>
            <span class="task-assignee">{{ formatDate(milestone.targetDate) }}</span>
          </div>
          <span
            v-for="value in timeline.days"
            :key="value.toISOString()"
            class="day-cell"
            :class="{ today: isToday(value), weekend: [0, 6].includes(value.getDay()) }"
          />
          <div
            class="gantt-milestone-marker"
            :style="{ gridColumn: columnFor(milestone.targetDate) }"
            :title="`${milestone.name} · ${formatDate(milestone.targetDate)}`"
          >◆</div>
        </div>
      </div>
    </section>
  </main>
</template>

<style scoped>
.gantt-container {
  padding: 0 24px;
  overflow-x: auto;
}

.gantt-chart {
  min-width: 800px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  overflow: hidden;
}

.gantt-grid {
  display: grid;
  position: relative;
  align-items: stretch;
}

.gantt-header {
  position: sticky;
  z-index: 3;
  top: 0;
  border-bottom: 2px solid #e4e7ed;
  background: #f8f9fd;
}

.gantt-label-col {
  position: sticky;
  z-index: 2;
  left: 0;
  display: flex;
  min-width: 0;
  padding: 9px 12px;
  flex-direction: column;
  justify-content: center;
  border-right: 1px solid var(--color-border);
  background: #fff;
  font-weight: 500;
}

.gantt-day {
  padding: 9px 0;
  border-right: 1px solid #edf0f5;
  text-align: center;
  font-size: 12px;
  color: #909399;
}

.gantt-row {
  min-height: 58px;
  border-bottom: 1px solid #f0f0f0;
}

.gantt-row:last-child { border-bottom: 0; }
.day-cell { grid-row: 1; border-right: 1px solid #f0f2f6; }
.day-cell.weekend, .gantt-day.weekend { background: #f7f8fb; }
.day-cell.today, .gantt-day.today { background: #eef0ff; box-shadow: inset 1px 0 #9ba2ea, inset -1px 0 #9ba2ea; }

.task-title {
  font-size: 14px;
}

.task-assignee {
  font-size: 12px;
  color: #909399;
}

.milestone-title {
  font-size: 14px;
  color: #e6a23c;
}

.gantt-bar {
  z-index: 1;
  grid-row: 1;
  align-self: center;
  height: 28px;
  margin: 0 2px;
  border-radius: 7px;
  overflow: hidden;
  padding: 5px 8px;
  font-size: 12px;
  color: white;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.gantt-milestone-marker {
  z-index: 1;
  grid-row: 1;
  align-self: center;
  justify-self: center;
  color: #e6a23c;
  font-size: 24px;
  filter: drop-shadow(0 2px 2px rgba(135, 89, 26, .18));
}
</style>
