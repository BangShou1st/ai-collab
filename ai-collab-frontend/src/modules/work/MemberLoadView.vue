<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { visualizationApi, type MemberLoad } from './visualization-api'

const route = useRoute()
const projectId = computed(() => String(route.params.projectId ?? ''))

const members = ref<MemberLoad[]>([])
const loading = ref(false)
const loadError = ref(false)
const project = ref<Project | null>(null)
const unassignedUncompleted = ref(0)

// 请求令牌：快速切换项目时旧请求不得覆盖新项目的数据
let requestSeq = 0

async function load(): Promise<void> {
  const token = ++requestSeq
  const pid = projectId.value
  if (!pid) return
  loading.value = true
  loadError.value = false
  try {
    const [result, projectResult] = await Promise.all([
      visualizationApi.memberLoad(pid),
      projectApi.get(pid),
    ])
    if (token !== requestSeq || pid !== projectId.value) return
    members.value = result.data.members
    unassignedUncompleted.value = result.data.unassignedUncompletedTasks ?? 0
    project.value = projectResult.data
  } catch (error) {
    if (token !== requestSeq) return
    loadError.value = true
    showApiError(error, '成员负载加载')
  } finally {
    if (token === requestSeq) loading.value = false
  }
}

watch(projectId, () => { void load() })
onMounted(() => { void load() })

function formatHours(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${value}h`
}

function getCompletionRate(member: MemberLoad): number {
  if (member.totalTasks === 0) return 0
  return Math.round((member.completedTasks / member.totalTasks) * 100)
}

function getCompletionColor(rate: number): string {
  if (rate >= 80) return 'var(--color-success)'
  if (rate >= 50) return 'var(--color-warning)'
  return 'var(--color-danger)'
}

function taskShare(member: MemberLoad, value: number): string {
  return `${member.totalTasks ? Math.round((value / member.totalTasks) * 100) : 0}%`
}
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目执行"
      title="成员负载"
      description="基于任务分配与估算工时的当前工作量概览，非实际投入工时"
      :context="project?.name"
    >
      <template #actions>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="load-container">
      <el-alert
        v-if="!loading && !loadError && unassignedUncompleted > 0"
        type="info"
        :closable="false"
        class="unassigned-note"
        :title="`另有 ${unassignedUncompleted} 个未完成任务尚未分配负责人，不计入下列任何成员`"
      />
      <el-result
        v-if="!loading && loadError && members.length === 0"
        icon="error"
        title="成员负载加载失败"
        sub-title="网络或服务异常，可能是临时问题"
      >
        <template #extra>
          <el-button type="primary" @click="load">重试</el-button>
        </template>
      </el-result>
      <el-empty
        v-else-if="!loading && members.length === 0"
        description="项目还没有成员，或成员数据暂时不可见"
        :image-size="64"
      />
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
              <span class="stat-label">未完成任务</span>
              <span class="stat-value">{{ member.uncompletedTasks ?? member.totalTasks - member.completedTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">进行中</span>
              <span class="stat-value">{{ member.inProgressTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">未完成估算工时</span>
              <span class="stat-value">{{ formatHours(member.uncompletedEstimateHours) }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">估算缺失</span>
              <span class="stat-value">{{ member.missingEstimateTasks ?? 0 }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">已完成</span>
              <span class="stat-value">{{ member.completedTasks }}</span>
            </div>
            <div class="stat-item">
              <span class="stat-label">累计估算（含历史）</span>
              <span class="stat-value">{{ formatHours(member.totalEstimateHours) }}</span>
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
  color: var(--color-text-muted);
}

.stat-value {
  font-size: 18px;
  font-weight: 500;
}

.completion-bar {
  margin-top: 8px;
}
.workload-visual { margin: 6px 0 18px; }
.workload-track { display: flex; width: 100%; height: 12px; overflow: hidden; border-radius: 6px; background: var(--color-border); }
.workload-track span { display: block; height: 100%; }
.workload-track .done { background: var(--color-success); }
.workload-track .progress { background: var(--color-primary); }
.workload-legend { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 7px; color: var(--color-text-secondary); font-size: 11px; }
.workload-legend span { display: flex; align-items: center; gap: 4px; }
.workload-legend i { width: 8px; height: 8px; border-radius: 50%; background: var(--color-border); }
.workload-legend i.done { background: var(--color-success); }
.workload-legend i.progress { background: var(--color-primary); }

.completion-label {
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}
</style>
