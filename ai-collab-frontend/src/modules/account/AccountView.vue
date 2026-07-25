<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { authApi } from '../../api/auth-api'
import { useAuthStore } from '../../stores/auth-store'
import { accountApi } from './account-api'

const auth = useAuthStore()
const router = useRouter()
const profile = reactive({
  displayName: auth.currentUser?.displayName ?? '',
  email: auth.currentUser?.email ?? '',
})
const password = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
const errorMessage = ref('')

async function saveProfile(): Promise<void> {
  try {
    const result = await accountApi.updateProfile({
      displayName: profile.displayName,
      email: profile.email.trim() || null,
    })
    auth.currentUser = result.data
    ElMessage.success('资料已更新')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

async function changePassword(): Promise<void> {
  if (!auth.accessToken || password.newPassword !== password.confirmPassword) return
  await ElMessageBox.confirm('修改密码后，所有设备的 Refresh 会话都会退出。是否继续？', '确认修改密码')
  try {
    await authApi.changePassword(auth.accessToken, password.currentPassword, password.newPassword)
    auth.clearAuth()
    await router.replace('/login')
    ElMessage.success('密码已修改，请重新登录')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    password.currentPassword = ''
    password.newPassword = ''
    password.confirmPassword = ''
  }
}

async function logoutAll(): Promise<void> {
  if (!auth.accessToken) return
  await ElMessageBox.confirm('这会退出所有设备上的登录会话。是否继续？', '全部设备退出')
  try {
    await authApi.logoutAll(auth.accessToken)
    auth.clearAuth()
    await router.replace('/login')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-header">
      <div><p class="eyebrow">ACCOUNT</p><h1>账号设置</h1></div>
      <router-link to="/projects">返回项目</router-link>
    </header>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section class="settings-grid">
      <el-card>
        <template #header><h2>个人资料</h2></template>
        <el-form label-position="top" @submit.prevent="saveProfile">
          <el-form-item label="用户名"><el-input :model-value="auth.currentUser?.username" disabled /></el-form-item>
          <el-form-item label="显示名称"><el-input v-model="profile.displayName" /></el-form-item>
          <el-form-item label="邮箱"><el-input v-model="profile.email" /></el-form-item>
          <el-button type="primary" native-type="submit">保存资料</el-button>
        </el-form>
      </el-card>
      <el-card>
        <template #header><h2>安全设置</h2></template>
        <el-form label-position="top" @submit.prevent="changePassword">
          <el-form-item label="当前密码"><el-input v-model="password.currentPassword" type="password" /></el-form-item>
          <el-form-item label="新密码"><el-input v-model="password.newPassword" type="password" /></el-form-item>
          <el-form-item label="确认新密码"><el-input v-model="password.confirmPassword" type="password" /></el-form-item>
          <div class="actions">
            <el-button native-type="submit" type="warning">修改密码</el-button>
            <el-button type="danger" plain @click="logoutAll">全部设备退出</el-button>
          </div>
        </el-form>
      </el-card>
    </section>
  </main>
</template>
