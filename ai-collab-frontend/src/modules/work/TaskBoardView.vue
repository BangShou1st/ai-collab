<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { useAuthStore } from '../../stores/auth-store'
import { projectApi } from '../project/project-api'
import type { Project, ProjectMember } from '../project/types'
import { workApi } from './work-api'
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
const errorMessage = ref('')
const commentContent = ref('')
const filters = reactive({ assigneeId: '', priority: '', milestoneId: '' })
const form = reactive({
  title: '', description: '', assigneeId: '', milestoneId: '',
  priority: 'MEDIUM' as TaskPriority, dueDate: '',
})
const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const visibleTasks = computed(() =>
  tasks.value.filter(task => !filters.priority || task.priority === filters.priority),
)

function columnTasks(status: TaskStatus): Task[] {
  return visibleTasks.value.filter(task => task.status === status)
}

async function load(): Promise<void> {
  try {
    const params = {
      assigneeId: filters.assigneeId || undefined,
      milestoneId: filters.milestoneId || undefined,
    }
    const [projectResult, memberResult, milestoneResult, taskResult] = await Promise.all([
      projectApi.get(projectId),
      projectApi.members(projectId),
      workApi.milestones(projectId),
      workApi.tasks(projectId, params),
    ])
    project.value = projectResult.data
    members.value = memberResult.data
    milestones.value = milestoneResult.data
    tasks.value = taskResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

function canChangeStatus(task: Task): boolean {
  return canManage.value || (project.value?.role === 'MEMBER' && task.assigneeId === auth.currentUser?.id)
}

async function updateStatus(task: Task, status: TaskStatus): Promise<void> {
  try {
    const updated = (await workApi.updateStatus(projectId, task, status)).data
    tasks.value = tasks.value.map(item => item.id === task.id ? updated : item)
    if (selected.value?.id === updated.id) selected.value = updated
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
    ElMessage.error(errorMessage.value)
    await load()
  }
}

async function createTask(): Promise<void> {
  try {
    await workApi.createTask(projectId, {
      title: form.title,
      description: form.description,
      assigneeId: form.assigneeId || null,
      milestoneId: form.milestoneId || null,
      priority: form.priority,
      dueDate: form.dueDate || null,
      status: 'TODO',
    })
    createVisible.value = false
    Object.assign(form, {
      title: '', description: '', assigneeId: '', milestoneId: '', priority: 'MEDIUM', dueDate: '',
    })
    await load()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

async function openTask(task: Task): Promise<void> {
  try {
    selected.value = (await workApi.task(projectId, task.id)).data
    comments.value = (await workApi.comments(projectId, task.id)).data
    drawerVisible.value = true
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

async function saveDependencies(ids: string[]): Promise<void> {
  if (!selected.value) return
  try {
    selected.value = (await workApi.replaceDependencies(projectId, selected.value.id, ids)).data
    await load()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

async function addComment(): Promise<void> {
  if (!selected.value || !commentContent.value.trim()) return
  try {
    await workApi.createComment(projectId, selected.value.id, commentContent.value)
    commentContent.value = ''
    comments.value = (await workApi.comments(projectId, selected.value.id)).data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

watch(() => [filters.assigneeId, filters.milestoneId], load)
onMounted(load)
</script>

<template>
  <main class="workspace-page board-page">
    <header class="workspace-header">
      <div><p class="eyebrow">TASK BOARD</p><h1>{{ project?.name }} · 工作台</h1></div>
      <nav class="header-actions">
        <router-link to="/projects">项目列表</router-link>
        <router-link :to="`/projects/${projectId}/milestones`">里程碑</router-link>
        <el-button v-if="canManage" type="primary" @click="createVisible = true">创建任务</el-button>
      </nav>
    </header>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section class="filter-bar">
      <el-select v-model="filters.assigneeId" clearable placeholder="负责人">
        <el-option v-for="member in members" :key="member.userId" :label="member.displayName" :value="member.userId" />
      </el-select>
      <el-select v-model="filters.priority" clearable placeholder="优先级">
        <el-option v-for="priority in priorities" :key="priority" :value="priority" />
      </el-select>
      <el-select v-model="filters.milestoneId" clearable placeholder="里程碑">
        <el-option v-for="milestone in milestones" :key="milestone.id" :label="milestone.name" :value="milestone.id" />
      </el-select>
    </section>
    <section class="board-grid">
      <div v-for="status in statuses" :key="status" class="board-column">
        <h2>{{ status }} <el-tag round>{{ columnTasks(status).length }}</el-tag></h2>
        <el-card
          v-for="task in columnTasks(status)"
          :key="task.id"
          class="task-card"
          shadow="hover"
          @click="openTask(task)"
        >
          <h3>{{ task.title }}</h3>
          <p>负责人：{{ task.assigneeDisplayName || '未分配' }}</p>
          <p>截止：{{ task.dueDate || '未设置' }} · {{ task.priority }}</p>
          <el-tag v-if="task.unfinishedDependencyCount" type="warning">
            {{ task.unfinishedDependencyCount }} 个未完成依赖
          </el-tag>
          <el-select
            :model-value="task.status"
            size="small"
            :disabled="!canChangeStatus(task)"
            @click.stop
            @change="updateStatus(task, $event)"
          >
            <el-option v-for="value in statuses" :key="value" :value="value" />
          </el-select>
        </el-card>
      </div>
    </section>

    <el-drawer v-model="drawerVisible" title="任务详情" size="520px">
      <template v-if="selected">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="标题">{{ selected.title }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ selected.description || '无' }}</el-descriptions-item>
          <el-descriptions-item label="负责人">{{ selected.assigneeDisplayName || '未分配' }}</el-descriptions-item>
          <el-descriptions-item label="里程碑">{{ selected.milestoneName || '无' }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ selected.startDate || '未设置' }} → {{ selected.dueDate || '未设置' }}</el-descriptions-item>
          <el-descriptions-item label="状态 / 优先级">{{ selected.status }} / {{ selected.priority }}</el-descriptions-item>
        </el-descriptions>
        <section class="drawer-section">
          <h3>依赖</h3>
          <el-select
            :model-value="selected.dependencyIds"
            multiple
            :disabled="!canManage"
            @change="saveDependencies"
          >
            <el-option
              v-for="candidate in tasks.filter(item => item.id !== selected?.id)"
              :key="candidate.id"
              :label="candidate.title"
              :value="candidate.id"
            />
          </el-select>
        </section>
        <section class="drawer-section">
          <h3>评论</h3>
          <div v-for="comment in comments" :key="comment.id" class="comment">
            <strong>{{ comment.authorDisplayName }}</strong>
            <p>{{ comment.content }}</p>
          </div>
          <el-input v-model="commentContent" type="textarea" placeholder="发表评论" />
          <el-button type="primary" @click="addComment">发送评论</el-button>
        </section>
      </template>
    </el-drawer>

    <el-dialog v-model="createVisible" title="创建任务">
      <el-form label-position="top" @submit.prevent="createTask">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
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
          <el-select v-model="form.priority"><el-option v-for="value in priorities" :key="value" :value="value" /></el-select>
        </el-form-item>
        <el-form-item label="截止日期"><el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-button type="primary" native-type="submit" :disabled="!form.title">创建</el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
