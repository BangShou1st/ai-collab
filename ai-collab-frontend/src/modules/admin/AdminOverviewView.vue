<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Bell, Cpu, Plus, Setting, User,
} from '@element-plus/icons-vue'
import { showApiError } from '../../api/api-result'
import { adminApi } from './admin-api'
import { adminAiProviderApi, type AdminSharedAiProvider } from '../../api/admin-ai-provider-api'
import { adminNotificationApi } from '../../api/admin-notification-api'
import { systemConfigApi, type SystemConfig, type SystemRuntimeStatus } from './system-config-api'
import type { AdminUser } from './types'

const router = useRouter()

const loading = ref(false)
const users = ref<AdminUser[]>([])
const providers = ref<AdminSharedAiProvider[]>([])
const unreadNotificationCount = ref(0)
const config = ref<SystemConfig | null>(null)
const runtimeStatus = ref<SystemRuntimeStatus | null>(null)

async function loadOverview(): Promise<void> {
  loading.value = true
  try {
    const [userRes, providerRes, unreadRes, configRes, statusRes] = await Promise.all([
      adminApi.users(),
      adminAiProviderApi.list(),
      adminNotificationApi.unreadCount(),
      systemConfigApi.getConfig(),
      systemConfigApi.getRuntimeStatus(),
    ])
    users.value = userRes.data
    providers.value = providerRes.data
    unreadNotificationCount.value = unreadRes.data.unreadCount
    config.value = configRes.data
    runtimeStatus.value = statusRes.data
  } catch (err) {
    showApiError(err, '平台概览加载')
  } finally {
    loading.value = false
  }
}

onMounted(loadOverview)
</script>

<template>
  <div v-loading="loading" class="overview-container">
    <header class="overview-header">
      <div>
        <h1 class="page-title">平台概览</h1>
        <p class="page-desc">系统运行健康度、账号分布与全站 AI 服务状态</p>
      </div>
      <div class="header-actions">
        <el-button type="primary" :icon="Plus" @click="router.push('/admin/ai-providers')">配置 AI 供应商</el-button>
        <el-button :icon="User" @click="router.push('/admin/users')">管理账号</el-button>
      </div>
    </header>

    <!-- 关键核心指标 -->
    <section class="stat-grid">
      <div class="stat-card" @click="router.push('/admin/users')">
        <div class="stat-icon user-icon"><el-icon><User /></el-icon></div>
        <div class="stat-info">
          <span class="stat-label">总注册账号</span>
          <strong class="stat-value">{{ users.length }}</strong>
          <span class="stat-hint">正常 {{ users.filter(u => u.status === 'ACTIVE').length }} 人</span>
        </div>
      </div>

      <div class="stat-card" @click="router.push('/admin/ai-providers')">
        <div class="stat-icon ai-icon"><el-icon><Cpu /></el-icon></div>
        <div class="stat-info">
          <span class="stat-label">全站共享供应商</span>
          <strong class="stat-value">{{ providers.filter(p => p.isShared).length }}</strong>
          <span class="stat-hint">已配置 {{ providers.length }} 个</span>
        </div>
      </div>

      <div class="stat-card" @click="router.push('/admin/notifications')">
        <div class="stat-icon alert-icon"><el-icon><Bell /></el-icon></div>
        <div class="stat-info">
          <span class="stat-label">未读管理通知</span>
          <strong class="stat-value" :class="{ 'has-unread': unreadNotificationCount > 0 }">
            {{ unreadNotificationCount }}
          </strong>
          <span class="stat-hint">{{ unreadNotificationCount > 0 ? '需处理事件' : '暂无异常' }}</span>
        </div>
      </div>

      <div class="stat-card" @click="router.push('/admin/settings')">
        <div class="stat-icon setting-icon"><el-icon><Setting /></el-icon></div>
        <div class="stat-info">
          <span class="stat-label">运行模式</span>
          <strong class="stat-value status-text">
            {{ config?.systemMaintenanceMode ? '维护中' : '正常服务' }}
          </strong>
          <span class="stat-hint">{{ config?.publicRegistrationEnabled ? '开放注册' : '邀请制' }}</span>
        </div>
      </div>
    </section>

    <!-- 核心板块与快速管理 -->
    <div class="dashboard-columns">
      <!-- 平台功能就绪状态 -->
      <el-card class="content-card" shadow="never">
        <template #header>
          <div class="card-title-bar">
            <strong>核心服务状态</strong>
            <el-tag :type="config?.systemMaintenanceMode ? 'danger' : 'success'" size="small">
              {{ config?.systemMaintenanceMode ? '维护模式' : '健康运行' }}
            </el-tag>
          </div>
        </template>
        <div class="feature-status-list">
          <div class="feature-row">
            <span class="feature-name">协作 Agent 引擎</span>
            <el-tag :type="config?.agentEnabled ? 'success' : 'info'" size="small">
              {{ config?.agentEnabled ? '已启用' : '已停用' }}
            </el-tag>
          </div>
          <div class="feature-row">
            <span class="feature-name">AI 对话问答</span>
            <el-tag :type="config?.chatEnabled ? 'success' : 'info'" size="small">
              {{ config?.chatEnabled ? '已启用' : '已停用' }}
            </el-tag>
          </div>
          <div class="feature-row">
            <span class="feature-name">Worker 调度并发</span>
            <span class="feature-val">{{ config?.workerConcurrency ?? 2 }} 并发进程</span>
          </div>
          <div class="feature-row">
            <span class="feature-name">RAG 语义阈值</span>
            <span class="feature-val">{{ config?.ragRetrievalThreshold ?? 0.55 }}（最大 {{ config?.ragMaxSources ?? 12 }} 源）</span>
          </div>
        </div>
      </el-card>

      <!-- 共享 AI 供应商摘要 -->
      <el-card class="content-card" shadow="never">
        <template #header>
          <div class="card-title-bar">
            <strong>全站 AI 供应商</strong>
            <el-button text type="primary" size="small" @click="router.push('/admin/ai-providers')">管理全部 →</el-button>
          </div>
        </template>
        <div v-if="providers.length === 0" class="empty-hint">
          暂未配置 AI 供应商，点击「配置 AI 供应商」添加并发布全站共享模型。
        </div>
        <div v-else class="provider-mini-list">
          <div v-for="item in providers.slice(0, 4)" :key="item.id" class="provider-mini-item">
            <div class="item-left">
              <strong>{{ item.name }}</strong>
              <small>{{ item.modelName }} · {{ item.providerType }}</small>
            </div>
            <div class="item-right">
              <el-tag v-if="item.isShared" type="success" size="small">全站共享</el-tag>
              <el-tag v-else type="info" size="small">私有</el-tag>
            </div>
          </div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
