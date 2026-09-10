<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { showApiError } from '../api/api-result'
import { useAuthStore } from '../stores/auth-store'
import { useProjectContextStore } from '../stores/project-context-store'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const projectCtx = useProjectContextStore()
const loggingOut = ref(false)
const collapsed = ref(false)

const projectId = computed(() =>
  typeof route.params.projectId === 'string' ? route.params.projectId : '',
)

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
    showApiError(error, '退出登录')
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="app-shell" :class="{ collapsed }">
    <aside class="app-sidebar" aria-label="工作区导航">
      <router-link class="brand" to="/home" aria-label="工作台">
        <span class="brand-mark">AI</span>
        <span v-if="!collapsed" class="brand-copy">
          <strong>AI 项目协作平台</strong>
          <small>项目协作与知识工作台</small>
        </span>
      </router-link>

      <nav class="side-group" aria-label="全局导航">
        <router-link to="/home">工作台</router-link>
        <router-link to="/projects">我的项目</router-link>
        <router-link to="/notifications">通知中心</router-link>
        <router-link to="/settings/ai">AI 设置</router-link>
        <router-link to="/account">账号设置</router-link>
        <router-link v-if="auth.currentUser?.systemAdmin" to="/admin">管理中心</router-link>
      </nav>

      <nav v-if="projectId" class="side-group" aria-label="项目导航">
        <p class="side-title">{{ projectCtx.project?.name ?? '当前项目' }}</p>
        <router-link :to="`/projects/${projectId}/dashboard`">项目概览</router-link>
        <router-link :to="`/projects/${projectId}/board`">任务看板</router-link>
        <router-link :to="`/projects/${projectId}/documents`">项目文档</router-link>
        <router-link :to="`/projects/${projectId}/knowledge`">知识问答</router-link>
        <router-link :to="`/projects/${projectId}/ai-planning`">任务规划</router-link>
        <router-link :to="`/projects/${projectId}/agent`">协作 Agent</router-link>
        <el-dropdown trigger="click" @command="router.push(String($event))">
          <button class="side-menu" type="button">更多视图⌄</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :command="`/projects/${projectId}/gantt`">甘特图</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/calendar`">日历</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/dependency-graph`">任务依赖</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/member-load`">成员负载</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/milestones`">里程碑</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/weekly-report`">项目周报</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/risk-analysis`">风险分析</el-dropdown-item>
              <el-dropdown-item :command="`/projects/${projectId}/plan-comparison`">规划对比</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-dropdown trigger="click" @command="router.push(String($event))">
          <button class="side-menu" type="button">项目设置⌄</button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item :command="`/projects/${projectId}/members`">成员管理</el-dropdown-item>
              <el-dropdown-item v-if="projectCtx.isAdminOrOwner" :command="`/projects/${projectId}/audit-logs`">操作日志</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <router-link to="/projects">切换项目</router-link>
      </nav>

      <div class="side-footer">
        <button class="side-collapse" type="button" @click="collapsed = !collapsed" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'">
          {{ collapsed ? '»' : '«' }}
        </button>
        <span v-if="!collapsed" class="user-copy">
          <strong>{{ auth.currentUser?.displayName || auth.currentUser?.username }}</strong>
        </span>
        <el-button v-if="!collapsed" text :loading="loggingOut" aria-label="退出登录" @click="logout">退出</el-button>
      </div>
    </aside>

    <div class="app-content">
      <slot />
    </div>
  </div>
</template>

