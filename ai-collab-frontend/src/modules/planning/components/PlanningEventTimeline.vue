<script setup lang="ts">
import type { TaskPlanEvent } from '../types'
import { eventLabel, fieldLabel, issueLabel } from '../planning-labels'

const props = defineProps<{
  events: TaskPlanEvent[]
}>()

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
            修改：{{ event.changedFields.map(fieldLabel).join('、') }}
          </div>
          <div class="event-issues" v-if="event.issueCodes.length > 0">
            问题：{{ event.issueCodes.map(issueLabel).join('、') }}
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
