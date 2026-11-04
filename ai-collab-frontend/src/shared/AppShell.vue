<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell, CaretBottom, Cpu, DataBoard, Document, Expand, Fold, Folder, House, List,
  MagicStick, MoreFilled, Setting, Tickets, User,
} from '@element-plus/icons-vue'
import type { Component } from 'vue'
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
const projectAccessDenied = computed(() =>
  Boolean(projectId.value) && projectCtx.accessDeniedProjectId === projectId.value,
)
const projectAccessReady = computed(() =>
  Boolean(projectId.value) && projectCtx.currentProjectId === projectId.value,
)

const isTaskViewActive = (type: 'board' | 'list' | 'gantt' | 'calendar' | 'dependency-graph' | 'milestones'): boolean => {
  const p = route.path || ''
  const q = route.query?.view as string | undefined
  if (type === 'milestones') {
    return p.endsWith('/milestones')
  }
  if (type === 'board') {
    // /tasks 无查询参数时任务中心实际渲染看板（TaskCenterView 默认），高亮必须与渲染一致。
    return p.endsWith('/board') || (p.endsWith('/tasks') && (q === 'board' || (!q && route.meta?.initialView !== 'list' && route.meta?.initialView !== 'gantt' && route.meta?.initialView !== 'calendar' && route.meta?.initialView !== 'dependency-graph')))
  }
  if (type === 'list') {
    return p.endsWith('/tasks') && q === 'list'
  }
  if (type === 'gantt') {
    return p.endsWith('/gantt') || (p.endsWith('/tasks') && q === 'gantt')
  }
  if (type === 'calendar') {
    return p.endsWith('/calendar') || (p.endsWith('/tasks') && q === 'calendar')
  }
  if (type === 'dependency-graph') {
    return p.endsWith('/dependency-graph') || (p.endsWith('/tasks') && q === 'dependency-graph')
  }
  return false
}

const isAnyTaskActive = computed(() => {
  const p = route.path || ''
  return p.includes('/tasks') || p.endsWith('/board') || p.endsWith('/gantt')
    || p.endsWith('/calendar') || p.endsWith('/dependency-graph') || p.endsWith('/milestones')
})

/* ===== 项目导航分组模型：父级可点击跳默认页，箭头独立负责展开/收起 ===== */
interface SideChild {
  key: string
  label: string
  to: string | { path: string; query?: Record<string, string> }
  active: boolean
  /** 任务中心子项需要覆盖 router-link-active 的路径级匹配 */
  taskScoped?: boolean
}
interface SideNavGroup {
  key: string
  label: string
  icon: Component
  defaultTo: string | { path: string; query?: Record<string, string> }
  children: SideChild[]
}

const endsWithSegment = (segment: string): boolean =>
  (route.path || '').endsWith(`/${segment}`)

const projectNavGroups = computed<SideNavGroup[]>(() => {
  const pid = projectId.value
  if (!pid || projectAccessDenied.value) return []
  const link = (segment: string) => `/projects/${pid}/${segment}`
  const taskLink = (view?: string) =>
    view ? { path: link('tasks'), query: { view } } : link('tasks')
  const groups: SideNavGroup[] = [
    {
      key: 'task',
      label: '任务中心',
      icon: Tickets,
      defaultTo: taskLink('list'),
      children: [
        { key: 'board', label: '任务看板', to: link('board'), active: isTaskViewActive('board'), taskScoped: true },
        { key: 'list', label: '任务列表', to: taskLink('list'), active: isTaskViewActive('list'), taskScoped: true },
        { key: 'gantt', label: '甘特图', to: link('gantt'), active: isTaskViewActive('gantt'), taskScoped: true },
        { key: 'calendar', label: '日历', to: link('calendar'), active: isTaskViewActive('calendar'), taskScoped: true },
        { key: 'dependency', label: '任务依赖', to: link('dependency-graph'), active: isTaskViewActive('dependency-graph'), taskScoped: true },
        { key: 'milestones', label: '里程碑管理', to: link('milestones'), active: isTaskViewActive('milestones'), taskScoped: true },
      ],
    },
    {
      key: 'ai',
      label: 'AI 工作区',
      icon: MagicStick,
      defaultTo: link('ai-planning'),
      children: [
        { key: 'planning', label: 'AI 任务规划', to: link('ai-planning'), active: endsWithSegment('ai-planning') },
        { key: 'agent', label: '协作 Agent', to: link('agent'), active: endsWithSegment('agent') },
      ],
    },
    {
      key: 'knowledge',
      label: '知识中心',
      icon: Document,
      defaultTo: link('documents'),
      children: [
        { key: 'documents', label: '项目文档', to: link('documents'), active: endsWithSegment('documents') },
        { key: 'knowledge', label: '知识问答', to: link('knowledge'), active: endsWithSegment('knowledge') },
        { key: 'knowledge-source', label: '飞书知识源', to: link('knowledge-source'), active: endsWithSegment('knowledge-source') },
      ],
    },
    {
      key: 'insight',
      label: '项目洞察',
      icon: List,
      defaultTo: link('member-load'),
      children: [
        { key: 'member-load', label: '成员负载', to: link('member-load'), active: endsWithSegment('member-load') },
        { key: 'weekly-report', label: '项目周报', to: link('weekly-report'), active: endsWithSegment('weekly-report') },
        { key: 'risk-analysis', label: '风险分析', to: link('risk-analysis'), active: endsWithSegment('risk-analysis') },
        { key: 'plan-comparison', label: '规划对比', to: link('plan-comparison'), active: endsWithSegment('plan-comparison') },
      ],
    },
    {
      key: 'settings',
      label: '项目设置',
      icon: Setting,
      defaultTo: link('members'),
      children: [
        { key: 'members', label: '成员管理', to: link('members'), active: endsWithSegment('members') },
        { key: 'integrations', label: '集成与 MCP', to: link('integrations'), active: endsWithSegment('integrations') },
      ],
    },
  ]
  if (projectCtx.isAdminOrOwner) {
    groups[4]!.children.push({
      key: 'audit-logs', label: '操作日志', to: link('audit-logs'), active: endsWithSegment('audit-logs'),
    })
  }
  return groups
})

