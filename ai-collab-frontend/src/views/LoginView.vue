<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../api/api-result'
import { resolveSafeRedirect } from '../shared/safe-redirect'
import { useAuthStore } from '../stores/auth-store'
import { authApi } from '../api/auth-api'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const form = reactive({ username: '', password: '' })
const errorMessage = ref('')
const registrationEnabled = ref(false)

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
    await auth.login(form.username, form.password)
    const redirect = resolveSafeRedirect(router, route.query.redirect)
    await router.replace(redirect)
    ElMessage.success('登录成功')
  } catch (error: unknown) {
    errorMessage.value = normalizeApiError(error).message
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card" shadow="always">
      <template #header>
        <div>
          <p class="eyebrow">高校竞赛协作平台</p>
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
        <el-button
          class="submit-button"
          type="primary"
          native-type="submit"
          :loading="auth.authenticating"
          :disabled="!form.username || !form.password"
        >
          {{ auth.authenticating ? '正在登录……' : '登录' }}
        </el-button>
        <p v-if="registrationEnabled" class="auth-footer">
          还没有账号？<router-link to="/register">注册账号</router-link>
        </p>
      </el-form>
    </el-card>
  </main>
</template>
