<script setup lang="ts">
import { computed } from 'vue'
import { getActivePinia } from 'pinia'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth-store'
import { DEFAULT_MAINTENANCE_MESSAGE, useSafeSystemStatusStore } from '../stores/system-status-store'

const router = useRouter()
const systemStatus = useSafeSystemStatusStore()

const message = computed(() =>
  systemStatus.maintenanceMessage?.trim() || DEFAULT_MAINTENANCE_MESSAGE,
)

async function refresh(): Promise<void> {
  await systemStatus.fetchStatus()
}

async function gotoAdminLogin(): Promise<void> {
  if (getActivePinia()) {
    const auth = useAuthStore()
    if (auth.isAuthenticated) {
      try {
        await auth.logout()
      } catch {
        auth.clearAuth()
      }
    }
  }
  await router.push({ path: '/login', query: { maintenanceAdmin: '1' } })
}
</script>

<template>
  <main class="maintenance-screen" role="status" aria-live="polite">
    <section class="maintenance-card">
      <div class="maintenance-badge">系统维护中</div>
      <h1>平台正在进行维护升级</h1>
      <p class="maintenance-message" data-test="maintenance-message">{{ message }}</p>
      <p class="maintenance-hint">普通用户服务已临时暂停，恢复后即可正常访问。系统管理员可继续登录管理后台操作。</p>
      <div class="maintenance-actions">
        <el-button type="primary" :loading="systemStatus.loading" data-test="maintenance-refresh" @click="refresh">
          刷新状态
        </el-button>
        <el-button plain data-test="maintenance-admin-login" @click="gotoAdminLogin">
          系统管理员登录
        </el-button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.maintenance-screen {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(135deg, #f8fafc 0%, #fef2f2 100%);
}
.maintenance-card {
  width: min(520px, 100%);
  background: #ffffff;
  border: 1px solid #fecaca;
  border-radius: 16px;
  padding: 36px 32px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
  text-align: center;
}
.maintenance-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  background: #fee2e2;
  color: #b91c1c;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 14px;
}
.maintenance-card h1 {
  margin: 0 0 12px;
  font-size: 22px;
  color: #111827;
}
.maintenance-message {
  margin: 0 0 12px;
  padding: 14px 16px;
  border-radius: 10px;
  background: #fff5f5;
  color: #991b1b;
  font-size: 15px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.maintenance-hint {
  margin: 0 0 24px;
  font-size: 13px;
  color: #6b7280;
  line-height: 1.6;
}
.maintenance-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
