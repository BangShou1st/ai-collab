<script setup lang="ts">
defineProps<{
  label: string
  tone?: 'info' | 'success' | 'warning' | 'danger'
}>()
</script>

<template>
  <el-tag :type="tone ?? 'info'" effect="light" class="status-badge">{{ label }}</el-tag>
</template>

