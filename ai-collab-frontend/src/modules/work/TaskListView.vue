<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  taskPriorityLabel,
  taskStatusLabel,
  formatDate,
} from '../../shared/display-labels'
import EmptyState from '../../shared/EmptyState.vue'
import type { Task, TaskPriority, TaskStatus } from './types'

const props = defineProps<{
  tasks: Task[]
  canManage: boolean
  selectedTaskIds: Set<string>
  openingTaskId: string
  updatingTaskId: string
  statusFilter?: string
}>()

const emit = defineEmits<{
  (e: 'open', task: Task): void
  (e: 'edit', task: Task): void
  (e: 'delete', task: Task): void
  (e: 'toggle-selection', taskId: string): void
  (e: 'update-status', payload: { taskId: string; status: TaskStatus }): void
}>()

type SortField = 'title' | 'status' | 'priority' | 'dueDate' | 'estimateHours'
type SortOrder = 'asc' | 'desc'

const sortField = ref<SortField>('dueDate')
const sortOrder = ref<SortOrder>('asc')

const PRIORITY_WEIGHTS: Record<TaskPriority, number> = {
  URGENT: 4,
  HIGH: 3,
  MEDIUM: 2,
  LOW: 1,
}

const STATUS_WEIGHTS: Record<TaskStatus, number> = {
  IN_PROGRESS: 1,
  BLOCKED: 2,
  TODO: 3,
  DONE: 4,
  CANCELED: 5,
}

function toggleSort(field: SortField): void {
  if (sortField.value === field) {
    sortOrder.value = sortOrder.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortField.value = field
    sortOrder.value = 'asc'
  }
}

const filteredTasks = computed(() => {
  if (!props.statusFilter) return props.tasks
  return props.tasks.filter((t) => t.status === props.statusFilter)
})

const sortedTasks = computed(() => {
  const list = [...filteredTasks.value]
  const field = sortField.value
  const order = sortOrder.value === 'asc' ? 1 : -1

  return list.sort((a, b) => {
    if (field === 'title') {
      return a.title.localeCompare(b.title, 'zh-CN') * order
    }
    if (field === 'status') {
      const wa = STATUS_WEIGHTS[a.status] ?? 99
      const wb = STATUS_WEIGHTS[b.status] ?? 99
      return (wa - wb) * order
    }
    if (field === 'priority') {
      const wa = PRIORITY_WEIGHTS[a.priority] ?? 0
      const wb = PRIORITY_WEIGHTS[b.priority] ?? 0
      return (wa - wb) * order
    }
    if (field === 'dueDate') {
      const da = a.dueDate ?? '9999-99-99'
      const db = b.dueDate ?? '9999-99-99'
      return da.localeCompare(db) * order
    }
    if (field === 'estimateHours') {
      const ha = a.estimateHours ?? -1
      const hb = b.estimateHours ?? -1
      return (ha - hb) * order
    }
    return 0
  })
})

function isOverdue(task: Task): boolean {
  if (!task.dueDate || task.status === 'DONE' || task.status === 'CANCELED') return false
  const today = new Date().toISOString().slice(0, 10)
  return task.dueDate < today
}

function priorityTagType(priority: TaskPriority): 'danger' | 'warning' | 'primary' | 'info' {
  switch (priority) {
    case 'URGENT': return 'danger'
    case 'HIGH': return 'warning'
    case 'MEDIUM': return 'primary'
    case 'LOW': return 'info'
  }
}

function statusTagType(status: TaskStatus): 'info' | 'primary' | 'warning' | 'success' | 'danger' {
  switch (status) {
    case 'TODO': return 'info'
    case 'IN_PROGRESS': return 'primary'
    case 'BLOCKED': return 'warning'
    case 'DONE': return 'success'
    case 'CANCELED': return 'danger'
  }
}
</script>

