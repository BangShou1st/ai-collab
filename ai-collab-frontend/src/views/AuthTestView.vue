<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { formatSafeJson, normalizeApiError, toSafeApiResult } from '../api/api-result'
import type { ApiResult } from '../api/types'
import { useAuthStore } from '../stores/auth-store'

const auth = useAuthStore()
const router = useRouter()
const lastOperation = ref('')
const lastResult = ref<ApiResult<unknown> | null>(null)
const accessTokenStatus = computed(() =>
  auth.accessToken ? 'Access Token：已存在' : 'Access Token：不存在',
)
const formattedData = computed(() => formatSafeJson(lastResult.value?.data ?? null))

async function runOperation(
  operation: string,
  request: () => Promise<ApiResult<unknown>>,
): Promise<void> {
  lastOperation.value = operation
  try {
    lastResult.value = toSafeApiResult(await request())
  } catch (error: unknown) {
    lastResult.value = normalizeApiError(error)
  }
}

async function loadCurrentUser(): Promise<void> {
  await runOperation('获取当前用户', () => auth.loadCurrentUser())
}

async function refresh(): Promise<void> {
  await runOperation('主动刷新', () => auth.refresh())
}

async function logout(): Promise<void> {
  lastOperation.value = '退出当前设备'
  try {
    lastResult.value = toSafeApiResult(await auth.logout())
  } catch (error: unknown) {
    lastResult.value = normalizeApiError(error)
  } finally {
    await router.replace('/login')
  }
}

function clearResult(): void {
  lastOperation.value = ''
  lastResult.value = null
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card wide" shadow="always">
      <template #header>
        <div class="header-row">
          <div>
            <p class="eyebrow">AUTH TEST</p>
            <h1>认证状态</h1>
          </div>
          <el-tag :type="auth.isAuthenticated ? 'success' : 'info'">
            {{ auth.isAuthenticated ? '已登录' : '未登录' }}
          </el-tag>
        </div>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="用户 ID">{{ auth.currentUser?.id }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ auth.currentUser?.username }}</el-descriptions-item>
        <el-descriptions-item label="显示名">{{ auth.currentUser?.displayName }}</el-descriptions-item>
        <el-descriptions-item label="邮箱">{{ auth.currentUser?.email ?? '未设置' }}</el-descriptions-item>
        <el-descriptions-item label="账号状态">{{ auth.currentUser?.status }}</el-descriptions-item>
        <el-descriptions-item label="认证凭据状态">{{ accessTokenStatus }}</el-descriptions-item>
      </el-descriptions>

      <div class="actions">
        <el-button @click="loadCurrentUser">获取当前用户</el-button>
        <el-button :loading="auth.refreshing" @click="refresh">主动刷新</el-button>
        <el-button type="danger" plain @click="logout">退出当前设备</el-button>
        <el-button plain @click="clearResult">清空请求结果</el-button>
      </div>

      <section v-if="lastResult" class="result-panel" aria-live="polite">
        <h2>最近一次操作</h2>
        <dl>
          <dt>操作名称</dt>
          <dd>{{ lastOperation }}</dd>
          <dt>HTTP 状态</dt>
          <dd>{{ lastResult.httpStatus ?? '无' }}</dd>
          <dt>业务 code</dt>
          <dd>{{ lastResult.code }}</dd>
          <dt>message</dt>
          <dd>{{ lastResult.message }}</dd>
        </dl>
        <h3>data</h3>
        <pre>{{ formattedData }}</pre>
      </section>
    </el-card>
  </main>
</template>
