<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import {
  Cpu,
  FolderAdd,
  CircleCheck,
  Bell,
  Stamp,
  Guide,
  Calendar,
  Warning,
} from '@element-plus/icons-vue'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import OnboardingGuideCard from './components/OnboardingGuideCard.vue'
import { homeApi, type HomeData } from '../../api/home-api'
import {
  activityDisplayLabel,
  formatDate,
  formatDateTime,
  projectStatusLabel,
  roleLabel,
  taskStatusLabel,
  taskPriorityLabel,
} from '../../shared/display-labels'

const auth = useAuthStore()
const home = ref<HomeData | null>(null)
const loading = ref(false)
const failed = ref(false)
/** 递增请求令牌：晚到的响应不得覆盖更新的状态，切换用户后也不得回填旧数据。 */
let requestToken = 0
let ownerUserId: string | null = null

const ONBOARDING_KEY_PREFIX = 'aicollab_onboarding_dismissed_'
const userGuideExpandedPreference = ref<boolean | null>(null)

function checkGuideDismissedState(userId: string | null) {
  if (!userId) {
    userGuideExpandedPreference.value = null
    return
  }
  try {
    const saved = localStorage.getItem(`${ONBOARDING_KEY_PREFIX}${userId}`)
    if (saved === 'true') {
      userGuideExpandedPreference.value = false
    } else if (saved === 'false') {
      userGuideExpandedPreference.value = true
    } else {
      userGuideExpandedPreference.value = null
    }
  } catch {
    userGuideExpandedPreference.value = null
  }
}

function handleDismissGuide() {
  userGuideExpandedPreference.value = false
  const uid = auth.currentUser?.id
  if (uid) {
    try {
      localStorage.setItem(`${ONBOARDING_KEY_PREFIX}${uid}`, 'true')
    } catch {
      // 容错处理
    }
  }
}

function handleRestoreGuide() {
  userGuideExpandedPreference.value = true
  const uid = auth.currentUser?.id
  if (uid) {
    try {
      localStorage.setItem(`${ONBOARDING_KEY_PREFIX}${uid}`, 'false')
    } catch {
      // 容错处理
    }
  }
}

/**
 * 判断引导卡片是否应该展开：
 * 1. 若用户在当前设备上有显式的手动偏好设置（展开/收起），优先尊重用户偏好；
 * 2. 否则按真实业务数据判断：
 *    - 纯新用户（未创建任何项目）：默认展开新手引导，帮助快速建立首个协作空间；
 *    - 已有项目的用户（无论任务是否分配给他人或已完成）：默认收起引导不打扰，可随时通过顶部「新手引导」按钮恢复查看。
 */
const showOnboardingGuide = computed(() => {
  if (!home.value) return false
  if (userGuideExpandedPreference.value !== null) {
    return userGuideExpandedPreference.value
  }
  const hasProjects = home.value.recentProjects.length > 0
  return !hasProjects
})

/** 工作台 AI 状态：只区分“未保存配置 / 已保存但不可解析 / 已保存且至少一项用途可解析”，绝不宣称“已就绪”。 */
type AiState = 'none' | 'unusable' | 'saved'
const aiState = computed<AiState | null>(() => {
  const summary = home.value?.aiConfigSummary
  if (!summary) return null
  if (!summary.configured) return 'none'
  return summary.anyPurposeUsable ? 'saved' : 'unusable'
})

/** 检查任务是否已经逾期（真实时间对比） */
function isTaskOverdue(dueDate: string | null): boolean {
  if (!dueDate) return false
  // 简易日期比较 YYYY-MM-DD
  const todayStr = new Date().toISOString().slice(0, 10)
  return dueDate < todayStr
}

/** 逾期待办任务计数 */
const overdueTaskCount = computed(() => {
  if (!home.value?.myTasks) return 0
  return home.value.myTasks.filter((t) => isTaskOverdue(t.dueDate)).length
})

