<script setup lang="ts">
import { computed } from 'vue'
import type { AgentActivity } from './agent-activity'

const props = defineProps<{
  items: AgentActivity[]
  runStatus: string | null
}>()

const operations = computed(() => props.items.filter((item) => item.kind !== 'narration'))
const doneCount = computed(() => operations.value.filter((item) => item.status === 'done').length)
const waitingCount = computed(() => operations.value.filter((item) => item.status === 'waiting').length)
const failedCount = computed(() => operations.value.filter((item) => item.status === 'failed').length)

const inFlight = computed(() => {
  const candidates = operations.value.filter((item) => item.status === 'running')
  return candidates.find((item) => item.kind === 'analysis') ?? candidates[0] ?? null
})

type StateCategory =
  | 'queued'
  | 'running'
  | 'succeeded'
  | 'paused'
  | 'waiting_approval'
  | 'waiting_input'
  | 'failed'
  | 'failed_retryable'
  | 'canceled'
  | 'budget_exceeded'
  | 'idle'

const normalizedStatus = computed<StateCategory>(() => {
  const s = props.runStatus
  if (s === 'QUEUED') return 'queued'
  if (s === 'RUNNING') return 'running'
  if (s === 'SUCCEEDED') return 'succeeded'
  if (s === 'PAUSED') return 'paused'
  if (s === 'WAITING_FOR_APPROVAL') return 'waiting_approval'
  if (s === 'WAITING_FOR_USER_INPUT') return 'waiting_input'
  if (s === 'FAILED') return 'failed'
  if (s === 'FAILED_RETRYABLE') return 'failed_retryable'
  if (s === 'CANCELED') return 'canceled'
  if (s === 'BUDGET_EXCEEDED') return 'budget_exceeded'
  if (operations.value.some((item) => item.status === 'running')) return 'running'
  if (operations.value.some((item) => item.status === 'failed')) return 'failed'
  return 'idle'
})

const isRunning = computed(() => normalizedStatus.value === 'running')

const glyph = computed(() => {
  switch (normalizedStatus.value) {
    case 'queued': return '◌'
    case 'running': return '◌'
    case 'succeeded': return '✓'
    case 'paused': return '⏸'
    case 'waiting_approval': return '⏳'
    case 'waiting_input': return '💬'
    case 'failed': return '✕'
    case 'failed_retryable': return '⚠'
    case 'canceled': return '⊘'
    case 'budget_exceeded': return '⚠'
    case 'idle': return doneCount.value > 0 ? '✓' : '•'
    default: return '•'
  }
})

const headline = computed(() => {
  const opsEnded = doneCount.value ? ` · ${doneCount.value} 项操作` : ''
  switch (normalizedStatus.value) {
    case 'queued': return '等待执行'
    case 'running': return doneCount.value ? `正在执行 · 已完成 ${doneCount.value} 项操作` : '正在执行'
    case 'succeeded': return `已完成${opsEnded}`
    case 'paused': return `已暂停${opsEnded}`
    case 'waiting_approval': return `等待审批${opsEnded}`
    case 'waiting_input': return `等待回复${opsEnded}`
    case 'failed': return `执行失败${opsEnded}`
    case 'failed_retryable': return `待重试${opsEnded}`
    case 'canceled': return `已取消${opsEnded}`
    case 'budget_exceeded': return `额度限制${opsEnded}`
    case 'idle': return doneCount.value ? `已完成 · ${doneCount.value} 项操作` : '已结束'
    default: return '已结束'
  }
})
</script>

<template>
  <div
    class="summary-bar"
    :class="[
      `status-${normalizedStatus}`,
      { running: isRunning }
    ]"
    data-test="agent-activity-summary"
    aria-live="polite"
  >
    <span class="summary-glyph" aria-hidden="true">{{ glyph }}</span>
    <span class="summary-headline">{{ headline }}</span>
    <span v-if="inFlight" class="summary-inflight"><i class="inflight-dot" aria-hidden="true" />{{ inFlight.title }}</span>
    <span v-if="waitingCount" class="summary-badge waiting">待处理 {{ waitingCount }}</span>
    <span v-if="failedCount" class="summary-badge failed">失败 {{ failedCount }}</span>
  </div>
</template>

<style scoped>
.summary-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  color: var(--color-text-secondary);
  transition: all 0.2s ease;
}
.summary-bar.running,
.summary-bar.status-running {
  border-color: var(--color-primary-soft);
  background: var(--color-primary-soft);
  color: var(--color-primary);
}
.summary-bar.status-succeeded {
  border-color: rgba(22, 163, 74, 0.35);
  background: rgba(22, 163, 74, 0.08);
}
.summary-bar.status-failed {
  border-color: rgba(199, 79, 89, 0.35);
  background: var(--color-danger-soft);
}
.summary-bar.status-failed_retryable,
.summary-bar.status-paused,
.summary-bar.status-waiting_approval,
.summary-bar.status-budget_exceeded {
  border-color: rgba(173, 118, 40, 0.35);
  background: var(--color-warning-soft);
}
.summary-glyph {
  flex: none;
  font-weight: 700;
  font-size: 12px;
}
.status-succeeded .summary-glyph {
  color: var(--color-success);
}
.status-running .summary-glyph,
.summary-bar.running .summary-glyph {
  color: var(--color-primary);
  animation: pulse 1.2s infinite;
}
.status-queued .summary-glyph {
  color: var(--color-text-secondary);
}
.status-paused .summary-glyph,
.status-waiting_approval .summary-glyph,
.status-failed_retryable .summary-glyph,
.status-budget_exceeded .summary-glyph {
  color: var(--color-warning);
}
.status-waiting_input .summary-glyph {
  color: var(--color-primary);
}
.status-failed .summary-glyph {
  color: var(--color-danger);
}
.status-canceled .summary-glyph {
  color: var(--color-text-muted);
}
.status-idle .summary-glyph {
  color: var(--color-text-secondary);
}
.summary-headline {
  font-weight: 600;
  color: var(--color-text);
}
.summary-bar.running .summary-headline,
.status-running .summary-headline {
  color: var(--color-primary);
}
.status-failed .summary-headline {
  color: var(--color-danger);
}
.summary-inflight {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.inflight-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-primary);
  animation: pulse 1.2s infinite;
  flex: none;
}
.summary-badge {
  flex: none;
  font-size: 11px;
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid;
}
.summary-badge.waiting {
  color: var(--color-warning);
  border-color: rgba(173, 118, 40, 0.35);
  background: var(--color-warning-soft);
}
.summary-badge.failed {
  color: var(--color-danger);
  border-color: rgba(199, 79, 89, 0.35);
  background: var(--color-danger-soft);
}
@keyframes pulse {
  0% { opacity: 1; }
  50% { opacity: 0.35; }
  100% { opacity: 1; }
}
</style>
