<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { clearConfirmationKey, confirmationKey, planningApi } from './planning-api'
import { PlanningPoller } from './planning-poller'
import type { PlanPermissions, TaskPlan, TaskPlanDraft } from './types'
import { projectApi } from '../project/project-api'
import { documentApi } from '../document/document-api'
import type { ProjectDocument } from '../document/types'
import type { ProjectMember } from '../project/types'
import { dependencyWouldCycle, removeTaskAndDependencies } from './planning-draft'

const route = useRoute(), router = useRouter()
const projectId = computed(() => String(route.params.projectId))
const plans = ref<TaskPlan[]>([]), selected = ref<TaskPlan | null>(null)
const permissions = ref<PlanPermissions | null>(null), draft = ref<TaskPlanDraft | null>(null)
const versions = ref<Array<{ id: string; versionNo: number; sourceType: string }>>([])
const selectedVersionId = ref(''), snapshot = ref(''), statusFilter = ref(''), errorMessage = ref('')
const createVisible = ref(false), checked = ref(false)
const canCreate = ref(false), pendingConfirmation = ref<{ versionId: string; key: string } | null>(null)
const documents = ref<ProjectDocument[]>([]), members = ref<ProjectMember[]>([])
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

async function list(): Promise<TaskPlan['status'][]> {
  plans.value = (await planningApi.list(projectId.value, statusFilter.value)).data.data
  return plans.value.map(plan => plan.status)
}
async function open(plan: TaskPlan): Promise<void> {
  if (dirty.value && !await discard()) return
  const [detail, history] = await Promise.all([planningApi.detail(projectId.value, plan.id), planningApi.versions(projectId.value, plan.id)])
  selected.value = detail.data.data.plan; permissions.value = detail.data.data.permissions; versions.value = history.data.data
  if (selected.value.latestVersionId) await openVersion(selected.value.latestVersionId)
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
  try { const plan = (await planningApi.create(projectId.value, form)).data.data; createVisible.value = false; await list(); await open(plan); startPolling() }
  catch (error) { errorMessage.value = normalizeApiError(error).message }
}
async function action(name: 'cancel' | 'retry-detail' | 'regenerate'): Promise<void> {
  if (!selected.value || name === 'regenerate' && dirty.value && !await discard()) return
  try {
    await planningApi.action(projectId.value, selected.value.id, name)
    await refresh()
    startPolling()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}
async function restore(): Promise<void> {
  if (!selected.value || !selectedVersionId.value) return
  try {
    await planningApi.restore(projectId.value, selected.value.id, selectedVersionId.value)
    await refresh()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}
async function save(): Promise<void> {
  if (!selected.value?.latestVersionId || !draft.value || !editingLatest.value) return
  try { await planningApi.save(projectId.value, selected.value.id, selected.value.latestVersionId, draft.value); await refresh() }
  catch (error) { errorMessage.value = normalizeApiError(error).message }
}
async function confirm(): Promise<void> {
  if (!selected.value || !selectedVersionId.value || !checked.value) return
  const versionId = selectedVersionId.value, key = confirmationKey(projectId.value, selected.value.id, versionId)
  try {
    const result = await planningApi.confirm(projectId.value, selected.value.id, versionId, key)
    if (result.data.data.status !== 'SUCCESS') {
      pendingConfirmation.value = { versionId, key }; startPolling(); return
    }
    pendingConfirmation.value = null
    clearConfirmationKey(projectId.value, selected.value.id, versionId)
    await router.push({ path: `/projects/${projectId.value}/board`, query: { sourcePlanId: selected.value.id } })
  } catch (error) { errorMessage.value = normalizeApiError(error).message }
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
  if (!draft.value || !canEditCurrent.value || draft.value.tasks.length >= selected.value!.maxTaskCount) return
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
      console.error('Planning poller error:', error)
    }
  })
}
async function discard(): Promise<boolean> { try { await ElMessageBox.confirm('未保存修改将被丢弃，是否继续？', '未保存保护'); return true } catch { return false } }
function beforeUnload(event: BeforeUnloadEvent): void { if (dirty.value) event.preventDefault() }
onMounted(async () => {
  window.addEventListener('beforeunload', beforeUnload)
  canCreate.value = ['OWNER', 'ADMIN'].includes((await projectApi.get(projectId.value)).data.role)
  documents.value = (await documentApi.list(projectId.value)).data.filter(document => document.status === 'READY')
  members.value = (await projectApi.listMembers(projectId.value)).data
  await list(); startPolling()
})
onBeforeUnmount(() => { poller.stop(); window.removeEventListener('beforeunload', beforeUnload) })
onBeforeRouteLeave(() => !dirty.value || window.confirm('当前规划有未保存修改，确定离开吗？'))
onBeforeRouteUpdate(() => !dirty.value || window.confirm('当前规划有未保存修改，确定切换项目吗？'))
watch(projectId, async () => {
  poller.stop(); selected.value = null; draft.value = null; await list(); startPolling()
  canCreate.value = ['OWNER', 'ADMIN'].includes((await projectApi.get(projectId.value)).data.role)
  documents.value = (await documentApi.list(projectId.value)).data.filter(document => document.status === 'READY')
  members.value = (await projectApi.listMembers(projectId.value)).data
})
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="AI 辅助" title="AI 任务规划" description="两阶段生成、不可变版本审阅与原子落地。">
      <template #actions><el-button v-if="canCreate" type="primary" @click="createVisible = true">创建规划</el-button></template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" />
    <el-select v-model="statusFilter" clearable placeholder="全部状态" @change="list">
      <el-option v-for="s in ['SKELETON_GENERATING','DETAIL_GENERATING','DETAIL_GENERATION_FAILED','READY','CONFIRMED','FAILED','CANCELED']" :key="s" :value="s" />
    </el-select>
    <section class="planning-layout">
      <el-card>
        <button v-for="plan in plans" :key="plan.id" class="planning-list-item" @click="open(plan)">
          <strong>{{ plan.title }}</strong><el-tag>{{ plan.status }}</el-tag><small>{{ plan.goal }} · v{{ plan.latestVersionNo }}</small>
        </button>
      </el-card>
      <el-card v-if="selected">
        <template #header><div class="actions"><strong>{{ selected.title }}</strong>
          <el-button v-if="permissions?.canCancel" @click="action('cancel')">取消</el-button>
          <el-button v-if="permissions?.canRetryDetail" @click="action('retry-detail')">重试细节</el-button>
          <el-button v-if="permissions?.canRegenerate" @click="action('regenerate')">重新生成</el-button>
        </div></template>
        <el-alert v-if="selected.lastErrorSummary" :title="selected.lastErrorSummary" type="warning" />
        <el-select :model-value="selectedVersionId" @change="requestVersion"><el-option v-for="v in versions" :key="v.id" :label="`v${v.versionNo} ${v.sourceType}`" :value="v.id" /></el-select>
        <template v-if="draft">
          <el-alert v-if="!editingLatest" title="历史版本只读；可先恢复为新版本后编辑" type="info" />
          <el-input v-model="draft.summary" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" placeholder="规划摘要" />
          <h3>假设</h3><el-input v-for="(_, index) in draft.assumptions" :key="`a-${index}`" v-model="draft.assumptions[index]" :readonly="!canEditCurrent" />
          <h3>风险</h3><el-input v-for="(_, index) in draft.risks" :key="`r-${index}`" v-model="draft.risks[index]" :readonly="!canEditCurrent" />
          <el-collapse><el-collapse-item v-for="m in draft.milestones" :key="m.tempKey" :title="m.title">
            <el-input v-model="m.title" :readonly="!permissions?.canEdit || !editingLatest" />
            <el-input v-model="m.objective" type="textarea" :readonly="!canEditCurrent" />
            <el-input v-model="m.description" type="textarea" :readonly="!canEditCurrent" placeholder="里程碑描述" /><el-date-picker v-model="m.targetDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" />
            <el-select v-model="m.sourceRefs" multiple :disabled="!canEditCurrent"><el-option v-for="source in draft.sources" :key="source.ref" :label="source.ref" :value="source.ref" /></el-select>
            <el-button v-if="canEditCurrent" @click="addTask(m.tempKey)">添加任务</el-button><el-button v-if="canEditCurrent" type="danger" @click="removeMilestone(m.tempKey)">删除空里程碑</el-button>
            <div v-for="task in draft.tasks.filter(t => t.milestoneTempKey === m.tempKey)" :key="task.tempKey" class="task-plan-row">
              <el-input v-model="task.title" :readonly="!permissions?.canEdit || !editingLatest" /><el-input v-model="task.description" type="textarea" :readonly="!permissions?.canEdit || !editingLatest" />
              <el-input v-model="task.objective" :readonly="!canEditCurrent" /><el-select v-model="task.priority" :disabled="!canEditCurrent"><el-option v-for="p in ['LOW','MEDIUM','HIGH','URGENT']" :key="p" :value="p" /></el-select>
              <el-input-number v-model="task.estimatedHours" :min="0.5" :max="80" :disabled="!canEditCurrent" />
              <el-date-picker v-model="task.startDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" /><el-date-picker v-model="task.dueDate" value-format="YYYY-MM-DD" :disabled="!canEditCurrent" />
              <small>AI 建议负责人：{{ task.suggestedAssigneeId || '无' }}</small><el-select v-model="task.assigneeId" clearable :disabled="!canEditCurrent"><el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" /></el-select>
              <el-select v-model="task.dependencyTempKeys" multiple :disabled="!canEditCurrent"><el-option v-for="candidate in draft.tasks.filter(candidate => candidate.tempKey !== task.tempKey)" :key="candidate.tempKey" :label="candidate.title" :value="candidate.tempKey" :disabled="dependencyWouldCycle(draft, task.tempKey, candidate.tempKey)" /></el-select>
              <el-select v-model="task.sourceRefs" multiple :disabled="!canEditCurrent"><el-option v-for="source in draft.sources" :key="source.ref" :label="source.ref" :value="source.ref" /></el-select>
              <el-button v-if="canEditCurrent" type="danger" @click="removeTask(task.tempKey)">删除任务</el-button>
            </div>
          </el-collapse-item></el-collapse>
          <el-button v-if="canEditCurrent" @click="addMilestone">添加里程碑</el-button>
          <div class="actions"><el-button v-if="permissions?.canEdit && editingLatest" :disabled="!dirty" @click="save">保存新版本</el-button>
            <el-button v-if="permissions?.canRestore && !editingLatest" @click="restore">恢复为新版本</el-button>
            <template v-if="permissions?.canConfirm"><span>将创建 {{ confirmationSummary.milestones }} 里程碑 / {{ confirmationSummary.tasks }} 任务 / {{ confirmationSummary.dependencies }} 依赖；未分配 {{ confirmationSummary.unassigned }}</span><el-checkbox v-model="checked">我已检查规划</el-checkbox><el-button type="success" :disabled="!checked || dirty" @click="confirm">确认并创建任务</el-button></template>
          </div>
        </template>
      </el-card>
    </section>
    <el-dialog v-model="createVisible" title="创建规划"><el-form label-position="top" @submit.prevent="create">
      <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item><el-form-item label="目标"><el-input v-model="form.goal" type="textarea" /></el-form-item>
      <el-form-item label="约束"><el-input v-model="form.constraints" /></el-form-item><el-form-item label="开始"><el-date-picker v-model="form.planStartDate" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="截止"><el-date-picker v-model="form.planDueDate" value-format="YYYY-MM-DD" /></el-form-item><el-form-item label="最大任务数"><el-select v-model="form.maxTaskCount"><el-option v-for="n in [10,20,30,40]" :key="n" :value="n" /></el-select></el-form-item>
      <el-form-item label="READY 文档（最多 10 个）"><el-select v-model="form.documentIds" multiple :multiple-limit="10"><el-option v-for="document in documents" :key="document.id" :label="document.displayName" :value="document.id" /></el-select></el-form-item>
      <el-button native-type="submit" type="primary">创建并生成</el-button>
    </el-form></el-dialog>
  </main>
</template>
