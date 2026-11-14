<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import EmptyState from '../../shared/EmptyState.vue'
import {
  agentMemoryApi,
  type AgentMemory,
  type AgentMemoryCandidate,
  type AgentMemoryJob,
  type AgentMemoryJobStatus,
} from './agent-memory-api'

const props = defineProps<{
  modelValue: boolean
  projectId: string
  canManage: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})
const activeTab = ref<'memories' | 'candidates' | 'jobs'>('memories')
const memories = ref<AgentMemory[]>([])
const candidates = ref<AgentMemoryCandidate[]>([])
const job = ref<AgentMemoryJob | null>(null)
const memoryLoading = ref(false)
const candidateLoading = ref(false)
const jobLoading = ref(false)
const jobStarting = ref(false)
const busyMemoryId = ref<string | null>(null)
const busyCandidateId = ref<string | null>(null)
const memoryError = ref('')
const candidateError = ref('')
const jobError = ref('')

const activeJobStatuses = new Set<AgentMemoryJobStatus>([
  'QUEUED', 'LEASED', 'RUNNING', 'FAILED_RETRYABLE',
])
const jobActive = computed(() => Boolean(job.value && activeJobStatuses.has(job.value.status)))
const jobStatusLabel = computed(() => job.value ? JOB_STATUS_LABEL[job.value.status] : '')
const jobStatusType = computed(() => {
  if (!job.value) return 'info'
  if (job.value.status === 'SUCCEEDED') return 'success'
  if (job.value.status === 'FAILED' || job.value.status === 'EXPIRED') return 'danger'
  if (job.value.status === 'FAILED_RETRYABLE' || job.value.status === 'CANCELED') return 'warning'
  return 'primary'
})

const JOB_STATUS_LABEL: Record<AgentMemoryJobStatus, string> = {
  QUEUED: '排队中',
  LEASED: '已领取',
  RUNNING: '提取中',
  SUCCEEDED: '已完成',
  FAILED_RETRYABLE: '可重试失败',
  FAILED: '已失败',
  CANCELED: '已取消',
  EXPIRED: '已过期',
}

let pollingTimer: ReturnType<typeof setTimeout> | undefined
let requestEpoch = 0
let mounted = true

function clearPolling() {
  if (pollingTimer) {
    clearTimeout(pollingTimer)
    pollingTimer = undefined
  }
}

function schedulePolling() {
  clearPolling()
  if (!mounted || !props.modelValue || !jobActive.value) return
  pollingTimer = setTimeout(() => { void refreshJob() }, 1500)
}

async function loadMemories() {
  if (!props.projectId) return
  const epoch = requestEpoch
  memoryLoading.value = true
  memoryError.value = ''
  try {
    const result = await agentMemoryApi.listMemories(props.projectId, true)
    if (!mounted || epoch !== requestEpoch) return
    memories.value = result.data
  } catch (error) {
    if (!mounted || epoch !== requestEpoch) return
    memoryError.value = showApiError(error, '项目记忆加载')
  } finally {
    if (mounted && epoch === requestEpoch) memoryLoading.value = false
  }
}

async function loadCandidates() {
  if (!props.projectId || !props.canManage) {
    candidates.value = []
    return
  }
  const epoch = requestEpoch
  candidateLoading.value = true
  candidateError.value = ''
  try {
    const result = await agentMemoryApi.listCandidates(props.projectId)
    if (!mounted || epoch !== requestEpoch) return
    candidates.value = result.data
  } catch (error) {
    if (!mounted || epoch !== requestEpoch) return
    candidateError.value = showApiError(error, '待审记忆加载')
  } finally {
    if (mounted && epoch === requestEpoch) candidateLoading.value = false
  }
}

