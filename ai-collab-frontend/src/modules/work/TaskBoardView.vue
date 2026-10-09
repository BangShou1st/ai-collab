<script setup lang="ts">
import { ElMessage } from 'element-plus'
import {
  taskPriorityLabel,
  taskStatusLabel,
} from '../../shared/display-labels'
import { allowedTaskStatusTransitions } from './task-status-transitions'
import { useTaskBoardView } from './use-task-board-view'
import TaskBoardCard from './TaskBoardCard.vue'
import TaskDetailDrawer from './TaskDetailDrawer.vue'

const {
  auth, statuses, priorities, project, members, milestones, tasks, selected, comments,
  drawerVisible, createVisible, editVisible, commentContent, loading,
  openingTaskId, updatingTaskId, creatingTask, savingTask, deletingTask,
  savingDependencies, addingComment, commentOperationId, editTargetStatus,
  filters, draggedTask, dragOverColumn, selectedTaskIds,
  batchOperationVisible, batchStatus, batchPriority, batchAssigneeId, batchProcessing,
  form, editForm,
  createStartDateDisabled, createDueDateDisabled, editStartDateDisabled, editDueDateDisabled,
  canManage, createValidationMessage, editValidationMessage, commentValidationMessage,
  highlightedSourcePlanId,
  columnTasks, showMyTasks, onDragStart, onDragEnd, onDragOver, onDragLeave, onDrop,
  toggleTaskSelection, selectAllTasks, clearSelection, openBatchDialog, executeBatchOperation,
  canChangeStatus, updateStatus, createTask, openTaskEditor, saveTask, deleteTask,
  openTask, saveDependencies, addComment, editComment, deleteComment,
} = useTaskBoardView()
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
  background-color: var(--color-primary-soft);
  border: 2px dashed var(--color-primary);
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
  background-color: var(--color-surface-raised);
  border-bottom: 1px solid var(--color-border);
}

.batch-count {
  color: var(--color-text-secondary);
  font-size: 14px;
}
</style>
