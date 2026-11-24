<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './shared/AppShell.vue'
import AdminShell from './shared/AdminShell.vue'
import MaintenanceView from './shared/MaintenanceView.vue'
import { useAuthStore } from './stores/auth-store'
import { useSafeSystemStatusStore } from './stores/system-status-store'

const route = useRoute()
const auth = useAuthStore()
const systemStatus = useSafeSystemStatusStore()

const isAdminRoute = computed(() => Boolean(route.path && route.path.startsWith('/admin')))
const showMaintenanceScreen = computed(() =>
  Boolean(
    systemStatus.maintenanceMode
    && !auth.currentUser?.systemAdmin
    && route.path !== '/login',
  ),
)

onMounted(() => {
  if (!systemStatus.loaded) {
    void systemStatus.fetchStatus()
  }
})
</script>

<template>
  <MaintenanceView v-if="showMaintenanceScreen" />
  <router-view v-else v-slot="{ Component }">
    <component :is="Component" v-if="route.meta.public" />
    <AdminShell v-else-if="isAdminRoute">
      <component :is="Component" />
    </AdminShell>
    <AppShell v-else>
      <component :is="Component" />
    </AppShell>
  </router-view>
</template>
