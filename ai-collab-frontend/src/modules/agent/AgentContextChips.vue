<script setup lang="ts">
import type { AgentPageContext } from './types'
const props = defineProps<{ context: AgentPageContext; taskTitle?: string; milestoneName?: string; documentName?: string }>()
const emit = defineEmits<{ (event: 'remove', key: keyof AgentPageContext): void; (event: 'clear'): void }>()
const chips = () => [
  { key: 'selectedTaskId' as const, label: props.taskTitle ?? (props.context.selectedTaskId ? '当前任务' : '') },
  { key: 'selectedMilestoneId' as const, label: props.milestoneName ?? (props.context.selectedMilestoneId ? '当前里程碑' : '') },
  { key: 'selectedDocumentId' as const, label: props.documentName ?? (props.context.selectedDocumentId ? '当前文档' : '') },
  { key: 'selectedPlanId' as const, label: props.context.selectedPlanId ? '当前规划' : '' },
].filter(item => item.label)
</script>

<template>
  <div v-if="chips().length" class="context-chips" aria-label="本次 Agent 页面上下文">
    <span>随消息发送：</span>
    <el-tag v-for="chip in chips()" :key="chip.key" closable @close="emit('remove', chip.key)">{{ chip.label }}</el-tag>
    <el-button link size="small" @click="emit('clear')">清除</el-button>
  </div>
</template>

<style scoped>.context-chips{display:flex;align-items:center;gap:6px;flex-wrap:wrap;color:var(--el-text-color-secondary);font-size:13px}</style>
