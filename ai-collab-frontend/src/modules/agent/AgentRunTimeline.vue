<script setup lang="ts">
import { computed } from 'vue'
import type { AgentPlanView, AgentRunStatus } from './types'
import { RUN_STATUS_LABEL } from './agent-labels'
const props = defineProps<{ plan: AgentPlanView | null; status?: AgentRunStatus }>()
const stepMark = (status: string) => {
  const s = status.toUpperCase()
  if (s === 'DONE' || s === 'COMPLETED' || s === 'SUCCEEDED') return '✓'
  if (s === 'RUNNING' || s === 'IN_PROGRESS') return '●'
  return '○'
}
const stepLabel = (status: string) => {
  const s = status.toUpperCase()
  if (s === 'DONE' || s === 'COMPLETED' || s === 'SUCCEEDED') return '已完成'
  if (s === 'RUNNING' || s === 'IN_PROGRESS') return '进行中'
  if (s === 'FAILED') return '失败'
  if (s === 'SKIPPED') return '已跳过'
  return '待执行'
}
const statusLabel = computed(() => (props.status ? RUN_STATUS_LABEL[props.status] ?? props.status : ''))
</script>

<template>
  <section v-if="plan || status" class="timeline" aria-label="Agent 执行计划">
    <p v-if="plan?.objective" class="plan-objective">{{ plan.objective }}</p>
    <ol v-if="plan?.steps?.length">
      <li v-for="step in plan.steps" :key="step.id" :class="step.status.toLowerCase()">
        <span class="mark">{{ stepMark(step.status) }}</span>
        <span class="step-title">{{ step.title }}</span>
        <span class="step-status">{{ stepLabel(step.status) }}</span>
      </li>
    </ol>
    <p v-if="plan?.successCriteria?.length" class="plan-criteria">完成标准：{{ plan.successCriteria.join('；') }}</p>
    <small v-if="status">当前状态：{{ statusLabel }}</small>
  </section>
</template>

<style scoped>.timeline ol{display:grid;gap:6px;margin:12px 0;padding:0;list-style:none}.timeline li{display:flex;gap:10px;padding:8px 10px;border:1px solid var(--color-border);border-radius:8px;background:var(--el-fill-color-lighter);font-size:13px}.timeline .mark{font-weight:700}.timeline .step-title{flex:1}.timeline .step-status,.timeline small{color:var(--el-text-color-secondary)}.plan-objective{font-size:13px;margin:0 0 4px}.plan-criteria{font-size:12px;color:var(--el-text-color-secondary)}</style>
