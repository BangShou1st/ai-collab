<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from './shared/AppShell.vue'
import AdminShell from './shared/AdminShell.vue'

const route = useRoute()
const isAdminRoute = computed(() => Boolean(route.path && route.path.startsWith('/admin')))
</script>

<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" v-if="route.meta.public" />
    <AdminShell v-else-if="isAdminRoute">
      <component :is="Component" />
    </AdminShell>
    <AppShell v-else>
      <component :is="Component" />
    </AppShell>
  </router-view>
</template>
