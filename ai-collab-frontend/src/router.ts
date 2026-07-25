import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth-store'
import LoginView from './views/LoginView.vue'
import AuthTestView from './views/AuthTestView.vue'
import RegisterView from './modules/auth/RegisterView.vue'
import AccountView from './modules/account/AccountView.vue'
import ProjectListView from './modules/project/ProjectListView.vue'
import TaskBoardView from './modules/work/TaskBoardView.vue'
import MilestoneView from './modules/work/MilestoneView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/register', component: RegisterView, meta: { public: true } },
    { path: '/account', component: AccountView },
    { path: '/projects', component: ProjectListView },
    { path: '/projects/:projectId/board', component: TaskBoardView },
    { path: '/projects/:projectId/milestones', component: MilestoneView },
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
  if ((to.path === '/login' || to.path === '/register') && auth.isAuthenticated) {
    return '/projects'
  }
  return true
})
