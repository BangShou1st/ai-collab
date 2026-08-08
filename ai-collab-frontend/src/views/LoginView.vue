<script setup lang="ts">
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../api/api-result'
import { resolveSafeRedirect } from '../shared/safe-redirect'
import { useAuthStore } from '../stores/auth-store'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const form = reactive({ username: '', password: '' })

async function submit(): Promise<void> {
  try {
    await auth.login(form.username, form.password)
    const redirect = resolveSafeRedirect(router, route.query.redirect)
    await router.replace(redirect)
    ElMessage.success('登录成功')
  } catch (error: unknown) {
    showApiError(error, '登录')
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
        <el-button
          class="submit-button"
          type="primary"
          native-type="submit"
          :loading="auth.authenticating"
          :disabled="!form.username || !form.password"
        >
          {{ auth.authenticating ? '正在登录……' : '登录' }}
        </el-button>
        <p class="auth-footer">
          还没有账号？<router-link to="/register">注册账号</router-link>
        </p>
      </el-form>
    </el-card>
  </main>
</template>
