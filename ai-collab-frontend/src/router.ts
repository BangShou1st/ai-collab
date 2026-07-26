import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth-store'
import LoginView from './views/LoginView.vue'
import RegisterView from './modules/auth/RegisterView.vue'
import AccountView from './modules/account/AccountView.vue'
import ProjectListView from './modules/project/ProjectListView.vue'
import TaskBoardView from './modules/work/TaskBoardView.vue'
import MilestoneView from './modules/work/MilestoneView.vue'
import ProjectMembersView from './modules/project/ProjectMembersView.vue'
import InvitationAcceptView from './modules/project/InvitationAcceptView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/projects' },
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/register', component: RegisterView, meta: { public: true, title: '注册账号' } },
    { path: '/invite/:code', component: InvitationAcceptView, meta: { public: true, title: '项目邀请' } },
    { path: '/account', component: AccountView, meta: { title: '账号设置' } },
    { path: '/projects', component: ProjectListView, meta: { title: '我的项目' } },
    { path: '/projects/:projectId/board', component: TaskBoardView, meta: { title: '任务看板' } },
    { path: '/projects/:projectId/milestones', component: MilestoneView, meta: { title: '里程碑' } },
    { path: '/projects/:projectId/members', component: ProjectMembersView, meta: { title: '成员管理' } },
  ],
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : '高校竞赛项目协作平台'
  document.title = `${title}｜高校竞赛项目协作平台`
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  const isInvitationRoute = to.path.startsWith('/invite/')
  if (!auth.initialized && !isInvitationRoute) {
    await auth.initialize()
  }
  if (isInvitationRoute) return true
  if (!to.meta.public && !auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if ((to.path === '/login' || to.path === '/register') && auth.isAuthenticated) {
    return '/projects'
  }
  return true
})
