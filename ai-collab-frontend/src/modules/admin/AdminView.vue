<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import PageHeader from '../../shared/PageHeader.vue'
import { adminApi } from './admin-api'
import type { AdminUser } from './types'

const users = ref<AdminUser[]>([])
const loading = ref(false)
const userDialog = ref(false)
const saving = ref(false)
const userForm = reactive({ username: '', displayName: '', password: '' })

async function load(): Promise<void> {
  loading.value = true
  try {
    const userResult = await adminApi.users()
    users.value = userResult.data
  } catch (error) {
    showApiError(error, '管理中心加载')
  } finally {
    loading.value = false
  }
}

async function createUser(): Promise<void> {
  saving.value = true
  try {
    await adminApi.createUser({ ...userForm })
    userDialog.value = false
    Object.assign(userForm, { username: '', displayName: '', password: '' })
    ElMessage.success('账号已创建')
    await load()
  } catch (error) {
    showApiError(error, '账号创建')
  } finally {
    saving.value = false
  }
}

async function toggleUser(user: AdminUser): Promise<void> {
  try {
    await adminApi.setUserEnabled(user.id, user.status !== 'ACTIVE')
    await load()
  } catch (error) {
    showApiError(error, user.status === 'ACTIVE' ? '账号停用' : '账号启用')
  }
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader eyebrow="系统设置" title="管理中心">
      <template #actions>
        <el-button @click="userDialog = true">新建账号</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="admin-stack">
      <el-card>
        <template #header><strong>账号管理</strong></template>
        <el-table :data="users" empty-text="暂无账号">
          <el-table-column prop="username" label="用户名" min-width="140" />
          <el-table-column prop="displayName" label="显示名称" min-width="160" />
          <el-table-column prop="email" label="邮箱" min-width="180" />
          <el-table-column label="角色" width="110">
            <template #default="{ row }">{{ row.systemAdmin ? '管理员' : '成员' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ row.status === 'ACTIVE' ? '正常' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button
                v-if="!row.systemAdmin"
                text
                :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
                @click="toggleUser(row)"
              >
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>

    <el-dialog v-model="userDialog" title="新建账号" width="460px">
      <el-form label-position="top">
        <el-form-item label="用户名"><el-input v-model="userForm.username" /></el-form-item>
        <el-form-item label="显示名称"><el-input v-model="userForm.displayName" /></el-form-item>
        <el-form-item label="初始密码"><el-input v-model="userForm.password" type="password" show-password /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createUser">创建</el-button>
      </template>
    </el-dialog>
  </main>
</template>

<style scoped>
.admin-stack { display: grid; gap: 18px; }
</style>
