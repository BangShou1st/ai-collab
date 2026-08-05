<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { authApi } from '../../api/auth-api'
import PageHeader from '../../shared/PageHeader.vue'
import { useAuthStore } from '../../stores/auth-store'
import { accountApi } from './account-api'

const auth = useAuthStore()
const router = useRouter()
const profile = reactive({
  displayName: auth.currentUser?.displayName ?? '',
  email: auth.currentUser?.email ?? '',
})
const password = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const savingProfile = ref(false)
const changingPassword = ref(false)
const loggingOutCurrent = ref(false)
const loggingOutAll = ref(false)
const profileValidationMessage = computed(() => {
  if (!profile.displayName.trim()) return '显示名称不能为空'
  if (profile.displayName.length > 60) return '显示名称不能超过 60 个字符'
  if (profile.email.length > 120) return '邮箱不能超过 120 个字符'
  return ''
})
const passwordValidationMessage = computed(() => {
  if (!password.currentPassword && !password.newPassword && !password.confirmPassword) return ''
  if (!password.currentPassword) return '请输入当前密码'
  if (password.newPassword.length < 8) return '新密码至少需要 8 个字符'
  if (password.newPassword.length > 72) return '新密码不能超过 72 个字符'
  if (password.newPassword === password.currentPassword) return '新密码不能与当前密码相同'
  if (password.newPassword !== password.confirmPassword) return '两次输入的新密码不一致'
  return ''
})
const canChangePassword = computed(() =>
  Boolean(
    password.currentPassword
    && password.newPassword
    && password.confirmPassword
    && !passwordValidationMessage.value,
  ),
)

function clearPasswordFields(): void {
  password.currentPassword = ''
  password.newPassword = ''
  password.confirmPassword = ''
}

async function saveProfile(): Promise<void> {
  if (profileValidationMessage.value || savingProfile.value) return
  savingProfile.value = true
  try {
    const result = await accountApi.updateProfile({
      displayName: profile.displayName.trim(),
      email: profile.email.trim() || null,
    })
    auth.currentUser = result.data
    ElMessage.success('资料已更新')
  } catch (error) {
    showApiError(error, '个人资料保存')
  } finally {
    savingProfile.value = false
  }
}

async function changePassword(): Promise<void> {
  if (!auth.accessToken || !canChangePassword.value || changingPassword.value) return
  try {
    await ElMessageBox.confirm(
      '修改密码后，所有设备的登录会话都会退出。是否继续？',
      '确认修改密码',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
    changingPassword.value = true
    await authApi.changePassword(auth.accessToken, password.currentPassword, password.newPassword)
    auth.clearAuth()
    await router.replace('/login')
    ElMessage.success('密码已修改，请重新登录')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '密码修改')
  } finally {
    changingPassword.value = false
    clearPasswordFields()
  }
}

async function logoutCurrent(): Promise<void> {
  if (loggingOutCurrent.value) return
  try {
    await ElMessageBox.confirm(
      '确认退出当前设备吗？',
      '退出当前设备',
      { confirmButtonText: '确认退出', cancelButtonText: '取消', type: 'warning' },
    )
    loggingOutCurrent.value = true
    await auth.logout()
    await router.replace('/login')
    ElMessage.success('当前设备已退出登录')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '当前设备退出')
  } finally {
    loggingOutCurrent.value = false
  }
}

async function logoutAll(): Promise<void> {
  if (!auth.accessToken || loggingOutAll.value) return
  try {
    await ElMessageBox.confirm(
      '确认让所有设备退出登录吗？',
      '全部设备退出',
      { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
    )
    loggingOutAll.value = true
    await authApi.logoutAll(auth.accessToken)
    auth.clearAuth()
    await router.replace('/login')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '全部设备退出')
  } finally {
    loggingOutAll.value = false
  }
}

onBeforeUnmount(clearPasswordFields)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="个人中心"
      title="账号设置"
    />
    <section class="settings-grid">
      <el-card>
        <template #header>
          <div class="section-card-header">
            <h2>基本资料</h2>
            <p>用于团队成员识别和项目协作。</p>
          </div>
        </template>
        <el-form label-position="top" @submit.prevent="saveProfile">
          <el-form-item label="用户名"><el-input :model-value="auth.currentUser?.username" disabled /></el-form-item>
          <el-form-item label="显示名称">
            <el-input v-model="profile.displayName" :maxlength="60" />
          </el-form-item>
          <el-form-item label="邮箱"><el-input v-model="profile.email" type="email" :maxlength="120" /></el-form-item>
          <el-alert
            v-if="profileValidationMessage"
            :title="profileValidationMessage"
            type="warning"
            :closable="false"
          />
          <el-button
            type="primary"
            native-type="submit"
            :loading="savingProfile"
            :disabled="Boolean(profileValidationMessage) || savingProfile"
          >
            保存资料
          </el-button>
        </el-form>
      </el-card>
      <el-card>
        <template #header>
          <div class="section-card-header">
            <h2>修改密码</h2>
            <p>更新后所有设备都需要重新登录。</p>
          </div>
        </template>
        <el-form label-position="top" @submit.prevent="changePassword">
          <el-form-item label="当前密码">
            <el-input v-model="password.currentPassword" type="password" autocomplete="current-password" show-password />
          </el-form-item>
          <el-form-item label="新密码">
            <el-input
              v-model="password.newPassword"
              type="password"
              autocomplete="new-password"
              :maxlength="72"
              show-password
            />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input
              v-model="password.confirmPassword"
              type="password"
              autocomplete="new-password"
              :maxlength="72"
              show-password
            />
          </el-form-item>
          <el-alert
            v-if="passwordValidationMessage"
            :title="passwordValidationMessage"
            type="warning"
            :closable="false"
          />
          <div class="actions">
            <el-button
              native-type="submit"
              type="warning"
              :loading="changingPassword"
              :disabled="!canChangePassword || changingPassword"
            >
              修改密码
            </el-button>
          </div>
        </el-form>
      </el-card>
      <el-card class="danger-zone">
        <template #header>
          <div class="section-card-header">
            <h2>登录会话</h2>
            <p>管理当前设备或账号下的全部登录会话。</p>
          </div>
        </template>
        <p class="readonly-note">“退出当前设备”只撤销本设备会话；“全部设备退出”会撤销账号的所有会话。</p>
        <div class="actions">
          <el-button
            type="warning"
            plain
            :loading="loggingOutCurrent"
            :disabled="loggingOutCurrent || loggingOutAll"
            @click="logoutCurrent"
          >
            退出当前设备
          </el-button>
          <el-button
            type="danger"
            plain
            :loading="loggingOutAll"
            :disabled="loggingOutCurrent || loggingOutAll"
            @click="logoutAll"
          >
            全部设备退出
          </el-button>
        </div>
      </el-card>
    </section>
  </main>
</template>
