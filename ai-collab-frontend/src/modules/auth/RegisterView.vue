<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../api/auth-api'
import { normalizeApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'

const auth = useAuthStore()
const router = useRouter()
const loadingPolicy = ref(true)
const enabled = ref(false)
const errorMessage = ref('')
const form = reactive({
  username: '',
  displayName: '',
  email: '',
  password: '',
  confirmPassword: '',
})
const canSubmit = computed(() =>
  enabled.value
  && Boolean(form.username && form.displayName && form.password)
  && form.password === form.confirmPassword,
)

onMounted(async () => {
  try {
    enabled.value = (await authApi.registrationPolicy()).data.enabled
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loadingPolicy.value = false
  }
})

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  errorMessage.value = ''
  try {
    await auth.register({
      username: form.username,
      displayName: form.displayName,
      email: form.email.trim() || null,
      password: form.password,
    })
    form.password = ''
    form.confirmPassword = ''
    await router.replace('/projects')
    ElMessage.success('注册成功')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card">
      <template #header>
        <p class="eyebrow">AI COLLAB</p>
        <h1>创建账号</h1>
        <p class="subtitle">注册后即可创建并管理自己的项目</p>
      </template>
      <el-alert
        v-if="!loadingPolicy && !enabled"
        title="当前未开放公开注册"
        type="info"
        :closable="false"
      />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名"><el-input v-model="form.username" autocomplete="username" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="form.displayName" /></el-form-item>
        <el-form-item label="邮箱（可选）"><el-input v-model="form.email" type="email" /></el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input v-model="form.confirmPassword" type="password" autocomplete="new-password" show-password />
        </el-form-item>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
        <el-button
          class="submit-button"
          native-type="submit"
          type="primary"
          :loading="auth.authenticating"
          :disabled="!canSubmit"
        >注册</el-button>
      </el-form>
      <p class="auth-footer"><router-link to="/login">返回登录</router-link></p>
    </el-card>
  </main>
</template>
