<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell, ChatDotRound, Cpu, DataBoard, Document, Expand, Fold, Folder, House, List,
  MagicStick, MoreFilled, Setting, Tickets, User,
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

const mobileNavOpen = ref(false)
const userInitial = computed(() => {
  const name = auth.currentUser?.displayName || auth.currentUser?.username || '协'
  return name.trim().charAt(0).toUpperCase() || '协'
})
const userName = computed(() => auth.currentUser?.displayName || auth.currentUser?.username || '协作用户')

const userDetail = computed(() => {
  const u = auth.currentUser
  if (!u) return ''
  return u.username && u.username !== u.displayName ? u.username : ''
})

async function onUserCommand(command: string): Promise<void> {
  if (command === 'account') {
    await gotoAccount()
  } else if (command === 'logout') {
    await logout()
  }
}

function closeMobileNav(): void {
  if (!mobileNavOpen.value) return
  mobileNavOpen.value = false
  document.body.classList.remove('mobile-nav-open')
}
function openMobileNav(): void {
  mobileNavOpen.value = true
  document.body.classList.add('mobile-nav-open')
}
function onEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeMobileNav()
}
async function gotoAccount(): Promise<void> {
  closeMobileNav()
  await router.push('/account')
}

watch(
  () => route.fullPath,
  () => closeMobileNav(),
)
onMounted(() => window.addEventListener('keydown', onEscape))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onEscape)
  document.body.classList.remove('mobile-nav-open')
})

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
  <div class="app-shell" :class="{ collapsed, 'mobile-open': mobileNavOpen }">
    <div class="mobile-topbar">
      <button class="hamburger" type="button" aria-label="打开导航" @click="openMobileNav">
        <el-icon><Expand /></el-icon>
      </button>
      <span class="mobile-brand">AI 项目协作平台</span>
    </div>
    <div v-if="mobileNavOpen" class="mobile-backdrop" @click="closeMobileNav" />
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
        <router-link to="/home" title="工作台" aria-label="工作台"><el-icon><House /></el-icon><span>工作台</span></router-link>
        <router-link to="/projects" title="我的项目" aria-label="我的项目"><el-icon><Folder /></el-icon><span>我的项目</span></router-link>
        <router-link to="/notifications" title="通知中心" aria-label="通知中心"><el-icon><Bell /></el-icon><span>通知中心</span></router-link>
      </nav>

      <nav class="side-group" aria-label="智能与设置">
        <p class="side-title">智能与设置</p>
        <router-link to="/settings/ai" title="AI 设置" aria-label="AI 设置"><el-icon><Cpu /></el-icon><span>AI 设置</span></router-link>
        <router-link to="/account" title="账号设置" aria-label="账号设置"><el-icon><User /></el-icon><span>账号设置</span></router-link>
        <router-link v-if="auth.currentUser?.systemAdmin" to="/admin" title="管理中心" aria-label="管理中心"><el-icon><Setting /></el-icon><span>管理中心</span></router-link>
      </nav>

      <nav v-if="projectId" class="side-group" aria-label="项目导航">
        <p class="side-title">{{ projectCtx.project?.name ?? '当前项目' }}</p>
        <router-link :to="`/projects/${projectId}/dashboard`" title="项目概览" aria-label="项目概览"><el-icon><DataBoard /></el-icon><span>项目概览</span></router-link>
        <router-link :to="`/projects/${projectId}/board`" title="任务看板" aria-label="任务看板"><el-icon><Tickets /></el-icon><span>任务看板</span></router-link>
        <router-link :to="`/projects/${projectId}/documents`" title="项目文档" aria-label="项目文档"><el-icon><Document /></el-icon><span>项目文档</span></router-link>
        <router-link :to="`/projects/${projectId}/knowledge`" title="知识问答" aria-label="知识问答"><el-icon><ChatDotRound /></el-icon><span>知识问答</span></router-link>
        <router-link :to="`/projects/${projectId}/ai-planning`" title="任务规划" aria-label="任务规划"><el-icon><MagicStick /></el-icon><span>任务规划</span></router-link>
        <router-link :to="`/projects/${projectId}/agent`" title="协作 Agent" aria-label="协作 Agent"><el-icon><Cpu /></el-icon><span>协作 Agent</span></router-link>
        <details class="side-details">
          <summary title="洞察分析"><el-icon><List /></el-icon><span>洞察分析</span></summary>
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
          <summary title="项目设置"><el-icon><Setting /></el-icon><span>项目设置</span></summary>
          <router-link :to="`/projects/${projectId}/members`">成员管理</router-link>
          <router-link :to="`/projects/${projectId}/integrations`">集成与 MCP</router-link>
          <router-link v-if="projectCtx.isAdminOrOwner" :to="`/projects/${projectId}/audit-logs`">操作日志</router-link>
        </details>
        <router-link to="/projects" title="切换项目" aria-label="切换项目"><el-icon><Folder /></el-icon><span>切换项目</span></router-link>
      </nav>

      <div class="side-footer">
        <button class="side-collapse" type="button" @click="collapsed = !collapsed" :aria-label="collapsed ? '展开侧栏' : '收起侧栏'" :title="collapsed ? '展开侧栏' : '收起侧栏'">
          <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
        </button>
        <div class="user-row">
          <span class="user-avatar" aria-hidden="true">{{ userInitial }}</span>
          <span class="user-copy">
            <strong>{{ userName }}</strong>
            <small v-if="userDetail">{{ userDetail }}</small>
          </span>
          <el-dropdown trigger="click" placement="top-start" @command="onUserCommand">
            <button class="user-menu-trigger" type="button" aria-label="账号菜单" title="账号菜单">
              <el-icon><MoreFilled /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="account">账号设置</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <el-dropdown trigger="click" placement="right-start" class="collapsed-user" @command="onUserCommand">
          <button class="collapsed-avatar" type="button" aria-label="账号菜单" title="账号菜单">
            <span class="user-avatar" aria-hidden="true">{{ userInitial }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="account">账号设置</el-dropdown-item>
              <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </aside>

    <div class="app-content" @click="closeMobileNav">
      <slot />
    </div>
  </div>
</template>
