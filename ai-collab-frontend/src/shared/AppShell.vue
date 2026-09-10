<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell, ChatDotRound, Cpu, DataBoard, Document, Folder, House, List,
  MagicStick, Setting, Tickets, User,
} from '@element-plus/icons-vue'
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
        <p class="side-title">工作区</p>
        <router-link to="/home"><el-icon><House /></el-icon><span>工作台</span></router-link>
        <router-link to="/projects"><el-icon><Folder /></el-icon><span>我的项目</span></router-link>
        <router-link to="/notifications"><el-icon><Bell /></el-icon><span>通知中心</span></router-link>
      </nav>

      <nav class="side-group" aria-label="智能与设置">
        <p class="side-title">智能与设置</p>
        <router-link to="/settings/ai"><el-icon><Cpu /></el-icon><span>AI 设置</span></router-link>
        <router-link to="/account"><el-icon><User /></el-icon><span>账号设置</span></router-link>
        <router-link v-if="auth.currentUser?.systemAdmin" to="/admin"><el-icon><Setting /></el-icon><span>管理中心</span></router-link>
      </nav>

      <nav v-if="projectId" class="side-group" aria-label="项目导航">
        <p class="side-title">{{ projectCtx.project?.name ?? '当前项目' }}</p>
        <router-link :to="`/projects/${projectId}/dashboard`"><el-icon><DataBoard /></el-icon><span>项目概览</span></router-link>
        <router-link :to="`/projects/${projectId}/board`"><el-icon><Tickets /></el-icon><span>任务看板</span></router-link>
        <router-link :to="`/projects/${projectId}/documents`"><el-icon><Document /></el-icon><span>项目文档</span></router-link>
        <router-link :to="`/projects/${projectId}/knowledge`"><el-icon><ChatDotRound /></el-icon><span>知识问答</span></router-link>
        <router-link :to="`/projects/${projectId}/ai-planning`"><el-icon><MagicStick /></el-icon><span>任务规划</span></router-link>
        <router-link :to="`/projects/${projectId}/agent`"><el-icon><Cpu /></el-icon><span>协作 Agent</span></router-link>
        <details class="side-details">
          <summary><el-icon><List /></el-icon><span>洞察分析</span></summary>
          <router-link :to="`/projects/${projectId}/gantt`">甘特图</router-link>
          <router-link :to="`/projects/${projectId}/calendar`">日历</router-link>
          <router-link :to="`/projects/${projectId}/dependency-graph`">任务依赖</router-link>
          <router-link :to="`/projects/${projectId}/member-load`">成员负载</router-link>
          <router-link :to="`/projects/${projectId}/milestones`">里程碑</router-link>
          <router-link :to="`/projects/${projectId}/weekly-report`">项目周报</router-link>
          <router-link :to="`/projects/${projectId}/risk-analysis`">风险分析</router-link>
          <router-link :to="`/projects/${projectId}/plan-comparison`">规划对比</router-link>
        </details>
        <details class="side-details">
          <summary><el-icon><Setting /></el-icon><span>项目设置</span></summary>
          <router-link :to="`/projects/${projectId}/members`">成员管理</router-link>
          <router-link :to="`/projects/${projectId}/integrations`">集成与 MCP</router-link>
          <router-link v-if="projectCtx.isAdminOrOwner" :to="`/projects/${projectId}/audit-logs`">操作日志</router-link>
        </details>
        <router-link to="/projects"><el-icon><Folder /></el-icon><span>切换项目</span></router-link>
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
