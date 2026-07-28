<script setup lang="ts">
import type { TaskPlanEvent } from '../types'

const props = defineProps<{
  events: TaskPlanEvent[]
}>()

const eventLabel = (type: string) => {
  const labels: Record<string, string> = {
    'PLAN_GENERATED': '规划已生成',
    'PLAN_GENERATED_WITH_ISSUES': '规划已生成（含待解决问题）',
    'TASK_PLAN_USER_EDITED': '用户编辑了规划',
    'TASK_PLAN_PARTIAL_REGENERATED': '局部重新生成',
    'TASK_PLAN_CANCELED': '规划生成已取消',
    'TASK_PLAN_REGENERATED': '规划已重新生成',
    'TASK_PLAN_CONFIRMED': '规划已确认',
    'TASK_PLAN_CONFIRMATION_FAILED': '规划确认失败',
    'TASK_PLAN_DELETED': '规划已删除',
  }
  return labels[type] || type
}

const formatTime = (ts: string | null) => {
  if (!ts) return ''
  return new Date(ts).toLocaleString('zh-CN')
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
          <div class="event-changes" v-if="event.changedFields.length > 0">
            修改：{{ event.changedFields.join(', ') }}
          </div>
          <div class="event-issues" v-if="event.issueCodes.length > 0">
            问题：{{ event.issueCodes.join(', ') }}
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
.event-changes, .event-issues {
  font-size: 12px;
  color: #666;
}
</style>