async function load(): Promise<void> {
  const token = ++requestToken
  loading.value = true
  failed.value = false
  try {
    const data = (await homeApi.get()).data
    if (token !== requestToken) return
    home.value = data
    ownerUserId = auth.currentUser?.id ?? null
  } catch (error) {
    if (token !== requestToken) return
    // 加载失败时保持"未知"：既有数据保留、绝不改写为"未配置"。
    failed.value = true
    showApiError(error, '工作台加载')
  } finally {
    if (token === requestToken) loading.value = false
  }
}

function greet(): string {
  const hour = new Date().getHours()
  const part = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'
  const name = auth.currentUser?.displayName || auth.currentUser?.username || ''
  return `${part}${name ? `，${name}` : ''}`
}

onMounted(() => {
  ownerUserId = auth.currentUser?.id ?? null
  checkGuideDismissedState(ownerUserId)
  void load()
})

// 账号切换（登录/退出/换人）时丢弃旧数据并重新拉取，避免显示上一个用户的状态。
auth.$subscribe(() => {
  const currentId = auth.currentUser?.id ?? null
  if (currentId === ownerUserId) return
  ownerUserId = currentId
  checkGuideDismissedState(currentId)
  requestToken += 1
  home.value = null
  failed.value = false
  if (currentId) void load()
})
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="个人工作台" :title="greet()" description="今天的项目、任务与 AI 状态都在这里">
      <template #actions>
        <el-button
          v-if="home && !showOnboardingGuide"
          size="default"
          plain
          @click="handleRestoreGuide"
        >
          <el-icon class="btn-icon"><Guide /></el-icon>
          <span>新手引导</span>
        </el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <router-link to="/projects"><el-button type="primary">新建项目</el-button></router-link>
      </template>
    </PageHeader>

    <div v-loading="loading" class="home-grid">
      <!-- 加载失败且无旧数据时的警示提示 -->
      <section v-if="!home && failed" class="ai-status-strip ai-status-error" role="alert">
        <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
        <div>
          <h2>工作台暂时无法加载</h2>
          <p>AI 状态当前未知，请重试后再判断。</p>
        </div>
        <el-button @click="load">重试</el-button>
      </section>

      <template v-if="home">
        <!-- 刷新失败但保留旧数据时的提示 -->
        <section v-if="home && failed" class="ai-status-strip ai-status-error" role="alert">
          <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
          <div>
            <h2>本次刷新失败</h2>
            <p>下面是上次成功加载的数据，AI 状态可能已过时。</p>
          </div>
          <el-button @click="load">重试</el-button>
        </section>

        <!-- 顶部行动概览指标卡片栏（让用户优先把握待办与审批事项） -->
        <div class="metrics-strip">
          <!-- 1. 待办任务指标 -->
          <div class="metric-pill" :class="{ 'has-urgent': overdueTaskCount > 0 }">
            <div class="metric-icon-wrap task-icon">
              <el-icon><CircleCheck /></el-icon>
            </div>
            <div class="metric-info">
              <span class="metric-label">我的待办</span>
              <div class="metric-value-row">
                <span class="metric-number">{{ home.myTasks.length }}</span>
                <span v-if="overdueTaskCount > 0" class="metric-badge overdue">
                  {{ overdueTaskCount }} 项逾期
                </span>
                <span v-else-if="home.myTasks.length > 0" class="metric-badge active">进行中</span>
                <span v-else class="metric-badge quiet">全部清空</span>
              </div>
            </div>
          </div>

          <!-- 2. 待审批 Agent 指标 -->
          <router-link
            to="/notifications"
            class="metric-pill metric-link"
            :class="{ 'has-action': home.pendingApprovals > 0 }"
          >
            <div class="metric-icon-wrap approval-icon">
              <el-icon><Stamp /></el-icon>
            </div>
            <div class="metric-info">
              <span class="metric-label">待审批 Agent</span>
              <div class="metric-value-row">
                <span class="metric-number">{{ home.pendingApprovals }}</span>
                <span v-if="home.pendingApprovals > 0" class="metric-badge warning">需确认</span>
                <span v-else class="metric-badge quiet">无待批</span>
              </div>
            </div>
          </router-link>

          <!-- 3. 未读通知指标 -->
          <router-link to="/notifications" class="metric-pill metric-link">
            <div class="metric-icon-wrap notice-icon">
              <el-icon><Bell /></el-icon>
            </div>
            <div class="metric-info">
              <span class="metric-label">未读通知</span>
              <div class="metric-value-row">
                <span class="metric-number">{{ home.notificationsSummary.unread }}</span>
                <span v-if="home.notificationsSummary.unread > 0" class="metric-badge info">新消息</span>
                <span v-else class="metric-badge quiet">已读完</span>
              </div>
            </div>
          </router-link>

          <!-- 4. 最近项目数（接口固定上限，不宣称全部项目总数） -->
          <router-link to="/projects" class="metric-pill metric-link" title="查看全部项目">
            <div class="metric-icon-wrap project-icon">
              <el-icon><FolderAdd /></el-icon>
            </div>
            <div class="metric-info">
              <span class="metric-label">最近项目</span>
              <div class="metric-value-row">
                <span class="metric-number">{{ home.recentProjects.length }}</span>
                <span class="metric-badge quiet">最多展示 5 项</span>
              </div>
            </div>
          </router-link>
        </div>

        <!-- 新用户协作引导卡片（按规则条件展示，支持收起/恢复） -->
        <OnboardingGuideCard
          v-if="showOnboardingGuide"
          :projects="home.recentProjects"
          :tasks="home.myTasks"
          :ai-config="home.aiConfigSummary"
          @dismiss="handleDismissGuide"
        />

        <!-- AI 状态横幅（完全保留既有测试约定的结构与文案） -->
        <section v-if="aiState === 'none'" class="ai-status-strip ai-onboard-card">
          <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
          <div>
            <h2>让 AI 加入你的工作流</h2>
            <p>还没有保存任何 AI 模型配置。连接自己的模型后，即可使用：知识问答 · AI 规划 · 项目 Agent</p>
          </div>
          <router-link to="/settings/ai"><el-button type="primary">配置 AI</el-button></router-link>
        </section>
        <section v-else-if="aiState === 'unusable'" class="ai-status-strip ai-warning-card">
          <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
          <div>
            <h2>AI 配置已保存，但暂不可用</h2>
            <p>已保存 {{ home.aiConfigSummary.providerCount }} 个模型配置，当前没有可用于知识问答、AI 规划或 Agent 的可解析模型（缺少已启用的默认模型或有效的用途覆盖）。</p>
          </div>
          <router-link to="/settings/ai"><el-button type="primary">管理 AI 设置</el-button></router-link>
        </section>
        <section v-else class="ai-status-strip ai-ready-card">
          <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
          <div>
            <h2>AI 配置已保存</h2>
            <p>
              已保存 {{ home.aiConfigSummary.providerCount }} 个模型配置<span v-if="home.aiConfigSummary.defaultModel"> · 默认模型 {{ home.aiConfigSummary.defaultModel }}</span>
              · 可用性需在 AI 设置中测试确认，此处不表示已就绪
            </p>
          </div>
          <router-link to="/settings/ai"><el-button>管理 AI 设置</el-button></router-link>
        </section>

        <!-- 主内容区域（双栏栅格响应式） -->
        <div class="home-main">
          <!-- 左侧：行动中心与待办（优先把第一行动呈现给用户） -->
          <section class="home-side-tasks">
            <div class="section-header-row">
              <h2 class="section-title">我的待办</h2>
              <span class="row-meta">最多展示 5 项</span>
            </div>

            <div class="home-activity tasks-card-wrap">
              <!-- 空状态提示：当用户没有待办时，友好说明 -->
              <div v-if="!home.myTasks.length" class="task-empty-box">
                <div class="compact-empty">暂无待办任务，享受清晰的工作台</div>
                <div v-if="home.recentProjects.length" class="empty-quick-action">
                  <router-link :to="`/projects/${home.recentProjects[0]!.id}/board`" class="quick-link">
                    前往「{{ home.recentProjects[0]!.name }}」看板建任务 →
                  </router-link>
                </div>
              </div>

              <!-- 待办任务链接列表（保留原有 a.home-task-link 结构，增强逾期与优先级展现） -->
              <router-link
                v-for="task in home.myTasks.slice(0, 5)"
                :key="task.id"
                class="compact-row home-task-link"
                :to="{ path: `/projects/${task.projectId}/board`, query: { taskId: task.id } }"
              >
                <span class="home-task-copy">
                  <span class="task-title-line">
                    <strong>{{ task.title }}</strong>
                    <span v-if="isTaskOverdue(task.dueDate)" class="tag-overdue-chip">
                      <el-icon><Warning /></el-icon> 已逾期
                    </span>
                    <span v-if="task.priority" class="tag-priority-chip">
                      {{ taskPriorityLabel(task.priority) }}
                    </span>
                  </span>
                  <span class="task-project-name">{{ task.projectName }}</span>
                  <span class="task-due-line">
                    <el-icon :size="12" class="due-icon"><Calendar /></el-icon>
                    <span>{{ task.dueDate ? `截止日期：${formatDate(task.dueDate)}` : '未设置截止日期' }}</span>
                  </span>
                </span>
                <StatusBadge :label="taskStatusLabel(task.status)" />
              </router-link>

              <!-- 待审批与通知快捷入口栏 -->
              <div class="compact-row summary-action-row">
                <div class="summary-item">
                  <strong>待审批 Agent</strong>
                  <span class="count-text">{{ home.pendingApprovals }} 个</span>
                </div>
                <router-link v-if="home.pendingApprovals > 0" to="/notifications" class="action-highlight-link">
                  去处理审批 →
                </router-link>
                <span v-else class="text-muted-quiet">全部已审</span>
              </div>
              <div class="compact-row summary-action-row">
                <div class="summary-item">
                  <strong>未读通知</strong>
                  <span class="count-text">{{ home.notificationsSummary.unread }} 条</span>
                </div>
                <router-link v-if="home.notificationsSummary.unread > 0" to="/notifications" class="action-highlight-link">
                  查看通知 →
                </router-link>
                <span v-else class="text-muted-quiet">暂无未读</span>
              </div>
            </div>
          </section>

          <!-- 右侧：最近项目列表 -->
          <section class="home-recent-col">
            <div class="section-header-row">
              <h2 class="section-title">最近项目</h2>
              <router-link to="/projects" class="all-link">全部项目 →</router-link>
            </div>

            <!-- 无项目时的友好空状态 -->
            <EmptyState
              v-if="!home.recentProjects.length"
              compact
              title="还没有项目"
              description="创建第一个项目，开始协作"
              action-label="创建项目"
              action-to="/projects"
            />

            <!-- 项目卡片列表 -->
            <div v-else class="projects-stack">
              <router-link
                v-for="project in home.recentProjects"
                :key="project.id"
                class="home-row-card enhanced-project-card"
                :to="`/projects/${project.id}/dashboard`"
              >
                <div class="project-card-top">
                  <strong class="project-title">{{ project.name }}</strong>
                  <StatusBadge :label="projectStatusLabel(project.status)" />
                </div>
                <div class="row-meta project-card-meta">
                  <span class="role-badge">{{ roleLabel(project.role) }}</span>
                  <div class="project-quick-shortcuts" @click.stop>
                    <router-link :to="`/projects/${project.id}/board`" class="shortcut-tag">看板</router-link>
                    <router-link :to="`/projects/${project.id}/documents`" class="shortcut-tag">知识库</router-link>
                  </div>
                </div>
              </router-link>
            </div>
          </section>
        </div>

        <!-- 底部：最近活动动态 -->
        <section class="home-activity-wrap">
          <div class="section-header-row">
            <h2 class="section-title">最近活动</h2>
          </div>
          <div class="home-activity">
            <div v-if="!home.recentActivity.length" class="compact-empty">暂无动态，项目更新会出现在这里</div>
            <div
              v-for="activity in home.recentActivity.slice(0, 8)"
              :key="activity.id"
              class="compact-row activity-row"
            >
              <div class="activity-main">
                <span class="activity-dot"></span>
                <span class="next-task-title">{{ activity.projectName }} · {{ activityDisplayLabel(activity.action) }}</span>
              </div>
              <span class="next-task-due">{{ formatDateTime(activity.createdAt) }}</span>
            </div>
          </div>
        </section>
      </template>
    </div>
  </main>
