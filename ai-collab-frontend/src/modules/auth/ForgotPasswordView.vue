<script setup lang="ts">
import { reactive, ref } from 'vue'
import { authApi } from '../../api/auth-api'
import { showApiError } from '../../api/api-result'
import AuthLayout from './components/AuthLayout.vue'
import './auth-card.css'

const form = reactive({ email: '' })
const submitting = ref(false)
const submitted = ref(false)

async function submit(): Promise<void> {
  if (submitting.value || !form.email.trim()) return
  submitting.value = true
  try {
    await authApi.forgotPassword(form.email.trim())
    submitted.value = true
  } catch (error) {
    showApiError(error, '密码重置申请')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout
    brand-title="找回你的工作台，快速重返团队协作"
    brand-subtitle="安全验证机制将保护你的项目与知识库资产"
    :show-illustration="true"
  >
    <el-card class="auth-card auth-form-card" shadow="always">
      <template #header>
        <div class="card-header-block">
          <p class="eyebrow card-eyebrow">
            <span>ACCOUNT RECOVERY</span>
          </p>
          <h1 class="card-title">找回密码</h1>
          <p class="subtitle card-subtitle">输入账号绑定的邮箱，获取一次性安全重置链接</p>
        </div>
      </template>

      <!-- 发送成功状态 -->
      <div v-if="submitted" class="success-state-container">
        <el-alert
          title="申请已受理"
          description="如果该邮箱已绑定账号，重置邮件将在几分钟内送达，链接 30 分钟内有效。"
          type="success"
          :closable="false"
          show-icon
        />
        <div class="auth-actions-group">
          <router-link to="/login">
            <el-button class="auth-submit-btn" type="primary">返回登录页</el-button>
          </router-link>
        </div>
      </div>

      <!-- 待输入状态 -->
      <el-form v-else label-position="top" @submit.prevent="submit">
        <div class="recovery-tip-box">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
          <span>重置密码后，所有已登录设备的会话将自动失效以保障安全。</span>
        </div>

        <el-form-item label="账号绑定邮箱">
          <el-input
            v-model="form.email"
            type="email"
            placeholder="请输入注册时绑定的邮箱"
            :maxlength="120"
            autocomplete="email"
            clearable
          />
        </el-form-item>

        <el-button
          class="submit-button auth-submit-btn"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="!form.email.trim() || submitting"
        >
          {{ submitting ? '正在发送……' : '发送重置邮件' }}
        </el-button>

        <p class="auth-footer auth-footer-links">
          记起密码了？<router-link to="/login" class="auth-link">返回登录</router-link>
        </p>
      </el-form>
    </el-card>
  </AuthLayout>
</template>

<style scoped>
.recovery-tip-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 9px;
  margin-bottom: 16px;
  font-size: 12px;
  line-height: 1.45;
  color: #166534;
}

.recovery-tip-box svg {
  flex-shrink: 0;
  margin-top: 1px;
}

.success-state-container {
  padding: 4px 0 12px;
}
</style>
