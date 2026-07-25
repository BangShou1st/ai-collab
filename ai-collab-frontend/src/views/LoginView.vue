<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { formatSafeJson, normalizeApiError, toSafeApiResult } from '../api/api-result'
import type { ApiResult } from '../api/types'
import { useAuthStore } from '../stores/auth-store'
import { authApi } from '../api/auth-api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const form = reactive({ username: '', password: '' })
const errorMessage = ref('')
const registrationEnabled = ref(false)
const lastResult = ref<ApiResult<unknown> | null>(null)
const displayedResult = computed<ApiResult<unknown> | null>(() => {
  if (lastResult.value) return lastResult.value
  return auth.initializationError ? toSafeApiResult(auth.initializationError) : null
})
const formattedData = computed(() => formatSafeJson(displayedResult.value?.data ?? null))

onMounted(async () => {
  try {
    const policyCall = authApi.registrationPolicy?.()
    if (policyCall) registrationEnabled.value = (await policyCall).data.enabled
  } catch {
    registrationEnabled.value = false
  }
})

async function submit(): Promise<void> {
  errorMessage.value = ''
  try {
    const result = await auth.login(form.username, form.password)
    lastResult.value = toSafeApiResult(result)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/projects'
    await router.replace(redirect)
    ElMessage.success('登录成功')
  } catch (error: unknown) {
    lastResult.value = normalizeApiError(error)
    errorMessage.value = lastResult.value.message
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card" shadow="always">
      <template #header>
        <div>
          <p class="eyebrow">AI COLLAB</p>
          <h1>登录</h1>
          <p class="subtitle">登录后进入项目与任务工作台</p>
        </div>
      </template>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="current-password" show-password />
        </el-form-item>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
        <section v-if="displayedResult" class="result-panel compact" aria-live="polite">
          <p>HTTP 状态：{{ displayedResult.httpStatus ?? '无' }}</p>
          <p>业务 code：{{ displayedResult.code }}</p>
          <p>message：{{ displayedResult.message }}</p>
          <pre>{{ formattedData }}</pre>
        </section>
        <el-button
          class="submit-button"
          type="primary"
          native-type="submit"
          :loading="auth.authenticating"
          :disabled="!form.username || !form.password"
        >
          登录
        </el-button>
        <p v-if="registrationEnabled" class="auth-footer">
          还没有账号？<router-link to="/register">公开注册</router-link>
        </p>
      </el-form>
    </el-card>
  </main>
</template>
