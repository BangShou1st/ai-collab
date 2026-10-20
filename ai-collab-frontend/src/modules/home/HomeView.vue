<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import { Cpu } from '@element-plus/icons-vue'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import { homeApi, type HomeData } from '../../api/home-api'
import { activityDisplayLabel, formatDate, formatDateTime, projectStatusLabel, roleLabel, taskStatusLabel, taskPriorityLabel } from '../../shared/display-labels'

const auth = useAuthStore()
const home = ref<HomeData | null>(null)
const loading = ref(false)
const failed = ref(false)
/** 递增请求令牌：晚到的响应不得覆盖更新的状态，切换用户后也不得回填旧数据。 */
let requestToken = 0
let ownerUserId: string | null = null

/** 工作台 AI 状态：只区分“未保存配置 / 已保存但不可解析 / 已保存且至少一项用途可解析”，绝不宣称“已就绪”。 */
type AiState = 'none' | 'unusable' | 'saved'
const aiState = computed<AiState | null>(() => {
  const summary = home.value?.aiConfigSummary
  if (!summary) return null
  if (!summary.configured) return 'none'
  return summary.anyPurposeUsable ? 'saved' : 'unusable'
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
  void load()
})

// 账号切换（登录/退出/换人）时丢弃旧数据并重新拉取，避免显示上一个用户的状态。
auth.$subscribe(() => {
  const currentId = auth.currentUser?.id ?? null
  if (currentId === ownerUserId) return
  ownerUserId = currentId
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
        <el-button @click="load" :loading="loading">刷新</el-button>
        <router-link to="/projects"><el-button type="primary">新建项目</el-button></router-link>
      </template>
    </PageHeader>

    <div v-loading="loading" class="home-grid">
      <section v-if="!home && failed" class="ai-status-strip ai-status-error" role="alert">
        <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
        <div>
          <h2>工作台暂时无法加载</h2>
          <p>AI 状态当前未知，请重试后再判断。</p>
        </div>
        <el-button @click="load">重试</el-button>
      </section>
      <template v-if="home">
        <section v-if="home && failed" class="ai-status-strip ai-status-error" role="alert">
          <span class="ai-strip-icon" aria-hidden="true"><el-icon><Cpu /></el-icon></span>
          <div>
            <h2>本次刷新失败</h2>
            <p>下面是上次成功加载的数据，AI 状态可能已过时。</p>
          </div>
          <el-button @click="load">重试</el-button>
        </section>
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

        <div class="home-main">
          <section class="home-recent">
            <h2 class="section-title">最近项目</h2>
            <EmptyState v-if="!home.recentProjects.length" compact title="还没有项目" description="创建第一个项目，开始协作" action-label="创建项目" action-to="/projects" />
            <router-link
              v-for="project in home.recentProjects"
              :key="project.id"
              class="home-row-card"
              :to="`/projects/${project.id}/dashboard`"
            >
              <strong>{{ project.name }}</strong>
              <span class="row-meta">
                <StatusBadge :label="projectStatusLabel(project.status)" />
                <span>{{ roleLabel(project.role) }}</span>
              </span>
            </router-link>
          </section>

          <section class="home-side">
            <h2 class="section-title">行动中心</h2>
            <div class="home-activity">
              <div class="compact-row">
                <strong>我的待办</strong>
                <span class="row-meta">最多展示 5 项</span>
              </div>
              <div v-if="!home.myTasks.length" class="compact-empty">暂无待办任务，享受清晰的工作台</div>
              <router-link
                v-for="task in home.myTasks.slice(0, 5)" :key="task.id"
                class="compact-row home-task-link"
                :to="{ path: `/projects/${task.projectId}/board`, query: { taskId: task.id } }"
              >
                <span class="home-task-copy">
                  <strong>{{ task.title }}</strong>
                  <span>{{ task.projectName }}</span>
                  <span>{{ task.dueDate ? `截止日期：${formatDate(task.dueDate)}` : '未设置截止日期' }}</span>
                </span>
                <StatusBadge :label="taskStatusLabel(task.status)" />
              </router-link>
              <div class="compact-row">
                <strong>待审批 Agent</strong>
                <span>{{ home.pendingApprovals }} 个</span>
                <router-link v-if="home.pendingApprovals > 0" to="/notifications">去处理</router-link>
              </div>
              <div class="compact-row">
                <strong>未读通知</strong>
                <router-link to="/notifications">{{ home.notificationsSummary.unread }} 条</router-link>
              </div>
            </div>
          </section>
        </div>

        <section class="home-activity-wrap">
          <h2 class="section-title">最近活动</h2>
          <div class="home-activity">
            <div v-if="!home.recentActivity.length" class="compact-empty">暂无动态，项目更新会出现在这里</div>
            <div v-for="activity in home.recentActivity.slice(0, 8)" :key="activity.id" class="compact-row">
              <span class="next-task-title">{{ activity.projectName }} · {{ activityDisplayLabel(activity.action) }}</span>
              <span class="next-task-due">{{ formatDateTime(activity.createdAt) }}</span>
            </div>
          </div>
        </section>
      </template>
    </div>
  </main>
</template>

<style scoped>
.home-grid { grid-template-columns: minmax(0, 1fr); }
.home-main > * { min-width: 0; }
.home-row-card { overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .home-main { grid-template-columns: minmax(0, 1fr); }
  .ai-status-strip { flex-wrap: wrap; }
  .ai-status-strip > div { flex-basis: calc(100% - 64px); }
  .compact-row { flex-wrap: wrap; }
}

.home-task-link { color: inherit; text-decoration: none; gap: 12px; }
.home-task-link:hover { background: var(--color-primary-soft); }
.home-task-link:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.home-task-copy { display: grid; gap: 4px; min-width: 0; overflow-wrap: anywhere; }
.home-task-copy > span { font-size: 12px; color: var(--color-text-secondary); }
</style>
