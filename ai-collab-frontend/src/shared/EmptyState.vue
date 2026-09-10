<script setup lang="ts">
defineProps<{
  title: string
  description?: string
  actionLabel?: string
  actionTo?: string
  compact?: boolean
}>()
</script>

<template>
  <div class="empty-state" :class="{ compact }">
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <router-link v-if="actionLabel && actionTo" :to="actionTo">
      <el-button type="primary" plain :size="compact ? 'small' : 'default'">{{ actionLabel }}</el-button>
    </router-link>
  </div>
</template>