.overview-container {
  max-width: 1200px;
  margin: 0 auto;
}
.overview-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 24px;
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

/* 统计卡片栅格 */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 24px;
}
.stat-card {
  background: #ffffff;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
  border-radius: 12px;
  padding: 18px 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.stat-card:hover {
  border-color: #2563eb;
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(37, 99, 235, 0.08);
}
.stat-icon {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex-shrink: 0;
}
.user-icon { background: #eff6ff; color: #2563eb; }
.ai-icon { background: #f5f3ff; color: #7c3aed; }
.alert-icon { background: #fef2f2; color: #ef4444; }
.setting-icon { background: #f0fdf4; color: #16a34a; }

.stat-info {
  display: flex;
  flex-direction: column;
}
.stat-label {
  font-size: 12px;
  color: #6b7280;
  font-weight: 500;
}
.stat-value {
  font-size: 22px;
  font-weight: 700;
  color: #111827;
  margin: 2px 0;
}
.stat-value.has-unread {
  color: #ef4444;
}
.stat-value.status-text {
  font-size: 18px;
}
.stat-hint {
  font-size: 11px;
  color: #9ca3af;
}

/* 左右分栏 */
.dashboard-columns {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.content-card {
  border-radius: 12px;
  border: 1px solid var(--el-border-color-lighter, #e5e7eb);
}
.card-title-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.feature-status-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.feature-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid #f3f4f6;
}
.feature-row:last-child {
  border-bottom: none;
}
.feature-name {
  font-size: 13px;
  color: #374151;
}
.feature-val {
  font-size: 13px;
  color: #4b5563;
  font-weight: 500;
}

.provider-mini-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.provider-mini-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  background: #f9fafb;
  border-radius: 8px;
}
.item-left {
  display: flex;
  flex-direction: column;
}
.item-left strong {
  font-size: 13px;
  color: #1f2937;
}
.item-left small {
  font-size: 11px;
  color: #6b7280;
  margin-top: 2px;
}
.empty-hint {
  font-size: 13px;
  color: #9ca3af;
  text-align: center;
  padding: 24px 0;
}

@media (max-width: 1024px) {
  .stat-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .dashboard-columns {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
