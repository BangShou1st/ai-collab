<script setup lang="ts">
import { ref, watch } from 'vue'
import { documentApi } from './document-api'
import { showApiError } from '../../api/api-result'
import type { DocumentBodyRead, DocumentReadingStatus } from './types'
const props = defineProps<{ projectId: string; documentId: string; chunkId?: string }>()
const status = ref<DocumentReadingStatus | null>(null)
const body = ref<DocumentBodyRead | null>(null)
const loading = ref(false)
let revision = 0
async function read(next = false) {
  if (loading.value) return
  loading.value = true
  const current = revision
  try {
    const cursor = next ? body.value?.continuation : undefined
    const response = await documentApi.readBody(props.projectId, props.documentId, {
      ...(cursor ?? {}), chunkId: props.chunkId, maxChars: 3000,
    })
    if (current === revision) body.value = response.data
  } catch (error) { if (current === revision) showApiError(error, '正文读取') }
  finally { if (current === revision) loading.value = false }
}
watch(() => [props.projectId, props.documentId, props.chunkId], async () => {
  const current = ++revision
  status.value = null; body.value = null; loading.value = false
  try {
    const response = await documentApi.readingStatus(props.projectId, props.documentId)
    if (current !== revision) return
    status.value = response.data
    if (props.chunkId) await read()
  } catch (error) { if (current === revision) showApiError(error, '正文状态加载') }
}, { immediate: true })
</script>
<template>
  <section v-if="status" aria-label="文档正文">
    <p>{{ status.bodyReadable ? '正文可读' : '正文尚不可读' }} · {{ status.searchAvailable ? '检索可用' : '检索尚不可用' }}</p>
    <p v-if="status.failureStage">失败阶段：{{ status.failureStage }}</p>
    <el-button v-if="status.bodyReadable && !body" :loading="loading" @click="read()">{{ chunkId ? '查看引用片段' : '阅读正文' }}</el-button>
    <template v-if="body">
      <p>本次仅阅读下列范围{{ body.hasMore ? '，可继续读取' : '' }}。</p>
      <article v-for="item in body.items" :key="item.chunkId">
        <strong>{{ item.heading || '正文' }}</strong>
        <small v-if="item.location?.pageNumber"> · 第 {{ item.location.pageNumber }} 页{{ item.location.pageThrough && item.location.pageThrough !== item.location.pageNumber ? `至 ${item.location.pageThrough} 页` : '' }}</small>
        <p>片段 {{ item.chunkNo + 1 }}，字符 {{ item.fromOffset }}–{{ item.throughOffset }}</p>
        <pre style="white-space: pre-wrap; overflow-wrap: anywhere">{{ item.content }}</pre>
      </article>
      <el-button v-if="body.hasMore" :loading="loading" @click="read(true)">继续阅读</el-button>
      <details><summary>来源详情</summary><p>原件摘要：{{ body.originalContentHash || '历史资料未记录' }}</p><p>解析版本：{{ body.parseVersion || '历史资料未记录' }}</p></details>
    </template>
  </section>
</template>
