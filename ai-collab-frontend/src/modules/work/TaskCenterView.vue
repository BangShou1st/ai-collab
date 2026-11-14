<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '../../shared/PageHeader.vue'
import {
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
import { allowedTaskStatusTransitions } from './task-status-transitions'
import { useTaskBoardView } from './use-task-board-view'
import { taskViewFromRoute, type TaskCenterViewType } from './task-view-mode'
import TaskBoardCard from './TaskBoardCard.vue'
import TaskDetailDrawer from './TaskDetailDrawer.vue'
import TaskListView from './TaskListView.vue'
import GanttView from './GanttView.vue'
import CalendarView from './CalendarView.vue'
import DependencyGraphView from './DependencyGraphView.vue'
import type { Task, TaskStatus } from './types'

const route = useRoute()
const router = useRouter()

const VIEW_TABS: Array<{ key: TaskCenterViewType; label: string; icon?: string }> = [
  { key: 'board', label: '看板' },
  { key: 'list', label: '列表' },
  { key: 'gantt', label: '甘特图' },
  { key: 'calendar', label: '日历' },
  { key: 'dependency-graph', label: '任务依赖' },
]

const currentView = computed<TaskCenterViewType>(() => taskViewFromRoute(route))

const {
  auth, statuses, priorities, project, members, milestones, tasks, selected, comments,
  drawerVisible, detailError, createVisible, editVisible, commentContent, loading,
  openingTaskId, updatingTaskId, creatingTask, savingTask, deletingTask,
  savingDependencies, addingComment, commentOperationId, editTargetStatus,
  filters, draggedTask, dragOverColumn, selectedTaskIds,
  listPage, listSize, listTotal, boardColumnState,
  batchOperationVisible, batchStatus, batchPriority, batchAssigneeId, batchProcessing,
  form, editForm,
  createStartDateDisabled, createDueDateDisabled, editStartDateDisabled, editDueDateDisabled,
  canManage, visibleTasks, createValidationMessage, editValidationMessage, commentValidationMessage,
  highlightedSourcePlanId,
  columnTasks, showMyTasks, onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
  loadMoreBoardColumn, changeListPage, changeListSize,
  toggleTaskSelection, selectAllTasks, clearSelection, openBatchDialog, executeBatchOperation,
  canChangeStatus, updateStatus, createTask, openTaskEditor, saveTask, deleteTask,
  openTask, saveDependencies, addComment, editComment, deleteComment,
} = useTaskBoardView()

const projectId = computed(() => String(route.params.projectId ?? ''))

async function handleListViewUpdateStatus(payload: { taskId: string; status: TaskStatus }): Promise<void> {
  const taskList = tasks.value || []
  const targetTask = taskList.find((t: Task) => t.id === payload.taskId)
  if (targetTask) {
    await updateStatus(targetTask, payload.status)
  }
}

async function switchView(view: TaskCenterViewType): Promise<void> {
  if (view === currentView.value) return
  const query = { ...route.query }

  if (route.path.includes('/tasks')) {
    query.view = view
    await router.push({ path: `/projects/${projectId.value}/tasks`, query })
  } else {
    // 兼容历史路径：如果当前在旧路由上，切换到对应旧路由
    if (view === 'board') {
      delete query.view
      await router.push({ path: `/projects/${projectId.value}/board`, query })
    } else if (view === 'gantt') {
      delete query.view
      await router.push({ path: `/projects/${projectId.value}/gantt`, query })
    } else if (view === 'calendar') {
      delete query.view
      await router.push({ path: `/projects/${projectId.value}/calendar`, query })
    } else if (view === 'dependency-graph') {
      delete query.view
      await router.push({ path: `/projects/${projectId.value}/dependency-graph`, query })
    } else {
      query.view = 'list'
      await router.push({ path: `/projects/${projectId.value}/tasks`, query })
    }
  }
}

async function handleSelectTaskId(taskId: string): Promise<void> {
  await router.push({ query: { ...route.query, taskId } })
}

const hasActiveFilters = computed(() =>
  Boolean(filters.assigneeId || filters.priority || filters.milestoneId || filters.status),
)
</script>

<template>
  <main class="workspace-page task-center-page" data-test="task-center-view">
    <PageHeader
      eyebrow="项目执行"
      title="任务中心"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" data-test="btn-create-task" @click="createVisible = true">
          新建任务
        </el-button>
        <el-button data-test="btn-my-tasks" @click="showMyTasks">我的任务</el-button>
        <el-button
          v-if="canManage && (currentView === 'board' || currentView === 'list')"
          data-test="btn-batch-operation"
          @click="selectedTaskIds.size > 0 ? openBatchDialog() : ElMessage.info('请先选择任务')"
        >
          批量操作
        </el-button>
      </template>
    </PageHeader>

    <!-- 视图切换导航栏 -->
    <div class="task-view-nav-bar" role="tablist" aria-label="任务视图切换">
      <div class="task-view-tabs">
        <button
          v-for="tab in VIEW_TABS"
          :key="tab.key"
          type="button"
          role="tab"
          class="task-view-tab"
          :class="{ active: currentView === tab.key }"
          :aria-selected="currentView === tab.key"
          :data-test="`tab-${tab.key}`"
          @click="switchView(tab.key)"
        >
          <span class="tab-label">{{ tab.label }}</span>
        </button>
      </div>
    </div>

    <!-- 统一筛选栏 -->
    <section class="task-filter-bar">
      <div class="filter-fields">
        <label class="filter-field">
          <span class="field-label">负责人</span>
          <el-select v-model="filters.assigneeId" clearable placeholder="全部负责人" aria-label="负责人筛选">
            <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
          </el-select>
        </label>
        <label class="filter-field">
          <span class="field-label">优先级</span>
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
          <span class="field-label">里程碑</span>
          <el-select v-model="filters.milestoneId" clearable placeholder="全部里程碑" aria-label="里程碑筛选">
            <el-option v-for="milestone in milestones" :key="milestone.id" :label="milestone.name" :value="milestone.id" />
          </el-select>
        </label>
        <label v-if="currentView === 'list'" class="filter-field">
          <span class="field-label">状态</span>
          <el-select v-model="filters.status" clearable placeholder="全部状态" aria-label="状态筛选">
            <el-option
              v-for="status in statuses"
              :key="status"
              :label="taskStatusLabel(status)"
              :value="status"
            />
          </el-select>
        </label>
      </div>
      <div v-if="currentView !== 'board' && currentView !== 'list' && hasActiveFilters" class="filter-hint-box">
        <span class="filter-chart-note">
          筛选条件将在「看板」与「列表」视图中生效。
        </span>
      </div>
    </section>

    <el-alert v-if="highlightedSourcePlanId" title="已高亮显示该 AI 规划创建的任务" type="success" show-icon />

    <!-- 批量工具栏：仅在已有选择时出现，避免空态占用空间 -->
    <section v-if="canManage && (currentView === 'board' || currentView === 'list') && selectedTaskIds.size > 0" class="batch-toolbar">
      <span class="batch-count">已选择 {{ selectedTaskIds.size }} 个任务</span>
      <el-button size="small" text @click="selectAllTasks">全选</el-button>
      <el-button size="small" text @click="clearSelection">清除选择</el-button>
    </section>

    <!-- 视图内容区域：按需条件加载，仅渲染当前视图 -->
    <div class="task-view-stage">
      <!-- 1. 看板视图 -->
      <section v-if="currentView === 'board'" v-loading="loading" class="board-grid" data-test="board-view-container">
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
            <el-button
              v-if="boardColumnState[status].hasNext"
              class="board-load-more"
              text
              :loading="boardColumnState[status].loadingMore"
              :data-test="`board-load-more-${status}`"
              @click="loadMoreBoardColumn(status)"
            >
              加载更多（已载 {{ columnTasks(status).length }} / {{ boardColumnState[status].total }}）
            </el-button>
          </div>
        </div>
      </section>

      <!-- 2. 列表视图 -->
      <section v-else-if="currentView === 'list'" v-loading="loading" class="list-view-container" data-test="list-view-container">
        <TaskListView
          :tasks="visibleTasks"
          :can-manage="canManage"
          :selected-task-ids="selectedTaskIds"
          :opening-task-id="openingTaskId"
          :updating-task-id="updatingTaskId"
          @open="openTask"
          @edit="openTaskEditor"
          @delete="deleteTask"
          @toggle-selection="toggleTaskSelection"
          @update-status="handleListViewUpdateStatus"
        />
        <div class="task-pagination">
          <el-pagination
            background
            layout="total, sizes, prev, pager, next"
            :total="listTotal"
            :current-page="listPage + 1"
            :page-size="listSize"
            :page-sizes="[20, 50, 100, 200]"
            data-test="task-pagination"
            @current-change="changeListPage($event - 1)"
            @size-change="changeListSize"
          />
        </div>
      </section>

      <!-- 3. 甘特图视图 -->
      <section v-else-if="currentView === 'gantt'" class="gantt-view-container" data-test="gantt-view-container">
        <GanttView :embedded="true" @select-task="handleSelectTaskId" />
      </section>

      <!-- 4. 日历视图 -->
      <section v-else-if="currentView === 'calendar'" class="calendar-view-container" data-test="calendar-view-container">
        <CalendarView :embedded="true" @select-task="handleSelectTaskId" />
      </section>

      <!-- 5. 依赖关系图视图 -->
      <section v-else-if="currentView === 'dependency-graph'" class="dependency-view-container" data-test="dependency-view-container">
        <DependencyGraphView :embedded="true" @select-task="handleSelectTaskId" />
      </section>
    </div>

    <!-- 任务详情抽屉 -->
    <TaskDetailDrawer
      v-model:visible="drawerVisible"
      v-model:comment-content="commentContent"
      :selected="selected"
      :loading="Boolean(openingTaskId)"
      :error="detailError"
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

    <!-- 新建任务对话框 -->
    <el-dialog v-model="createVisible" title="新建任务">
      <el-form label-position="top" @submit.prevent="createTask">
        <el-form-item label="任务标题" required>
          <el-input v-model="form.title" placeholder="输入任务标题" />
        </el-form-item>
        <el-form-item label="任务描述">
          <el-input v-model="form.description" type="textarea" placeholder="输入任务描述" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.assigneeId" clearable placeholder="选择负责人">
            <el-option
              v-for="member in members"
              :key="member.userId"
              :label="member.displayName"
              :value="member.userId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属里程碑">
          <el-select v-model="form.milestoneId" clearable placeholder="选择里程碑">
            <el-option
              v-for="milestone in milestones"
              :key="milestone.id"
              :label="milestone.name"
              :value="milestone.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="任务优先级">
          <el-select v-model="form.priority">
            <el-option
              v-for="priority in priorities"
              :key="priority"
              :label="taskPriorityLabel(priority)"
              :value="priority"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="预计工时">
          <el-input-number v-model="form.estimateHours" :min="0.5" :max="80" :step="0.5" />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" :disabled-date="createStartDateDisabled" />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" :disabled-date="createDueDateDisabled" />
        </el-form-item>
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
          创建
        </el-button>
      </el-form>
    </el-dialog>

    <!-- 编辑任务对话框 -->
    <el-dialog v-model="editVisible" title="编辑任务">
      <el-form label-position="top" @submit.prevent="saveTask">
        <el-form-item label="任务标题" required>
          <el-input v-model="editForm.title" />
        </el-form-item>
        <el-form-item label="任务描述">
          <el-input v-model="editForm.description" type="textarea" />
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="editForm.assigneeId" clearable placeholder="选择负责人">
            <el-option
              v-for="member in members"
              :key="member.userId"
              :label="member.displayName"
              :value="member.userId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属里程碑">
          <el-select v-model="editForm.milestoneId" clearable placeholder="选择里程碑">
            <el-option
              v-for="milestone in milestones"
              :key="milestone.id"
              :label="milestone.name"
              :value="milestone.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="任务状态">
          <div class="edit-status-row">
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

    <!-- 批量操作对话框 -->
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
.task-center-page {
  display: flex;
  flex-direction: column;
}
.task-view-nav-bar {
  display: flex;
  align-items: center;
  padding: 0 24px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--color-border);
}
.task-view-tabs {
  display: flex;
  gap: 4px;
}
.task-view-tab {
  padding: 8px 16px;
  background: transparent;
  border: none;
  border-bottom: 2px solid transparent;
  font-size: 14px;
  font-weight: 500;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.2s ease;
  position: relative;
  bottom: -1px;
}
.task-view-tab:hover {
  color: var(--color-primary);
}
.task-view-tab.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}
.task-filter-bar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 0 24px 12px;
}
.filter-fields {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-field {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-secondary);
}
.field-label {
  font-weight: 500;
  white-space: nowrap;
}
.filter-hint-box {
  padding: 6px 12px;
  border-radius: 6px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  font-size: 12px;
  color: var(--color-text-muted);
}
.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 24px;
  margin-bottom: 8px;
}
.batch-count {
  color: var(--color-text-secondary);
  font-size: 13px;
}
.task-view-stage {
  flex: 1;
  min-height: 0;
  padding: 0 24px 24px;
}
.list-view-container {
  width: 100%;
}
.task-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 12px;
}
.board-load-more {
  margin-top: 8px;
  width: 100%;
}
.gantt-view-container,
.calendar-view-container,
.dependency-view-container {
  width: 100%;
}
.board-column.drag-over {
  background-color: var(--color-primary-soft);
  border: 2px dashed var(--color-primary);
}
.task-card.dragging {
  opacity: 0.5;
  cursor: grabbing;
}
.edit-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
</style>
