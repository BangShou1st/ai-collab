<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppShell from './shared/AppShell.vue'

const route = useRoute()
</script>

<template>
  <router-view v-slot="{ Component }">
    <component :is="Component" v-if="route.meta.public" />
    <AppShell v-else>
      <component :is="Component" />
    </AppShell>
  </router-view>
</template>
