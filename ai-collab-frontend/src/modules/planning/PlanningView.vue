<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import EmptyState from '../../shared/EmptyState.vue'
import { userAiApi } from '../ai/user-ai-api'
import {
  isEndDateDisabled,
  isProjectDateDisabled,
  isStartDateDisabled,
  validateDateRange,
} from '../../shared/date-constraints'
import { clearConfirmationKey, confirmationKey, planningApi } from './planning-api'
import { PlanningPoller } from './planning-poller'
import type {
  PartialRepairMode,
  PlanPermissions,
  TaskPlan,
  TaskPlanDraft,
  StructuredValidationIssue,
  TaskPlanEvent,
} from './types'
import PlanningIssuePanel from './components/PlanningIssuePanel.vue'
import PlanningEventTimeline from './components/PlanningEventTimeline.vue'
import { projectApi } from '../project/project-api'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import type { Project, ProjectMember } from '../project/types'
import { dependencyWouldCycle, removeTaskAndDependencies } from './planning-draft'
import { planStatusLabel, priorityLabel, versionSourceLabel } from './planning-labels'
import { planningFailureLabel } from './planning-failure'

const route = useRoute(), router = useRouter()
const projectId = computed(() => String(route.params.projectId))
const plans = ref<TaskPlan[]>([]), selected = ref<TaskPlan | null>(null)
const permissions = ref<PlanPermissions | null>(null), draft = ref<TaskPlanDraft | null>(null)
const versions = ref<Array<{ id: string; versionNo: number; sourceType: string }>>([])
const selectedVersionId = ref(''), snapshot = ref('')
const expandedMilestones = ref<string[]>([])

// 检查里程碑是否有需要确认的内容（未分配负责人等）
function hasPendingItems(milestoneTempKey: string): boolean {
  return getUnassignedCount(milestoneTempKey) > 0
}

// 自动展开有需要确认内容的里程碑
function autoExpandPendingMilestones(): void {
  if (!draft.value) return
  const pendingMilestones = draft.value.milestones
    .filter(m => hasPendingItems(m.tempKey))
    .map(m => m.tempKey)
  expandedMilestones.value = [...new Set([...expandedMilestones.value, ...pendingMilestones])]
}
const createVisible = ref(false), checked = ref(false), saveDialogVisible = ref(false), saveComment = ref('')
const aiConfigured = ref(true)
const canCreate = ref(false), pendingConfirmation = ref<{ versionId: string; key: string } | null>(null)
const documents = ref<ProjectDocument[]>([]), members = ref<ProjectMember[]>([])
const project = ref<Project | null>(null)
const structuredIssues = ref<StructuredValidationIssue[]>([]), events = ref<TaskPlanEvent[]>([])
const form = reactive({ title: '', goal: '', constraints: '', planStartDate: '', planDueDate: '', maxTaskCount: 20, documentIds: [] as string[] })
const poller = new PlanningPoller()
const dirty = computed(() => draft.value !== null && JSON.stringify(draft.value) !== snapshot.value)
const editingLatest = computed(() => selectedVersionId.value === selected.value?.latestVersionId)
const canEditCurrent = computed(() => permissions.value?.canEdit === true && editingLatest.value)
const confirmationSummary = computed(() => ({
  milestones: draft.value?.milestones.length ?? 0,
  tasks: draft.value?.tasks.length ?? 0,
  dependencies: draft.value?.tasks.reduce((sum, task) => sum + task.dependencyTempKeys.length, 0) ?? 0,
  unassigned: draft.value?.tasks.filter(task => !task.assigneeId).length ?? 0,
}))
const targetNames = computed<Record<string, string>>(() => Object.fromEntries([
  ...(draft.value?.milestones.map(item => [item.tempKey, item.title] as const) ?? []),
  ...(draft.value?.tasks.map(item => [item.tempKey, item.title] as const) ?? []),
]))

const getTaskCount = (milestoneTempKey: string) => {
  return draft.value?.tasks.filter(t => t.milestoneTempKey === milestoneTempKey).length ?? 0
}

