<script setup lang="ts">
import { reactive, ref } from 'vue'
import { authApi } from '../../api/auth-api'
import { showApiError } from '../../api/api-result'

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
  <main class="auth-page">
    <div class="auth-split">
      <section class="auth-brand">
        <p class="eyebrow">高校竞赛协作平台</p>
        <h1>找回你的工作台</h1>
        <ul class="auth-highlights">
          <li>输入注册时绑定的邮箱</li>
          <li>收到邮件后 30 分钟内完成重置</li>
          <li>重置后所有设备的登录会话都会失效</li>
        </ul>
      </section>
      <el-card class="auth-card">
        <template #header>
          <p class="eyebrow">高校竞赛协作平台</p>
          <h1>忘记密码</h1>
          <p class="subtitle">我们会向绑定邮箱发送一次性重置链接</p>
        </template>
        <el-alert
          v-if="submitted"
          title="如果该邮箱已绑定账号，重置邮件已发送。请查收邮件并在 30 分钟内完成重置。"
          type="success"
          :closable="false"
          show-icon
        />
        <el-form v-else label-position="top" @submit.prevent="submit">
          <el-form-item label="绑定邮箱">
            <el-input v-model="form.email" type="email" :maxlength="120" autocomplete="email" />
          </el-form-item>
          <el-button
            class="submit-button"
            type="primary"
            native-type="submit"
            :loading="submitting"
            :disabled="!form.email.trim() || submitting"
          >
            发送重置邮件
          </el-button>
        </el-form>
        <p class="auth-footer">
          <router-link to="/login">返回登录</router-link>
        </p>
      </el-card>
    </div>
  </main>
</template>
