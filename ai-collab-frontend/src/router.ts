import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth-store'
import LoginView from './views/LoginView.vue'
import AuthTestView from './views/AuthTestView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/auth-test' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/auth-test', component: AuthTestView },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    await auth.initialize()
  }
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.path === '/login' && auth.isAuthenticated) {
    return '/auth-test'
  }
  return true
})
