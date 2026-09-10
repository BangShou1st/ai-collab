<script setup lang="ts">
import type { AgentPlanView, AgentRunEvent, AgentRunStatus } from './types'
defineProps<{ plan: AgentPlanView | null; events: AgentRunEvent[]; status?: AgentRunStatus }>()
</script>

<template>
  <section v-if="events.length || plan" class="timeline" aria-label="Agent 执行时间线">
    <ol>
      <li v-for="event in events" :key="event.sequence">
        <span>#{{ event.sequence }}</span><strong>{{ event.type }}</strong>
      </li>
    </ol>
    <small v-if="status">当前状态：{{ status }}</small>
  </section>
</template>

<style scoped>.timeline ol{display:grid;gap:6px;margin:12px 0;padding:0;list-style:none}.timeline li{display:flex;gap:10px;padding:8px 10px;border:1px solid var(--color-border);border-radius:8px;background:var(--el-fill-color-lighter);font-size:13px}.timeline span,.timeline small{color:var(--el-text-color-secondary)}</style>
