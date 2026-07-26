<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
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
const validationMessage = computed(() => {
  if (!form.username && !form.displayName && !form.email && !form.password && !form.confirmPassword) return ''
  if (!/^[A-Za-z0-9_]{3,40}$/.test(form.username)) return '用户名需为 3 至 40 位字母、数字或下划线'
  if (!form.displayName.trim()) return '显示名称不能为空'
  if (form.displayName.length > 60) return '显示名称不能超过 60 个字符'
  if (form.email.length > 120) return '邮箱不能超过 120 个字符'
  if (form.password.length < 8) return '密码至少需要 8 个字符'
  if (form.password.length > 72) return '密码不能超过 72 个字符'
  if (form.password !== form.confirmPassword) return '两次输入的密码不一致'
  return ''
})
const canSubmit = computed(() =>
  enabled.value
  && Boolean(form.username && form.displayName && form.password && form.confirmPassword)
  && !validationMessage.value,
)

function clearPasswordFields(): void {
  form.password = ''
  form.confirmPassword = ''
}

onMounted(async () => {
  errorMessage.value = ''
  try {
    enabled.value = (await authApi.registrationPolicy()).data.enabled
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loadingPolicy.value = false
  }
})

onBeforeUnmount(clearPasswordFields)

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  errorMessage.value = ''
  try {
    await auth.register({
      username: form.username,
      displayName: form.displayName.trim(),
      email: form.email.trim() || null,
      password: form.password,
    })
    clearPasswordFields()
    await router.replace('/projects')
    ElMessage.success('注册成功')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
    clearPasswordFields()
  }
}
</script>

<template>
  <main class="auth-page">
    <el-card class="auth-card">
      <template #header>
        <p class="eyebrow">高校竞赛协作平台</p>
        <h1>注册账号</h1>
        <p class="subtitle">注册后即可创建并管理自己的项目</p>
      </template>
      <el-alert
        v-if="!loadingPolicy && !enabled"
        title="当前未开放公开注册"
        type="info"
        :closable="false"
      />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input v-model="form.username" autocomplete="username" :maxlength="40" />
        </el-form-item>
        <el-form-item label="显示名称"><el-input v-model="form.displayName" :maxlength="60" /></el-form-item>
        <el-form-item label="邮箱（可选）"><el-input v-model="form.email" type="email" :maxlength="120" /></el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            autocomplete="new-password"
            :maxlength="72"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码">
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
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
        <el-button
          class="submit-button"
          native-type="submit"
          type="primary"
          :loading="auth.authenticating"
          :disabled="!canSubmit"
        >{{ auth.authenticating ? '正在创建……' : '创建账号' }}</el-button>
      </el-form>
      <p class="auth-footer"><router-link to="/login">返回登录</router-link></p>
    </el-card>
  </main>
</template>
