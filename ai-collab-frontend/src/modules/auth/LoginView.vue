<script setup lang="ts">
import { reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { resolveSafeRedirect } from '../../shared/safe-redirect'
import { useAuthStore } from '../../stores/auth-store'
import AuthLayout from './components/AuthLayout.vue'
import './auth-card.css'

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
  <AuthLayout
    brand-title="人与 AI 协同，让项目从想法走向成果"
    brand-subtitle="面向高校竞赛团队与项目组的一站式智能协作平台"
    :show-illustration="true"
  >
    <el-card class="auth-card auth-form-card" shadow="always">
      <template #header>
        <div class="card-header-block">
          <p class="eyebrow card-eyebrow">
            <span>AI COLLAB AUTH</span>
          </p>
          <h1 class="card-title">欢迎登录</h1>
          <p class="subtitle card-subtitle">登录后进入项目、知识库与任务工作台</p>
        </div>
      </template>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名或邮箱"
            autocomplete="username"
            clearable
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            autocomplete="current-password"
            show-password
          />
        </el-form-item>
        <el-button
          class="submit-button auth-submit-btn"
          type="primary"
          native-type="submit"
          :loading="auth.authenticating"
          :disabled="!form.username || !form.password"
        >
          {{ auth.authenticating ? '正在登录……' : '登录' }}
        </el-button>
        <p class="auth-footer auth-footer-links">
          还没有账号？<router-link to="/register" class="auth-link">注册账号</router-link>
          <span class="auth-footer-divider" aria-hidden="true">·</span>
          <router-link to="/forgot-password" class="auth-link">忘记密码？</router-link>
        </p>
      </el-form>
    </el-card>
  </AuthLayout>
</template>