const getUnassignedCount = (milestoneTempKey: string) => {
  return draft.value?.tasks.filter(t => t.milestoneTempKey === milestoneTempKey && !t.assigneeId).length ?? 0
}
const failureMessage = computed(() => planningFailureLabel(selected.value?.lastErrorSummary))
const historyReadOnlyMessage = '历史版本仅供查看，请先恢复为新版本'
let lastPollError = ''

function showValidationError(message: string): void {
  ElMessage.error({ message, grouping: true })
}

function formStartDisabled(date: Date): boolean {
  return isStartDateDisabled(date, project.value?.startDate ?? null, project.value?.dueDate ?? null, form.planDueDate || null)
}

function formDueDisabled(date: Date): boolean {
  return isEndDateDisabled(date, project.value?.startDate ?? null, project.value?.dueDate ?? null, form.planStartDate || null)
}

function milestoneDateDisabled(date: Date): boolean {
  return isProjectDateDisabled(
    date,
    selected.value?.planStartDate ?? project.value?.startDate ?? null,
    selected.value?.planDueDate ?? project.value?.dueDate ?? null,
  )
}

function taskStartDateDisabled(task: TaskPlanDraft['tasks'][number]): (date: Date) => boolean {
  return date => isStartDateDisabled(
      date,
      selected.value?.planStartDate ?? project.value?.startDate ?? null,
      selected.value?.planDueDate ?? project.value?.dueDate ?? null,
      task.dueDate,
    )
}

function taskDueDateDisabled(task: TaskPlanDraft['tasks'][number]): (date: Date) => boolean {
  return date => isEndDateDisabled(
      date,
      selected.value?.planStartDate ?? project.value?.startDate ?? null,
      selected.value?.planDueDate ?? project.value?.dueDate ?? null,
      task.startDate,
    )
}

function requireLatestVersion(): boolean {
  if (editingLatest.value) return true
  showValidationError(historyReadOnlyMessage)
  return false
}

async function list(): Promise<TaskPlan['status'][]> {
  plans.value = (await planningApi.list(projectId.value)).data.data
  return plans.value.map(plan => plan.status)
}
async function ensureDefaultSelection(): Promise<void> {
  if (!selected.value && plans.value.length > 0) {
    try {
      await open(plans.value[0])
    } catch (error) {
      showApiError(error, '任务规划详情加载')
    }
  }
}
async function open(plan: TaskPlan): Promise<void> {
  if (dirty.value && !await discard()) return
  const [detail, history] = await Promise.all([planningApi.detail(projectId.value, plan.id), planningApi.versions(projectId.value, plan.id)])
  selected.value = detail.data.data.plan; permissions.value = detail.data.data.permissions; versions.value = history.data.data
  structuredIssues.value = detail.data.data.structuredIssues || []
  if (selected.value.latestVersionId) {
    await openVersion(selected.value.latestVersionId)
  } else {
    selectedVersionId.value = ''
    draft.value = null
    snapshot.value = ''
  }
  // Load events if available
  try {
    const eventsResult = await planningApi.events(projectId.value, plan.id)
    events.value = eventsResult.data.data || []
  } catch (error) {
    events.value = []
    showApiError(error, '规划事件加载')
  }
}
async function openVersion(id: string): Promise<void> {
  if (!selected.value) return
  const result = await planningApi.version(projectId.value, selected.value.id, id)
  selectedVersionId.value = id; draft.value = structuredClone(result.data.data.draft); snapshot.value = JSON.stringify(draft.value)
}
async function requestVersion(id: string): Promise<void> {
  if (dirty.value && !await discard()) return
  await openVersion(id)
}
async function create(): Promise<void> {
  if (!form.title.trim()) return showValidationError('请填写规划标题')
  if (!form.goal.trim()) return showValidationError('请填写规划目标')
  if (!form.planStartDate || !form.planDueDate) return showValidationError('请选择规划开始日期和截止日期')
  if (!Number.isInteger(form.maxTaskCount) || form.maxTaskCount < 1 || form.maxTaskCount > 40) {
    return showValidationError('最大任务数必须是 1 到 40 之间的整数')
  }
  const dateError = validateDateRange(
    form.planStartDate,
    form.planDueDate,
    project.value?.startDate ?? null,
    project.value?.dueDate ?? null,
  )
  if (dateError) return showValidationError(dateError)
  try { const plan = (await planningApi.create(projectId.value, form)).data.data; createVisible.value = false; await list(); await open(plan); startPolling() }
  catch (error) { showApiError(error, '任务规划创建') }
}
async function action(name: 'cancel' | 'retry-detail' | 'regenerate'): Promise<void> {
  if (!selected.value || !requireLatestVersion() || name === 'regenerate' && dirty.value && !await discard()) return
  try {
    await planningApi.action(projectId.value, selected.value.id, name)
    await refresh()
    startPolling()
  } catch (error) {
    const actionLabel = name === 'cancel' ? '规划生成取消'
      : name === 'retry-detail' ? '规划细节重试' : '规划重新生成'
    showApiError(error, actionLabel)
  }
}
async function restore(): Promise<void> {
  if (!selected.value || !selectedVersionId.value) return
  try {
    await planningApi.restore(projectId.value, selected.value.id, selectedVersionId.value)
    await refresh()
  } catch (error) {
    showApiError(error, '规划版本恢复')
  }
}
async function save(): Promise<void> {
  if (!selected.value?.latestVersionId || !draft.value || !editingLatest.value) return
  saveComment.value = ''
  saveDialogVisible.value = true
}

