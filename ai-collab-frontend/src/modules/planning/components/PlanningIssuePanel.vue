<script setup lang="ts">
import { computed } from 'vue'
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

const blockingIssues = computed(() =>
  props.issues.filter(i => i.severity === 'BLOCKING_EDITABLE' || i.severity === 'HARD')
)

const warningIssues = computed(() =>
  props.issues.filter(i => i.severity === 'WARNING')
)

const severityLabel = (s: string) => {
  if (s === 'HARD') return '阻断错误'
  if (s === 'BLOCKING_EDITABLE') return '阻断项'
  return '提示'
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
  <div v-if="issues.length > 0" class="issue-panel">
    <!-- 阻断项区域 -->
    <div v-if="blockingIssues.length > 0" class="issue-section blocking-section">
      <div class="issue-header blocking-header">
        <span class="issue-count">⚠️ 存在 {{ blockingIssues.length }} 项阻断性问题（必须修复并保存新版本后方可确认创建任务）</span>
      </div>
      <div class="issue-list">
        <div
          v-for="issue in blockingIssues"
          :key="issue.id"
          class="issue-item blocking-item"
          :style="{ borderLeftColor: severityColor(issue.severity) }"
        >
          <div class="issue-content" @click="emit('locate', issue.targetTempKey || '', issue.field)">
            <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">
              {{ severityLabel(issue.severity) }}
            </span>
            <span class="issue-message">{{ formatIssue(issue) }}</span>
          </div>
          <div v-if="actionsEnabled && issue.targetTempKey" class="issue-actions">
            <button class="btn-sm" @click="emit('edit', issue)">手动编辑</button>
          </div>
        </div>
      </div>
    </div>

    <!-- 非阻断警告/提示区域 -->
    <div v-if="warningIssues.length > 0" class="issue-section warning-section" :class="{ 'with-margin-top': blockingIssues.length > 0 }">
      <div class="issue-header warning-header">
        <span class="issue-count">ℹ️ 存在 {{ warningIssues.length }} 项提示与建议（非阻断，不影响直接确认创建任务）</span>
      </div>
      <div class="issue-list">
        <div
          v-for="issue in warningIssues"
          :key="issue.id"
          class="issue-item warning-item"
          :style="{ borderLeftColor: severityColor(issue.severity) }"
        >
          <div class="issue-content" @click="emit('locate', issue.targetTempKey || '', issue.field)">
            <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">
              {{ severityLabel(issue.severity) }}
            </span>
            <span class="issue-message">{{ formatIssue(issue) }}</span>
          </div>
          <div v-if="actionsEnabled && issue.targetTempKey" class="issue-actions">
            <button class="btn-sm" @click="emit('edit', issue)">手动编辑</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.issue-panel {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 16px;
  background: var(--color-warning-soft);
}
.issue-section {
  display: flex;
  flex-direction: column;
}
.with-margin-top {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px dashed var(--color-border);
}
.issue-header {
  font-weight: 600;
  margin-bottom: 12px;
}
.blocking-header {
  color: #c62828;
}
.warning-header {
  color: #b26a00;
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
  background: var(--color-surface-raised);
}
</style>
