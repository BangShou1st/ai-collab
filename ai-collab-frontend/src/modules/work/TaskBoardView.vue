<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
import {
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import {
  isEndDateDisabled,
  isStartDateDisabled,
  validateDateRange,
} from '../../shared/date-constraints'
import { useAuthStore } from '../../stores/auth-store'
import { projectApi } from '../project/project-api'
import type { Project, ProjectMember } from '../project/types'
import { workApi } from './work-api'
import { allowedTaskStatusTransitions } from './task-status-transitions'
import TaskBoardCard from './TaskBoardCard.vue'
import TaskDetailDrawer from './TaskDetailDrawer.vue'
import type { Milestone, Task, TaskComment, TaskPriority, TaskStatus } from './types'

const statuses: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELED']
const priorities: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']
const route = useRoute()
const auth = useAuthStore()
const projectId = route.params.projectId as string
const highlightedSourcePlanId = computed(() => typeof route.query.sourcePlanId === 'string' ? route.query.sourcePlanId : '')
const project = ref<Project | null>(null)
const members = ref<ProjectMember[]>([])
const milestones = ref<Milestone[]>([])
const tasks = ref<Task[]>([])
const selected = ref<Task | null>(null)
const comments = ref<TaskComment[]>([])
const drawerVisible = ref(false)
const createVisible = ref(false)
const editVisible = ref(false)
const commentContent = ref('')
const loading = ref(false)
const openingTaskId = ref('')
const updatingTaskId = ref('')
const creatingTask = ref(false)
const savingTask = ref(false)
const deletingTask = ref(false)
const savingDependencies = ref(false)
const addingComment = ref(false)
const commentOperationId = ref('')
const editTargetStatus = ref<TaskStatus | null>(null)
const filters = reactive({ assigneeId: '', priority: '', milestoneId: '' })
const draggedTask = ref<Task | null>(null)
const dragOverColumn = ref<TaskStatus | null>(null)
const selectedTaskIds = ref<Set<string>>(new Set())
const batchOperationVisible = ref(false)
const batchStatus = ref<TaskStatus | null>(null)
const batchPriority = ref<TaskPriority | null>(null)
const batchAssigneeId = ref<string>('')
const batchProcessing = ref(false)
const form = reactive({
  title: '', description: '', assigneeId: '', milestoneId: '',
  priority: 'MEDIUM' as TaskPriority, estimateHours: null as number | null,
  startDate: '', dueDate: '',
})
const editForm = reactive({
  title: '', description: '', assigneeId: '', milestoneId: '',
  priority: 'MEDIUM' as TaskPriority,
  estimateHours: null as number | null, startDate: '', dueDate: '',
})
const createStartDateDisabled = (date: Date) => isStartDateDisabled(
  date, project.value?.startDate ?? null, project.value?.dueDate ?? null, form.dueDate || null,
)
const createDueDateDisabled = (date: Date) => isEndDateDisabled(
  date, project.value?.startDate ?? null, project.value?.dueDate ?? null, form.startDate || null,
)
const editStartDateDisabled = (date: Date) => isStartDateDisabled(
  date, project.value?.startDate ?? null, project.value?.dueDate ?? null, editForm.dueDate || null,
)
const editDueDateDisabled = (date: Date) => isEndDateDisabled(
  date, project.value?.startDate ?? null, project.value?.dueDate ?? null, editForm.startDate || null,
)
const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const visibleTasks = computed(() =>
  tasks.value.filter(task => !filters.priority || task.priority === filters.priority),
)
const createValidationMessage = computed(() => validateTaskForm(form))
const editValidationMessage = computed(() => validateTaskForm(editForm))
const commentValidationMessage = computed(() => {
  if (!commentContent.value.trim()) return '评论内容不能为空'
  if (commentContent.value.length > 2000) return '评论内容不能超过 2000 个字符'
  return ''
})

function validateTaskForm(value: {
  title: string
  description: string
  estimateHours: number | null
  startDate: string
  dueDate: string
}): string {
  if (!value.title.trim()) return '任务标题不能为空'
  if (value.title.length > 160) return '任务标题不能超过 160 个字符'
  if (value.description.length > 4000) return '任务描述不能超过 4000 个字符'
  if (value.estimateHours !== null && (value.estimateHours < 0.5 || value.estimateHours > 80)) {
    return '预计工时必须在 0.5 至 80 小时之间'
  }
  const dateError = validateDateRange(
    value.startDate || null,
    value.dueDate || null,
    project.value?.startDate ?? null,
    project.value?.dueDate ?? null,
  )
  if (dateError) return dateError
  return ''
}

function columnTasks(status: TaskStatus): Task[] {
  return visibleTasks.value.filter(task => task.status === status)
}

function showMyTasks(): void {
  filters.assigneeId = auth.currentUser?.id || ''
}

function onDragStart(task: Task, event: DragEvent): void {
  if (!canManage.value && !(project.value?.role === 'MEMBER' && task.assigneeId === auth.currentUser?.id)) {
    event.preventDefault()
    return
  }
  draggedTask.value = task
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', task.id)
  }
}

