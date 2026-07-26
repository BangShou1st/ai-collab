<script setup lang="ts">
defineProps<{
  eyebrow?: string
  title: string
  description: string
  context?: string
}>()
</script>

<template>
  <header class="page-header">
    <div class="page-heading">
      <p v-if="eyebrow" class="eyebrow">{{ eyebrow }}</p>
      <h1>{{ title }}</h1>
      <p class="subtitle">{{ description }}</p>
      <p v-if="context" class="page-context">{{ context }}</p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions" />
    </div>
  </header>
</template>
