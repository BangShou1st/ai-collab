<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { httpClient } from '../../api/http-client'
import { apiResultFromResponse, showApiError } from '../../api/api-result'
import type { ApiResponse } from '../../api/types'
import { planStatusLabel } from '../planning/planning-labels'
const props = defineProps<{ projectId: string; sessionId: string; runVersion?: number }>()
interface Operation { operationId: string; planId: string; title: string; status: string; versionNo: number; goalRevision: number; reviewPath: string; errorCode?: string }
const items = ref<Operation[]>([])
let timer: ReturnType<typeof setTimeout> | undefined
let epoch = 0
let active = true
async function load(current = epoch, showError = true) {
  if (!props.sessionId) { items.value = []; return }
  try {
    const response = apiResultFromResponse(await httpClient.get<ApiResponse<Operation[]>>(`/projects/${props.projectId}/agent/sessions/${props.sessionId}/planning-operations`))
    if (!active || current !== epoch) return
    items.value = response.data
    if (items.value.some(item => ['ACCEPTED', 'SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING'].includes(item.status))) timer = setTimeout(() => load(current, false), 5000)
  } catch (error) { if (active && current === epoch && showError) showApiError(error, '规划操作加载') }
}
watch(() => [props.projectId, props.sessionId, props.runVersion], () => {
  if (timer) clearTimeout(timer)
  epoch++; items.value = []; void load(epoch)
}, { immediate: true })
onBeforeUnmount(() => { active = false; epoch++; if (timer) clearTimeout(timer) })
function label(status: string) { return status === 'ACCEPTED' ? '已受理' : status === 'SUPERSEDED' ? '已结束，后续版本已变化' : status === 'DISCARDED' ? '结果已丢弃' : planStatusLabel(status as never) }
</script>
<template>
  <section v-if="items.length" aria-label="规划操作">
    <article v-for="item in items" :key="item.operationId" class="planning-operation">
      <strong>{{ item.title }}</strong><p>{{ label(item.status) }} · 版本 {{ item.versionNo || '尚未生成' }} · 所属目标 {{ item.goalRevision }}</p>
      <p v-if="['ACCEPTED', 'SKELETON_GENERATING', 'DETAIL_GENERATING', 'REPAIRING'].includes(item.status)">后台处理中，完成后请人工审阅。</p>
      <router-link :to="item.reviewPath">打开规划页审阅</router-link>
      <details v-if="item.errorCode"><summary>失败详情</summary>{{ item.errorCode }}</details>
    </article>
  </section>
</template>
<style scoped>.planning-operation{border:1px solid var(--color-border);padding:12px;margin:8px 0;border-radius:8px}.planning-operation p{margin:6px 0}</style>