async function refreshJob() {
  clearPolling()
  if (!job.value || !props.projectId) return
  const epoch = requestEpoch
  const jobId = job.value.id
  jobLoading.value = true
  jobError.value = ''
  try {
    const result = await agentMemoryApi.getJob(props.projectId, jobId)
    if (!mounted || epoch !== requestEpoch) return
    job.value = result.data
    if (result.data.status === 'SUCCEEDED') {
      await Promise.all([loadMemories(), loadCandidates()])
      ElMessage.success('记忆提取完成，候选已进入待审列表')
    } else if (activeJobStatuses.has(result.data.status)) {
      schedulePolling()
    }
  } catch (error) {
    if (!mounted || epoch !== requestEpoch) return
    jobError.value = showApiError(error, '记忆提取任务状态刷新')
  } finally {
    if (mounted && epoch === requestEpoch) jobLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadMemories(), loadCandidates()])
  if (job.value && activeJobStatuses.has(job.value.status)) await refreshJob()
}

async function startJob() {
  if (!props.canManage || !props.projectId) return
  jobStarting.value = true
  jobError.value = ''
  try {
    const result = await agentMemoryApi.createJob(props.projectId)
    job.value = result.data
    ElMessage.success('已发起记忆提取任务')
    if (activeJobStatuses.has(result.data.status)) schedulePolling()
    if (result.data.status === 'SUCCEEDED') await loadCandidates()
  } catch (error) {
    jobError.value = showApiError(error, '发起记忆提取任务')
  } finally {
    jobStarting.value = false
  }
}

