<script setup lang="ts">
import { computed } from 'vue'
import SemanticDiff from '../../shared/SemanticDiff.vue'
import { presentApproval } from './approval-presentation'
import type { AgentApproval } from './types'
import type { ProjectMember } from '../project/types'
const props = defineProps<{ approval: AgentApproval; members?: ProjectMember[] }>()
const emit = defineEmits<{ (event: 'approve', value: AgentApproval): void; (event: 'reject', value: AgentApproval): void }>()

/** 根据 assigneeId 查找负责人显示名称 */
function resolveAssignee(approval: AgentApproval, memberList?: ProjectMember[]): string | null {
  if (!memberList?.length) return null
  const diff = approval.diff as Record<string, unknown> | undefined
  const after = diff?.after as Record<string, unknown> | undefined
  const args = approval.arguments as Record<string, unknown> | undefined
  const source = after ?? args ?? {}
  const assigneeId = source.assigneeId as string | undefined
  if (!assigneeId) return null
  const member = memberList.find(m => m.userId === assigneeId)
  return member?.displayName ?? null
}

const presentation = computed(() => {
  const p = presentApproval(props.approval)
  // 用成员列表解析的名称覆盖"已指定"
  const resolved = resolveAssignee(props.approval, props.members)
  if (resolved) {
    p.fields = p.fields.map(f => f.label === '负责人' ? { ...f, value: resolved } : f)
  }
  return p
})
const hasRevision = computed(() => props.approval.revision > 1)

/** 过滤掉 null/undefined 值，只保留有意义的字段 */
function cleanDiff(diff: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(diff)) {
    if (v === null || v === undefined) continue
    if (typeof v === 'object' && !Array.isArray(v) && v !== null) {
      const nested = cleanDiff(v as Record<string, unknown>)
      if (Object.keys(nested).length > 0) out[k] = nested
    } else {
      out[k] = v
    }
  }
  return out
}
const cleanedDiff = computed(() => {
  if (!props.approval.diff) return null
  const cleaned = cleanDiff(props.approval.diff)
  return Object.keys(cleaned).length > 0 ? cleaned : null
})
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
    <SemanticDiff v-if="cleanedDiff" :diff="cleanedDiff" />
    <p>处理期限：{{ new Date(approval.expiresAt).toLocaleString('zh-CN') }}</p>
    <div v-if="approval.status === 'PENDING'">
      <el-button type="success" @click="emit('approve', approval)">批准并执行</el-button>
      <el-button @click="emit('reject', approval)">拒绝</el-button>
    </div>
  </el-card>
</template>

<style scoped>.approval{margin-bottom:12px}.approval :deep(.el-card__header){display:flex;justify-content:space-between}.approval-header{display:flex;align-items:center;gap:8px}.approval-tags{display:flex;gap:4px}dl{display:grid;grid-template-columns:120px minmax(0,1fr);gap:8px 16px;margin:0 0 14px}dt,p{color:var(--el-text-color-secondary)}dd{margin:0;font-weight:600}p{font-size:13px}.approval-diff{margin:12px 0;padding:8px;background:var(--el-fill-color-lighter);border-radius:4px;font-size:12px}.approval-diff pre{margin:8px 0 0;white-space:pre-wrap;word-break:break-all}</style>
