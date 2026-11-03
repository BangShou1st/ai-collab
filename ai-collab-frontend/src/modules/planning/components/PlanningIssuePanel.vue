<script setup lang="ts">
import { computed, ref } from 'vue'
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

const panelRef = ref<HTMLElement | null>(null)
const expanded = ref(false)

const blockingIssues = computed(() =>
  props.issues.filter(i => i.severity === 'BLOCKING_EDITABLE' || i.severity === 'HARD')
)

const warningIssues = computed(() =>
  props.issues.filter(i => i.severity === 'WARNING')
)

const SEVERITY_ORDER: Record<string, number> = {
  HARD: 1,
  BLOCKING_EDITABLE: 2,
  WARNING: 3,
}

const sortedIssues = computed(() => {
  return [...props.issues].sort((a, b) => {
    const rankA = SEVERITY_ORDER[a.severity] ?? 99
    const rankB = SEVERITY_ORDER[b.severity] ?? 99
    return rankA - rankB
  })
})

const displayedIssues = computed(() => {
  if (expanded.value || sortedIssues.value.length <= 3) {
    return sortedIssues.value
  }
  return sortedIssues.value.slice(0, 3)
})

const severityLabel = (s: string) => {
  if (s === 'HARD') return '阻断错误'
  if (s === 'BLOCKING_EDITABLE') return '阻断项'
  return '提示'
}

const severityColor = (s: string) => {
  if (s === 'HARD') return 'var(--color-danger)'
  if (s === 'BLOCKING_EDITABLE') return 'var(--color-warning)'
  return 'var(--color-info)'
}

const formatIssue = (issue: StructuredValidationIssue) => {
  const name = issue.targetTempKey ? props.targetNames[issue.targetTempKey] : null
  const target = issue.targetType === 'TASK' ? `任务“${name ?? '未命名任务'}”` :
                 issue.targetType === 'MILESTONE' ? `里程碑“${name ?? '未命名里程碑'}”` : '规划'
  const field = issue.field ? ` 的${fieldLabel(issue.field)}` : ''
  return `${target}${field}：${issueLabel(issue.code)}`
}

function collapseIssues(): void {
  expanded.value = false
  if (panelRef.value && typeof panelRef.value.getBoundingClientRect === 'function') {
    const rect = panelRef.value.getBoundingClientRect()
    if (rect.top < 0 || rect.bottom < 100) {
      panelRef.value.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' })
    }
  }
}
</script>

<template>
  <div v-if="issues.length > 0" ref="panelRef" class="issue-panel" data-test="planning-issue-panel">
    <div class="issue-panel-header">
      <div class="issue-panel-title" data-test="issue-panel-title">
        <span class="title-main">规划校验警告 · 共 {{ issues.length }} 条</span>
      </div>
      <div class="issue-panel-summary">
        <span v-if="blockingIssues.length > 0" class="issue-count blocking-header">
          ⚠️ 存在 {{ blockingIssues.length }} 项阻断性问题（必须修复并保存新版本后方可确认创建任务）
        </span>
        <span v-if="warningIssues.length > 0" class="issue-count warning-header" :class="{ 'with-margin-top': blockingIssues.length > 0 }">
          ℹ️ 存在 {{ warningIssues.length }} 项提示与建议（非阻断，不影响直接确认创建任务）
        </span>
      </div>
    </div>

    <div class="issue-list" data-test="issue-list">
      <div
        v-for="issue in displayedIssues"
        :key="issue.id"
        class="issue-item"
        :class="{
          'blocking-item': issue.severity === 'HARD' || issue.severity === 'BLOCKING_EDITABLE',
          'warning-item': issue.severity === 'WARNING',
        }"
        :style="{ borderLeftColor: severityColor(issue.severity) }"
        :data-severity="issue.severity"
      >
        <div
          class="issue-content"
          role="button"
          tabindex="0"
          :title="`点击定位到相关字段`"
          @click="emit('locate', issue.targetTempKey || '', issue.field)"
          @keydown.enter.prevent="emit('locate', issue.targetTempKey || '', issue.field)"
          @keydown.space.prevent="emit('locate', issue.targetTempKey || '', issue.field)"
        >
          <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">
            {{ severityLabel(issue.severity) }}
          </span>
          <span class="issue-message">{{ formatIssue(issue) }}</span>
        </div>
        <div v-if="actionsEnabled && issue.targetTempKey" class="issue-actions">
          <button class="btn-sm" type="button" @click="emit('edit', issue)">手动编辑</button>
        </div>
      </div>
    </div>

    <!-- 折叠展开控制：超过 3 条时显示 -->
    <div v-if="issues.length > 3" class="issue-collapse-footer">
      <button
        v-if="!expanded"
        type="button"
        class="toggle-btn expand-btn"
        data-test="expand-all-issues"
        @click="expanded = true"
      >
        展开全部 {{ issues.length }} 条
      </button>
      <button
        v-else
        type="button"
        class="toggle-btn collapse-btn"
        data-test="collapse-issues"
        @click="collapseIssues"
      >
        收起警告
      </button>
    </div>
  </div>
</template>

<style scoped>
.issue-panel {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--color-warning-soft);
}
.issue-panel-header {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
}
.title-main {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text-primary, #1e293b);
  letter-spacing: -0.01em;
}
.issue-panel-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.issue-count {
  font-size: 12.5px;
  font-weight: 600;
}
.blocking-header {
  color: var(--color-danger);
}
.warning-header {
  color: var(--color-warning);
}
.with-margin-top {
  margin-top: 4px;
}
.issue-list {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.issue-item {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 0;
  border-bottom: 1px solid var(--color-border);
  border-left: 3px solid transparent;
  padding: 6px 8px;
  background: transparent;
  border-radius: 0;
}
.issue-item:last-child {
  border-bottom: 0;
}
.issue-content {
  cursor: pointer;
  display: flex;
  gap: 8px;
  align-items: center;
  flex: 1;
  min-width: 0;
  border-radius: 4px;
  padding: 2px 4px;
  transition: color 0.15s ease, background-color 0.15s ease;
}
.issue-content:hover .issue-message {
  color: var(--color-primary);
}
.issue-content:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
.issue-severity {
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.issue-message {
  font-size: 13px;
  min-width: 0;
}
.issue-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}
.btn-sm {
  font-size: 12px;
  padding: 3px 8px;
  border: 1px solid var(--color-border-strong, #cbd5e1);
  border-radius: 4px;
  background: var(--color-surface, #ffffff);
  cursor: pointer;
  transition: background-color 0.15s ease;
}
.btn-sm:hover {
  background: var(--color-surface-raised, #f8fafc);
}
.issue-collapse-footer {
  display: flex;
  justify-content: center;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--color-border);
}
.toggle-btn {
  font-size: 12.5px;
  color: var(--color-primary, #2563eb);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 4px 12px;
  border-radius: 4px;
  font-weight: 500;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.toggle-btn:hover {
  background: rgba(37, 99, 235, 0.08);
}
.toggle-btn:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
</style>