const groupHasActiveChild = (group: SideNavGroup): boolean =>
  group.children.some((child) => child.active)

/** 分组展开状态：路由变化时自动展开当前激活分组，用户可手动收起其他分组。 */
const expandedGroups = ref<Record<string, boolean>>({ 'task': true, 'ai': true })

function toggleGroup(key: string): void {
  expandedGroups.value[key] = !expandedGroups.value[key]
}

watch(
  () => [route.fullPath, projectNavGroups.value.map((group) => group.key).join('|')],
  () => {
    const activeGroup = projectNavGroups.value.find((group) => groupHasActiveChild(group))
    if (activeGroup) expandedGroups.value[activeGroup.key] = true
  },
  { immediate: true },
)

watch(
  projectId,
  async (id) => {
    if (id) {
      try {
        await projectCtx.loadProject(id)
      } catch (error) {
        showApiError(error, '加载项目')
      }
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

      <nav v-if="projectId && !projectAccessDenied" class="side-group" aria-label="项目导航">
        <p class="side-title">{{ projectCtx.project?.name ?? '当前项目' }}</p>
        <router-link :to="`/projects/${projectId}/dashboard`" title="项目概览" aria-label="项目概览"><el-icon><DataBoard /></el-icon><span>项目概览</span></router-link>

        <div
          v-for="group in projectNavGroups"
          :key="group.key"
          class="side-details"
          :class="{ 'is-open': expandedGroups[group.key], 'is-parent-active': groupHasActiveChild(group) }"
        >
          <div class="side-parent-row">
            <router-link
              class="side-parent-link"
              :to="group.defaultTo"
              :title="group.label"
              active-class="side-parent-active"
            >
              <el-icon><component :is="group.icon" /></el-icon>
              <span>{{ group.label }}</span>
            </router-link>
            <button
              class="side-parent-arrow"
              type="button"
              :aria-expanded="Boolean(expandedGroups[group.key])"
              :aria-label="`${expandedGroups[group.key] ? '收起' : '展开'}${group.label}子菜单`"
              @click.stop="toggleGroup(group.key)"
            >
              <el-icon class="side-arrow-icon"><CaretBottom /></el-icon>
            </button>
          </div>
          <div class="side-children">
            <router-link
              v-for="child in group.children"
              :key="child.key"
              :to="child.to"
              :title="child.label"
              :class="child.taskScoped
                ? { 'is-nav-active': child.active, 'is-nav-inactive': isAnyTaskActive && !child.active }
                : undefined"
            >{{ child.label }}</router-link>
          </div>
        </div>

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
      <section v-if="projectAccessDenied" class="project-access-denied" role="alert">
        <h1>项目不存在或无权访问</h1>
        <p>该项目不存在，或当前账号没有访问权限。</p>
        <router-link to="/projects">返回我的项目</router-link>
      </section>
      <section
        v-else-if="projectId && !projectAccessReady"
        class="project-access-denied"
        role="status"
        aria-live="polite"
      >
        <h1>{{ projectCtx.loading ? '正在确认项目访问权限' : '无法确认项目访问权限' }}</h1>
        <p>{{ projectCtx.loading ? '请稍候。' : '请刷新页面重试。' }}</p>
      </section>
      <slot v-else />
    </div>
  </div>
</template>
