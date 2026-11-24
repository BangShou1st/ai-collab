<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth-store'
import { DEFAULT_MAINTENANCE_MESSAGE, useSafeSystemStatusStore } from '../stores/system-status-store'

const route = useRoute()
const auth = useAuthStore()
const systemStatus = useSafeSystemStatusStore()

const visible = computed(() =>
  Boolean(systemStatus.maintenanceMode && auth.currentUser?.systemAdmin),
)
const isSettingsPage = computed(() =>
  route.path.startsWith('/admin/settings') || route.path.startsWith('/admin/config'),
)
const detail = computed(() =>
  systemStatus.maintenanceMessage?.trim() || DEFAULT_MAINTENANCE_MESSAGE,
)
</script>

<template>
  <div v-if="visible" class="maintenance-admin-banner" role="alert" data-test="maintenance-admin-banner">
    <div class="banner-text">
      <strong>系统当前处于维护停站模式</strong>
      <span>普通用户已被拦截并展示公告：“{{ detail }}”</span>
    </div>
    <router-link v-if="!isSettingsPage" to="/admin/settings" class="banner-link">
      前往系统设置关闭
    </router-link>
  </div>
</template>

<style scoped>
.maintenance-admin-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 18px;
  background: #dc2626;
  color: #ffffff;
  font-size: 13px;
  border-radius: 10px;
  margin-bottom: 16px;
}
.banner-text {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.banner-link {
  color: #ffffff;
  font-weight: 600;
  text-decoration: underline;
  white-space: nowrap;
}
</style>
