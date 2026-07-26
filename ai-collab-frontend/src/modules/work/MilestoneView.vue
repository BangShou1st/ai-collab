<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { formatDate, milestoneStatusLabel } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from '../project/project-api'
import type { Project } from '../project/types'
import { workApi } from './work-api'
import type { Milestone, MilestoneStatus } from './types'

const route = useRoute()
const projectId = route.params.projectId as string
const project = ref<Project | null>(null)
const milestones = ref<Milestone[]>([])
const loading = ref(false)
const saving = ref(false)
const deletingId = ref('')
const dialogVisible = ref(false)
const editing = ref<Milestone | null>(null)
const errorMessage = ref('')
const canManage = computed(() => project.value?.role === 'OWNER' || project.value?.role === 'ADMIN')
const form = reactive({
  name: '', description: '', targetDate: '', status: 'PLANNED' as MilestoneStatus, sortOrder: 0,
})
const validationMessage = computed(() => {
  if (!form.name.trim()) return '里程碑名称不能为空'
  if (form.name.length > 100) return '里程碑名称不能超过 100 个字符'
  if (form.description.length > 1000) return '里程碑描述不能超过 1000 个字符'
  if (form.sortOrder < 0) return '排序值不能小于 0'
  return ''
})

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const [projectResult, milestoneResult] = await Promise.all([
      projectApi.get(projectId), workApi.milestones(projectId),
    ])
    project.value = projectResult.data
    milestones.value = milestoneResult.data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
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
  if (validationMessage.value || saving.value) return
  const payload = {
    name: form.name,
    description: form.description,
    targetDate: form.targetDate || null,
    status: form.status,
    sortOrder: form.sortOrder,
    ...(editing.value ? { version: editing.value.version } : {}),
  }
  saving.value = true
  errorMessage.value = ''
  try {
    if (editing.value) await workApi.updateMilestone(projectId, editing.value.id, payload)
    else await workApi.createMilestone(projectId, payload)
    dialogVisible.value = false
    await load()
    ElMessage.success('里程碑已保存')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    saving.value = false
  }
}

async function remove(item: Milestone): Promise<void> {
  if (deletingId.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除里程碑“${item.name}”吗？任务不会被删除。`,
      '删除里程碑',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    deletingId.value = item.id
    errorMessage.value = ''
    await workApi.deleteMilestone(projectId, item.id)
    await load()
    ElMessage.success('里程碑已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    errorMessage.value = normalizeApiError(error).message
  } finally {
    deletingId.value = ''
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目进度"
      title="里程碑"
      description="按目标日期组织关键交付节点，保持团队节奏一致。"
      :context="project?.name"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" @click="openEditor()">新建里程碑</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <el-table v-loading="loading" :data="milestones" empty-text="暂无里程碑">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column label="状态" width="130">
        <template #default="{ row }">{{ milestoneStatusLabel(row.status) }}</template>
      </el-table-column>
      <el-table-column label="目标日期" width="150">
        <template #default="{ row }">{{ formatDate(row.targetDate) }}</template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column v-if="canManage" label="操作" width="160">
        <template #default="{ row }">
          <el-button text :disabled="Boolean(deletingId)" @click="openEditor(row)">编辑</el-button>
          <el-button
            text
            type="danger"
            :loading="deletingId === row.id"
            :disabled="Boolean(deletingId)"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="project?.role === 'MEMBER'" class="readonly-note">成员仅可查看里程碑。</p>
    <el-dialog v-model="dialogVisible" :title="editing ? '编辑里程碑' : '新建里程碑'">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="名称"><el-input v-model="form.name" :maxlength="100" /></el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :maxlength="1000" show-word-limit />
        </el-form-item>
        <el-form-item label="目标日期"><el-date-picker v-model="form.targetDate" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option
              v-for="value in ['PLANNED','ACTIVE','COMPLETED','CANCELED']"
              :key="value"
              :label="milestoneStatusLabel(value)"
              :value="value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
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
          保存
        </el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