function onDragEnd(): void {
  draggedTask.value = null
  dragOverColumn.value = null
}

function onDragOver(status: TaskStatus, event: DragEvent): void {
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'move'
  }
  dragOverColumn.value = status
}

function onDragLeave(): void {
  dragOverColumn.value = null
}

async function onDrop(targetStatus: TaskStatus, event: DragEvent): Promise<void> {
  event.preventDefault()
  dragOverColumn.value = null
  if (!draggedTask.value || draggedTask.value.status === targetStatus) {
    draggedTask.value = null
    return
  }
  if (!allowedTaskStatusTransitions(draggedTask.value.status).includes(targetStatus)) {
    ElMessage.warning('不允许从 ' + taskStatusLabel(draggedTask.value.status) + ' 转换到 ' + taskStatusLabel(targetStatus))
    draggedTask.value = null
    return
  }
  const task = draggedTask.value
  draggedTask.value = null
  await updateStatus(task, targetStatus)
}

function toggleTaskSelection(taskId: string): void {
  if (selectedTaskIds.value.has(taskId)) {
    selectedTaskIds.value.delete(taskId)
  } else {
    selectedTaskIds.value.add(taskId)
  }
  selectedTaskIds.value = new Set(selectedTaskIds.value)
}

function selectAllTasks(): void {
  const allIds = visibleTasks.value.map(t => t.id)
  selectedTaskIds.value = new Set(allIds)
}

function clearSelection(): void {
  selectedTaskIds.value = new Set()
}

function openBatchDialog(): void {
  batchStatus.value = null
  batchPriority.value = null
  batchAssigneeId.value = ''
  batchOperationVisible.value = true
}

async function executeBatchOperation(): Promise<void> {
  if (selectedTaskIds.value.size === 0) return
  if (!batchStatus.value && !batchPriority.value && !batchAssigneeId.value) {
    ElMessage.warning('请至少选择一项批量变更')
    return
  }
  batchProcessing.value = true
  try {
    const items = Array.from(selectedTaskIds.value).map(taskId => {
      const task = tasks.value.find(t => t.id === taskId)
      if (!task) return null
      const updates: {
        taskId: string
        sortOrder?: number
        status?: TaskStatus
        priority?: TaskPriority
        assigneeId?: string
        version: number
      } = {
        taskId,
        version: task.version,
      }
      if (batchStatus.value && allowedTaskStatusTransitions(task.status).includes(batchStatus.value)) {
        updates.status = batchStatus.value
      }
      if (batchPriority.value) updates.priority = batchPriority.value
      if (batchAssigneeId.value) updates.assigneeId = batchAssigneeId.value
      return updates
    }).filter(Boolean) as Array<{
      taskId: string
      sortOrder?: number
      status?: TaskStatus
      priority?: TaskPriority
      assigneeId?: string
      version: number
    }>

    if (items.length === 0) {
      ElMessage.warning('没有可执行的操作')
      return
    }

    await workApi.batchUpdate(projectId, items)
    batchOperationVisible.value = false
    selectedTaskIds.value = new Set()
    await refreshTaskData()
    ElMessage.success(`已批量更新 ${items.length} 个任务`)
  } catch (error) {
    showApiError(error, '任务批量更新')
  } finally {
    batchProcessing.value = false
  }
}

function taskQueryParams(): { assigneeId?: string; milestoneId?: string } {
  return {
    assigneeId: filters.assigneeId || undefined,
    milestoneId: filters.milestoneId || undefined,
  }
}

