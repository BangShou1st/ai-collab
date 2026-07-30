<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { visualizationApi, type MemberLoad } from './visualization-api'

const route = useRoute()
const projectId = route.params.projectId as string

const members = ref<MemberLoad[]>([])
const loading = ref(false)
const errorMessage = ref('')

const project = ref<Project | null>(null)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [result, projectResult] = await Promise.all([
      visualizationApi.memberLoad(projectId),
      projectApi.get(projectId),
    ])
    members.value = result.data.members
    project.value = projectResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

function getCompletionRate(member: MemberLoad): number {
  if (member.totalTasks === 0) return 0
  return Math.round((member.completedTasks / member.totalTasks) * 100)
}

function getCompletionColor(rate: number): string {
  if (rate >= 80) return '#67c23a'
  if (rate >= 50) return '#e6a23c'
  return '#f56c6c'
}

function taskShare(member: MemberLoad, value: number): string {
  return `${member.totalTasks ? Math.round((value / member.totalTasks) * 100) : 0}%`
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目执行"
      title="成员负载"
      :context="project?.name"
    />
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="load-container">
      <el-empty v-if="!loading && members.length === 0" description="暂无成员数据" :image-size="64" />
      <div v-else class="load-grid">
        <el-card v-for="member in members" :key="member.userId" class="member-card">
          <template #header>
            <div class="member-header">
              <span class="member-name">{{ member.displayName }}</span>
              <el-tag :type="member.overdueTasks > 0 ? 'danger' : 'success'" size="small">
                {{ member.overdueTasks > 0 ? `${member.overdueTasks} 逾期` : '无逾期' }}
              </el-tag>
            </div>
          </template>
          <div class="member-stats">
            <div class="stat-item">
              <span class="stat-label">总任务</span>
              <span class="stat-value">{{ member.totalTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">已完成</span>
              <span class="stat-value">{{ member.completedTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">进行中</span>
              <span class="stat-value">{{ member.inProgressTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">预计工时</span>
              <span class="stat-value">{{ member.totalEstimateHours }}h</span>
            </div>
          </div>
          <div class="workload-visual" :aria-label="`${member.displayName} 的任务分布`">
            <div class="workload-track">
              <span
                class="done"
                :style="{ width: taskShare(member, member.completedTasks) }"
                :title="`已完成 ${member.completedTasks}`"
              />
              <span
                class="progress"
                :style="{ width: taskShare(member, member.inProgressTasks) }"
                :title="`进行中 ${member.inProgressTasks}`"
              />
            </div>
            <div class="workload-legend">
              <span><i class="done" />完成 {{ member.completedTasks }}</span>
              <span><i class="progress" />进行中 {{ member.inProgressTasks }}</span>
              <span><i class="rest" />其他 {{ Math.max(0, member.totalTasks - member.completedTasks - member.inProgressTasks) }}</span>
            </div>
          </div>
          <div class="completion-bar">
            <div class="completion-label">完成率</div>
            <el-progress
              :percentage="getCompletionRate(member)"
              :color="getCompletionColor(getCompletionRate(member))"
            />
          </div>
        </el-card>
      </div>
    </section>
  </main>
</template>

<style scoped>
.load-container {
  padding: 0 24px;
}

.load-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 16px;
}

.member-card {
  height: 100%;
}

.member-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.member-name {
  font-weight: 500;
  font-size: 16px;
}

.member-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.stat-item {
  display: flex;
  flex-direction: column;
}

.stat-label {
  font-size: 12px;
  color: #909399;
}

.stat-value {
  font-size: 18px;
  font-weight: 500;
}

.completion-bar {
  margin-top: 8px;
}
.workload-visual { margin: 6px 0 18px; }
.workload-track { display: flex; width: 100%; height: 12px; overflow: hidden; border-radius: 6px; background: #e7eaf1; }
.workload-track span { display: block; height: 100%; }
.workload-track .done { background: var(--color-success); }
.workload-track .progress { background: var(--color-primary); }
.workload-legend { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 7px; color: var(--color-text-secondary); font-size: 11px; }
.workload-legend span { display: flex; align-items: center; gap: 4px; }
.workload-legend i { width: 8px; height: 8px; border-radius: 50%; background: #e7eaf1; }
.workload-legend i.done { background: var(--color-success); }
.workload-legend i.progress { background: var(--color-primary); }

.completion-label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
</style>
