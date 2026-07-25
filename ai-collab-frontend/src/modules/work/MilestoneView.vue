<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { workApi } from './work-api'
import type { Milestone, MilestoneStatus } from './types'

const route = useRoute()
const projectId = route.params.projectId as string
const project = ref<Project | null>(null)
const milestones = ref<Milestone[]>([])
const dialogVisible = ref(false)
const editing = ref<Milestone | null>(null)
const errorMessage = ref('')
const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const form = reactive({
  name: '', description: '', targetDate: '', status: 'PLANNED' as MilestoneStatus, sortOrder: 0,
})

async function load(): Promise<void> {
  try {
    const [projectResult, milestoneResult] = await Promise.all([
      projectApi.get(projectId), workApi.milestones(projectId),
    ])
    project.value = projectResult.data
    milestones.value = milestoneResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

function openEditor(item?: Milestone): void {
  editing.value = item ?? null
  form.name = item?.name ?? ''
  form.description = item?.description ?? ''
  form.targetDate = item?.targetDate ?? ''
  form.status = item?.status ?? 'PLANNED'
  form.sortOrder = item?.sortOrder ?? 0
  dialogVisible.value = true
}

async function save(): Promise<void> {
  const payload = {
    name: form.name,
    description: form.description,
    targetDate: form.targetDate || null,
    status: form.status,
    sortOrder: form.sortOrder,
    ...(editing.value ? { version: editing.value.version } : {}),
  }
  try {
    if (editing.value) await workApi.updateMilestone(projectId, editing.value.id, payload)
    else await workApi.createMilestone(projectId, payload)
    dialogVisible.value = false
    await load()
    ElMessage.success('里程碑已保存')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

async function remove(item: Milestone): Promise<void> {
  await ElMessageBox.confirm(`确定删除里程碑“${item.name}”吗？任务不会被删除。`, '删除里程碑')
  try {
    await workApi.deleteMilestone(projectId, item.id)
    await load()
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-header">
      <div><p class="eyebrow">MILESTONES</p><h1>{{ project?.name }} · 里程碑</h1></div>
      <nav class="header-actions">
        <router-link to="/projects">项目列表</router-link>
        <router-link :to="`/projects/${projectId}/board`">任务看板</router-link>
        <el-button v-if="canManage" type="primary" @click="openEditor()">创建里程碑</el-button>
      </nav>
    </header>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <el-table :data="milestones">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="status" label="状态" width="130" />
      <el-table-column prop="targetDate" label="目标日期" width="140" />
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column v-if="canManage" label="操作" width="160">
        <template #default="{ row }">
          <el-button text @click="openEditor(row)">编辑</el-button>
          <el-button text type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="project?.role === 'MEMBER'" class="readonly-note">MEMBER 角色仅可查看里程碑。</p>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑里程碑' : '创建里程碑'">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-form-item label="目标日期"><el-date-picker v-model="form.targetDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option v-for="value in ['PLANNED','ACTIVE','COMPLETED','CANCELED']" :key="value" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-button type="primary" native-type="submit">保存</el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
