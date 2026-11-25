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
  livePhase?: string | null
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <section class="inspector-block run-status-card" aria-label="运行状态">
    <h2 class="status-heading">
      <i v-if="run.status === 'RUNNING'" class="live-dot" aria-hidden="true" />
      <span>运行 · {{ RUN_STATUS_LABEL[run.status] ?? run.status }}</span>
    </h2>
    <el-alert
      v-if="state && (state.severity === 'error' || state.severity === 'warning')"
      :title="state.title"
      :type="state.severity === 'error' ? 'error' : state.severity"
      :closable="false"
      show-icon
    />
    <div class="inspector-meta-lines">
      <span v-if="model" class="inspector-meta">模型 {{ model }}</span>
      <p class="inspector-meta">{{ ssePaused ? '实时更新已暂停' : sseLine }}</p>
      <p v-if="livePhase" class="inspector-meta live-phase">{{ livePhase }}</p>
      <p class="inspector-meta">
        第 {{ run.stepsUsed }} 步 / 共 {{ run.maxSteps }} 步 · 已调用工具 {{ run.toolCallsUsed }} 次
      </p>
    </div>
    <div v-if="state?.canRetry" class="inspector-actions">
      <el-button data-test="agent-retry" size="small" :loading="sending" @click="$emit('retry')">重试</el-button>
    </div>
  </section>
</template>

<style scoped>
.status-heading {
  display: flex;
  align-items: center;
  gap: 6px;
}
.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-primary);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.16);
  animation: status-pulse 1.4s ease-in-out infinite;
  flex: none;
}
.live-phase {
  color: var(--color-primary);
  font-weight: 500;
}
@keyframes status-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}
</style>
