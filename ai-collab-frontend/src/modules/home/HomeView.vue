<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import { homeApi, type HomeData } from '../../api/home-api'
import { projectStatusLabel, roleLabel, taskStatusLabel, taskPriorityLabel } from '../../shared/display-labels'

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
        <section v-if="!home.aiConfigSummary.configured" class="ai-onboard-card">
          <div>
            <h2>让 AI 加入你的工作流</h2>
            <p>连接自己的 AI 模型后，即可使用：知识问答 · AI 规划 · 项目 Agent</p>
          </div>
          <router-link to="/settings/ai"><el-button type="primary">配置 AI</el-button></router-link>
        </section>
        <section v-else class="ai-ready-card">
          <div>
            <h2>AI 已就绪</h2>
            <p>默认模型 {{ home.aiConfigSummary.defaultModel ?? '未设置默认' }} · 已配置 {{ home.aiConfigSummary.providerCount }} 个 Provider</p>
          </div>
          <router-link to="/settings/ai"><el-button>管理 AI 设置</el-button></router-link>
        </section>

        <section class="home-columns">
          <div class="home-col">
            <h2 class="section-title">最近项目</h2>
            <EmptyState v-if="!home.recentProjects.length" title="还没有项目" action-label="创建项目" action-to="/projects" />
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
          </div>

          <div class="home-col">
            <h2 class="section-title">我的任务</h2>
            <EmptyState v-if="!home.myTasks.length" title="暂无临期任务" />
            <div v-for="task in home.myTasks" :key="task.id" class="home-row-card">
              <strong>{{ task.title }}</strong>
              <span class="row-meta">
                <span>{{ task.projectName }}</span>
                <StatusBadge :label="taskStatusLabel(task.status)" />
                <span>{{ taskPriorityLabel(task.priority) }}</span>
              </span>
            </div>
          </div>

          <div class="home-col">
            <h2 class="section-title">待处理</h2>
            <div class="home-row-card">
              <strong>未读通知</strong>
              <router-link to="/notifications">{{ home.notificationsSummary.unread }} 条</router-link>
            </div>
            <div class="home-row-card">
              <strong>待审批 Agent</strong>
              <span>{{ home.pendingApprovals }} 个</span>
            </div>
            <h2 class="section-title">最近活动</h2>
            <EmptyState v-if="!home.recentActivity.length" title="暂无动态" />
            <div v-for="activity in home.recentActivity" :key="activity.id" class="home-row-card">
              <span>{{ activity.projectName }} · {{ activity.action }}</span>
            </div>
          </div>
        </section>
      </template>
    </div>
  </main>
</template>