async function loadTasks(): Promise<void> {
  tasks.value = (await workApi.tasks(projectId, taskQueryParams())).data
}

async function refreshTaskData(selectedTaskId?: string): Promise<void> {
  const [freshTasks, freshSelected] = await Promise.all([
    workApi.tasks(projectId, taskQueryParams()),
    selectedTaskId ? workApi.task(projectId, selectedTaskId) : Promise.resolve(null),
  ])
  tasks.value = freshTasks.data
  if (freshSelected) selected.value = freshSelected.data
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [projectResult, memberResult, milestoneResult, taskResult] = await Promise.all([
      projectApi.get(projectId),
      projectApi.listMembers(projectId),
      workApi.milestones(projectId),
      workApi.tasks(projectId, taskQueryParams()),
    ])
    project.value = projectResult.data
    members.value = memberResult.data
    milestones.value = milestoneResult.data
    tasks.value = taskResult.data
  } catch (error) {
    showApiError(error, '任务看板加载')
  } finally {
    loading.value = false
  }
}

function canChangeStatus(task: Task): boolean {
  return canManage.value || (project.value?.role === 'MEMBER' && task.assigneeId === auth.currentUser?.id)
}

async function reloadSelectedTask(taskId: string): Promise<void> {
  const fresh = (await workApi.task(projectId, taskId)).data
  selected.value = fresh
  tasks.value = tasks.value.map(item => item.id === taskId ? fresh : item)
}

async function updateStatus(task: Task, status: TaskStatus): Promise<void> {
  if (
    updatingTaskId.value
    || !allowedTaskStatusTransitions(task.status).includes(status)
  ) return
  updatingTaskId.value = task.id
  const selectedTaskId = drawerVisible.value ? selected.value?.id : undefined
  try {
    await workApi.updateStatus(projectId, task, status, canManage.value)
    await refreshTaskData(selectedTaskId)
    ElMessage.success('任务状态已更新')
  } catch (error) {
    await Promise.allSettled([refreshTaskData(selectedTaskId)])
    showApiError(error, '任务状态更新')
  } finally {
    updatingTaskId.value = ''
  }
}

async function createTask(): Promise<void> {
  if (createValidationMessage.value || creatingTask.value) return
  creatingTask.value = true
  try {
    await workApi.createTask(projectId, {
      title: form.title.trim(),
      description: form.description,
      assigneeId: form.assigneeId || null,
      milestoneId: form.milestoneId || null,
      priority: form.priority,
      estimateHours: form.estimateHours,
      startDate: form.startDate || null,
      dueDate: form.dueDate || null,
      status: 'TODO',
    })
    createVisible.value = false
    Object.assign(form, {
      title: '', description: '', assigneeId: '', milestoneId: '', priority: 'MEDIUM',
      estimateHours: null, startDate: '', dueDate: '',
    })
    await refreshTaskData(drawerVisible.value ? selected.value?.id : undefined)
  } catch (error) {
    showApiError(error, '任务创建')
  } finally {
    creatingTask.value = false
  }
}

function openTaskEditor(): void {
  if (!selected.value) return
  editForm.title = selected.value.title
  editForm.description = selected.value.description
  editForm.assigneeId = selected.value.assigneeId ?? ''
  editForm.milestoneId = selected.value.milestoneId ?? ''
  editTargetStatus.value = null
  editForm.priority = selected.value.priority
  editForm.estimateHours = selected.value.estimateHours
  editForm.startDate = selected.value.startDate ?? ''
  editForm.dueDate = selected.value.dueDate ?? ''
  editVisible.value = true
}

async function saveTask(): Promise<void> {
  if (!selected.value || editValidationMessage.value || savingTask.value) return
  savingTask.value = true
  try {
    selected.value = (await workApi.updateTask(projectId, selected.value.id, {
      title: editForm.title.trim(),
      description: editForm.description,
      milestoneId: editForm.milestoneId || null,
      assigneeId: editForm.assigneeId || null,
      status: editTargetStatus.value ?? selected.value.status,
      priority: editForm.priority,
      estimateHours: editForm.estimateHours,
      startDate: editForm.startDate || null,
      dueDate: editForm.dueDate || null,
      version: selected.value.version,
    })).data
    editVisible.value = false
    await refreshTaskData(selected.value.id)
    ElMessage.success('任务已更新')
  } catch (error) {
    const safeError = normalizeApiError(error)
    showApiError(error, '任务保存')
    if (safeError.code === 'VERSION_CONFLICT' && selected.value) {
      await Promise.allSettled([reloadSelectedTask(selected.value.id)])
    }
  } finally {
    savingTask.value = false
  }
}

