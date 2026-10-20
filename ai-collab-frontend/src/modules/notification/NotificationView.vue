<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { showApiError } from '../../api/api-result'
import { notificationLabel } from '../../shared/display-labels'
import PageHeader from '../../shared/PageHeader.vue'
import { notificationApi } from './notification-api'
import { notificationTaskRoute } from './notification-task-route'
import type { Notification } from './types'

const router = useRouter()
const route = useRoute()
const notifications = ref<Notification[]>([])
const loading = ref(false)
const page = ref(0)
const hasMore = ref(true)
// Serialize paging and writes so read-all cannot race an older page response.
const busy = ref(false)
let generation = 0
let disposed = false
watch(() => route.fullPath, () => { ++generation })
onBeforeUnmount(() => { disposed = true; ++generation })
function context() {
  const current = generation
  return () => !disposed && generation === current
}

async function load(more = false): Promise<void> {
  if (busy.value || (more && !hasMore.value)) return
  busy.value = true
  loading.value = !more
  const active = context()
  try {
    const nextPage = more ? page.value + 1 : 0
    const result = await notificationApi.list(nextPage, 20)
    if (!active()) return
    notifications.value = more ? [...notifications.value, ...result.data] : result.data
    hasMore.value = result.data.length === 20
    page.value = nextPage
  } catch (error) {
    if (active()) showApiError(error, more ? '更多通知加载' : '通知列表加载')
  } finally {
    if (!disposed) { busy.value = false; loading.value = false }
  }
}

async function operate(notification: Notification, openTask = false): Promise<void> {
  if (busy.value) return
  const target = openTask ? notificationTaskRoute(notification) : null
  if (openTask && !target) return
  busy.value = true
  const active = context()
  let action = '通知标记已读'
  try {
    if (!notification.read) {
      await notificationApi.markAsRead(notification.id)
      if (!active()) return
      notification.read = true
    }
    action = '打开任务'
    if (target && active()) await router.push(target)
  } catch (error) {
    if (active()) showApiError(error, action)
  } finally {
    if (!disposed) busy.value = false
  }
}

async function markAllAsRead(): Promise<void> {
  if (busy.value) return
  busy.value = true
  const active = context()
  try {
    await notificationApi.markAllAsRead()
    if (!active()) return
    notifications.value.forEach(n => { n.read = true })
    ElMessage.success('已全部标记为已读')
  } catch (error) {
    if (active()) showApiError(error, '全部通知标记已读')
  } finally {
    if (!disposed) busy.value = false
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

onMounted(() => load())
</script>

<template>
  <main class="workspace-page">
    <PageHeader
      eyebrow="协作"
      title="通知中心"
    >
      <template #actions>
        <el-button :disabled="busy" @click="markAllAsRead">全部已读</el-button>
      </template>
    </PageHeader>
    <section v-loading="loading" class="notification-list">
      <el-empty v-if="!loading && notifications.length === 0" description="暂无通知" :image-size="64" />
      <div
        v-for="notification in notifications"
        :key="notification.id"
        class="notification-item"
        :class="{ unread: !notification.read }"
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
        <div class="notification-actions">
          <el-button v-if="notificationTaskRoute(notification)" :disabled="busy" @click="operate(notification, true)">
            {{ notification.read ? '打开任务' : '标为已读并打开任务' }}
          </el-button>
          <el-button v-if="!notification.read" :disabled="busy" @click="operate(notification)">标为已读</el-button>
          <span v-else class="notification-project">已读</span>
        </div>
      </div>
      <el-button
        v-if="hasMore && !loading"
        :disabled="busy"
        class="load-more"
        @click="load(true)"
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
  overflow-wrap: anywhere;
  padding: 16px;
  margin-bottom: 8px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  transition: all 0.2s;
}

.notification-item.unread {
  background-color: var(--color-primary-soft);
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
  color: var(--color-text-muted);
}

.notification-title {
  font-weight: 500;
  margin-bottom: 4px;
}

.notification-content {
  font-size: 14px;
  color: var(--color-text-secondary);
  margin-bottom: 4px;
}

.notification-project {
  font-size: 12px;
  color: var(--color-text-muted);
}

.notification-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.notification-actions .el-button + .el-button {
  margin-left: 0;
}

.load-more {
  width: 100%;
  margin-top: 16px;
}
</style>
