import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError, showApiError } from '../../api/api-result'
import {
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
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
import type { Milestone, Task, TaskComment, TaskPriority, TaskStatus } from './types'

/**
 * 任务看板视图的状态与业务逻辑。
 * 从 TaskBoardView.vue 下沉：看板加载、拖拽换列、批量操作、
 * 任务增删改、评论与依赖管理都在这里，视图层只负责渲染与编排。
 */
export function useTaskBoardView() {
  const statuses: TaskStatus[] = ['TODO', 'IN_PROGRESS', 'BLOCKED', 'DONE', 'CANCELED']
  const priorities: TaskPriority[] = ['LOW', 'MEDIUM', 'HIGH', 'URGENT']
  const route = useRoute()
  const router = useRouter()
  const auth = useAuthStore()
  const projectId = computed(() => String(route.params.projectId ?? ''))
  const highlightedSourcePlanId = computed(() => typeof route.query.sourcePlanId === 'string' ? route.query.sourcePlanId : '')
  const project = ref<Project | null>(null)
  const members = ref<ProjectMember[]>([])
  const milestones = ref<Milestone[]>([])
  const tasks = ref<Task[]>([])
  const selected = ref<Task | null>(null)
  const comments = ref<TaskComment[]>([])
  const detailVisible = ref(false)
  const detailError = ref('')
  let detailGeneration = 0
  let boardGeneration = 0
  let listGeneration = 0
  let disposed = false
  const drawerVisible = computed({
    get: () => detailVisible.value,
    set: (visible: boolean) => { if (!visible) void closeTask() },
  })
  function detailContext() {
    const generation = detailGeneration
    const id = projectId.value
    return () => !disposed && generation === detailGeneration && id === projectId.value
  }
  async function closeTask(): Promise<void> {
    ++detailGeneration
    detailVisible.value = false
    selected.value = null
    comments.value = []
    commentContent.value = ''
    detailError.value = ''
    openingTaskId.value = ''
    const query = { ...route.query }
    delete query.taskId
    await router.push({ query })
  }
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

      await workApi.batchUpdate(projectId.value, items)
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
    const generation = ++listGeneration
    const id = projectId.value
    if (!id || disposed) return
    const result = await workApi.tasks(id, taskQueryParams())
    if (!disposed && id === projectId.value && generation === listGeneration) tasks.value = result.data
  }
  function clearUnavailableDetail(): void {
    selected.value = null
    comments.value = []
    commentContent.value = ''
    detailError.value = '任务不存在或无权访问，请关闭详情后选择其他任务'
  }

  async function refreshTaskData(selectedTaskId?: string): Promise<void> {
    const current = detailContext()
    const id = projectId.value
    await Promise.all([
      loadTasks(),
      selectedTaskId ? workApi.task(id, selectedTaskId).then(result => {
        if (current() && selected.value?.id === selectedTaskId) selected.value = result.data
      }).catch(error => {
        if (current() && selected.value?.id === selectedTaskId) clearUnavailableDetail()
        throw error
      }) : Promise.resolve(),
    ])
  }
  async function load(): Promise<void> {
    const generation = ++boardGeneration
    const id = projectId.value
    // Route params clear before this component unmounts on Back to notifications.
    if (!id || disposed) return
    loading.value = true
    try {
      const [projectResult, memberResult, milestoneResult] = await Promise.all([
        projectApi.get(id), projectApi.listMembers(id), workApi.milestones(id), loadTasks(),
      ])
      if (disposed || generation !== boardGeneration || id !== projectId.value) return
      project.value = projectResult.data
      members.value = memberResult.data
      milestones.value = milestoneResult.data
    } catch (error) {
      if (!disposed && generation === boardGeneration && id === projectId.value) showApiError(error, '任务看板加载')
    } finally {
      if (!disposed && generation === boardGeneration && id === projectId.value) loading.value = false
    }
  }

  function canChangeStatus(task: Task): boolean {
    return canManage.value || (project.value?.role === 'MEMBER' && task.assigneeId === auth.currentUser?.id)
  }

  async function reloadSelectedTask(taskId: string): Promise<void> {
    const current = detailContext()
    const fresh = (await workApi.task(projectId.value, taskId)).data
    if (!current() || selected.value?.id !== taskId) return
    selected.value = fresh
    tasks.value = tasks.value.map(item => item.id === taskId ? fresh : item)
  }

  async function updateStatus(task: Task, status: TaskStatus): Promise<void> {
    if (
      updatingTaskId.value
      || !allowedTaskStatusTransitions(task.status).includes(status)
    ) return
    updatingTaskId.value = task.id
    const current = detailContext()
    const selectedTaskId = drawerVisible.value ? selected.value?.id : undefined
    try {
      await workApi.updateStatus(projectId.value, task, status, canManage.value)
      if (!current()) return
      await refreshTaskData(selectedTaskId)
      ElMessage.success('任务状态已更新')
    } catch (error) {
      if (!current()) return
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
      await workApi.createTask(projectId.value, {
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
    const current = detailContext()
    try {
      const result = (await workApi.updateTask(projectId.value, selected.value.id, {
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
      if (!current()) return
      selected.value = result
      editVisible.value = false
      await refreshTaskData(selected.value.id)
      ElMessage.success('任务已更新')
    } catch (error) {
      if (!current()) return
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
    const current = detailContext()
    const taskId = selected.value.id
    try {
      await ElMessageBox.confirm(
        `确认删除任务“${selected.value.title}”吗？`,
        '删除任务',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
      if (!current()) return
      deletingTask.value = true
      await workApi.deleteTask(projectId.value, taskId)
      if (!current()) return
      await closeTask()
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
    await router.push({ query: { ...route.query, taskId: task.id } })
  }
  async function loadDetail(): Promise<void> {
    const generation = ++detailGeneration
    const id = projectId.value
    const taskId = route.query.taskId
    selected.value = null
    comments.value = []
    commentContent.value = ''
    editVisible.value = false
    detailError.value = ''
    openingTaskId.value = ''
    detailVisible.value = taskId !== undefined
    if (taskId === undefined) return
    const unavailable = '任务不存在或无权访问，请关闭详情后选择其他任务'
    // Backend IDs are UUIDs; ambiguous/repeated query values are rejected locally.
    if (typeof taskId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(taskId)) {
      detailError.value = unavailable
      return
    }
    openingTaskId.value = taskId
    const current = () => !disposed && generation === detailGeneration && id === projectId.value
    try {
      const task = (await workApi.task(id, taskId)).data
      if (!current()) return
      const taskComments = (await workApi.comments(id, taskId)).data
      if (!current()) return
      selected.value = task
      comments.value = taskComments
    } catch {
      if (current()) detailError.value = unavailable
    } finally {
      if (current()) openingTaskId.value = ''
    }
  }

  async function saveDependencies(ids: string[]): Promise<void> {
    if (!selected.value || savingDependencies.value) return
    const current = detailContext()
    const taskId = selected.value.id
    savingDependencies.value = true
    try {
      await workApi.replaceDependencies(projectId.value, taskId, ids)
      if (!current()) return
      await refreshTaskData(taskId)
      ElMessage.success('前置任务已更新')
    } catch (error) {
      if (!current()) return
      await Promise.allSettled([refreshTaskData(taskId)])
      showApiError(error, '任务依赖保存')
    } finally {
      savingDependencies.value = false
    }
  }

  async function addComment(): Promise<void> {
    if (!selected.value || commentValidationMessage.value || addingComment.value) return
    addingComment.value = true
    const current = detailContext()
    try {
      await workApi.createComment(projectId.value, selected.value.id, commentContent.value.trim())
      if (!current()) return
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
    const current = detailContext()
    const result = await workApi.comments(projectId.value, selected.value.id)
    if (current()) comments.value = result.data
  }

  async function editComment(comment: TaskComment): Promise<void> {
    if (
      !selected.value
      || comment.authorId !== auth.currentUser?.id
      || commentOperationId.value
    ) return
    const current = detailContext()
    const taskId = selected.value.id
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
      if (!current()) return
      commentOperationId.value = comment.id
      await workApi.updateComment(projectId.value, taskId, comment.id, value.trim())
      if (!current()) return
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
    const current = detailContext()
    const taskId = selected.value.id
    try {
      await ElMessageBox.confirm(
        '确认删除该评论吗？',
        '删除评论',
        { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
      )
      if (!current()) return
      commentOperationId.value = comment.id
      await workApi.deleteComment(projectId.value, taskId, comment.id)
      if (!current()) return
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
  watch(projectId, () => {
    project.value = null
    members.value = []
    milestones.value = []
    tasks.value = []
    selectedTaskIds.value = new Set()
    createVisible.value = false
    batchOperationVisible.value = false
    filters.assigneeId = ''
    filters.milestoneId = ''
    filters.priority = ''
    void load()
  }, { immediate: true, flush: 'sync' })
  watch([projectId, () => route.query.taskId], () => { void loadDetail() }, { immediate: true, flush: 'sync' })
  onBeforeUnmount(() => {
    disposed = true
    ++detailGeneration
    ++boardGeneration
    ++listGeneration
  })

  return {
    auth, statuses, priorities, project, members, milestones, tasks, selected, comments,
    drawerVisible, detailError, createVisible, editVisible, commentContent, loading,
    openingTaskId, updatingTaskId, creatingTask, savingTask, deletingTask,
    savingDependencies, addingComment, commentOperationId, editTargetStatus,
    filters, draggedTask, dragOverColumn, selectedTaskIds,
    batchOperationVisible, batchStatus, batchPriority, batchAssigneeId, batchProcessing,
    form, editForm,
    createStartDateDisabled, createDueDateDisabled, editStartDateDisabled, editDueDateDisabled,
    canManage, visibleTasks, createValidationMessage, editValidationMessage, commentValidationMessage,
    highlightedSourcePlanId,
    columnTasks, showMyTasks, onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
    toggleTaskSelection, selectAllTasks, clearSelection, openBatchDialog, executeBatchOperation,
    canChangeStatus, updateStatus, createTask, openTaskEditor, saveTask, deleteTask,
    openTask, saveDependencies, addComment, editComment, deleteComment,
  }
}