async function confirmSave(): Promise<void> {
  if (!selected.value?.latestVersionId || !draft.value || !editingLatest.value) return
  try {
    await planningApi.save(projectId.value, selected.value.id, selected.value.latestVersionId,
      selected.value.latestVersionNo, draft.value, saveComment.value || undefined)
    snapshot.value = JSON.stringify(draft.value)
    saveDialogVisible.value = false
    await refresh()
  }
  catch (error) { showApiError(error, '规划草案保存') }
}
async function manualEdit(issue: StructuredValidationIssue): Promise<void> {
  if (!requireLatestVersion()) return
  await locate(issue.targetTempKey || '', issue.field)
}
async function locate(target: string, field: string | null): Promise<void> {
  const milestoneTempKey = draft.value?.tasks.find(task => task.tempKey === target)?.milestoneTempKey
    ?? draft.value?.milestones.find(milestone => milestone.tempKey === target)?.tempKey
  if (milestoneTempKey && !expandedMilestones.value.includes(milestoneTempKey)) {
    expandedMilestones.value = [...expandedMilestones.value, milestoneTempKey]
  }
  await nextTick()
  const exact = document.getElementById(`planning-${target}-${field ?? 'entity'}`)
  const fallback = document.getElementById(`planning-${target}-entity`)
  const element = exact ?? fallback
  element?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  element?.querySelector<HTMLElement>('input,textarea,button')?.focus()
}
async function removePlan(): Promise<void> {
  if (!selected.value || !await window.confirm('删除后无法恢复，确定删除该规划吗？')) return
  try {
    await planningApi.remove(projectId.value, selected.value.id)
    selected.value = null; draft.value = null; structuredIssues.value = []; events.value = []
    await list()
    await ensureDefaultSelection()
  } catch (error) { showApiError(error, '任务规划删除') }
}
async function confirm(): Promise<void> {
  if (!selected.value || !selectedVersionId.value || !checked.value || !requireLatestVersion()) return
  const versionId = selectedVersionId.value, key = confirmationKey(projectId.value, selected.value.id, versionId)
  try {
    const result = await planningApi.confirm(projectId.value, selected.value.id, versionId, key)
    if (result.data.data.status !== 'SUCCESS') {
      pendingConfirmation.value = { versionId, key }; startPolling(); return
    }
    pendingConfirmation.value = null
    clearConfirmationKey(projectId.value, selected.value.id, versionId)
    await router.push({ path: `/projects/${projectId.value}/board`, query: { sourcePlanId: selected.value.id } })
  } catch (error) { showApiError(error, '任务规划确认') }
}
async function refresh(): Promise<void> {
  const id = selected.value?.id; await list(); const current = plans.value.find(plan => plan.id === id)
  if (current && !dirty.value) await open(current)
}
function addMilestone(): void {
  if (!draft.value || !canEditCurrent.value || draft.value.milestones.length >= 8) return
  const tempKey = `m-${crypto.randomUUID()}`
  draft.value.milestones.push({ tempKey, title: '新里程碑', objective: '填写目标', description: '填写描述', targetDate: null, sortOrder: draft.value.milestones.length, sourceRefs: [] })
}
function addTask(milestoneTempKey: string): void {
  if (!draft.value || !canEditCurrent.value
      || draft.value.tasks.length >= (selected.value?.maxTaskCount ?? 0)) return
  draft.value.tasks.push({ tempKey: `t-${crypto.randomUUID()}`, milestoneTempKey, title: '新任务', objective: '填写目标', description: '填写描述',
    priority: 'MEDIUM', estimatedHours: null, startDate: null, dueDate: null, suggestedAssigneeId: null,
    assigneeId: null, dependencyTempKeys: [], sourceRefs: [], sortOrder: draft.value.tasks.length })
}
async function removeTask(tempKey: string): Promise<void> {
  if (!draft.value || !canEditCurrent.value) return
  const affected = draft.value.tasks.filter(task => task.dependencyTempKeys.includes(tempKey)).length
  if (affected && !window.confirm(`删除将同步清除 ${affected} 条依赖，是否继续？`)) return
  draft.value = removeTaskAndDependencies(draft.value, tempKey)
}
function removeMilestone(tempKey: string): void {
  if (!draft.value || !canEditCurrent.value || draft.value.tasks.some(task => task.milestoneTempKey === tempKey)) return
  draft.value.milestones = draft.value.milestones.filter(item => item.tempKey !== tempKey)
}
function addAssumption(): void { if (draft.value && canEditCurrent.value) draft.value.assumptions.push('') }
function addRisk(): void { if (draft.value && canEditCurrent.value) draft.value.risks.push('') }
const memberName = (id: string | null) => members.value.find(member => member.userId === id)?.displayName ?? '无'