</template>

<style scoped>
.workspace-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.btn-icon {
  margin-right: 4px;
}

.home-grid {
  display: grid;
  gap: 16px;
  grid-template-columns: minmax(0, 1fr);
}

/* 顶部行动概览指标卡片栏 */
.metrics-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.metric-pill {
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--color-surface, #ffffff);
  border: 1px solid var(--color-border, #e5e8ef);
  border-radius: var(--radius-card, 10px);
  padding: 12px 14px;
  transition: all 0.2s ease;
  text-decoration: none;
  color: inherit;
}

.metric-link:hover {
  border-color: rgba(88, 101, 216, 0.4);
  box-shadow: 0 4px 12px rgba(88, 101, 216, 0.06);
  transform: translateY(-1px);
}

.metric-icon-wrap {
  width: 36px;
  height: 36px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}

.task-icon {
  background: #eef2ff;
  color: #5865d8;
}

.approval-icon {
  background: #fef3c7;
  color: #d97706;
}

.notice-icon {
  background: #e0f2fe;
  color: #0284c7;
}

.project-icon {
  background: #ecfdf5;
  color: #059669;
}

.metric-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.metric-label {
  font-size: 12px;
  color: var(--color-text-secondary, #596279);
  font-weight: 500;
}

.metric-value-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.metric-number {
  font-size: 18px;
  font-weight: 800;
  color: var(--color-text-primary, #182033);
  line-height: 1;
}

.metric-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 4px;
  line-height: 1.3;
}

.metric-badge.overdue {
  background: #fee2e2;
  color: #dc2626;
}

.metric-badge.warning {
  background: #fef3c7;
  color: #b45309;
}

.metric-badge.active {
  background: #eef2ff;
  color: #4f46e5;
}

.metric-badge.info {
  background: #e0f2fe;
  color: #0369a1;
}

.metric-badge.quiet {
  background: #f3f4f6;
  color: #6b7280;
}

/* 主内容双栏 */
.home-main {
  display: grid;
  grid-template-columns: repeat(12, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}

.home-side-tasks {
  grid-column: span 7;
  display: grid;
  gap: 10px;
  min-width: 0;
}

.home-recent-col {
  grid-column: span 5;
  display: grid;
  gap: 10px;
  min-width: 0;
}

.section-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 4px;
}

.section-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text-primary, #182033);
  margin: 0;
}

.all-link {
  font-size: 12px;
  color: #5865d8;
  text-decoration: none;
}

.all-link:hover {
  text-decoration: underline;
}

.tasks-card-wrap {
  border-radius: var(--radius-card, 10px);
}

.task-empty-box {
  padding: 20px 16px;
  text-align: center;
}

.empty-quick-action {
  margin-top: 8px;
}

.quick-link {
  font-size: 12px;
  font-weight: 600;
  color: #5865d8;
  text-decoration: none;
}

.quick-link:hover {
  text-decoration: underline;
}

/* 待办任务链接 */
.home-task-link {
  color: inherit;
  text-decoration: none;
  gap: 12px;
  transition: background 0.15s ease;
}

.home-task-link:hover {
  background: var(--color-primary-soft, #f0f3ff);
}

.home-task-link:focus-visible {
  outline: 2px solid var(--color-primary, #5865d8);
  outline-offset: 2px;
}

.home-task-copy {
  display: grid;
  gap: 4px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.task-title-line {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.task-title-line strong {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--color-text-primary, #182033);
}

.tag-overdue-chip {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 10px;
  font-weight: 700;
  color: #dc2626;
  background: #fee2e2;
  border-radius: 4px;
  padding: 1px 4px;
}

.tag-priority-chip {
  font-size: 10px;
  font-weight: 600;
  color: #4b5563;
  background: #f3f4f6;
  border-radius: 4px;
  padding: 1px 5px;
}

.task-project-name {
  font-size: 12px;
  color: var(--color-text-secondary, #596279);
}

.task-due-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text-secondary, #596279);
}

.due-icon {
  color: var(--color-text-muted, #7c8ba1);
}

.summary-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.summary-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.count-text {
  font-weight: 600;
  color: var(--color-text-primary, #182033);
}

.action-highlight-link {
  font-size: 12px;
  font-weight: 600;
  color: #5865d8;
  text-decoration: none;
}

.action-highlight-link:hover {
  text-decoration: underline;
}

.text-muted-quiet {
  font-size: 12px;
  color: var(--color-text-muted, #7c8ba1);
}

/* 项目卡片堆栈 */
.projects-stack {
  display: grid;
  gap: 8px;
}

.enhanced-project-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  background: var(--color-surface, #ffffff);
  border: 1px solid var(--color-border, #e5e8ef);
  border-radius: var(--radius-surface, 8px);
  padding: 12px 14px;
  color: inherit;
  text-decoration: none;
  transition: all 0.2s ease;
}

.enhanced-project-card:hover {
  border-color: rgba(88, 101, 216, 0.4);
  box-shadow: 0 3px 10px rgba(88, 101, 216, 0.05);
}

.project-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.project-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--color-text-primary, #182033);
}

.project-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.role-badge {
  font-size: 11px;
  color: var(--color-text-secondary, #596279);
  background: #f1f3f9;
  border-radius: 4px;
  padding: 1px 6px;
}

.project-quick-shortcuts {
  display: flex;
  align-items: center;
  gap: 6px;
}

.shortcut-tag {
  font-size: 11px;
  color: #5865d8;
  background: #f5f6ff;
  border: 1px solid rgba(88, 101, 216, 0.15);
  border-radius: 4px;
  padding: 1px 6px;
  text-decoration: none;
  transition: all 0.15s ease;
}

.shortcut-tag:hover {
  background: #eef0ff;
  color: #3b82f6;
}

/* 最近活动列表 */
.activity-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.activity-main {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.activity-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #5865d8;
  flex-shrink: 0;
}

.next-task-title {
  font-size: 13px;
  color: var(--color-text-primary, #182033);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.next-task-due {
  font-size: 12px;
  color: var(--color-text-muted, #7c8ba1);
  white-space: nowrap;
  flex-shrink: 0;
}

/* 响应式适配 */
@media (max-width: 1100px) {
  .home-main {
    grid-template-columns: 1fr;
  }
  .home-side-tasks,
  .home-recent-col {
    grid-column: span 12;
  }
  .metrics-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .home-main {
    grid-template-columns: minmax(0, 1fr);
  }
  .home-side-tasks,
  .home-recent-col {
    grid-column: span 1;
  }
  .ai-status-strip {
    flex-wrap: wrap;
  }
  .ai-status-strip > div {
    flex-basis: calc(100% - 64px);
  }
  .compact-row {
    flex-wrap: wrap;
  }
  .metrics-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
  .metric-pill {
    padding: 8px 10px;
    gap: 8px;
  }
  .metric-icon-wrap {
    width: 30px;
    height: 30px;
    font-size: 15px;
  }
  .metric-number {
    font-size: 16px;
  }
}
</style>
