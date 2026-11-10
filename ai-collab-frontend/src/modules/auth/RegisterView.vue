<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../api/auth-api'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import AuthLayout from './components/AuthLayout.vue'
import './auth-card.css'

const auth = useAuthStore()
const router = useRouter()
const loadingPolicy = ref(true)
const enabled = ref(false)
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
  try {
    enabled.value = (await authApi.registrationPolicy()).data.enabled
  } catch (error) {
    showApiError(error, '注册策略加载')
  } finally {
    loadingPolicy.value = false
  }
})

onBeforeUnmount(clearPasswordFields)

async function submit(): Promise<void> {
  if (!canSubmit.value) return
  try {
    const registeredWithEmail = Boolean(form.email.trim())
    await auth.register({
      username: form.username,
      displayName: form.displayName.trim(),
      email: form.email.trim() || null,
      password: form.password,
    })
    clearPasswordFields()
    await router.replace('/home')
    ElMessage.success(registeredWithEmail
      ? '注册成功；如未收到验证邮件，请稍后在账号设置中重发。账号邮件共用 60 秒发送间隔。'
      : '注册成功')
  } catch (error) {
    showApiError(error, '账号注册')
    clearPasswordFields()
  }
}
</script>

<template>
  <AuthLayout
    brand-title="与团队同行，以 AI 赋能项目全周期"
    brand-subtitle="注册后即可加入或创建项目，开启智能任务拆解与协作"
    :show-illustration="true"
  >
    <el-card class="auth-card auth-form-card" shadow="always">
      <template #header>
        <div class="card-header-block">
          <p class="eyebrow card-eyebrow">
            <span>JOIN AI COLLAB</span>
          </p>
          <h1 class="card-title">创建新账号</h1>
          <p class="subtitle card-subtitle">注册后即可创建并管理自己的项目团队</p>
        </div>
      </template>

      <el-alert
        v-if="!loadingPolicy && !enabled"
        title="当前未开放公开注册"
        type="info"
        :closable="false"
        show-icon
      />

      <el-form class="register-form" label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名">
          <el-input
            v-model="form.username"
            placeholder="3-40位字母、数字或下划线"
            autocomplete="username"
            :maxlength="40"
            clearable
          />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input
            v-model="form.displayName"
            placeholder="团队成员看到的昵称（如：张同学）"
            :maxlength="60"
            clearable
          />
        </el-form-item>
        <el-form-item label="邮箱（可选）">
          <el-input
            v-model="form.email"
            type="email"
            placeholder="用于接收邀请通知与找回密码"
            :maxlength="120"
            clearable
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="至少 8 位字符"
            autocomplete="new-password"
            :maxlength="72"
            show-password
          />
        </el-form-item>
        <el-form-item label="确认密码">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="请再次输入密码"
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

        <el-button
          class="submit-button auth-submit-btn"
          native-type="submit"
          type="primary"
          :loading="auth.authenticating"
          :disabled="!canSubmit"
        >
          {{ auth.authenticating ? '正在创建……' : '创建账号' }}
        </el-button>
      </el-form>

      <p class="auth-footer auth-footer-links">
        已有账号？<router-link to="/login" class="auth-link">返回登录</router-link>
      </p>
    </el-card>
  </AuthLayout>
</template>

<style scoped>
.register-form :deep(.el-form-item) {
  margin-bottom: 12px; /* 注册字段较多，紧凑排布防止过长 */
}

@media (max-width: 480px) {
  .register-form :deep(.el-form-item) {
    margin-bottom: 8px;
  }
}
</style>
