<script setup lang="ts">
import type { AgentPageContext } from './types'
const props = defineProps<{
  context: AgentPageContext
  taskTitle?: string | null
  milestoneName?: string | null
  documentName?: string | null
  planTitle?: string | null
}>()
const emit = defineEmits<{
  (event: 'remove', key: keyof AgentPageContext): void
  (event: 'clear'): void
  (event: 'toggle-strict-document-scope'): void
}>()
const chips = () => [
  { key: 'selectedTaskId' as const, label: props.context.selectedTaskId ? (props.taskTitle?.trim() || '当前任务') : '' },
  { key: 'selectedMilestoneId' as const, label: props.context.selectedMilestoneId ? (props.milestoneName?.trim() || '当前里程碑') : '' },
  { key: 'selectedDocumentId' as const, label: props.context.selectedDocumentId ? (props.documentName?.trim() || '当前文档') : '' },
  { key: 'selectedPlanId' as const, label: props.context.selectedPlanId ? (props.planTitle?.trim() || '当前规划') : '' },
].filter(item => item.label)
</script>

<template>
  <div v-if="chips().length" class="context-chips" aria-label="本次 Agent 页面上下文">
    <span>随消息发送：</span>
    <el-tag v-for="chip in chips()" :key="chip.key" closable @close="emit('remove', chip.key)">{{ chip.label }}</el-tag>
    <el-button
      v-if="props.context.selectedDocumentId"
      size="small"
      plain
      :type="props.context.strictDocumentScope ? 'warning' : 'info'"
      data-test="agent-strict-document-scope-toggle"
      @click="emit('toggle-strict-document-scope')"
    >
      {{ props.context.strictDocumentScope ? '仅限当前文档' : '全项目检索（优先当前文档）' }}
    </el-button>
    <el-button link size="small" @click="emit('clear')">清除</el-button>
  </div>
</template>

<style scoped>.context-chips{display:flex;align-items:center;gap:6px;flex-wrap:wrap;color:var(--el-text-color-secondary);font-size:13px}</style>
