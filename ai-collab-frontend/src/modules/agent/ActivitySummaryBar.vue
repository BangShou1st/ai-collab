<script setup lang="ts">
import { computed } from 'vue'
import type { AgentActivity } from './agent-activity'

const props = defineProps<{
  items: AgentActivity[]
  runStatus: string | null
}>()

const OPERATING_STATUSES = new Set([
  'QUEUED', 'RUNNING', 'FAILED_RETRYABLE',
  'WAITING_FOR_APPROVAL', 'WAITING_FOR_USER_INPUT', 'PAUSED',
])

const operations = computed(() => props.items.filter((item) => item.kind !== 'narration'))
const running = computed(() =>
  (props.runStatus != null && OPERATING_STATUSES.has(props.runStatus))
  || operations.value.some((item) => item.status === 'running'))
const doneCount = computed(() => operations.value.filter((item) => item.status === 'done').length)
const waitingCount = computed(() => operations.value.filter((item) => item.status === 'waiting').length)
const failedCount = computed(() => operations.value.filter((item) => item.status === 'failed').length)
const inFlight = computed(() => {
  const candidates = operations.value.filter((item) => item.status === 'running')
  return candidates.find((item) => item.kind === 'analysis') ?? candidates[0] ?? null
})
const headline = computed(() => {
  if (running.value) return doneCount.value ? `正在执行 · 已完成 ${doneCount.value} 项操作` : '正在执行'
  const word = props.runStatus === 'SUCCEEDED' ? '已完成' : '已结束'
  return doneCount.value ? `${word} · ${doneCount.value} 项操作` : word
})
</script>

<template>
  <div class="summary-bar" :class="{ running }" data-test="agent-activity-summary" aria-live="polite">
    <span class="summary-glyph" aria-hidden="true">{{ running ? '◌' : '✓' }}</span>
    <span class="summary-headline">{{ headline }}</span>
    <span v-if="inFlight" class="summary-inflight"><i class="inflight-dot" aria-hidden="true" />{{ inFlight.title }}</span>
    <span v-if="waitingCount" class="summary-badge waiting">待处理 {{ waitingCount }}</span>
    <span v-if="failedCount" class="summary-badge failed">失败 {{ failedCount }}</span>
  </div>
</template>

<style scoped>
.summary-bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:8px 12px;border:1px solid var(--color-border);border-radius:10px;background:var(--el-fill-color-lighter);font-size:13px;color:var(--color-text-secondary)}
.summary-bar.running{border-color:var(--color-primary-soft);background:var(--color-primary-soft);color:var(--color-primary)}
.summary-glyph{flex:none;font-weight:700;font-size:12px;color:var(--color-success)}
.summary-bar.running .summary-glyph{color:var(--color-primary);animation:pulse 1.2s infinite}
.summary-headline{font-weight:600;color:var(--color-text)}
.summary-bar.running .summary-headline{color:var(--color-primary)}
.summary-inflight{display:inline-flex;align-items:center;gap:6px;min-width:0}
.inflight-dot{width:6px;height:6px;border-radius:50%;background:var(--color-primary);animation:pulse 1.2s infinite;flex:none}
.summary-badge{flex:none;font-size:11px;padding:1px 8px;border-radius:999px;border:1px solid}
.summary-badge.waiting{color:var(--color-warning);border-color:rgba(173,118,40,.35);background:var(--color-warning-soft)}
.summary-badge.failed{color:var(--color-danger);border-color:rgba(199,79,89,.35);background:var(--color-danger-soft)}
@keyframes pulse{0%{opacity:1}50%{opacity:.35}100%{opacity:1}}
</style>