async function disableMemory(memory: AgentMemory) {
  if (!props.canManage) return
  try {
    await ElMessageBox.confirm(
      `停用“${memory.title}”后，该记忆不再进入 Agent 上下文。`,
      '停用项目记忆',
      {
        type: 'warning',
        confirmButtonText: '停用',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  busyMemoryId.value = memory.id
  try {
    await agentMemoryApi.disableMemory(props.projectId, memory.id, memory.version)
    ElMessage.success('项目记忆已停用')
    await loadMemories()
  } catch (error) {
    showApiError(error, '停用项目记忆')
  } finally {
    busyMemoryId.value = null
  }
}

async function approveCandidate(candidate: AgentMemoryCandidate) {
  if (!props.canManage) return
  try {
    await ElMessageBox.confirm(
      `批准“${candidate.title}”后，它会成为项目正式记忆并可能进入后续 Agent 上下文。`,
      '批准记忆候选',
      {
        type: 'warning',
        confirmButtonText: '批准',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  busyCandidateId.value = candidate.id
  try {
    await agentMemoryApi.approveCandidate(props.projectId, candidate.id)
    ElMessage.success('记忆候选已批准')
    await Promise.all([loadCandidates(), loadMemories()])
  } catch (error) {
    showApiError(error, '批准记忆候选')
  } finally {
    busyCandidateId.value = null
  }
}

async function rejectCandidate(candidate: AgentMemoryCandidate) {
  if (!props.canManage) return
  let reason = ''
  try {
    const prompt = await ElMessageBox.prompt(
      `请说明拒绝“${candidate.title}”的原因。`,
      '拒绝记忆候选',
      {
        confirmButtonText: '拒绝',
        cancelButtonText: '取消',
        inputPlaceholder: '必填，最多 500 字',
        inputValidator: (value: string) => value.trim() ? true : '请填写拒绝原因',
      },
    )
    reason = prompt.value.trim()
  } catch {
    return
  }
  busyCandidateId.value = candidate.id
  try {
    await agentMemoryApi.rejectCandidate(props.projectId, candidate.id, reason)
    ElMessage.success('记忆候选已拒绝')
    await loadCandidates()
  } catch (error) {
    showApiError(error, '拒绝记忆候选')
  } finally {
    busyCandidateId.value = null
  }
}

function confidenceLabel(value: number | null): string {
  return value == null ? '未提供' : value.toFixed(2)
}

function formatTime(value: string | null): string {
  if (!value) return ''
  return new Date(value).toLocaleString('zh-CN')
}

function resetState() {
  clearPolling()
  requestEpoch += 1
  activeTab.value = 'memories'
  memories.value = []
  candidates.value = []
  job.value = null
  memoryError.value = ''
  candidateError.value = ''
  jobError.value = ''
  memoryLoading.value = false
  candidateLoading.value = false
  jobLoading.value = false
  jobStarting.value = false
  busyMemoryId.value = null
  busyCandidateId.value = null
}

watch(() => props.modelValue, (open) => {
  if (open) {
    activeTab.value = 'memories'
    void loadAll()
  } else {
    clearPolling()
    requestEpoch += 1
  }
}, { immediate: true })

watch(() => props.projectId, () => {
  resetState()
  if (props.modelValue) void loadAll()
})

watch(() => props.canManage, (canManage) => {
  if (canManage && props.modelValue) void loadCandidates()
  if (!canManage) candidates.value = []
})

onBeforeUnmount(() => {
  mounted = false
  requestEpoch += 1
  clearPolling()
})
</script>

<template>
  <el-drawer v-model="visible" title="项目记忆" size="min(720px, 96vw)" data-test="agent-memory-drawer">
    <div class="memory-drawer">
      <p class="memory-intro">查看已发布记忆、审核提取候选，或发起一次新的项目记忆提取。</p>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="已发布记忆" name="memories">
          <div class="section-head">
            <span>ACTIVE 记忆会参与后续 Agent 召回。</span>
            <el-button size="small" plain :loading="memoryLoading" @click="loadMemories">刷新</el-button>
          </div>
          <el-alert v-if="memoryError" :title="memoryError" type="error" :closable="false" show-icon />
          <p v-if="memoryLoading && !memories.length" class="muted">正在加载项目记忆…</p>
          <EmptyState
            v-else-if="!memories.length"
            compact
            title="还没有已发布记忆"
            description="从候选批准，或运行一次记忆提取任务。"
          />
          <ul v-else class="memory-list">
            <li v-for="memory in memories" :key="memory.id" class="memory-card" data-test="memory-row">
              <div class="card-head">
                <div>
                  <strong>{{ memory.title }}</strong>
                  <div class="meta-line">
                    <el-tag size="small" effect="plain">{{ memory.type }}</el-tag>
                    <el-tag size="small" type="success" effect="plain">{{ memory.verificationState || 'VERIFIED' }}</el-tag>
                    <span>v{{ memory.version }}</span>
                  </div>
                </div>
                <el-button
                  v-if="canManage"
                  size="small"
                  type="danger"
                  plain
                  :loading="busyMemoryId === memory.id"
                  data-test="memory-disable"
                  @click="disableMemory(memory)"
                >
                  停用
                </el-button>
              </div>
              <p class="memory-content">{{ memory.content }}</p>
              <p class="muted">来源 {{ memory.sourceType }}<template v-if="memory.sourceId"> · {{ memory.sourceId }}</template> · 更新于 {{ formatTime(memory.updatedAt) }}</p>
            </li>
          </ul>
        </el-tab-pane>

        <el-tab-pane label="待审候选" name="candidates">
          <p v-if="!canManage" class="muted">仅项目管理员可查看和审批候选。</p>
          <template v-else>
            <div class="section-head">
              <span>候选批准前不会写入正式记忆。</span>
              <el-button size="small" plain :loading="candidateLoading" @click="loadCandidates">刷新</el-button>
            </div>
            <el-alert v-if="candidateError" :title="candidateError" type="error" :closable="false" show-icon />
            <p v-if="candidateLoading && !candidates.length" class="muted">正在加载待审候选…</p>
            <EmptyState
              v-else-if="!candidates.length"
              compact
              title="没有待审候选"
              description="提取任务完成后，候选会出现在这里。"
            />
            <ul v-else class="candidate-list">
              <li v-for="candidate in candidates" :key="candidate.id" class="memory-card" data-test="candidate-row">
                <div class="card-head">
                  <div>
                    <strong>{{ candidate.title }}</strong>
                    <div class="meta-line">
                      <el-tag size="small" effect="plain">{{ candidate.kind }}</el-tag>
                      <span>置信度 {{ confidenceLabel(candidate.confidence) }}</span>
                    </div>
                  </div>
                  <div class="candidate-actions">
                    <el-button
                      size="small"
                      type="primary"
                      :loading="busyCandidateId === candidate.id"
                      data-test="candidate-approve"
                      @click="approveCandidate(candidate)"
                    >
                      批准
                    </el-button>
                    <el-button
                      size="small"
                      type="danger"
                      plain
                      :disabled="busyCandidateId === candidate.id"
                      data-test="candidate-reject"
                      @click="rejectCandidate(candidate)"
                    >
                      拒绝
                    </el-button>
                  </div>
                </div>
                <p class="memory-content">{{ candidate.content }}</p>
                <p v-if="candidate.sourceRef" class="source-ref">来源：{{ candidate.sourceRef }}</p>
                <p class="muted">任务 {{ candidate.jobId }} · 第 {{ candidate.attemptNo }} 次尝试 · {{ formatTime(candidate.createdAt) }}</p>
              </li>
            </ul>
          </template>
        </el-tab-pane>

        <el-tab-pane label="提取任务" name="jobs">
          <p v-if="!canManage" class="muted">仅项目管理员可发起记忆提取任务。</p>
          <template v-else>
            <div class="section-head">
              <span>任务由 ai-worker 异步提取，结果先进入待审候选。</span>
              <el-button
                type="primary"
                :loading="jobStarting"
                :disabled="jobActive"
                data-test="memory-job-start"
                @click="startJob"
              >
                {{ jobActive ? '提取进行中' : '提取记忆候选' }}
              </el-button>
            </div>
            <el-alert v-if="jobError" :title="jobError" type="error" :closable="false" show-icon />
            <div v-if="job" class="job-card" data-test="memory-job">
              <div class="card-head">
                <div>
                  <strong>提取任务</strong>
                  <p class="job-id">{{ job.id }}</p>
                </div>
                <el-tag :type="jobStatusType" effect="light" data-test="memory-job-status">{{ jobStatusLabel }}</el-tag>
              </div>
              <div class="job-progress">
                <span :class="{ done: job.status !== 'QUEUED' }">QUEUED</span>
                <span :class="{ done: ['RUNNING', 'SUCCEEDED'].includes(job.status) }">RUNNING</span>
                <span :class="{ done: job.status === 'SUCCEEDED' }">SUCCEEDED</span>
              </div>
              <p class="muted">尝试 {{ job.attemptCount }}/{{ job.maxAttempts }} · 更新于 {{ formatTime(job.updatedAt) }}</p>
              <p v-if="job.lastErrorCode" class="error-line">失败代码：{{ job.lastErrorCode }}</p>
              <el-button
                size="small"
                plain
                :loading="jobLoading"
                data-test="memory-job-refresh"
                @click="refreshJob"
              >
                刷新状态
              </el-button>
            </div>
            <EmptyState
              v-else
              compact
              title="本轮尚未发起提取任务"
              description="点击上方按钮创建 Job；页面会轮询到任务完成。"
            />
          </template>
        </el-tab-pane>
      </el-tabs>
    </div>
  </el-drawer>
</template>

<style scoped>
.memory-drawer{display:grid;gap:12px}
.memory-intro{margin:0;color:var(--color-text-secondary);font-size:13px}
.section-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px;font-size:13px;color:var(--color-text-secondary)}
.muted{margin:4px 0 0;color:var(--color-text-secondary);font-size:12px}
.memory-list,.candidate-list{display:grid;gap:10px;margin:0;padding:0;list-style:none}
.memory-card,.job-card{border:1px solid var(--color-border);border-radius:10px;padding:12px;background:var(--color-surface)}
.card-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}
.meta-line{display:flex;align-items:center;gap:6px;flex-wrap:wrap;margin-top:6px;color:var(--color-text-secondary);font-size:12px}
.memory-content{margin:10px 0;white-space:pre-wrap;line-height:1.65;color:var(--color-text)}
.source-ref{margin:6px 0;padding:8px 10px;border-radius:8px;background:var(--el-fill-color-lighter);font-size:12px;color:var(--color-text-secondary);overflow-wrap:anywhere}
.candidate-actions{display:flex;gap:6px;flex:none}
.job-id{margin:4px 0 0;color:var(--color-text-muted);font-size:12px;overflow-wrap:anywhere}
.job-progress{display:flex;gap:8px;margin:14px 0 8px}
.job-progress span{flex:1;text-align:center;padding:5px 0;border-radius:6px;background:var(--el-fill-color-light);font-size:11px;color:var(--color-text-muted)}
.job-progress span.done{background:var(--el-color-success-light-9);color:var(--el-color-success)}
.error-line{margin:6px 0;color:var(--color-danger);font-size:12px}
</style>
