<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { showApiError } from '../../api/api-result'
import {
  formatDate,
  projectStatusLabel,
  projectTypeLabel,
  roleLabel,
} from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import StatusBadge from '../../shared/StatusBadge.vue'
import { isEndDateDisabled, isStartDateDisabled } from '../../shared/date-constraints'
import { projectApi } from './project-api'
import type { Project, ProjectType } from './types'

const projects = ref<Project[]>([])
const loading = ref(false)
const saving = ref(false)
const deletingProjectId = ref('')
const dialogVisible = ref(false)
const editingProject = ref<Project | null>(null)
const form = reactive({
  name: '',
  description: '',
  type: 'OTHER' as ProjectType,
  startDate: '',
  dueDate: '',
  status: 'ACTIVE' as Project['status'],
})
const dialogTitle = computed(() => editingProject.value ? '编辑项目' : '新建项目')
const startDateDisabled = (date: Date) => isStartDateDisabled(date, null, null, form.dueDate || null)
const dueDateDisabled = (date: Date) => isEndDateDisabled(date, null, null, form.startDate || null)
const validationMessage = computed(() => {
  if (!form.name.trim()) return '项目名称不能为空'
  if (form.name.length > 100) return '项目名称不能超过 100 个字符'
  if (form.description.length > 2000) return '项目描述不能超过 2000 个字符'
  if (form.startDate && form.dueDate && form.startDate > form.dueDate) {
    return '截止日期不能早于开始日期'
  }
  return ''
})

async function load(): Promise<void> {
  loading.value = true
  try {
    projects.value = (await projectApi.list()).data
  } catch (error) {
    showApiError(error, '项目列表加载')
  } finally {
    loading.value = false
  }
}

function resetForm(): void {
  editingProject.value = null
  form.name = ''
  form.description = ''
  form.type = 'OTHER'
  form.startDate = ''
  form.dueDate = ''
  form.status = 'ACTIVE'
}

function openCreate(): void {
  resetForm()
  dialogVisible.value = true
}

function openEdit(project: Project): void {
  editingProject.value = project
  form.name = project.name
  form.description = project.description
  form.type = project.type
  form.startDate = project.startDate ?? ''
  form.dueDate = project.dueDate ?? ''
  form.status = project.status
  dialogVisible.value = true
}

async function saveProject(): Promise<void> {
  if (validationMessage.value || saving.value) return
  saving.value = true
  try {
    const project = editingProject.value
    if (project) {
      await projectApi.update(project.id, {
        name: form.name.trim(),
        description: form.description,
        type: form.type,
        startDate: form.startDate || null,
        dueDate: form.dueDate || null,
        status: form.status,
        version: project.version,
      })
    } else {
      await projectApi.create({
        name: form.name.trim(),
        description: form.description,
        type: form.type,
        startDate: form.startDate || null,
        dueDate: form.dueDate || null,
      })
    }
    dialogVisible.value = false
    resetForm()
    await load()
    ElMessage.success(project ? '项目已更新' : '项目已创建')
  } catch (error) {
    showApiError(error, editingProject.value ? '项目更新' : '项目创建')
  } finally {
    saving.value = false
  }
}

async function deleteProject(project: Project): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除项目“${project.name}”吗？此操作不可恢复。`,
      '删除项目',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  deletingProjectId.value = project.id
  try {
    await projectApi.remove(project.id)
    projects.value = projects.value.filter((candidate) => candidate.id !== project.id)
    ElMessage.success('项目已删除')
  } catch (error) {
    showApiError(error, '项目删除')
  } finally {
    deletingProjectId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目中心"
      title="我的项目"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">新建项目</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="project-grid">
      <el-card v-for="project in projects" :key="project.id" class="project-card">
        <template #header>
          <div class="header-row">
            <h2>{{ project.name }}</h2>
            <el-tag>{{ roleLabel(project.role) }}</el-tag>
          </div>
        </template>
        <p class="project-description">{{ project.description || '暂无项目描述' }}</p>
        <p class="project-meta-line">
          <StatusBadge :label="projectStatusLabel(project.status)" />
          <span>{{ projectTypeLabel(project.type) }}</span>
          <span>{{ roleLabel(project.role) }}</span>
        </p>
        <p class="project-meta-line muted">项目周期：{{ formatDate(project.startDate) }} 至 {{ formatDate(project.dueDate) }}</p>
        <div class="actions">
          <router-link class="el-button el-button--primary" :to="`/projects/${project.id}/dashboard`">
            进入项目
          </router-link>
          <el-dropdown v-if="project.role === 'OWNER'" trigger="click">
            <el-button text aria-label="更多操作">更多⌄</el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item data-action="edit-project" @click="openEdit(project)">编辑</el-dropdown-item>
                <el-dropdown-item
                  data-action="delete-project"
                  :disabled="deletingProjectId === project.id"
                  @click="deleteProject(project)"
                >删除项目</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-card>
      <el-empty v-if="!loading && projects.length === 0" description="还没有项目，创建第一个项目吧" />
    </section>
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" @closed="resetForm">
      <el-form label-position="top" @submit.prevent="saveProject">
        <el-form-item label="项目名称"><el-input v-model="form.name" :maxlength="100" /></el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="form.description" type="textarea" :maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="项目类型">
          <el-select v-model="form.type">
            <el-option label="竞赛项目" value="COMPETITION" />
            <el-option label="课程设计" value="COURSE_DESIGN" />
            <el-option label="软件实训" value="SOFTWARE_TRAINING" />
            <el-option label="其他" value="OTHER" />
          </el-select>
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" :disabled-date="startDateDisabled" />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" :disabled-date="dueDateDisabled" />
        </el-form-item>
        <el-form-item v-if="editingProject" label="项目状态">
          <el-select v-model="form.status">
            <el-option label="准备中" value="PREPARING" />
            <el-option label="进行中" value="ACTIVE" />
            <el-option label="已完成" value="COMPLETED" />
            <el-option label="已归档" value="ARCHIVED" />
          </el-select>
        </el-form-item>
        <el-alert
          v-if="validationMessage"
          :title="validationMessage"
          type="warning"
          :closable="false"
        />
        <el-button
          type="primary"
          native-type="submit"
          :loading="saving"
          :disabled="Boolean(validationMessage) || saving"
        >
          {{ editingProject ? '保存修改' : '新建' }}
        </el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
