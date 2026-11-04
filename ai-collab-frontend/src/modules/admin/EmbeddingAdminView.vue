<script setup lang="ts">
import EmbeddingAdminContent from './components/EmbeddingAdminContent.vue'
</script>

<template>
  <div class="embedding-page">
    <header class="page-header">
      <div>
        <h1 class="page-title">系统 Embedding 与索引</h1>
        <p class="page-desc">系统全局唯一向量模型配置、连通测试与候选索引代际管理</p>
      </div>
    </header>
    <EmbeddingAdminContent />
  </div>
</template>

<style scoped>
.embedding-page {
  max-width: 1200px;
  margin: 0 auto;
}
.page-header {
  margin-bottom: 20px;
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
</style>
