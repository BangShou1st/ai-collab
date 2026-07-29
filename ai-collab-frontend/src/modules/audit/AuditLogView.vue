<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { auditApi } from './audit-api'
import { auditEntityLabel, formatDateTime } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import type { AuditLogItem } from './types'

const route = useRoute()
const projectId = route.params.projectId as string

const items = ref<AuditLogItem[]>([])
const loading = ref(false)
const errorMessage = ref('')
const currentPage = ref(1)
const pageSize = ref(20)
const total = ref(0)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await auditApi.page(projectId, currentPage.value, pageSize.value)
    items.value = result.data.items
    total.value = result.data.total
  } catch (error) {
    const normalized = normalizeApiError(error)
    if (normalized.httpStatus === 403) {
      errorMessage.value = '需要项目管理员权限才能查看操作日志'
    } else {
      errorMessage.value = normalized.message
    }
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number): void {
  currentPage.value = page
  load()
}

function handleSizeChange(size: number): void {
  pageSize.value = size
  currentPage.value = 1
  load()
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="项目中心"
      title="操作日志"
    >
      <template #actions>
        <el-button @click="load" :loading="loading">刷新</el-button>
      </template>
    </PageHeader>

    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon style="margin-bottom: 16px" />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="items" stripe>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column prop="userDisplayName" label="操作人" width="120" />
        <el-table-column prop="summary" label="操作摘要" min-width="300" show-overflow-tooltip />
        <el-table-column label="对象类型" width="120">
          <template #default="{ row }">
            {{ auditEntityLabel(row.entityType) }}
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrapper" v-if="total > 0">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
      <el-empty v-if="!loading && items.length === 0 && !errorMessage" description="暂无操作日志" />
    </el-card>
  </main>
</template>

<style scoped>
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
