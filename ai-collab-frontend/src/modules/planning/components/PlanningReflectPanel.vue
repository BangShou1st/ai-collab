<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ReflectIssue } from '../types'
import { fieldLabel } from '../planning-labels'

const props = defineProps<{
  issues: ReflectIssue[]
  targetNames: Record<string, string>
}>()

const emit = defineEmits<{
  (e: 'locate', targetTempKey: string, field: string | null): void
}>()

const panelRef = ref<HTMLElement | null>(null)
const expanded = ref(false)

const hardIssues = computed(() => props.issues.filter(i => i.severity === 'HARD'))
const softIssues = computed(() => props.issues.filter(i => i.severity === 'SOFT'))

const SEVERITY_ORDER: Record<string, number> = { HARD: 1, SOFT: 2 }
const sortedIssues = computed(() =>
  [...props.issues].sort((a, b) => (SEVERITY_ORDER[a.severity] ?? 99) - (SEVERITY_ORDER[b.severity] ?? 99)),
)

const displayedIssues = computed(() => {
  if (expanded.value || sortedIssues.value.length <= 3) return sortedIssues.value
  return sortedIssues.value.slice(0, 3)
})

const severityLabel = (s: string) => (s === 'HARD' ? '建议修复' : '改进建议')
const severityColor = (s: string) => (s === 'HARD' ? 'var(--color-warning)' : 'var(--color-info)')

const formatIssue = (issue: ReflectIssue) => {
  const name = issue.targetTempKey ? props.targetNames[issue.targetTempKey] : null
  const target = issue.targetType === 'TASK' ? `任务“${name ?? '未命名任务'}”`
    : issue.targetType === 'MILESTONE' ? `里程碑“${name ?? '未命名里程碑'}”` : '规划'
  const field = issue.field ? ` 的${fieldLabel(issue.field)}` : ''
  return `${target}${field}：${issue.suggestion}`
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
  <div ref="panelRef" class="reflect-panel" data-test="planning-reflect-panel">
    <div class="reflect-panel-header">
      <div class="reflect-panel-title" data-test="reflect-panel-title">
        <span class="title-main">AI 自评建议 · 共 {{ issues.length }} 条</span>
      </div>
      <div class="reflect-panel-summary">
        <span v-if="hardIssues.length > 0" class="issue-count hard-count">建议修复 {{ hardIssues.length }} 项，不影响确认创建任务</span>
        <span v-if="softIssues.length > 0" class="issue-count soft-count">改进建议 {{ softIssues.length }} 项</span>
      </div>
    </div>
    <div class="issue-list" data-test="reflect-issue-list">
      <div
        v-for="(issue, index) in displayedIssues"
        :key="index"
        class="issue-item"
        :class="issue.severity === 'HARD' ? 'hard-item' : 'soft-item'"
        :data-severity="issue.severity"
      >
        <div
          v-if="issue.targetTempKey"
          class="issue-content"
          role="button"
          tabindex="0"
          title="点击定位到相关字段"
          @click="emit('locate', issue.targetTempKey, issue.field)"
          @keydown.enter.prevent="emit('locate', issue.targetTempKey, issue.field)"
          @keydown.space.prevent="emit('locate', issue.targetTempKey, issue.field)"
        >
          <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">{{ severityLabel(issue.severity) }}</span>
          <span class="issue-message">{{ formatIssue(issue) }}</span>
        </div>
        <div v-else class="issue-content">
          <span class="issue-severity" :style="{ color: severityColor(issue.severity) }">{{ severityLabel(issue.severity) }}</span>
          <span class="issue-message">{{ formatIssue(issue) }}</span>
        </div>
      </div>
    </div>
    <div v-if="issues.length > 3" class="reflect-collapse-footer">
      <button v-if="!expanded" type="button" class="toggle-btn" data-test="expand-reflect-issues" @click="expanded = true">
        展开全部 {{ issues.length }} 条
      </button>
      <button v-else type="button" class="toggle-btn" data-test="collapse-reflect-issues" @click="collapseIssues">
        收起建议
      </button>
    </div>
  </div>
</template>

<style scoped>
.reflect-panel {
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 16px;
  background: var(--color-info-soft, rgba(37, 99, 235, 0.04));
}
.reflect-panel-header {
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
.reflect-panel-summary {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.issue-count {
  font-size: 12.5px;
  font-weight: 600;
}
.hard-count {
  color: var(--color-warning);
}
.soft-count {
  color: var(--color-info);
}
.issue-list {
  display: flex;
  flex-direction: column;
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
.hard-item {
  border-left-color: var(--color-warning);
}
.soft-item {
  border-left-color: var(--color-info);
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
.reflect-collapse-footer {
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
