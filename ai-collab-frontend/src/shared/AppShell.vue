<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../api/api-result'
import { useAuthStore } from '../stores/auth-store'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loggingOut = ref(false)
const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

async function logout(): Promise<void> {
  if (loggingOut.value) return
  try {
    await ElMessageBox.confirm(
      '确认退出当前设备吗？',
      '退出登录',
      { confirmButtonText: '确认退出', cancelButtonText: '取消', type: 'warning' },
    )
    loggingOut.value = true
    await auth.logout()
    await router.replace('/login')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(normalizeApiError(error).message)
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="app-shell">
    <header class="app-topbar">
      <router-link class="brand" to="/projects" aria-label="AI Collab 项目列表">
        <span class="brand-mark">AI</span>
        <span>
          <strong>AI Collab</strong>
          <small>高校竞赛项目协作平台</small>
        </span>
      </router-link>
      <nav class="global-nav" aria-label="全局导航">
        <router-link to="/projects">我的项目</router-link>
        <router-link to="/account">账号设置</router-link>
      </nav>
      <div class="user-entry">
        <span class="avatar" aria-hidden="true">
          {{ auth.currentUser?.displayName?.slice(0, 1) || auth.currentUser?.username?.slice(0, 1) || '用' }}
        </span>
        <span class="user-copy">
          <strong>{{ auth.currentUser?.displayName || auth.currentUser?.username }}</strong>
          <small>{{ auth.currentUser?.username }}</small>
        </span>
        <el-button text :loading="loggingOut" aria-label="退出登录" @click="logout">退出</el-button>
      </div>
    </header>

    <nav v-if="projectId" class="project-nav" aria-label="项目导航">
      <router-link :to="`/projects/${projectId}/board`">任务看板</router-link>
      <router-link :to="`/projects/${projectId}/milestones`">里程碑</router-link>
      <router-link :to="`/projects/${projectId}/members`">成员管理</router-link>
      <router-link :to="`/projects/${projectId}/documents`">项目文档</router-link>
      <router-link :to="`/projects/${projectId}/knowledge`">知识问答</router-link>
      <router-link class="project-nav-back" to="/projects">返回项目列表</router-link>
    </nav>

    <div class="app-content">
      <slot />
    </div>
  </div>
</template>
