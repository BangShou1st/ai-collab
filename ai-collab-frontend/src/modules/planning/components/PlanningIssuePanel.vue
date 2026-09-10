<script setup lang="ts">
import type { StructuredValidationIssue } from '../types'
import { fieldLabel, issueLabel } from '../planning-labels'

const props = defineProps<{
  issues: StructuredValidationIssue[]
  targetNames: Record<string, string>
  actionsEnabled: boolean
}>()

const emit = defineEmits<{
  (e: 'locate', targetTempKey: string, field: string | null): void
  (e: 'edit', issue: StructuredValidationIssue): void
  (e: 'regenerate', targetTempKey: string): void
}>()

const severityLabel = (s: string) => {
  if (s === 'HARD') return '硬错误'
  if (s === 'BLOCKING_EDITABLE') return '可修复'
  return '警告'
}

const severityColor = (s: string) => {
  if (s === 'HARD') return '#e53935'
  if (s === 'BLOCKING_EDITABLE') return '#fb8c00'
  return '#fdd835'
}

const formatIssue = (issue: StructuredValidationIssue) => {
  const name = issue.targetTempKey ? props.targetNames[issue.targetTempKey] : null
  const target = issue.targetType === 'TASK' ? `任务“${name ?? '未命名任务'}”` :
                 issue.targetType === 'MILESTONE' ? `里程碑“${name ?? '未命名里程碑'}”` : '规划'
  const field = issue.field ? ` 的${fieldLabel(issue.field)}` : ''
  return `${target}${field}：${issueLabel(issue.code)}`
}
</script>

<template>
  <div class="issue-panel" v-if="issues.length > 0">
    <div class="issue-header">
      <span class="issue-count">规划已生成，但有 {{ issues.length }} 项需要确认</span>
    </div>
    <div class="issue-list">
      <div
        v-for="issue in issues"
        :key="issue.id"
        class="issue-item"
        :style="{ borderLeftColor: severityColor(issue.severity) }"
      >
        <div class="issue-content" @click="emit('locate', issue.targetTempKey || '', issue.field)">
          <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">
            {{ severityLabel(issue.severity) }}
          </span>
          <span class="issue-message">{{ formatIssue(issue) }}</span>
        </div>
        <div class="issue-actions" v-if="actionsEnabled && issue.targetTempKey">
          <button class="btn-sm" @click="emit('edit', issue)">手动编辑</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issue-panel {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: #fff8e1;
}
.issue-header {
  font-weight: 600;
  margin-bottom: 12px;
}
.issue-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.issue-item {
  border: 1px solid var(--color-border);
  padding: 8px 12px;
  background: white;
  border-radius: 8px;
}
.issue-content {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
}
.issue-severity {
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.issue-message {
  font-size: 14px;
}
.issue-actions {
  margin-top: 8px;
  display: flex;
  gap: 8px;
}
.btn-sm {
  font-size: 12px;
  padding: 4px 8px;
  border: 1px solid #ccc;
  border-radius: 4px;
  background: white;
  cursor: pointer;
}
.btn-sm:hover {
  background: #f5f5f5;
}
</style>
