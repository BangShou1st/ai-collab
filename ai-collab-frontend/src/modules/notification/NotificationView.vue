<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { normalizeApiError } from '../../api/api-result'
import { notificationLabel } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { notificationApi } from './notification-api'
import type { Notification } from './types'

const notifications = ref<Notification[]>([])
const loading = ref(false)
const errorMessage = ref('')
const page = ref(0)
const hasMore = ref(true)
const loadingMore = ref(false)

async function load(): Promise<void> {
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await notificationApi.list(0, 20)
    notifications.value = result.data
    hasMore.value = result.data.length === 20
    page.value = 0
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const nextPage = page.value + 1
    const result = await notificationApi.list(nextPage, 20)
    notifications.value.push(...result.data)
    hasMore.value = result.data.length === 20
    page.value = nextPage
  } catch (error) {
    errorMessage.value = normalizeApiError(error).message
  } finally {
    loadingMore.value = false
  }
}

async function markAsRead(notification: Notification): Promise<void> {
  if (notification.read) return
  try {
    await notificationApi.markAsRead(notification.id)
    notification.read = true
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  }
}

async function markAllAsRead(): Promise<void> {
  try {
    await notificationApi.markAllAsRead()
    notifications.value.forEach(n => { n.read = true })
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    ElMessage.error(normalizeApiError(error).message)
  }
}

function formatTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  const hours = Math.floor(diff / 3600000)
  const days = Math.floor(diff / 86400000)

  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  if (hours < 24) return `${hours} 小时前`
  if (days < 7) return `${days} 天前`
  return date.toLocaleDateString('zh-CN')
}

onMounted(load)
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="协作"
      title="通知中心"
    >
      <template #actions>
        <el-button @click="markAllAsRead">全部已读</el-button>
      </template>
    </PageHeader>
    <el-alert v-if="errorMessage" :title="errorMessage" type="error" show-icon />
    <section v-loading="loading" class="notification-list">
      <el-empty v-if="!loading && notifications.length === 0" description="暂无通知" :image-size="64" />
      <div
        v-for="notification in notifications"
        :key="notification.id"
        class="notification-item"
        :class="{ unread: !notification.read }"
        @click="markAsRead(notification)"
      >
        <div class="notification-header">
          <el-tag :type="notification.read ? 'info' : 'primary'" size="small">
            {{ notificationLabel(notification.type) }}
          </el-tag>
          <span class="notification-time">{{ formatTime(notification.createdAt) }}</span>
        </div>
        <div class="notification-title">{{ notification.title }}</div>
        <div v-if="notification.content" class="notification-content">{{ notification.content }}</div>
        <div class="notification-project">{{ notification.projectName }}</div>
      </div>
      <el-button
        v-if="hasMore && !loading"
        :loading="loadingMore"
        class="load-more"
        @click="loadMore"
      >
        加载更多
      </el-button>
    </section>
  </main>
</template>

<style scoped>
.notification-list {
  padding: 0 24px;
}

.notification-item {
  padding: 16px;
  margin-bottom: 8px;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.2s;
}

.notification-item:hover {
  border-color: #409eff;
}

.notification-item.unread {
  background-color: #f0f9ff;
  border-color: #b3d8ff;
}

.notification-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.notification-time {
  font-size: 12px;
  color: #909399;
}

.notification-title {
  font-weight: 500;
  margin-bottom: 4px;
}

.notification-content {
  font-size: 14px;
  color: #606266;
  margin-bottom: 4px;
}

.notification-project {
  font-size: 12px;
  color: #909399;
}

.load-more {
  width: 100%;
  margin-top: 16px;
}
</style>
