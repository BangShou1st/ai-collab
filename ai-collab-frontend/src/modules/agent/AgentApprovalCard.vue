<script setup lang="ts">
import { computed } from 'vue'
import { presentApproval } from './approval-presentation'
import type { AgentApproval } from './types'
const props = defineProps<{ approval: AgentApproval }>()
const emit = defineEmits<{ (event: 'approve', value: AgentApproval): void; (event: 'reject', value: AgentApproval): void }>()
const presentation = computed(() => presentApproval(props.approval))
</script>

<template>
  <el-card class="approval">
    <template #header><strong>{{ presentation.actionLabel }}</strong><el-tag>{{ presentation.statusLabel }}</el-tag></template>
    <dl><template v-for="field in presentation.fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
    <p>处理期限：{{ new Date(approval.expiresAt).toLocaleString('zh-CN') }}</p>
    <div v-if="approval.status === 'PENDING'">
      <el-button type="success" @click="emit('approve', approval)">批准并执行</el-button>
      <el-button @click="emit('reject', approval)">拒绝</el-button>
    </div>
  </el-card>
</template>

<style scoped>.approval{margin-bottom:12px}.approval :deep(.el-card__header){display:flex;justify-content:space-between}dl{display:grid;grid-template-columns:120px minmax(0,1fr);gap:8px 16px;margin:0 0 14px}dt,p{color:var(--el-text-color-secondary)}dd{margin:0;font-weight:600}p{font-size:13px}</style>
