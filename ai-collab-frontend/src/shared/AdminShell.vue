<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  Bell, Cpu, Expand, Odometer, Setting, SwitchButton, User,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth-store'
import { adminNotificationApi } from '../api/admin-notification-api'
import { showApiError } from '../api/api-result'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const mobileNavOpen = ref(false)
const loggingOut = ref(false)
const unreadCount = ref(0)

const activeNavKey = computed<'overview' | 'users' | 'ai-providers' | 'notifications' | 'settings'>(() => {
  const p = route.path || ''
  if (p === '/admin' || p === '/admin/overview') return 'overview'
  if (p.startsWith('/admin/users')) return 'users'
  if (p.startsWith('/admin/ai-providers') || p.startsWith('/admin/embedding')) return 'ai-providers'
  if (p.startsWith('/admin/notifications')) return 'notifications'
  if (p.startsWith('/admin/settings') || p.startsWith('/admin/config')) return 'settings'
  return 'overview'
})

const adminInitial = computed(() => {
  const name = auth.currentUser?.displayName || auth.currentUser?.username || '管'
  return name.trim().charAt(0).toUpperCase() || '管'
})

const adminName = computed(() => auth.currentUser?.displayName || auth.currentUser?.username || '系统管理员')

function closeMobileNav(): void {
  if (!mobileNavOpen.value) return
  mobileNavOpen.value = false
  document.body.classList.remove('mobile-nav-open')
}

function openMobileNav(): void {
  mobileNavOpen.value = true
  document.body.classList.add('mobile-nav-open')
}

function onEscape(event: KeyboardEvent): void {
  if (event.key === 'Escape') closeMobileNav()
}

watch(
  () => route.fullPath,
  () => closeMobileNav(),
)

async function fetchUnreadCount(): Promise<void> {
  if (!auth.currentUser?.systemAdmin) return
  try {
    const res = await adminNotificationApi.unreadCount()
    unreadCount.value = res.data.unreadCount
  } catch {
    // 静默降级
  }
}

onMounted(() => {
  window.addEventListener('keydown', onEscape)
  fetchUnreadCount()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onEscape)
  document.body.classList.remove('mobile-nav-open')
})

