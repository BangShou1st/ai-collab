<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import {
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
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
const project = ref<Project | null>(null)
const members = ref<ProjectMember[]>([])
const milestones = ref<Milestone[]>([])
const tasks = ref<Task[]>([])
const selected = ref<Task | null>(null)
const comments = ref<TaskComment[]>([])
const drawerVisible = ref(false)
const createVisible = ref(false)
const editVisible = ref(false)
const errorMessage = ref('')
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
  if (value.startDate && value.dueDate && value.startDate > value.dueDate) {
    return '截止日期不能早于开始日期'
  }
  return ''
}

function columnTasks(status: TaskStatus): Task[] {
  return visibleTasks.value.filter(task => task.status === status)
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
  errorMessage.value = ''
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
    errorMessage.value = normalizeApiError(error).message
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
  errorMessage.value = ''
  const selectedTaskId = drawerVisible.value ? selected.value?.id : undefined
  try {
    await workApi.updateStatus(projectId, task, status, canManage.value)
    await refreshTaskData(selectedTaskId)
    ElMessage.success('任务状态已更新')
  } catch (error) {
    const safeError = normalizeApiError(error)
    ElMessage.error(safeError.message)
    try {
      await refreshTaskData(selectedTaskId)
    } catch {
      // 保留原始业务错误，且不把失败前的本地状态作为服务端真相。
    }
    errorMessage.value = safeError.message
  } finally {
    updatingTaskId.value = ''
  }
}

async function createTask(): Promise<void> {
  if (createValidationMessage.value || creatingTask.value) return
  creatingTask.value = true
  errorMessage.value = ''
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
    errorMessage.value = normalizeApiError(error).message
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
  errorMessage.value = ''
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
    errorMessage.value = safeError.message
    if (safeError.code === 'VERSION_CONFLICT' && selected.value) {
      await reloadSelectedTask(selected.value.id)
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
    errorMessage.value = ''
    await workApi.deleteTask(projectId, selected.value.id)
    drawerVisible.value = false
    selected.value = null
    comments.value = []
    await loadTasks()
    ElMessage.success('任务已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
  } finally {
    deletingTask.value = false
  }
}

async function openTask(task: Task): Promise<void> {
  if (openingTaskId.value) return
  openingTaskId.value = task.id
  errorMessage.value = ''
  try {
    selected.value = (await workApi.task(projectId, task.id)).data
    comments.value = (await workApi.comments(projectId, task.id)).data
    drawerVisible.value = true
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    openingTaskId.value = ''
  }
}

async function saveDependencies(ids: string[]): Promise<void> {
  if (!selected.value || savingDependencies.value) return
  const taskId = selected.value.id
  savingDependencies.value = true
  errorMessage.value = ''
  try {
    await workApi.replaceDependencies(projectId, taskId, ids)
    await refreshTaskData(taskId)
    ElMessage.success('前置任务已更新')
  } catch (error) {
    const safeError = normalizeApiError(error)
    try {
      await refreshTaskData(taskId)
    } catch {
      // 保留原始业务错误；刷新失败时仍不把本地候选值当作服务端真相。
    }
    errorMessage.value = safeError.message
    ElMessage.error(safeError.message)
  } finally {
    savingDependencies.value = false
  }
}

async function addComment(): Promise<void> {
  if (!selected.value || commentValidationMessage.value || addingComment.value) return
  addingComment.value = true
  errorMessage.value = ''
  try {
    await workApi.createComment(projectId, selected.value.id, commentContent.value.trim())
    commentContent.value = ''
    await reloadComments()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
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
    errorMessage.value = ''
    await workApi.updateComment(projectId, selected.value.id, comment.id, value.trim())
    await reloadComments()
    ElMessage.success('评论已更新')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
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
    errorMessage.value = ''
    await workApi.deleteComment(projectId, selected.value.id, comment.id)
    await reloadComments()
    ElMessage.success('评论已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
  } finally {
    commentOperationId.value = ''
  }
}

watch(
  () => [filters.assigneeId, filters.milestoneId],
  async () => {
    loading.value = true
    errorMessage.value = ''
    try {
      await loadTasks()
    } catch (error) {
      errorMessage.value = normalizeApiError(error).message
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
      description="按状态推进任务，及时识别依赖阻塞与交付风险。"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="createVisible = true">新建任务</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
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
    <section v-loading="loading" class="board-grid">
      <div v-for="status in statuses" :key="status" class="board-column">
        <h2>{{ taskStatusLabel(status) }} <el-tag round>{{ columnTasks(status).length }}</el-tag></h2>
        <TaskBoardCard
          v-for="task in columnTasks(status)"
          :key="task.id"
          :task="task"
          :can-change-status="canChangeStatus(task)"
          :opening="openingTaskId === task.id"
          :updating="updatingTaskId === task.id"
          :operation-locked="Boolean(updatingTaskId)"
          @open="openTask"
          @update-status="updateStatus"
        />
        <el-empty
          v-if="columnTasks(status).length === 0"
          description="暂无任务"
          :image-size="64"
        />
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
          <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="截止日期"><el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" /></el-form-item>
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
          <el-date-picker v-model="editForm.startDate" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="editForm.dueDate" value-format="YYYY-MM-DD" />
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
  </main>
</template>