function toggleMilestone(tempKey: string): void {
  const index = expandedMilestones.value.indexOf(tempKey)
  if (index === -1) {
    expandedMilestones.value = [...expandedMilestones.value, tempKey]
  } else {
    expandedMilestones.value = expandedMilestones.value.filter(key => key !== tempKey)
  }
}

function startPolling(): void {
  poller.start({
    load: async () => {
      const statuses = await list()
      if (pendingConfirmation.value && selected.value) {
        const pending = pendingConfirmation.value
        const result = await planningApi.confirm(projectId.value, selected.value.id, pending.versionId, pending.key)
        if (result.data.data.status === 'SUCCESS') {
          clearConfirmationKey(projectId.value, selected.value.id, pending.versionId)
          pendingConfirmation.value = null
          await router.push({ path: `/projects/${projectId.value}/board`, query: { sourcePlanId: selected.value.id } })
          return statuses
        }
      }
      if (selected.value && !dirty.value) await refresh()
      return statuses
    },
    onError: (error) => {
      const message = normalizeApiError(error).message
      if (message !== lastPollError) {
        lastPollError = message
        showApiError(error, '规划状态刷新')
      }
    }
  })
}
async function loadWorkspace(): Promise<void> {
  const [projectResult, planResult, documentResult, memberResult, aiResult] = await Promise.allSettled([
    projectApi.get(projectId.value),
    list(),
    documentApi.list(projectId.value),
    projectApi.listMembers(projectId.value),
    userAiApi.list(),
  ])

  if (projectResult.status === 'fulfilled') {
    project.value = projectResult.value.data
    canCreate.value = ['OWNER', 'ADMIN'].includes(projectResult.value.data.role)
  } else {
    project.value = null
    canCreate.value = false
    showApiError(projectResult.reason, '项目信息加载')
  }
  if (planResult.status === 'rejected') {
    plans.value = []
    showApiError(planResult.reason, '任务规划列表加载')
  }
  if (planResult.status === 'fulfilled') {
    await ensureDefaultSelection()
  }
  if (documentResult.status === 'fulfilled') {
    documents.value = documentResult.value.data.filter(document => document.status === 'READY')
  } else {
    documents.value = []
    showApiError(documentResult.reason, '参考文档加载')
  }
  if (memberResult.status === 'fulfilled') {
    members.value = memberResult.value.data
  } else {
    members.value = []
    showApiError(memberResult.reason, '项目成员加载')
  }
  aiConfigured.value = aiResult.status === 'fulfilled' && aiResult.value.data.length > 0
  startPolling()
}
async function discard(): Promise<boolean> { try { await ElMessageBox.confirm('未保存修改将被丢弃，是否继续？', '未保存保护'); return true } catch { return false } }
function beforeUnload(event: BeforeUnloadEvent): void { if (dirty.value) event.preventDefault() }
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  await loadWorkspace()
})
onBeforeUnmount(() => { poller.stop(); window.removeEventListener('beforeunload', beforeUnload) })
onBeforeRouteLeave(() => !dirty.value || window.confirm('当前规划有未保存修改，确定离开吗？'))
onBeforeRouteUpdate(() => !dirty.value || window.confirm('当前规划有未保存修改，确定切换项目吗？'))
watch(projectId, async () => {
  poller.stop(); selected.value = null; draft.value = null
  await loadWorkspace()
})
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="AI 辅助" title="AI 任务规划">
      <template #actions><router-link v-if="selected" :to="`/projects/${projectId}/agent?plan=${selected.id}`"><el-button text type="primary">交给 Agent</el-button></router-link><el-button v-if="canCreate" type="primary" @click="createVisible = true">创建规划</el-button></template>
    </PageHeader>
    <section class="planning-layout">
      <div class="planning-list-panel">
        <button v-for="plan in plans" :key="plan.id" class="planning-list-item" :class="{ 'is-active': selected?.id === plan.id }" @click="open(plan)">
          <span class="planning-item-title">{{ plan.title }}</span>
          <span class="planning-item-meta"><span class="status-dot" :class="plan.status === 'CONFIRMED' ? 'done' : plan.status === 'FAILED' ? 'fail' : 'run'" /><span>{{ planStatusLabel(plan.status) }}</span><span>·</span><span>v{{ plan.latestVersionNo }}</span></span>
          <small class="planning-item-desc">{{ plan.goal }}</small>
        </button>
        <EmptyState
          v-if="!plans.length"
          compact
          title="当前还没有规划"
          description="描述你的目标，AI 会生成一份草案"
        />
      </div>
      <el-card v-if="!selected" class="planning-detail-empty">
        <EmptyState
          compact
          title="选择一份规划查看详情"
          description="将项目目标转化为可审核的任务方案。草案 → 审核 → 确认，只有确认后才会写入真实项目任务。"
        />
        <div class="planning-empty-actions">
          <el-button v-if="canCreate" type="primary" @click="createVisible = true">创建规划</el-button>
        </div>
        <el-alert
          v-if="!aiConfigured"
          title="尚未配置 AI"
          description="配置个人 AI 后即可生成规划"
          type="info"
          show-icon
          :closable="false"
        >
          <template #default>
            <router-link to="/settings/ai"><el-button text type="primary">前往 AI 设置</el-button></router-link>
          </template>
        </el-alert>
      </el-card>
      <el-card v-if="selected">
        <template #header><div class="actions"><strong>{{ selected.title }}</strong>
          <el-button v-if="permissions?.canCancel && editingLatest" @click="action('cancel')">取消</el-button>
          <el-button v-if="permissions?.canRetryDetail && editingLatest" @click="action('retry-detail')">重试细节</el-button>
          <el-button v-if="permissions?.canRegenerate && editingLatest" @click="action('regenerate')">重新生成</el-button>
          <el-button v-if="permissions?.canDelete" type="danger" @click="removePlan">删除规划</el-button>
        </div></template>
        <el-alert v-if="failureMessage" :title="failureMessage" type="warning" />
        <PlanningIssuePanel
          v-if="structuredIssues.length > 0"
          :issues="structuredIssues"
          :target-names="targetNames"
          :actions-enabled="editingLatest"
          @locate="locate"
          @edit="manualEdit"
        />
        <PlanningEventTimeline v-if="events.length > 0" :events="events" />
        <el-select data-testid="planning-version-select" :model-value="selectedVersionId" @change="requestVersion"><el-option v-for="v in versions" :key="v.id" :label="`v${v.versionNo} ${versionSourceLabel(v.sourceType)}`" :value="v.id" /></el-select>
        <template v-if="draft">
          <el-alert v-if="!editingLatest" :title="historyReadOnlyMessage" type="info" />
          <label>规划摘要</label><el-input v-model="draft.summary" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" placeholder="规划摘要" />
          <h3>假设</h3><div v-for="(_, index) in draft.assumptions" :key="`a-${index}`"><label>假设 {{ index + 1 }}</label><el-input v-model="draft.assumptions[index]" :readonly="!canEditCurrent" /><el-button v-if="canEditCurrent" @click="draft.assumptions.splice(index, 1)">删除</el-button></div><el-button v-if="canEditCurrent" @click="addAssumption">添加假设</el-button>
          <h3>风险</h3><div v-for="(_, index) in draft.risks" :key="`r-${index}`"><label>风险 {{ index + 1 }}</label><el-input v-model="draft.risks[index]" :readonly="!canEditCurrent" /><el-button v-if="canEditCurrent" @click="draft.risks.splice(index, 1)">删除</el-button></div><el-button v-if="canEditCurrent" @click="addRisk">添加风险</el-button>

          <!-- 里程碑列表 -->
          <div class="milestone-list">
            <div v-for="m in draft.milestones" :key="m.tempKey" class="milestone-section" :class="{ 'milestone-pending': hasPendingItems(m.tempKey) }">
              <div class="milestone-header" :id="`planning-${m.tempKey}-entity`" @click="toggleMilestone(m.tempKey)">
                <div class="milestone-expand-icon">
                  <span v-if="expandedMilestones.includes(m.tempKey)">▼</span>
                  <span v-else>▶</span>
                </div>
                <div class="milestone-title-row">
                  <h3 class="milestone-title">{{ m.title }}</h3>
                  <span class="milestone-date" v-if="m.targetDate">目标：{{ m.targetDate }}</span>
                </div>
                <div class="milestone-stats">
                  <span class="stat-item">{{ getTaskCount(m.tempKey) }} 个任务</span>
                  <span v-if="getUnassignedCount(m.tempKey) > 0" class="stat-unassigned">
                    {{ getUnassignedCount(m.tempKey) }} 未分配
                  </span>
                </div>
                <div class="milestone-actions" v-if="canEditCurrent" @click.stop>
                  <el-button size="small" @click="addTask(m.tempKey)">添加任务</el-button>
                  <el-button size="small" type="danger" @click="removeMilestone(m.tempKey)">删除</el-button>
                </div>
              </div>

              <!-- 里程碑详情 -->
              <div v-if="expandedMilestones.includes(m.tempKey)" class="milestone-details">
                <div class="detail-row">
                  <label>标题</label>
                  <el-input v-model="m.title" :readonly="!permissions?.canEdit || !editingLatest" />
                </div>
                <div class="detail-row">
                  <label>目标</label>
                  <el-input v-model="m.objective" type="textarea" :readonly="!canEditCurrent" />
                </div>
                <div class="detail-row">
                  <label>描述</label>
                  <el-input v-model="m.description" type="textarea" :readonly="!canEditCurrent" placeholder="里程碑描述" />
                </div>
                <div class="detail-row">
                  <label>目标日期</label>
                  <span :id="`planning-${m.tempKey}-targetDate`">
                            <el-date-picker v-model="m.targetDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="milestoneDateDisabled" />
                  </span>
                </div>
                <div class="detail-row">
                  <label>参考来源</label>
                  <el-select v-model="m.sourceRefs" multiple :disabled="!canEditCurrent">
                    <el-option v-for="source in draft.sources" :key="source.ref" :label="source.documentName" :value="source.ref" />
                  </el-select>
                </div>
              </div>

              <!-- 任务列表 -->
              <div v-if="expandedMilestones.includes(m.tempKey)" class="task-list">
                <div v-for="task in draft.tasks.filter(t => t.milestoneTempKey === m.tempKey)"
                     :id="`planning-${task.tempKey}-entity`"
                     :key="task.tempKey"
                     class="task-card"
                     :class="{ 'task-unassigned': !task.assigneeId }">
                  <div class="task-header">
                    <span class="task-title">{{ task.title }}</span>
                    <el-tag v-if="!task.assigneeId" type="warning" size="small">未分配</el-tag>
                  </div>

                  <div class="task-details">
                    <div class="detail-row">
                      <label>标题</label>
                      <el-input v-model="task.title" :readonly="!permissions?.canEdit || !editingLatest" />
                    </div>
                    <div class="detail-row">
                      <label>描述</label>
                      <el-input :id="`planning-${task.tempKey}-description`" v-model="task.description" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" />
                    </div>
                    <div class="detail-row">
                      <label>目标</label>
                      <el-input v-model="task.objective" :readonly="!canEditCurrent" />
                    </div>
                    <div class="detail-row-inline">
                      <div class="detail-item">
                        <label>优先级</label>
                        <span :id="`planning-${task.tempKey}-priority`">
                          <el-select v-model="task.priority" :disabled="!canEditCurrent">
                            <el-option v-for="p in ['LOW','MEDIUM','HIGH','URGENT']" :key="p" :label="priorityLabel(p)" :value="p" />
                          </el-select>
                        </span>
                      </div>
                      <div class="detail-item">
                        <label>预估工时</label>
                        <el-input-number v-model="task.estimatedHours" :min="0.5" :max="80" :disabled="!canEditCurrent" />
                      </div>
                    </div>
                    <div class="detail-row-inline">
                      <div class="detail-item">
                        <label>开始日期</label>
                        <span :id="`planning-${task.tempKey}-startDate`">
                              <el-date-picker v-model="task.startDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="taskStartDateDisabled(task)" />
                        </span>
                      </div>
                      <div class="detail-item">
                        <label>截止日期</label>
                        <span :id="`planning-${task.tempKey}-dueDate`">
                              <el-date-picker v-model="task.dueDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" :disabled-date="taskDueDateDisabled(task)" />
                        </span>
                      </div>
                    </div>
                    <div class="detail-row">
                      <label>AI 建议负责人</label>
                      <small>{{ memberName(task.suggestedAssigneeId) }}</small>
                    </div>
                    <div class="detail-row">
                      <label>负责人</label>
                      <el-select v-model="task.assigneeId" clearable :disabled="!canEditCurrent">
                        <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
                      </el-select>
                    </div>
                    <div class="detail-row">
                      <label>前置任务</label>
                      <el-select v-model="task.dependencyTempKeys" multiple :disabled="!canEditCurrent">
                        <el-option v-for="candidate in draft.tasks.filter(candidate => candidate.tempKey !== task.tempKey)"
                                   :key="candidate.tempKey"
                                   :label="candidate.title"
                                   :value="candidate.tempKey"
                                   :disabled="dependencyWouldCycle(draft, task.tempKey, candidate.tempKey)" />
                      </el-select>
                    </div>
                    <div class="detail-row">
                      <label>参考来源</label>
                      <el-select v-model="task.sourceRefs" multiple :disabled="!canEditCurrent">
                        <el-option v-for="source in draft.sources" :key="source.ref" :label="source.documentName" :value="source.ref" />
                      </el-select>
                    </div>
                  </div>

                  <div class="task-actions" v-if="canEditCurrent">
                    <el-button type="danger" size="small" @click="removeTask(task.tempKey)">删除任务</el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
          <el-button v-if="canEditCurrent" @click="addMilestone">添加里程碑</el-button>
          <div class="actions"><el-button v-if="permissions?.canEdit && editingLatest" :disabled="!dirty" @click="save">保存新版本</el-button>
            <el-button v-if="permissions?.canRestore && !editingLatest" @click="restore">恢复为新版本</el-button>
            <template v-if="permissions?.canConfirm && editingLatest"><span>将创建 {{ confirmationSummary.milestones }} 里程碑 / {{ confirmationSummary.tasks }} 任务 / {{ confirmationSummary.dependencies }} 依赖；未分配 {{ confirmationSummary.unassigned }}</span><el-checkbox v-model="checked">我已检查规划</el-checkbox><el-button type="success" :disabled="!checked || dirty" @click="confirm">确认并创建任务</el-button></template>
          </div>
        </template>
      </el-card>
    </section>
    <el-dialog v-model="createVisible" title="创建规划"><el-form label-position="top" @submit.prevent="create">
      <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="目标"><el-input v-model="form.goal" type="textarea" /></el-form-item>
      <el-form-item label="约束"><el-input v-model="form.constraints" /></el-form-item><el-form-item label="开始"><el-date-picker v-model="form.planStartDate" value-format="YYYY-MM-DD" :disabled-date="formStartDisabled" /></el-form-item>
      <el-form-item label="截止"><el-date-picker v-model="form.planDueDate" value-format="YYYY-MM-DD" :disabled-date="formDueDisabled" /></el-form-item><el-form-item label="最大任务数"><el-input-number v-model="form.maxTaskCount" :min="1" :max="40" :step="1" controls-position="right" /></el-form-item>
      <el-form-item label="可用参考文档（最多 10 个）"><el-select v-model="form.documentIds" multiple :multiple-limit="10"><el-option v-for="document in documents" :key="document.id" :label="document.displayName" :value="document.id" /></el-select></el-form-item>
      <el-button native-type="submit" type="primary">创建并生成</el-button>
    </el-form></el-dialog>
    <el-dialog v-model="saveDialogVisible" title="保存新版本">
      <el-form label-position="top">
        <el-form-item label="修改说明（可选）">
          <el-input v-model="saveComment" type="textarea" placeholder="描述本次修改的内容..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="saveDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmSave">确认保存</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.milestone-list {
  margin-top: 16px;
}

