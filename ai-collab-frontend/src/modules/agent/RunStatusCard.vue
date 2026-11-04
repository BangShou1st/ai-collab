<script setup lang="ts">
import type { AgentRunPresentation } from './agent-run-state'
import { RUN_STATUS_LABEL } from './agent-labels'
import type { AgentRun } from './types'

defineProps<{
  run: AgentRun
  state: AgentRunPresentation | null
  model: string | null
  sseLine: string
  ssePaused: boolean
  sending: boolean
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <section class="inspector-block run-status-card" aria-label="运行状态">
    <h2>运行 · {{ RUN_STATUS_LABEL[run.status] ?? run.status }}</h2>
    <el-alert
      v-if="state && (state.severity === 'error' || state.severity === 'warning')"
      :title="state.title"
      :type="state.severity === 'error' ? 'error' : state.severity"
      :closable="false"
      show-icon
    />
    <div class="inspector-meta-lines">
      <span v-if="model" class="inspector-meta">模型 {{ model }}</span>
      <p class="inspector-meta">SSE {{ ssePaused ? '已暂停（无需实时连接）' : sseLine }}</p>
      <p class="inspector-meta">Steps {{ run.stepsUsed }}/{{ run.maxSteps }} · Tools {{ run.toolCallsUsed }}/{{ run.maxToolCalls }}{{ run.contextPolicyVersion === 2 ? ' · 仅本运行，不含子运行' : '' }}</p>
    </div>
    <div v-if="state?.canRetry" class="inspector-actions">
      <el-button data-test="agent-retry" size="small" :loading="sending" @click="$emit('retry')">重试</el-button>
    </div>
  </section>
</template>
