<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import { adminApi } from './admin-api'
import type { AdminUser } from './types'

const users = ref<AdminUser[]>([])
const loading = ref(false)
const saving = ref(false)
const userDialog = ref(false)
const searchQuery = ref('')

const userForm = reactive({ username: '', displayName: '', password: '' })

const filteredUsers = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return users.value
  return users.value.filter(u =>
    (u.username && u.username.toLowerCase().includes(q))
    || (u.displayName && u.displayName.toLowerCase().includes(q))
    || (u.email && u.email.toLowerCase().includes(q)),
  )
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await adminApi.users()
    users.value = res.data
  } catch (error) {
    showApiError(error, '账号列表加载')
  } finally {
    loading.value = false
  }
}

async function createUser(): Promise<void> {
  if (!userForm.username || !userForm.displayName || !userForm.password) {
    ElMessage.warning('请填写完整的账号信息')
    return
  }
  saving.value = true
  try {
    await adminApi.createUser({ ...userForm })
    userDialog.value = false
    Object.assign(userForm, { username: '', displayName: '', password: '' })
    ElMessage.success('账号已成功创建')
    await load()
  } catch (error) {
    showApiError(error, '账号创建')
  } finally {
    saving.value = false
  }
}

async function toggleUser(user: AdminUser): Promise<void> {
  const actionText = user.status === 'ACTIVE' ? '停用' : '启用'
  try {
    await ElMessageBox.confirm(
      `确认${actionText}账号「${user.displayName || user.username}」吗？`,
      `${actionText}账号确认`,
      {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        type: user.status === 'ACTIVE' ? 'warning' : 'info',
      },
    )
    await adminApi.setUserEnabled(user.id, user.status !== 'ACTIVE')
    ElMessage.success(`账号已${actionText}`)
    await load()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    showApiError(error, `账号${actionText}`)
  }
}

onMounted(load)
</script>

<template>
  <div class="users-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">账号管理</h1>
        <p class="page-desc">查看全平台注册账号、设置权限角色或启停访问状态</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="userDialog = true">新建账号</el-button>
      </div>
    </header>

    <!-- 搜索过滤条 -->
    <div class="filter-bar">
      <el-input
        v-model="searchQuery"
        placeholder="搜索用户名、姓名或邮箱..."
        :prefix-icon="Search"
        clearable
        class="search-input"
      />
      <span class="user-count">共 {{ filteredUsers.length }} 个账号</span>
    </div>

    <!-- 账号表格 -->
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="filteredUsers" empty-text="未找到匹配账号" style="width: 100%">
        <el-table-column prop="username" label="用户名" min-width="140">
          <template #default="{ row }">
            <span class="user-cell">
              <strong>{{ row.username }}</strong>
              <el-tag v-if="row.systemAdmin" size="small" type="danger" effect="plain" class="admin-badge">管理员</el-tag>
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="显示姓名" min-width="150" />
        <el-table-column prop="email" label="邮箱地址" min-width="180">
          <template #default="{ row }">
            <span class="text-secondary">{{ row.email || '未绑定邮箱' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <el-tag :type="row.systemAdmin ? 'danger' : 'info'" size="small">
              {{ row.systemAdmin ? '系统管理员' : '普通用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '正常' : '已停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.systemAdmin"
              text
              :type="row.status === 'ACTIVE' ? 'danger' : 'primary'"
              size="small"
              @click="toggleUser(row)"
            >
              {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
            </el-button>
            <span v-else class="text-secondary text-sm">内置保护</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建账号对话框 -->
    <el-dialog v-model="userDialog" title="新建账号" width="460px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="用户名" required>
          <el-input v-model="userForm.username" placeholder="英文小写、数字，如 user01" />
        </el-form-item>
        <el-form-item label="显示姓名" required>
          <el-input v-model="userForm.displayName" placeholder="真实姓名或展示昵称" />
        </el-form-item>
        <el-form-item label="初始密码" required>
          <el-input v-model="userForm.password" type="password" show-password placeholder="至少 8 位密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="userDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="createUser">确认创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.users-container {
  max-width: 1200px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: wrap;
}
.page-title {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  margin: 0 0 4px;
}
.page-desc {
  font-size: 13px;
  color: #6b7280;
  margin: 0;
}
.header-actions {
  display: flex;
  gap: 10px;
}

.filter-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  gap: 16px;
}
.search-input {
  max-width: 320px;
}
.user-count {
  font-size: 13px;
  color: #6b7280;
}

.table-card {
  border-radius: 12px;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
}
.user-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.admin-badge {
  font-size: 10px;
  padding: 0 4px;
}
.text-secondary {
  color: #9ca3af;
}
.text-sm {
  font-size: 12px;
}
</style>