.milestone-section {
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  margin-bottom: 16px;
  overflow: hidden;
}

.milestone-pending {
  background-color: #fff8e1;
  border-color: #ffcc02;
}

.milestone-header {
  background: #f5f5f5;
  padding: 12px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  cursor: pointer;
  user-select: none;
}

.milestone-pending .milestone-header {
  background: #fff3cd;
}

.milestone-expand-icon {
  width: 20px;
  text-align: center;
  color: #666;
  font-size: 12px;
}

.milestone-title-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
}

.milestone-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.milestone-date {
  color: #666;
  font-size: 13px;
}

.milestone-stats {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #666;
}

.stat-unassigned {
  color: #ff9800;
  font-weight: 500;
}

.milestone-actions {
  display: flex;
  gap: 8px;
}

.milestone-details {
  padding: 16px;
  border-top: 1px solid #e0e0e0;
}

.detail-row {
  margin-bottom: 12px;
}

.detail-row label {
  display: block;
  font-size: 13px;
  color: #666;
  margin-bottom: 4px;
}

.detail-row-inline {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
}

.detail-item {
  flex: 1;
}

.detail-item label {
  display: block;
  font-size: 13px;
  color: #666;
  margin-bottom: 4px;
}

.task-list {
  padding: 0 16px 16px;
}

.task-card {
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  padding: 12px;
  margin-top: 12px;
  background: white;
}

.task-unassigned {
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: #fff8e1;
}

.task-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.task-title {
  font-weight: 500;
  font-size: 14px;
}

.task-details {
  padding: 0;
}

.task-actions {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #eee;
}

/* 选择框样式 - 使其更大更长 */
:deep(.el-select) {
  width: 100%;
}

:deep(.el-select .el-input__wrapper) {
  min-height: 36px;
}

/* 里程碑详情中的选择框 */
.milestone-details :deep(.el-select) {
  width: 100%;
}

/* 任务详情中的选择框 */
.task-details :deep(.el-select) {
  width: 100%;
}

.detail-row {
  margin-bottom: 12px;
  width: 100%;
}
</style>
