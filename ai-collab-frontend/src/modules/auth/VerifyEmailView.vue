<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { authApi } from '../../api/auth-api'
import { normalizeApiError } from '../../api/api-result'

type ViewState = 'verifying' | 'success' | 'invalid'

const route = useRoute()
const token = computed(() => {
  const value = route.query.token
  return typeof value === 'string' ? value : ''
})
const view = ref<ViewState>('verifying')

onMounted(async () => {
  if (!token.value) {
    view.value = 'invalid'
    return
  }
  try {
    await authApi.verifyEmail(token.value)
    view.value = 'success'
  } catch (error) {
    normalizeApiError(error)
    view.value = 'invalid'
  }
})
</script>

<template>
  <main class="auth-page">
    <div class="auth-split">
      <section class="auth-brand">
        <p class="eyebrow">高校竞赛协作平台</p>
        <h1>邮箱验证</h1>
        <ul class="auth-highlights">
          <li>验证邮箱后可接收邀请与找回密码邮件</li>
          <li>验证链接一次性有效，24 小时内完成</li>
        </ul>
      </section>
      <el-card class="auth-card">
        <template #header>
          <p class="eyebrow">高校竞赛协作平台</p>
          <h1>邮箱验证</h1>
        </template>
        <el-skeleton v-if="view === 'verifying'" :rows="3" animated />
        <el-alert
          v-else-if="view === 'success'"
          title="邮箱验证成功"
          description="你的邮箱已完成验证，现在可以正常接收平台邮件。"
          type="success"
          :closable="false"
          show-icon
        />
        <template v-else>
          <el-alert
            title="验证链接无效或已过期"
            description="链接可能已被使用或超过 24 小时有效期。请登录后在账号设置中重新发送验证邮件。"
            type="error"
            :closable="false"
            show-icon
          />
          <div class="actions">
            <router-link to="/login">
              <el-button type="primary" plain>前往登录</el-button>
            </router-link>
          </div>
        </template>
      </el-card>
    </div>
  </main>
</template>
