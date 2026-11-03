<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authApi } from '../../api/auth-api'
import { normalizeApiError } from '../../api/api-result'
import AuthLayout from './components/AuthLayout.vue'
import './auth-card.css'

type ViewState = 'form' | 'success' | 'invalid'

const route = useRoute()
const token = computed(() => {
  const value = route.query.token
  return typeof value === 'string' ? value : ''
})
const view = ref<ViewState>(token.value ? 'form' : 'invalid')
const submitting = ref(false)
const errorMessage = ref('')
const form = reactive({ newPassword: '', confirmPassword: '' })

const validationMessage = computed(() => {
  if (!form.newPassword && !form.confirmPassword) return ''
  if (form.newPassword.length < 8) return '新密码至少需要 8 个字符'
  if (form.newPassword.length > 72) return '新密码不能超过 72 个字符'
  if (form.newPassword !== form.confirmPassword) return '两次输入的密码不一致'
  return ''
})

async function submit(): Promise<void> {
  if (submitting.value || validationMessage.value || !token.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await authApi.resetPassword(token.value, form.newPassword)
    view.value = 'success'
  } catch (error) {
    const normalized = normalizeApiError(error)
    if (normalized.code === 'ACCOUNT_TOKEN_INVALID') {
      view.value = 'invalid'
    } else {
      errorMessage.value = normalized.message
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <AuthLayout
    brand-title="重新设置密码，保障竞赛数据安全"
    brand-subtitle="设置一个强密码，保护团队代码库、知识文档与 AI 规划资产"
    :show-illustration="true"
  >
    <el-card class="auth-card auth-form-card" shadow="always">
      <template #header>
        <div class="card-header-block">
          <p class="eyebrow card-eyebrow">
            <span>SECURITY RECOVERY</span>
          </p>
          <h1 class="card-title">重置密码</h1>
          <p class="subtitle card-subtitle">请为账号设置至少 8 位的新登录密码</p>
        </div>
      </template>

      <!-- 链接无效或过期状态 -->
      <template v-if="view === 'invalid'">
        <el-alert
          title="重置链接无效或已过期"
          description="该链接可能已被使用或超过 30 分钟安全有效期。请重新申请密码重置。"
          type="error"
          :closable="false"
          show-icon
        />
        <div class="actions auth-actions-group">
          <router-link to="/forgot-password">
            <el-button class="auth-submit-btn" type="primary">重新申请密码重置</el-button>
          </router-link>
          <router-link to="/login">
            <el-button text>返回登录</el-button>
          </router-link>
        </div>
      </template>

      <!-- 重置成功状态 -->
      <template v-else-if="view === 'success'">
        <el-alert
          title="密码已重置"
          description="密码重置成功！为保护账号安全，所有设备的登录会话已退出。请使用新密码重新登录。"
          type="success"
          :closable="false"
          show-icon
        />
        <div class="actions auth-actions-group">
          <router-link to="/login">
            <el-button class="auth-submit-btn" type="primary">前往登录</el-button>
          </router-link>
        </div>
      </template>

      <!-- 输入重置密码表单 -->
      <el-form v-else label-position="top" @submit.prevent="submit">
        <el-form-item label="新密码">
          <el-input
            v-model="form.newPassword"
            type="password"
            placeholder="至少 8 位字符"
            autocomplete="new-password"
            :maxlength="72"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入新密码"
            autocomplete="new-password"
            :maxlength="72"
            show-password
          />
        </el-form-item>

        <el-alert
          v-if="validationMessage"
          :title="validationMessage"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-alert
          v-if="errorMessage"
          :title="errorMessage"
          type="error"
          :closable="false"
          show-icon
        />

        <el-button
          class="submit-button auth-submit-btn"
          type="primary"
          native-type="submit"
          :loading="submitting"
          :disabled="Boolean(validationMessage) || submitting"
        >
          {{ submitting ? '正在重置……' : '确认重置密码' }}
        </el-button>

        <p class="auth-footer auth-footer-links">
          记起原密码？<router-link to="/login" class="auth-link">返回登录</router-link>
        </p>
      </el-form>
    </el-card>
  </AuthLayout>
</template>
