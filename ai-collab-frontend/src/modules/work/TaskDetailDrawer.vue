<script setup lang="ts">
import { formatDate, formatDateTime, taskPriorityLabel, taskStatusLabel } from '../../shared/display-labels'
import type { Task, TaskComment } from './types'

const props = defineProps<{
  visible: boolean
  selected: Task | null
  tasks: Task[]
  comments: TaskComment[]
  canManage: boolean
  currentUserId?: string
  savingDependencies: boolean
  deletingTask: boolean
  addingComment: boolean
  commentOperationId: string
  commentContent: string
  commentValidationMessage: string
}>()

defineEmits<{
  'update:visible': [visible: boolean]
  'update:commentContent': [content: string]
  editTask: []
  deleteTask: []
  saveDependencies: [ids: string[]]
  editComment: [comment: TaskComment]
  deleteComment: [comment: TaskComment]
  addComment: []
}>()

function dependencyName(dependencyId: string): string {
  return props.tasks.find(task => task.id === dependencyId)?.title ?? '任务已不可用'
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="任务详情"
    size="560px"
    @update:model-value="$emit('update:visible', $event)"
  >
    <template v-if="selected">
      <div v-if="canManage" class="actions task-detail-actions">
        <el-button type="primary" :disabled="deletingTask" @click="$emit('editTask')">编辑任务</el-button>
        <el-button type="danger" plain :loading="deletingTask" @click="$emit('deleteTask')">删除任务</el-button>
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="标题">{{ selected.title }}</el-descriptions-item>
        <el-descriptions-item label="描述">{{ selected.description || '无' }}</el-descriptions-item>
        <el-descriptions-item label="负责人">{{ selected.assigneeDisplayName || '未分配' }}</el-descriptions-item>
        <el-descriptions-item label="里程碑">{{ selected.milestoneName || '无' }}</el-descriptions-item>
        <el-descriptions-item label="预计工时">
          {{ selected.estimateHours === null ? '未设置' : `${selected.estimateHours} 小时` }}
        </el-descriptions-item>
        <el-descriptions-item label="开始日期">{{ formatDate(selected.startDate) }}</el-descriptions-item>
        <el-descriptions-item label="截止日期">{{ formatDate(selected.dueDate) }}</el-descriptions-item>
        <el-descriptions-item label="任务状态">{{ taskStatusLabel(selected.status) }}</el-descriptions-item>
        <el-descriptions-item label="任务优先级">{{ taskPriorityLabel(selected.priority) }}</el-descriptions-item>
      </el-descriptions>

      <section class="drawer-section">
        <h3>前置任务</h3>
        <p class="readonly-note">
          已配置 {{ selected.dependencyIds.length }} 项前置任务；
          其中 {{ selected.unfinishedDependencyCount }} 项尚未完成。
        </p>
        <el-select
          v-if="canManage"
          :model-value="selected.dependencyIds"
          multiple
          placeholder="选择前置任务"
          :loading="savingDependencies"
          :disabled="savingDependencies"
          aria-label="选择前置任务"
          @change="$emit('saveDependencies', $event)"
        >
          <el-option
            v-for="candidate in tasks.filter(item => item.id !== selected?.id)"
            :key="candidate.id"
            :label="candidate.title"
            :value="candidate.id"
          />
        </el-select>
        <ul v-else-if="selected.dependencyIds.length" class="dependency-list">
          <li v-for="dependencyId in selected.dependencyIds" :key="dependencyId">
            {{ dependencyName(dependencyId) }}
          </li>
        </ul>
        <p v-else class="readonly-note">暂无前置任务</p>
        <div v-if="selected.unfinishedDependencyCount" class="dependency-blocked">
          还有 {{ selected.unfinishedDependencyCount }} 项前置任务未完成，当前任务暂时不能推进。
        </div>
      </section>

      <section class="drawer-section">
        <h3>评论</h3>
        <div v-for="comment in comments" :key="comment.id" class="comment">
          <div class="comment-header">
            <strong>{{ comment.authorDisplayName }}</strong>
            <span>{{ formatDateTime(comment.updatedAt) }}</span>
          </div>
          <p>{{ comment.content }}</p>
          <div
            v-if="comment.authorId === currentUserId || canManage"
            class="comment-actions"
          >
            <el-button
              v-if="comment.authorId === currentUserId"
              text
              :loading="commentOperationId === comment.id"
              :disabled="Boolean(commentOperationId)"
              @click="$emit('editComment', comment)"
            >
              编辑评论
            </el-button>
            <el-button
              type="danger"
              text
              :loading="commentOperationId === comment.id"
              :disabled="Boolean(commentOperationId)"
              @click="$emit('deleteComment', comment)"
            >
              删除评论
            </el-button>
          </div>
        </div>
        <el-empty v-if="comments.length === 0" description="暂无评论" :image-size="64" />
        <el-form-item label="评论内容">
          <el-input
            :model-value="commentContent"
            type="textarea"
            placeholder="请输入评论内容"
            :maxlength="2000"
            show-word-limit
            @update:model-value="$emit('update:commentContent', $event)"
          />
        </el-form-item>
        <el-alert
          v-if="commentContent && commentValidationMessage"
          :title="commentValidationMessage"
          type="warning"
          :closable="false"
        />
        <el-button
          type="primary"
          :loading="addingComment"
          :disabled="Boolean(commentValidationMessage) || addingComment"
          @click="$emit('addComment')"
        >
          发送评论
        </el-button>
      </section>
    </template>
  </el-drawer>
</template>
