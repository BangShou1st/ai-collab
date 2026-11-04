<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, Check, Finished, Refresh, Right } from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import { adminNotificationApi, type AdminNotification } from '../../api/admin-notification-api'

const router = useRouter()

const loading = ref(false)
const notifications = ref<AdminNotification[]>([])
const filterTab = ref<'unread' | 'all'>('unread')

const filteredList = computed(() => {
  if (filterTab.value === 'unread') {
    return notifications.value.filter((n) => !n.isRead)
  }
  return notifications.value
})

const unreadCount = computed(() => notifications.value.filter((n) => !n.isRead).length)

async function loadNotifications(): Promise<void> {
  loading.value = true
  try {
    const res = await adminNotificationApi.list(false, 100)
    notifications.value = res.data
  } catch (err) {
    showApiError(err, '管理通知加载')
  } finally {
    loading.value = false
  }
}

async function markRead(item: AdminNotification): Promise<void> {
  if (item.isRead) return
  try {
    await adminNotificationApi.markAsRead(item.id)
    item.isRead = true
  } catch (err) {
    showApiError(err, '标记已读')
  }
}

async function markAllRead(): Promise<void> {
  try {
    await adminNotificationApi.markAllAsRead()
    notifications.value.forEach((n) => (n.isRead = true))
    ElMessage.success('已全部标记为已读')
  } catch (err) {
    showApiError(err, '批量标记已读')
  }
}

async function handleAction(item: AdminNotification): Promise<void> {
  await markRead(item)
  if (item.targetPath) {
    await router.push(item.targetPath)
  }
}

function categoryLabel(cat: string): string {
  switch (cat) {
    case 'AI_PROVIDER': return 'AI 供应商'
    case 'EMBEDDING': return '向量索引'
    case 'ACCOUNT': return '账号安全'
    case 'MAINTENANCE': return '系统维护'
    default: return '系统通知'
  }
}

function formatTime(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('zh-CN', {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    })
  } catch {
    return iso
  }
}

onMounted(loadNotifications)
</script>

<template>
  <div class="notifications-container">
    <header class="page-header">
      <div>
        <h1 class="page-title">系统通知中心</h1>
        <p class="page-desc">平台运行事件、模型发布与系统异常告警，与普通项目日常协作消息物理隔离</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="loadNotifications">刷新</el-button>
        <el-button
          type="primary"
          plain
          :icon="Finished"
          :disabled="unreadCount === 0"
          @click="markAllRead"
        >
          全部标为已读
        </el-button>
      </div>
    </header>

    <!-- 筛选 Tab -->
    <div class="filter-tab-bar">
      <el-radio-group v-model="filterTab" size="default">
        <el-radio-button label="unread">
          未读事件 ({{ unreadCount }})
        </el-radio-button>
        <el-radio-button label="all">
          全部历史 ({{ notifications.length }})
        </el-radio-button>
      </el-radio-group>
    </div>

    <!-- 通知列表 -->
    <div v-loading="loading" class="notification-list-area">
      <div v-if="filteredList.length === 0" class="empty-state">
        <el-icon class="empty-icon"><Bell /></el-icon>
        <p>{{ filterTab === 'unread' ? '暂无未读系统事件，系统运行良好' : '暂无系统通知记录' }}</p>
      </div>

      <div v-else class="notification-stack">
        <div
          v-for="item in filteredList"
          :key="item.id"
          class="notification-card"
          :class="{ 'is-unread': !item.isRead, [`level-${item.level.toLowerCase()}`]: true }"
        >
          <div class="card-left">
            <div class="tag-row">
              <el-tag
                size="small"
                :type="item.level === 'ERROR' ? 'danger' : (item.level === 'WARNING' ? 'warning' : 'primary')"
                effect="dark"
              >
                {{ item.level === 'ERROR' ? '异常告警' : (item.level === 'WARNING' ? '重要提醒' : '通知') }}
              </el-tag>
              <el-tag size="small" effect="plain">{{ categoryLabel(item.category) }}</el-tag>
              <span class="time-stamp">{{ formatTime(item.createdAt) }}</span>
            </div>
            <h3 class="event-title">{{ item.title }}</h3>
            <p v-if="item.content" class="event-content">{{ item.content }}</p>
          </div>

          <div class="card-right">
            <el-button
              v-if="item.targetPath"
              type="primary"
              size="small"
              text
              :icon="Right"
              @click="handleAction(item)"
            >
              前往处理
            </el-button>
            <el-button
              v-if="!item.isRead"
              size="small"
              text
              :icon="Check"
              @click="markRead(item)"
            >
              标为已读
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notifications-container {
  max-width: 1000px;
  margin: 0 auto;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: wrap;
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
.header-actions {
  display: flex;
  gap: 10px;
}

.filter-tab-bar {
  margin-bottom: 16px;
}

.empty-state {
  background: #ffffff;
  border: 1px dashed #d1d5db;
  border-radius: 12px;
  padding: 60px 20px;
  text-align: center;
  color: #6b7280;
}
.empty-icon {
  font-size: 44px;
  color: #9ca3af;
  margin-bottom: 12px;
}

.notification-stack {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.notification-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 10px;
  padding: 16px 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  transition: all 0.15s ease;
}
.notification-card.is-unread {
  background: #fdfefe;
  border-left: 4px solid #2563eb;
}
.notification-card.is-unread.level-error {
  border-left-color: #ef4444;
}
.notification-card.is-unread.level-warning {
  border-left-color: #f59e0b;
}

.card-left {
  flex: 1;
  min-width: 0;
}
.tag-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.time-stamp {
  font-size: 12px;
  color: #9ca3af;
  margin-left: 4px;
}
.event-title {
  font-size: 15px;
  font-weight: 600;
  color: #111827;
  margin: 0 0 4px;
}
.event-content {
  font-size: 13px;
  color: #4b5563;
  margin: 0;
  line-height: 1.5;
}

.card-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .notification-card {
    flex-direction: column;
    align-items: flex-start;
  }
  .card-right {
    width: 100%;
    justify-content: flex-end;
    margin-top: 8px;
  }
}
</style>