<template>
  <div class="task-list-view" data-test="task-list-view">
    <EmptyState
      v-if="sortedTasks.length === 0"
      compact
      title="暂无匹配任务"
      description="当前筛选条件下没有任务，请尝试调整筛选或新建任务。"
    />
    <div v-else class="task-table-wrapper">
      <table class="task-table">
        <thead>
          <tr>
            <th v-if="canManage" class="col-check" width="40">
              <span class="sr-only">选择</span>
            </th>
            <th class="col-title sortable" @click="toggleSort('title')">
              <span>任务名称</span>
              <span v-if="sortField === 'title'" class="sort-indicator">{{ sortOrder === 'asc' ? '▲' : '▼' }}</span>
            </th>
            <th class="col-status sortable" width="110" @click="toggleSort('status')">
              <span>状态</span>
              <span v-if="sortField === 'status'" class="sort-indicator">{{ sortOrder === 'asc' ? '▲' : '▼' }}</span>
            </th>
            <th class="col-priority sortable" width="100" @click="toggleSort('priority')">
              <span>优先级</span>
              <span v-if="sortField === 'priority'" class="sort-indicator">{{ sortOrder === 'asc' ? '▲' : '▼' }}</span>
            </th>
            <th class="col-assignee" width="130">
              <span>负责人</span>
            </th>
            <th class="col-milestone" width="140">
              <span>里程碑</span>
            </th>
            <th class="col-due sortable" width="120" @click="toggleSort('dueDate')">
              <span>截止日期</span>
              <span v-if="sortField === 'dueDate'" class="sort-indicator">{{ sortOrder === 'asc' ? '▲' : '▼' }}</span>
            </th>
            <th class="col-hours sortable" width="90" @click="toggleSort('estimateHours')">
              <span>预估</span>
              <span v-if="sortField === 'estimateHours'" class="sort-indicator">{{ sortOrder === 'asc' ? '▲' : '▼' }}</span>
            </th>
            <th class="col-actions" width="140">
              <span>操作</span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in sortedTasks"
            :key="task.id"
            class="task-row"
            :class="{
              selected: selectedTaskIds.has(task.id),
              overdue: isOverdue(task),
              busy: openingTaskId === task.id || updatingTaskId === task.id,
            }"
            @click="emit('open', task)"
          >
            <td v-if="canManage" class="col-check" @click.stop>
              <input
                type="checkbox"
                :checked="selectedTaskIds.has(task.id)"
                :aria-label="`选择任务 ${task.title}`"
                @change="emit('toggle-selection', task.id)"
              />
            </td>
            <td class="col-title">
              <div class="task-title-cell">
                <span class="task-title-text">{{ task.title }}</span>
                <span v-if="task.unfinishedDependencyCount > 0" class="dep-badge" title="有未完成依赖">
                  依赖 {{ task.unfinishedDependencyCount }}
                </span>
              </div>
            </td>
            <td class="col-status">
              <el-tag :type="statusTagType(task.status)" size="small">
                {{ taskStatusLabel(task.status) }}
              </el-tag>
            </td>
            <td class="col-priority">
              <el-tag :type="priorityTagType(task.priority)" size="small" effect="plain">
                {{ taskPriorityLabel(task.priority) }}
              </el-tag>
            </td>
            <td class="col-assignee">
              <span class="assignee-text">{{ task.assigneeDisplayName || '未分配' }}</span>
            </td>
            <td class="col-milestone">
              <span class="milestone-text">{{ task.milestoneName || '—' }}</span>
            </td>
            <td class="col-due">
              <span :class="{ 'overdue-text': isOverdue(task) }">
                {{ formatDate(task.dueDate) || '—' }}
              </span>
            </td>
            <td class="col-hours">
              <span class="hours-text">{{ task.estimateHours != null ? `${task.estimateHours}h` : '—' }}</span>
            </td>
            <td class="col-actions" @click.stop>
              <div class="action-buttons">
                <button type="button" class="tbl-btn" title="查看任务详情" @click="emit('open', task)">
                  详情
                </button>
                <button
                  v-if="canManage"
                  type="button"
                  class="tbl-btn"
                  title="编辑任务"
                  @click="emit('edit', task)"
                >
                  编辑
                </button>
                <button
                  v-if="canManage"
                  type="button"
                  class="tbl-btn danger"
                  title="删除任务"
                  @click="emit('delete', task)"
                >
                  删除
                </button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.task-list-view {
  width: 100%;
}
.task-table-wrapper {
  overflow-x: auto;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-card, 8px);
  background: var(--color-surface);
}
.task-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  text-align: left;
}
.task-table th {
  background: var(--el-fill-color-lighter);
  color: var(--color-text-secondary);
  font-weight: 600;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border);
  white-space: nowrap;
}
.task-table th.sortable {
  cursor: pointer;
  user-select: none;
}
.task-table th.sortable:hover {
  color: var(--color-primary);
}
.sort-indicator {
  font-size: 10px;
  margin-left: 4px;
}
.task-table td {
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text-primary);
  vertical-align: middle;
}
.task-row {
  cursor: pointer;
  transition: background-color 0.15s ease;
}
.task-row:hover {
  background-color: var(--el-fill-color-light);
}
.task-row.selected {
  background-color: var(--el-color-primary-light-9);
}
.task-row:last-child td {
  border-bottom: none;
}
.task-title-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 180px;
}
.task-title-text {
  font-weight: 550;
  color: var(--color-text-primary);
}
.task-row:hover .task-title-text {
  color: var(--color-primary);
}
.dep-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--color-warning-soft);
  color: var(--color-warning);
  border: 1px solid rgba(173, 118, 40, 0.35);
  white-space: nowrap;
}
.assignee-text,
.milestone-text,
.hours-text {
  color: var(--color-text-secondary);
  font-size: 12.5px;
}
.overdue-text {
  color: var(--color-danger);
  font-weight: 600;
}
.action-buttons {
  display: flex;
  align-items: center;
  gap: 6px;
}
.tbl-btn {
  font-size: 12px;
  padding: 2px 8px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;
}
.tbl-btn:hover {
  color: var(--color-primary);
  border-color: var(--color-primary-soft);
  background: var(--color-surface-raised);
}
.tbl-btn.danger:hover {
  color: var(--color-danger);
  border-color: rgba(199, 79, 89, 0.35);
  background: var(--color-danger-soft);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  border: 0;
}
</style>
