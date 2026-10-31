<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authApi } from '../../api/auth-api'
import { normalizeApiError } from '../../api/api-result'

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
  <main class="auth-page">
    <div class="auth-split">
      <section class="auth-brand">
        <p class="eyebrow">高校竞赛协作平台</p>
        <h1>重置密码</h1>
        <ul class="auth-highlights">
          <li>重置链接一次性有效</li>
          <li>重置成功后请使用新密码重新登录</li>
          <li>所有已登录设备都会退出</li>
        </ul>
      </section>
      <el-card class="auth-card">
        <template #header>
          <p class="eyebrow">高校竞赛协作平台</p>
          <h1>重置密码</h1>
          <p class="subtitle">请为新密码设置至少 8 位字符</p>
        </template>

        <el-alert
          v-if="view === 'invalid'"
          title="重置链接无效或已过期"
          description="链接可能已被使用或超过 30 分钟有效期。请重新申请密码重置。"
          type="error"
          :closable="false"
          show-icon
        />
        <div v-if="view === 'invalid'" class="actions">
          <router-link to="/forgot-password">
            <el-button type="primary" plain>重新申请密码重置</el-button>
          </router-link>
          <router-link to="/login">
            <el-button text>返回登录</el-button>
          </router-link>
        </div>

        <el-alert
          v-if="view === 'success'"
          title="密码已重置"
          description="请使用新密码重新登录。为保护账号安全，所有设备的登录会话已退出。"
          type="success"
          :closable="false"
          show-icon
        />
        <div v-if="view === 'success'" class="actions">
          <router-link to="/login">
            <el-button type="primary">前往登录</el-button>
          </router-link>
        </div>

        <el-form v-if="view === 'form'" label-position="top" @submit.prevent="submit">
          <el-form-item label="新密码">
            <el-input
              v-model="form.newPassword"
              type="password"
              autocomplete="new-password"
              :maxlength="72"
              show-password
            />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input
              v-model="form.confirmPassword"
              type="password"
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
          />
          <el-alert
            v-if="errorMessage"
            :title="errorMessage"
            type="error"
            :closable="false"
          />
          <el-button
            class="submit-button"
            type="primary"
            native-type="submit"
            :loading="submitting"
            :disabled="Boolean(validationMessage) || submitting"
          >
            重置密码
          </el-button>
        </el-form>
      </el-card>
    </div>
  </main>
</template>
