<script setup lang="ts">
import { computed } from 'vue'
import { milestoneStatusLabel, taskPriorityLabel, taskStatusLabel } from './display-labels'
const props = defineProps<{ diff: Record<string, unknown> }>()
interface Row { field: string; before: string; after: string }
const FIELD_LABELS: Record<string, string> = {
  title: '标题', name: '名称', status: '状态', priority: '优先级',
  assignee: '负责人', assigneeId: '负责人', assigneeName: '负责人', assigneeDisplayName: '负责人',
  milestone: '里程碑', milestoneId: '里程碑', milestoneName: '里程碑',
  startDate: '开始日期', dueDate: '截止日期', endDate: '截止日期', targetDate: '目标日期',
  description: '描述', content: '内容', reason: '原因', goal: '目标',
  estimateHours: '预计工时', objective: '目标',
}
const HIDDEN_FIELDS = new Set([
  'id', 'projectId', 'project_id', 'version', 'createdAt', 'updatedAt', 'created_at', 'updated_at',
  'operation', 'op', 'tempKey', 'resourceId', 'resourceVersion', 'subjectKey', 'proposalFamily',
])
function same(a: unknown, b: unknown): boolean {
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null)
}
function text(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'string') return v.trim() || '—'
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  if (Array.isArray(v)) {
    if (!v.length) return '—'
    return v.map((x) => (x && typeof x === 'object' ? ((x as Record<string, unknown>).title ?? (x as Record<string, unknown>).name ?? '一项') : String(x))).join('、').slice(0, 120)
  }
  if (typeof v === 'object') {
    const o = v as Record<string, unknown>
    for (const k of ['title', 'name', 'label', 'displayName']) {
      if (typeof o[k] === 'string' && (o[k] as string).trim()) return (o[k] as string).trim()
    }
    const keys = Object.keys(o)
    return keys.length ? `共 ${keys.length} 个字段` : '—'
  }
  return '—'
}
function label(field: string): string {
  return FIELD_LABELS[field] ?? field
}
function localize(field: string, value: string): string {
  if (value === '—' || value === '未设置') return value
  if (field === 'status' || field === '状态') {
    const mapped = taskStatusLabel(value) !== '未知状态' ? taskStatusLabel(value) : milestoneStatusLabel(value)
    return mapped
  }
  if (field === 'priority' || field === '优先级') return taskPriorityLabel(value)
  if (/日期/.test(field) && /^\d{4}-\d{2}-\d{2}/.test(value)) {
    const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(value)
    if (m) return `${m[2]}/${m[3]}`
  }
  return value
}
const rows = computed<Row[]>(() => {
  const d = (props.diff ?? {}) as Record<string, unknown>
  const before = (d.before && typeof d.before === 'object' ? d.before : null) as Record<string, unknown> | null
  const after = (d.after && typeof d.after === 'object' ? d.after : null) as Record<string, unknown> | null
  if (before || after) {
    // UPDATE 后 after 只含变更字段：只遍历 after；CREATE 则展示 after 有意义字段。
    const keys = Object.keys(after ?? before ?? {}).filter((k) => !HIDDEN_FIELDS.has(k))
    const out: Row[] = []
    for (const k of keys) {
      const b = before?.[k]
      const a = after?.[k]
      if (before && after && same(b, a)) continue
      const field = label(k)
      const beforeText = b === null || b === undefined ? (after ? '未设置' : '—') : text(b)
      out.push({ field, before: localize(field, beforeText), after: localize(field, text(a)) })
      if (out.length >= 12) break
    }
    return out
  }
  const fields = (d.fields ?? d.changes ?? d) as Record<string, unknown>
  if (!fields || typeof fields !== 'object') return []
  return Object.entries(fields).filter(([k]) => !HIDDEN_FIELDS.has(k)).slice(0, 12).map(([field, change]) => {
    if (change && typeof change === 'object' && ('before' in (change as object) || 'after' in (change as object))) {
      const c = change as Record<string, unknown>
      const name = label(field)
      return { field: name, before: localize(name, text(c.before)), after: localize(name, text(c.after)) }
    }
    const name = label(field)
    return { field: name, before: '未设置', after: localize(name, text(change)) }
  })
})
</script>
<template>
  <div class="semantic-diff">
    <div v-for="row in rows" :key="row.field" class="semantic-diff__row">
      <div class="semantic-diff__field">{{ row.field }}</div>
      <div class="semantic-diff__change"><span class="before">{{ row.before }}</span><span class="arrow">→</span><span class="after">{{ row.after }}</span></div>
    </div>
    <div v-if="rows.length === 0" class="semantic-diff__empty">无字段级变更摘要</div>
    <details class="semantic-diff__raw"><summary>技术详情</summary><pre>{{ JSON.stringify(diff, null, 2) }}</pre></details>
  </div>
</template>
<style scoped>
.semantic-diff { display: grid; gap: 8px; }
.semantic-diff__field { font-size: 12px; color: var(--ac-meta, #6b7280); }
.semantic-diff__change { display: flex; gap: 8px; align-items: baseline; font-size: 13px; }
.semantic-diff__change .before { color: #6b7280; text-decoration: line-through; }
.semantic-diff__change .arrow { color: #9ca3af; }
.semantic-diff__change .after { font-weight: 600; }
.semantic-diff__raw { margin-top: 4px; font-size: 12px; }
.semantic-diff__raw pre { max-height: 180px; overflow: auto; background: #0f172a; color: #e2e8f0; padding: 8px; border-radius: 8px; }
</style>
