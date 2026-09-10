<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ diff: Record<string, unknown> }>()
interface Row { field: string; before: string; after: string }
function text(v: unknown): string {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'string') return v || '—'
  return String(v)
}
const rows = computed<Row[]>(() => {
  const d = props.diff ?? {}
  const fields = (d.fields ?? d.changes ?? d) as Record<string, unknown>
  if (!fields || typeof fields !== 'object') return []
  return Object.entries(fields).slice(0, 12).map(([field, change]) => {
    if (change && typeof change === 'object' && ('before' in (change as object) || 'after' in (change as object))) {
      const c = change as Record<string, unknown>
      return { field, before: text(c.before), after: text(c.after) }
    }
    return { field, before: '—', after: text(change) }
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

