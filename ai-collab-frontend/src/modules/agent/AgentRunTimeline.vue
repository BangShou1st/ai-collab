<script setup lang="ts">
import { computed } from 'vue'
import type { AgentPlanView, AgentRunStatus, AgentRunEvent } from './types'
import { RUN_STATUS_LABEL } from './agent-labels'
const props = defineProps<{ plan: AgentPlanView | null; status?: AgentRunStatus; events?: AgentRunEvent[] }>()
// Skill templates have no durable link to invocations. The full tool/process feed lives in the
// conversation main area; the inspector only keeps query facts and reference material.
const statusLabel = computed(() => (props.status ? RUN_STATUS_LABEL[props.status] ?? props.status : ''))
const taskFacts = computed(() => (props.events ?? []).filter(event => event.type === 'TOOL_CALL_COMPLETED'
  && event.payload?.toolName === 'list_tasks' && event.payload?.status === 'SUCCEEDED')
  .flatMap(event => Array.isArray(event.payload.taskFacts) ? event.payload.taskFacts : [])
  .filter((fact): fact is { id: string; title: string; status: string; assigneeId: string | null; assigneeName: string | null } =>
    !!fact && typeof fact === 'object' && typeof fact.title === 'string' && typeof fact.status === 'string').slice(-50))
const taskStatus = (status: string) => ({ TODO: '待处理', IN_PROGRESS: '进行中', BLOCKED: '已阻塞', DONE: '已完成', CANCELED: '已取消' })[status] ?? status
</script>

<template>
  <section v-if="plan || status" class="timeline" aria-label="Agent 参考步骤与查询事实">
    <div v-if="taskFacts.length" aria-label="任务查询事实">
      <p class="plan-criteria">查询返回的任务事实</p>
      <table><thead><tr><th>标题</th><th>状态</th><th>负责人</th></tr></thead>
        <tbody><tr v-for="(fact, index) in taskFacts" :key="`${fact.id}-${index}`"><td>{{ fact.title }}</td><td>{{ taskStatus(fact.status) }}（{{ fact.status }}）</td><td>{{ fact.assigneeName ?? (fact.assigneeId ? '已分配（姓名不可用）' : '未分配') }}</td></tr></tbody>
      </table>
    </div>
    <p v-if="!taskFacts.length && plan?.steps?.length" class="plan-criteria">暂无工具执行记录</p>
    <p v-if="plan?.steps?.length" class="plan-criteria">参考步骤（Skill 模板，不代表已执行或承诺执行）</p>
    <p v-if="plan?.objective" class="plan-objective">{{ plan.objective }}</p>
    <ol v-if="plan?.steps?.length" aria-label="参考步骤">
      <li v-for="step in plan.steps" :key="step.id" class="reference">
        <span class="mark">○</span>
        <span class="step-title">{{ step.title }}</span>
        <span class="step-status">参考步骤</span>
      </li>
    </ol>
    <p v-if="plan?.successCriteria?.length" class="plan-criteria">参考完成标准：{{ plan.successCriteria.join('；') }}</p>
    <small v-if="status">当前状态：{{ statusLabel }}</small>
  </section>
</template>

<style scoped>.timeline ol{display:grid;gap:6px;margin:12px 0;padding:0;list-style:none}.timeline li{display:flex;gap:10px;padding:8px 10px;border:1px solid var(--color-border);border-radius:8px;background:var(--el-fill-color-lighter);font-size:13px}.timeline .mark{font-weight:700}.timeline .step-title{flex:1}.timeline .step-status,.timeline small{color:var(--el-text-color-secondary)}.plan-objective{font-size:13px;margin:0 0 4px}.plan-criteria{font-size:12px;color:var(--el-text-color-secondary)}</style>
