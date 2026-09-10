<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ diff: Record<string, unknown> }>()
interface Row { field: string; before: string; after: string }
const FIELD_LABELS: Record<string, string> = {
  title: '标题', name: '名称', status: '状态', priority: '优先级', assignee: '负责人',
  assigneeId: '负责人', milestone: '里程碑', milestoneId: '里程碑', startDate: '开始日期',
  dueDate: '截止日期', endDate: '截止日期', targetDate: '目标日期', description: '描述',
  content: '内容', reason: '原因', goal: '目标', estimateHours: '预计工时',
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
const rows = computed<Row[]>(() => {
  const d = props.diff ?? {}
  const fields = (d.fields ?? d.changes ?? d) as Record<string, unknown>
  if (!fields || typeof fields !== 'object') return []
  return Object.entries(fields).slice(0, 12).map(([field, change]) => {
    if (change && typeof change === 'object' && ('before' in (change as object) || 'after' in (change as object))) {
      const c = change as Record<string, unknown>
      return { field: label(field), before: text(c.before), after: text(c.after) }
    }
    return { field: label(field), before: '—', after: text(change) }
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