async function logout(): Promise<void> {
  if (loggingOut.value) return
  try {
    await ElMessageBox.confirm('确认退出管理员工作区吗？', '退出登录', {
      confirmButtonText: '确认退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
    loggingOut.value = true
    await auth.logout()
    await router.replace('/login')
  } catch (err) {
    if (err === 'cancel' || err === 'close') return
    showApiError(err, '退出登录')
  } finally {
    loggingOut.value = false
  }
}
</script>

<template>
  <div class="admin-shell" :class="{ 'mobile-open': mobileNavOpen }">
    <!-- 移动端顶部状态栏 -->
    <header class="admin-mobile-bar">
      <button class="hamburger-btn" type="button" aria-label="打开管理导航" @click="openMobileNav">
        <el-icon><Expand /></el-icon>
      </button>
      <div class="admin-mobile-title">
        <span class="badge-dot" />
        <strong>AI Collab 管理平台</strong>
      </div>
    </header>

    <!-- 遮罩层 -->
    <div v-if="mobileNavOpen" class="admin-backdrop" @click="closeMobileNav" />

    <!-- 扁平五大一级入口侧栏 -->
    <aside class="admin-sidebar" aria-label="管理后台主导航">
      <div class="admin-brand">
        <div class="brand-badge">Admin</div>
        <div class="brand-text">
          <h2>管理后台</h2>
          <small>系统运维与全站 AI 配置</small>
        </div>
      </div>

      <nav class="admin-nav" aria-label="一级入口">
        <router-link
          to="/admin"
          class="nav-item"
          :class="{ 'is-active': activeNavKey === 'overview' }"
          title="平台概览"
        >
          <el-icon class="nav-icon"><Odometer /></el-icon>
          <span class="nav-label">平台概览</span>
        </router-link>

        <router-link
          to="/admin/users"
          class="nav-item"
          :class="{ 'is-active': activeNavKey === 'users' }"
          title="账号管理"
        >
          <el-icon class="nav-icon"><User /></el-icon>
          <span class="nav-label">账号管理</span>
        </router-link>

        <router-link
          to="/admin/ai-providers"
          class="nav-item"
          :class="{ 'is-active': activeNavKey === 'ai-providers' }"
          title="AI 供应商"
        >
          <el-icon class="nav-icon"><Cpu /></el-icon>
          <span class="nav-label">AI 供应商</span>
        </router-link>

        <router-link
          to="/admin/notifications"
          class="nav-item"
          :class="{ 'is-active': activeNavKey === 'notifications' }"
          title="通知中心"
        >
          <el-icon class="nav-icon"><Bell /></el-icon>
          <span class="nav-label">通知中心</span>
          <span v-if="unreadCount > 0" class="nav-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
        </router-link>

        <router-link
          to="/admin/settings"
          class="nav-item"
          :class="{ 'is-active': activeNavKey === 'settings' }"
          title="系统设置"
        >
          <el-icon class="nav-icon"><Setting /></el-icon>
          <span class="nav-label">系统设置</span>
        </router-link>
      </nav>

      <!-- 侧栏底部：管理员身份与退出操作 -->
      <footer class="admin-user-footer">
        <div class="admin-profile">
          <div class="avatar">{{ adminInitial }}</div>
          <div class="meta">
            <span class="name" :title="adminName">{{ adminName }}</span>
            <span class="role-tag">系统管理员</span>
          </div>
        </div>
        <button class="logout-btn" type="button" title="退出管理平台" @click="logout">
          <el-icon><SwitchButton /></el-icon>
        </button>
      </footer>
    </aside>

    <!-- 主工作区内容 -->
    <main class="admin-main">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.admin-shell {
  display: flex;
  min-height: 100vh;
  background-color: var(--el-bg-color-page, #f5f7fa);
  color: var(--el-text-color-primary, #303133);
}

/* 移动端顶栏 */
.admin-mobile-bar {
  display: none;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 52px;
  background: #ffffff;
  border-bottom: 1px solid var(--el-border-color-lighter, #ebeef5);
  padding: 0 16px;
  align-items: center;
  z-index: 100;
}
.hamburger-btn {
  background: transparent;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: var(--el-text-color-regular, #606266);
  display: flex;
  align-items: center;
  padding: 4px;
}
.admin-mobile-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 12px;
  font-size: 15px;
}
.badge-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: var(--el-color-primary, #409eff);
}

/* 侧边栏 */
.admin-sidebar {
  width: 240px;
  background: #ffffff;
  border-right: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  z-index: 90;
  flex-shrink: 0;
}

.admin-brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 24px 20px 20px;
  border-bottom: 1px solid var(--el-border-color-extra-light, #f2f6fc);
}
.brand-badge {
  background: linear-gradient(135deg, #1d4ed8, #2563eb);
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
  padding: 4px 8px;
  border-radius: 6px;
  text-transform: uppercase;
}
.brand-text h2 {
  font-size: 15px;
  margin: 0;
  font-weight: 600;
  color: var(--el-text-color-primary, #1f2937);
}
.brand-text small {
  display: block;
  font-size: 11px;
  color: var(--el-text-color-secondary, #909399);
  margin-top: 2px;
}

/* 扁平一级导航 */
.admin-nav {
  flex: 1;
  padding: 16px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  color: var(--el-text-color-regular, #4b5563);
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.15s ease;
  position: relative;
}
.nav-item:hover {
  background-color: var(--el-fill-color-light, #f3f4f6);
  color: var(--el-text-color-primary, #111827);
}
.nav-item.is-active {
  background-color: #eff6ff;
  color: #2563eb;
  font-weight: 600;
}
.nav-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background-color: #2563eb;
  border-radius: 0 4px 4px 0;
}
.nav-icon {
  font-size: 18px;
  flex-shrink: 0;
}
.nav-label {
  flex: 1;
}
.nav-badge {
  background-color: #ef4444;
  color: #ffffff;
  font-size: 11px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 10px;
  line-height: 16px;
}

/* 底部操作与身份 */
.admin-user-footer {
  padding: 16px;
  border-top: 1px solid var(--el-border-color-lighter, #ebeef5);
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #fafbfc;
}
.admin-profile {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: #e0e7ff;
  color: #3730a3;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}
.meta {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.meta .name {
  font-size: 13px;
  font-weight: 600;
  color: #1f2937;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.meta .role-tag {
  font-size: 11px;
  color: #6b7280;
}
.logout-btn {
  background: transparent;
  border: none;
  font-size: 16px;
  cursor: pointer;
  color: #9ca3af;
  padding: 6px;
  border-radius: 6px;
  transition: all 0.15s ease;
  display: flex;
  align-items: center;
}
.logout-btn:hover {
  background: #fee2e2;
  color: #dc2626;
}

/* 主内容区 */
.admin-main {
  flex: 1;
  min-width: 0;
  padding: 24px 32px 48px;
  overflow-y: auto;
}

/* 响应式样式适配 */
@media (max-width: 768px) {
  .admin-mobile-bar {
    display: flex;
  }
  .admin-sidebar {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    transform: translateX(-100%);
    transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    box-shadow: none;
  }
  .admin-shell.mobile-open .admin-sidebar {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
  }
  .admin-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(15, 23, 42, 0.45);
    z-index: 80;
    backdrop-filter: blur(2px);
  }
  .admin-main {
    padding: 72px 16px 32px;
  }
}
</style>
