<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import { homeApi, type HomeData } from '../../api/home-api'
import { activityDisplayLabel, formatDateTime, projectStatusLabel, roleLabel, taskStatusLabel, taskPriorityLabel } from '../../shared/display-labels'

const auth = useAuthStore()
const home = ref<HomeData | null>(null)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    home.value = (await homeApi.get()).data
  } catch (error) {
    showApiError(error, '工作台加载')
  } finally {
    loading.value = false
  }
}

function greet(): string {
  const hour = new Date().getHours()
  const part = hour < 6 ? '夜深了' : hour < 12 ? '上午好' : hour < 18 ? '下午好' : '晚上好'
  const name = auth.currentUser?.displayName || auth.currentUser?.username || ''
  return `${part}${name ? `，${name}` : ''}`
}

onMounted(load)
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
      <template v-if="home">
        <section v-if="!home.aiConfigSummary.configured" class="ai-status-strip ai-onboard-card">
          <div>
            <h2>让 AI 加入你的工作流</h2>
            <p>连接自己的 AI 模型后，即可使用：知识问答 · AI 规划 · 项目 Agent</p>
          </div>
          <router-link to="/settings/ai"><el-button type="primary">配置 AI</el-button></router-link>
        </section>
        <section v-else class="ai-status-strip ai-ready-card">
          <div>
            <h2>AI 已就绪</h2>
            <p>默认模型 {{ home.aiConfigSummary.defaultModel ?? '未设置默认' }} · 已配置 {{ home.aiConfigSummary.providerCount }} 个 Provider</p>
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
                <strong>临期任务</strong>
                <span class="row-meta">{{ home.myTasks.length }} 个</span>
              </div>
              <div v-if="!home.myTasks.length" class="compact-empty">暂无临期任务，享受清晰的工作台</div>
              <div v-for="task in home.myTasks.slice(0, 5)" :key="task.id" class="compact-row">
                <span class="next-task-title">{{ task.title }}</span>
                <StatusBadge :label="taskStatusLabel(task.status)" />
              </div>
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
