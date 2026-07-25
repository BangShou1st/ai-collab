<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { projectApi } from './project-api'
import type { Project } from './types'

const projects = ref<Project[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const errorMessage = ref('')
const form = reactive({ name: '', description: '' })

async function load(): Promise<void> {
  loading.value = true
  try {
    projects.value = (await projectApi.list()).data
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

async function createProject(): Promise<void> {
  try {
    await projectApi.create({ name: form.name, description: form.description })
    dialogVisible.value = false
    form.name = ''
    form.description = ''
    await load()
    ElMessage.success('项目已创建')
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <header class="workspace-header">
      <div><p class="eyebrow">PROJECTS</p><h1>我的项目</h1></div>
      <nav class="header-actions">
        <router-link to="/account">账号设置</router-link>
        <el-button type="primary" @click="dialogVisible = true">创建项目</el-button>
      </nav>
    </header>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="project-grid">
      <el-card v-for="project in projects" :key="project.id" class="project-card">
        <template #header>
          <div class="header-row">
            <h2>{{ project.name }}</h2>
            <el-tag>{{ project.role }}</el-tag>
          </div>
        </template>
        <p>{{ project.description || '暂无项目描述' }}</p>
        <div class="actions">
          <router-link :to="`/projects/${project.id}/milestones`">里程碑</router-link>
          <el-button type="primary" text>
            <router-link :to="`/projects/${project.id}/board`">进入工作台</router-link>
          </el-button>
        </div>
      </el-card>
      <el-empty v-if="!loading && projects.length === 0" description="还没有项目，创建第一个项目吧" />
    </section>
    <el-dialog v-model="dialogVisible" title="创建项目" width="480px">
      <el-form label-position="top" @submit.prevent="createProject">
        <el-form-item label="项目名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="项目描述"><el-input v-model="form.description" type="textarea" /></el-form-item>
        <el-button type="primary" native-type="submit" :disabled="!form.name">创建</el-button>
      </el-form>
    </el-dialog>
  </main>
</template>
