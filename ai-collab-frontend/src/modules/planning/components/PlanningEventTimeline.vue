<script setup lang="ts">
import type { TaskPlanEvent } from '../types'
import { eventLabel } from '../planning-labels'

const props = defineProps<{
  events: TaskPlanEvent[]
}>()

const formatTime = (ts: string | null) => {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN')
}

const formatEventDescription = (event: TaskPlanEvent) => {
  const { eventType, changedFields, changedTargets, issueCodes, comment } = event

  // 如果有用户自定义的comment，优先显示
  if (comment && comment.trim()) {
    return comment.trim()
  }

  // 简化显示：根据事件类型返回简洁描述
  if (eventType === 'PLAN_GENERATED' || eventType === 'PLAN_GENERATED_WITH_ISSUES') {
    return 'AI 生成了规划'
  }
  if (eventType === 'PARTIAL_REPAIR' || eventType === 'TASK_PLAN_PARTIAL_REGENERATED') {
    return `AI 重新生成了 ${changedTargets.length} 个任务`
  }
  if (eventType === 'TASK_PLAN_USER_EDITED') {
    // 统计实际修改的字段数量（去重后）
    const uniqueFields = new Set(changedFields.map(f => {
      // 提取最后一个段作为字段名
      const lastDot = f.lastIndexOf('.')
      return lastDot === -1 ? f : f.substring(lastDot + 1)
    }))
    return `用户修改了 ${uniqueFields.size} 个字段`
  }
  if (eventType === 'TASK_PLAN_REGENERATED') {
    return 'AI 重新生成了整个规划'
  }
  if (eventType === 'TASK_PLAN_CONFIRMED') {
    return '规划已确认并创建任务'
  }
  if (eventType === 'TASK_PLAN_VERSION_SAVED') {
    return '规划新版本已保存'
  }

  // 默认显示变更统计
  if (changedFields.length > 0) {
    const uniqueFields = new Set(changedFields.map(f => {
      const lastDot = f.lastIndexOf('.')
      return lastDot === -1 ? f : f.substring(lastDot + 1)
    }))
    return `更新了 ${uniqueFields.size} 个字段`
  }
  return ''
}
</script>

<template>
  <div class="event-timeline" v-if="events.length > 0">
    <h4>事件时间线</h4>
    <div class="event-list">
      <div v-for="event in events" :key="event.id" class="event-item">
        <div class="event-dot"></div>
        <div class="event-content">
          <span class="event-label">{{ eventLabel(event.eventType) }}</span>
          <span class="event-time">{{ formatTime(event.createdAt) }}</span>
          <div class="event-description" v-if="formatEventDescription(event)">
            {{ formatEventDescription(event) }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.event-timeline h4 {
  margin: 0 0 12px 0;
  font-size: 14px;
  color: #666;
}
.event-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.event-item {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}
.event-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #1976d2;
  margin-top: 6px;
  flex-shrink: 0;
}
.event-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.event-label {
  font-size: 14px;
  font-weight: 500;
}
.event-time {
  font-size: 12px;
  color: #999;
}
.event-description {
  font-size: 12px;
  color: #666;
}
</style>
