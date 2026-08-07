<script setup lang="ts">
import { computed } from 'vue'
import { presentApproval } from './approval-presentation'
import type { AgentApproval } from './types'
const props = defineProps<{ approval: AgentApproval }>()
const emit = defineEmits<{ (event: 'approve', value: AgentApproval): void; (event: 'reject', value: AgentApproval): void }>()
const presentation = computed(() => presentApproval(props.approval))
const hasRevision = computed(() => props.approval.revision > 1)
</script>

<template>
  <el-card class="approval">
    <template #header>
      <div class="approval-header">
        <strong>{{ presentation.actionLabel }}</strong>
        <div class="approval-tags">
          <el-tag v-if="hasRevision" type="info">修订 #{{ approval.revision }}</el-tag>
          <el-tag>{{ presentation.statusLabel }}</el-tag>
        </div>
      </div>
    </template>
    <dl><template v-for="field in presentation.fields" :key="field.label"><dt>{{ field.label }}</dt><dd>{{ field.value }}</dd></template></dl>
    <div v-if="approval.diff && Object.keys(approval.diff).length > 0" class="approval-diff">
      <strong>变更内容：</strong>
      <pre>{{ JSON.stringify(approval.diff, null, 2) }}</pre>
    </div>
    <p>处理期限：{{ new Date(approval.expiresAt).toLocaleString('zh-CN') }}</p>
    <div v-if="approval.status === 'PENDING'">
      <el-button type="success" @click="emit('approve', approval)">批准并执行</el-button>
      <el-button @click="emit('reject', approval)">拒绝</el-button>
    </div>
  </el-card>
</template>

<style scoped>.approval{margin-bottom:12px}.approval :deep(.el-card__header){display:flex;justify-content:space-between}.approval-header{display:flex;align-items:center;gap:8px}.approval-tags{display:flex;gap:4px}dl{display:grid;grid-template-columns:120px minmax(0,1fr);gap:8px 16px;margin:0 0 14px}dt,p{color:var(--el-text-color-secondary)}dd{margin:0;font-weight:600}p{font-size:13px}.approval-diff{margin:12px 0;padding:8px;background:var(--el-fill-color-lighter);border-radius:4px;font-size:12px}.approval-diff pre{margin:8px 0 0;white-space:pre-wrap;word-break:break-all}</style>
