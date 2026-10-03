import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
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
import { projectApi } from '../project/project-api'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import type { Project, ProjectMember } from '../project/types'
import { dependencyWouldCycle, removeTaskAndDependencies } from './planning-draft'
import { planningFailureLabel } from './planning-failure'

/**
 * AI 任务规划视图的状态与业务逻辑。
 * 从 PlanningView.vue 下沉：规划列表/版本管理、草案编辑、轮询与确认流程都在这里，
 * 视图层只负责渲染与编排。
 */
export function usePlanningView() {
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
const repairDiagnostics = ref<Omit<StructuredValidationIssue, 'id'>[]>([])
function adoptAssignee(task: TaskPlanDraft['tasks'][number]) {
  if (!canEditCurrent.value || !task.suggestedAssigneeId || !members.value.some(member => member.userId === task.suggestedAssigneeId)) return
  task.assigneeId = task.suggestedAssigneeId
}
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
  repairDiagnostics.value = detail.data.data.repairDiagnostics || []
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
async function repairTask(targetTempKey: string): Promise<void> {
  if (!selected.value?.latestVersionId || !permissions.value?.canPartialRegenerate || !requireLatestVersion()) return
  if (dirty.value) return showValidationError('请先保存当前修改，再进行 AI 局部修复')
  const mode: PartialRepairMode = 'REGENERATE_SELECTED_TASK_DETAILS'
  try {
    await planningApi.partialRegenerate(projectId.value, selected.value.id, {
      baseVersionId: selected.value.latestVersionId,
      expectedVersionNo: selected.value.latestVersionNo,
      targetTempKeys: [targetTempKey],
      allowedFields: ['description', 'priority', 'estimatedHours', 'startDate', 'dueDate', 'suggestedAssigneeId', 'dependencyTempKeys', 'sourceRefs'],
      lockedFields: ['title', 'objective', 'milestoneTempKey'],
      issueIds: [],
      mode,
    })
    await refresh()
    startPolling()
  } catch (error) {
    showApiError(error, '规划局部修复')
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

  return {
    projectId, plans, selected, permissions, draft, versions, selectedVersionId,
    expandedMilestones, createVisible, checked, saveDialogVisible, saveComment,
    aiConfigured, canCreate, pendingConfirmation, documents, members, project,
    structuredIssues, repairDiagnostics, adoptAssignee, events, form, dirty, editingLatest, canEditCurrent,
    confirmationSummary, targetNames, getTaskCount, getUnassignedCount,
    failureMessage, hasPendingItems, autoExpandPendingMilestones,
    formStartDisabled, formDueDisabled, milestoneDateDisabled,
    taskStartDateDisabled, taskDueDateDisabled,
    open, openVersion, requestVersion, create, action, restore, save, confirmSave,
    manualEdit, locate, removePlan, confirm, refresh, repairTask,
    addMilestone, addTask, removeTask, removeMilestone, addAssumption, addRisk,
    memberName, toggleMilestone,
    historyReadOnlyMessage, dependencyWouldCycle,
  }
}