async function deleteTask(): Promise<void> {
  if (!selected.value || deletingTask.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除任务“${selected.value.title}”吗？`,
      '删除任务',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    deletingTask.value = true
    await workApi.deleteTask(projectId, selected.value.id)
    drawerVisible.value = false
    selected.value = null
    comments.value = []
    await loadTasks()
    ElMessage.success('任务已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '任务删除')
  } finally {
    deletingTask.value = false
  }
}

async function openTask(task: Task): Promise<void> {
  if (openingTaskId.value) return
  openingTaskId.value = task.id
  try {
    selected.value = (await workApi.task(projectId, task.id)).data
    comments.value = (await workApi.comments(projectId, task.id)).data
    drawerVisible.value = true
  } catch (error) {
    showApiError(error, '任务详情加载')
  } finally {
    openingTaskId.value = ''
  }
}

async function saveDependencies(ids: string[]): Promise<void> {
  if (!selected.value || savingDependencies.value) return
  const taskId = selected.value.id
  savingDependencies.value = true
  try {
    await workApi.replaceDependencies(projectId, taskId, ids)
    await refreshTaskData(taskId)
    ElMessage.success('前置任务已更新')
  } catch (error) {
    await Promise.allSettled([refreshTaskData(taskId)])
    showApiError(error, '任务依赖保存')
  } finally {
    savingDependencies.value = false
  }
}

async function addComment(): Promise<void> {
  if (!selected.value || commentValidationMessage.value || addingComment.value) return
  addingComment.value = true
  try {
    await workApi.createComment(projectId, selected.value.id, commentContent.value.trim())
    commentContent.value = ''
    await reloadComments()
  } catch (error) {
    showApiError(error, '任务评论添加')
  } finally {
    addingComment.value = false
  }
}

async function reloadComments(): Promise<void> {
  if (!selected.value) return
  comments.value = (await workApi.comments(projectId, selected.value.id)).data
}

async function editComment(comment: TaskComment): Promise<void> {
  if (
    !selected.value
    || comment.authorId !== auth.currentUser?.id
    || commentOperationId.value
  ) return
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入修改后的评论',
      '编辑评论',
      {
        inputValue: comment.content,
        inputType: 'textarea',
        inputValidator: input => {
          if (!input.trim()) return '评论内容不能为空'
          if (input.length > 2000) return '评论内容不能超过 2000 个字符'
          return true
        },
        confirmButtonText: '保存',
        cancelButtonText: '取消',
      },
    )
    commentOperationId.value = comment.id
    await workApi.updateComment(projectId, selected.value.id, comment.id, value.trim())
    await reloadComments()
    ElMessage.success('评论已更新')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '任务评论编辑')
  } finally {
    commentOperationId.value = ''
  }
}

async function deleteComment(comment: TaskComment): Promise<void> {
  if (!selected.value || commentOperationId.value) return
  try {
    await ElMessageBox.confirm(
      '确认删除该评论吗？',
      '删除评论',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    commentOperationId.value = comment.id
    await workApi.deleteComment(projectId, selected.value.id, comment.id)
    await reloadComments()
    ElMessage.success('评论已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, '任务评论删除')
  } finally {
    commentOperationId.value = ''
  }
}

watch(
  () => [filters.assigneeId, filters.milestoneId],
  async () => {
    loading.value = true
    try {
      await loadTasks()
    } catch (error) {
      showApiError(error, '任务列表筛选')
    } finally {
      loading.value = false
    }
  },
)
onMounted(load)
</script>

<template>
  <main class="workspace-page board-page">
    <PageHeader
      eyebrow="项目执行"
      title="任务看板"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="createVisible = true">新建任务</el-button>
        <el-button @click="showMyTasks">我的任务</el-button>
        <el-button v-if="canManage" @click="selectedTaskIds.size > 0 ? openBatchDialog() : ElMessage.info('请先选择任务')">批量操作</el-button>
      </template>
    </PageHeader>
    <section class="filter-bar">
      <label class="filter-field">
        <span>负责人</span>
        <el-select v-model="filters.assigneeId" clearable placeholder="全部负责人" aria-label="负责人筛选">
          <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
        </el-select>
      </label>
      <label class="filter-field">
        <span>优先级</span>
        <el-select v-model="filters.priority" clearable placeholder="全部优先级" aria-label="优先级筛选">
          <el-option
            v-for="priority in priorities"
            :key="priority"
            :label="taskPriorityLabel(priority)"
            :value="priority"
          />
        </el-select>
      </label>
      <label class="filter-field">
        <span>里程碑</span>
        <el-select v-model="filters.milestoneId" clearable placeholder="全部里程碑" aria-label="里程碑筛选">
          <el-option v-for="milestone in milestones" :key="milestone.id" :label="milestone.name" :value="milestone.id" />
        </el-select>
      </label>
    </section>
    <el-alert v-if="highlightedSourcePlanId" title="已高亮显示该 AI 规划创建的任务" type="success" show-icon />
    <section v-if="canManage" class="batch-toolbar">
      <el-button size="small" @click="selectAllTasks">全选</el-button>
      <el-button size="small" @click="clearSelection">清除选择</el-button>
      <span v-if="selectedTaskIds.size > 0" class="batch-count">已选择 {{ selectedTaskIds.size }} 个任务</span>
    </section>
    <section v-loading="loading" class="board-grid">
      <div
        v-for="status in statuses"
        :key="status"
        class="board-column"
        :class="{ 'drag-over': dragOverColumn === status }"
        @dragover="onDragOver(status, $event)"
        @dragleave="onDragLeave"
        @drop="onDrop(status, $event)"
      >
        <h2>{{ taskStatusLabel(status) }} <span class="board-count">{{ columnTasks(status).length }}</span></h2>
        <div class="board-tasks">
          <TaskBoardCard
            v-for="task in columnTasks(status)"
            :key="task.id"
            :task="task"
            :can-change-status="canChangeStatus(task)"
            :opening="openingTaskId === task.id"
            :updating="updatingTaskId === task.id"
            :operation-locked="Boolean(updatingTaskId)"
            :draggable="canManage || (project?.role === 'MEMBER' && task.assigneeId === auth.currentUser?.id)"
            :selected="selectedTaskIds.has(task.id)"
            :class="{ 'source-plan-highlight': task.sourcePlanId === highlightedSourcePlanId, 'dragging': draggedTask?.id === task.id }"
            @open="openTask"
            @update-status="updateStatus"
            @dragstart="onDragStart(task, $event)"
            @dragend="onDragEnd"
            @toggle-select="toggleTaskSelection"
          />
          <div v-if="columnTasks(status).length === 0" class="board-empty">暂无任务</div>
        </div>
      </div>
    </section>

    <TaskDetailDrawer
      v-model:visible="drawerVisible"
      v-model:comment-content="commentContent"
      :selected="selected"
      :tasks="tasks"
      :comments="comments"
      :can-manage="canManage"
      :current-user-id="auth.currentUser?.id"
      :saving-dependencies="savingDependencies"
      :deleting-task="deletingTask"
      :adding-comment="addingComment"
      :comment-operation-id="commentOperationId"
      :comment-validation-message="commentValidationMessage"
      @edit-task="openTaskEditor"
      @delete-task="deleteTask"
      @save-dependencies="saveDependencies"
      @edit-comment="editComment"
      @delete-comment="deleteComment"
      @add-comment="addComment"
    />

    <el-dialog v-model="createVisible" title="新建任务">
      <el-form label-position="top" @submit.prevent="createTask">
        <el-form-item label="标题"><el-input v-model="form.title" :maxlength="160" /></el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.assigneeId" clearable>
            <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
          </el-select>
        </el-form-item>
        <el-form-item label="里程碑">
          <el-select v-model="form.milestoneId" clearable>
            <el-option v-for="milestone in milestones" :key="milestone.id" :label="milestone.name" :value="milestone.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority">
            <el-option
              v-for="value in priorities"
              :key="value"
              :label="taskPriorityLabel(value)"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="预计工时">
          <el-input-number v-model="form.estimateHours" :min="0.5" :max="80" :step="0.5" />
        </el-form-item>
        <el-form-item label="开始日期">
            <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" :disabled-date="createStartDateDisabled" />
        </el-form-item>
          <el-form-item label="截止日期"><el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" :disabled-date="createDueDateDisabled" /></el-form-item>
        <el-alert
          v-if="createValidationMessage"
          :title="createValidationMessage"
          type="warning"
          :closable="false"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="creatingTask"
          :disabled="Boolean(createValidationMessage) || creatingTask"
        >
          新建
        </el-button>
      </el-form>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑任务">
      <el-form label-position="top" @submit.prevent="saveTask">
        <el-form-item label="标题"><el-input v-model="editForm.title" :maxlength="160" /></el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :maxlength="4000" show-word-limit />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="editForm.assigneeId" clearable>
            <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
          </el-select>
        </el-form-item>
        <el-form-item label="里程碑">
          <el-select v-model="editForm.milestoneId" clearable>
            <el-option v-for="milestone in milestones" :key="milestone.id" :label="milestone.name" :value="milestone.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="任务状态（可选变更）">
          <div class="actions">
            <el-tag>{{ selected ? taskStatusLabel(selected.status) : '' }}</el-tag>
            <el-select v-model="editTargetStatus" clearable placeholder="选择目标状态">
            <el-option
              v-for="value in selected ? allowedTaskStatusTransitions(selected.status) : []"
              :key="value"
              :label="taskStatusLabel(value)"
              :value="value"
            />
            </el-select>
          </div>
        </el-form-item>
        <el-form-item label="任务优先级">
          <el-select v-model="editForm.priority">
            <el-option
              v-for="value in priorities"
              :key="value"
              :label="taskPriorityLabel(value)"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="预计工时">
          <el-input-number v-model="editForm.estimateHours" :min="0.5" :max="80" :step="0.5" />
        </el-form-item>
        <el-form-item label="开始日期">
            <el-date-picker v-model="editForm.startDate" value-format="YYYY-MM-DD" :disabled-date="editStartDateDisabled" />
        </el-form-item>
        <el-form-item label="截止日期">
            <el-date-picker v-model="editForm.dueDate" value-format="YYYY-MM-DD" :disabled-date="editDueDateDisabled" />
        </el-form-item>
        <el-alert
          v-if="editValidationMessage"
          :title="editValidationMessage"
          type="warning"
          :closable="false"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="savingTask"
          :disabled="Boolean(editValidationMessage) || savingTask"
        >
          保存
        </el-button>
      </el-form>
    </el-dialog>

    <el-dialog v-model="batchOperationVisible" title="批量操作">
      <el-form label-position="top">
        <el-form-item label="变更状态">
          <el-select v-model="batchStatus" clearable placeholder="选择目标状态">
            <el-option
              v-for="status in statuses"
              :key="status"
              :label="taskStatusLabel(status)"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="变更优先级">
          <el-select v-model="batchPriority" clearable placeholder="选择优先级">
            <el-option
              v-for="priority in priorities"
              :key="priority"
              :label="taskPriorityLabel(priority)"
              :value="priority"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="变更负责人">
          <el-select v-model="batchAssigneeId" clearable placeholder="选择负责人">
            <el-option
              v-for="member in members"
              :key="member.userId"
              :label="member.displayName"
              :value="member.userId"
            />
          </el-select>
        </el-form-item>
        <el-button
          type="primary"
          :loading="batchProcessing"
          :disabled="batchProcessing"
          @click="executeBatchOperation"
        >
          执行批量操作
        </el-button>
      </el-form>
    </el-dialog>
  </main>
</template>

<style scoped>
.board-column.drag-over {
  background-color: #f0f9ff;
  border: 2px dashed #409eff;
}

.task-card.dragging {
  opacity: 0.5;
  cursor: grabbing;
}

.task-card[draggable="true"] {
  cursor: grab;
}

.task-card[draggable="true"]:active {
  cursor: grabbing;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 24px;
  background-color: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
}

.batch-count {
  color: #606266;
  font-size: 14px;
}
</style>
