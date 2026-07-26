<script setup lang="ts">
import { formatDate, taskPriorityLabel, taskStatusLabel } from '../../shared/display-labels'
import { allowedTaskStatusTransitions } from './task-status-transitions'
import type { Task, TaskStatus } from './types'

defineProps<{
  task: Task
  canChangeStatus: boolean
  opening: boolean
  updating: boolean
  operationLocked: boolean
}>()

defineEmits<{
  open: [task: Task]
  updateStatus: [task: Task, status: TaskStatus]
}>()
</script>

<template>
  <el-card
    v-loading="opening"
    class="task-card"
    shadow="hover"
    tabindex="0"
    role="button"
    :aria-label="`查看任务“${task.title}”详情`"
    @click="$emit('open', task)"
    @keydown.enter="$emit('open', task)"
    @keydown.space.prevent="$emit('open', task)"
  >
    <h3>{{ task.title }}</h3>
    <p>负责人：{{ task.assigneeDisplayName || '未分配' }}</p>
    <p>截止日期：{{ formatDate(task.dueDate) }} · {{ taskPriorityLabel(task.priority) }}</p>
    <div v-if="task.dependencyIds.length" class="dependency-summary">
      <span class="dependency-count">前置任务 {{ task.dependencyIds.length }} 项</span>
      <span v-if="task.unfinishedDependencyCount" class="dependency-blocked">
        还有 {{ task.unfinishedDependencyCount }} 项未完成，暂时不能推进
      </span>
    </div>
    <div class="actions" @click.stop @keydown.stop>
      <el-tag class="task-status-readonly">{{ taskStatusLabel(task.status) }}</el-tag>
      <el-select
        v-if="canChangeStatus"
        :model-value="null"
        size="small"
        placeholder="变更状态"
        :loading="updating"
        :disabled="operationLocked"
        :aria-label="`修改任务“${task.title}”的状态`"
        @change="$emit('updateStatus', task, $event)"
      >
        <el-option
          v-for="value in allowedTaskStatusTransitions(task.status)"
          :key="value"
          :label="taskStatusLabel(value)"
          :value="value"
        />
      </el-select>
    </div>
  </el-card>
</template>
