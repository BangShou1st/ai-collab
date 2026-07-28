<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import {
  formatDate,
  projectStatusLabel,
  roleLabel,
} from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { projectApi } from './project-api'
import type { Project } from './types'

const projects = ref<Project[]>([])
const loading = ref(false)
const creating = ref(false)
const dialogVisible = ref(false)
const errorMessage = ref('')
const form = reactive({ name: '', description: '', startDate: '', dueDate: '' })
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
  errorMessage.value = ''
  try {
    projects.value = (await projectApi.list()).data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

async function createProject(): Promise<void> {
  if (validationMessage.value || creating.value) return
  creating.value = true
  errorMessage.value = ''
  try {
    await projectApi.create({
      name: form.name.trim(),
      description: form.description,
      startDate: form.startDate || null,
      dueDate: form.dueDate || null,
    })
    dialogVisible.value = false
    form.name = ''
    form.description = ''
    form.startDate = ''
    form.dueDate = ''
    await load()
    ElMessage.success('项目已创建')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    creating.value = false
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
        <el-button type="primary" @click="dialogVisible = true">新建项目</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="project-grid">
      <el-card v-for="project in projects" :key="project.id" class="project-card">
        <template #header>
          <div class="header-row">
            <h2>{{ project.name }}</h2>
            <el-tag>{{ roleLabel(project.role) }}</el-tag>
          </div>
        </template>
        <p class="project-description">{{ project.description || '暂无项目描述' }}</p>
        <p>项目状态：{{ projectStatusLabel(project.status) }}</p>
        <p>项目周期：{{ formatDate(project.startDate) }} 至 {{ formatDate(project.dueDate) }}</p>
        <div class="actions">
          <router-link class="el-button el-button--primary" :to="`/projects/${project.id}/board`">
            进入项目
          </router-link>
        </div>
      </el-card>
      <el-empty v-if="!loading && projects.length === 0" description="还没有项目，创建第一个项目吧" />
    </section>
    <el-dialog v-model="dialogVisible" title="新建项目" width="480px">
      <el-form label-position="top" @submit.prevent="createProject">
        <el-form-item label="项目名称"><el-input v-model="form.name" :maxlength="100" /></el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="form.description" type="textarea" :maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="开始日期">
          <el-date-picker v-model="form.startDate" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="form.dueDate" value-format="YYYY-MM-DD" />
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
          :loading="creating"
          :disabled="Boolean(validationMessage) || creating"
        >
          新建
        </el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
