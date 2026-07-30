<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../api/api-result'
import { useAuthStore } from '../stores/auth-store'
import { useProjectContextStore } from '../stores/project-context-store'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectCtx = useProjectContextStore()
const loggingOut = ref(false)

const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

// 当进入项目路由时加载项目上下文
watch(
  projectId,
  async (id) => {
    if (id) {
      await projectCtx.loadProject(id)
    } else {
      projectCtx.clear()
    }
  },
  { immediate: true },
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
    projectCtx.clear()
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
      <router-link class="brand" to="/projects" aria-label="高校竞赛 AI 项目协作平台项目列表">
        <span class="brand-mark">AI</span>
        <span>
          <strong>高校竞赛 AI 项目协作平台</strong>
          <small>项目协作与知识工作台</small>
        </span>
      </router-link>
      <nav class="global-nav" aria-label="全局导航">
        <router-link to="/projects">我的项目</router-link>
        <router-link to="/notifications">通知中心</router-link>
        <router-link v-if="auth.currentUser?.systemAdmin" to="/admin">管理中心</router-link>
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
      <router-link :to="`/projects/${projectId}/dashboard`">项目概览</router-link>
      <router-link :to="`/projects/${projectId}/board`">任务看板</router-link>
      <router-link :to="`/projects/${projectId}/documents`">项目文档</router-link>
      <router-link :to="`/projects/${projectId}/knowledge`">知识问答</router-link>
      <router-link :to="`/projects/${projectId}/ai-planning`">任务规划</router-link>
      <router-link :to="`/projects/${projectId}/agent`">协作 Agent</router-link>
      <el-dropdown trigger="click" @command="router.push(String($event))">
        <button class="project-nav-menu" type="button">计划视图⌄</button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item :command="`/projects/${projectId}/gantt`">甘特图</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/calendar`">日历</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/dependency-graph`">任务依赖</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/member-load`">成员负载</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/milestones`">里程碑</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dropdown trigger="click" @command="router.push(String($event))">
        <button class="project-nav-menu" type="button">智能工具⌄</button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item :command="`/projects/${projectId}/weekly-report`">项目周报</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/risk-analysis`">风险分析</el-dropdown-item>
            <el-dropdown-item :command="`/projects/${projectId}/plan-comparison`">规划对比</el-dropdown-item>
            <el-dropdown-item v-if="projectCtx.isAdminOrOwner" :command="`/projects/${projectId}/knowledge/eval`">问答评测</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <el-dropdown trigger="click" @command="router.push(String($event))">
        <button class="project-nav-menu" type="button">项目设置⌄</button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item :command="`/projects/${projectId}/members`">成员管理</el-dropdown-item>
            <el-dropdown-item v-if="projectCtx.isAdminOrOwner" :command="`/projects/${projectId}/audit-logs`">操作日志</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <router-link class="project-nav-back" to="/projects">返回项目列表</router-link>
    </nav>

    <div class="app-content">
      <slot />
    </div>
  </div>
</template>
