import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth-store'
import LoginView from './views/LoginView.vue'
import RegisterView from './modules/auth/RegisterView.vue'
import AccountView from './modules/account/AccountView.vue'
import HomeView from './modules/home/HomeView.vue'
import UserAiSettingsView from './modules/ai/UserAiSettingsView.vue'
import EmbeddingAdminView from './modules/admin/EmbeddingAdminView.vue'
import ProjectListView from './modules/project/ProjectListView.vue'
import ProjectIntegrationsView from './modules/project/ProjectIntegrationsView.vue'
import DashboardView from './modules/dashboard/DashboardView.vue'
import TaskBoardView from './modules/work/TaskBoardView.vue'
import MilestoneView from './modules/work/MilestoneView.vue'
import ProjectMembersView from './modules/project/ProjectMembersView.vue'
import InvitationAcceptView from './modules/project/InvitationAcceptView.vue'
import DocumentView from './modules/document/DocumentView.vue'
import KnowledgeView from './modules/knowledge/KnowledgeView.vue'
import PlanningView from './modules/planning/PlanningView.vue'
import AuditLogView from './modules/audit/AuditLogView.vue'
import NotificationView from './modules/notification/NotificationView.vue'
import GanttView from './modules/work/GanttView.vue'
import DependencyGraphView from './modules/work/DependencyGraphView.vue'
import CalendarView from './modules/work/CalendarView.vue'
import MemberLoadView from './modules/work/MemberLoadView.vue'
import WeeklyReportView from './modules/work/WeeklyReportView.vue'
import RiskAnalysisView from './modules/work/RiskAnalysisView.vue'
import PlanComparisonView from './modules/work/PlanComparisonView.vue'
import AgentView from './modules/agent/AgentView.vue'
import ProjectModelSettings from './modules/project/ProjectModelSettings.vue'
import AdminView from './modules/admin/AdminView.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', component: LoginView, meta: { public: true, title: '登录' } },
    { path: '/register', component: RegisterView, meta: { public: true, title: '注册账号' } },
    { path: '/invite/:code', component: InvitationAcceptView, meta: { public: true, title: '项目邀请' } },
    { path: '/home', component: HomeView, meta: { title: '工作台' } },
    { path: '/settings/ai', component: UserAiSettingsView, meta: { title: 'AI 设置' } },
    { path: '/account', component: AccountView, meta: { title: '账号设置' } },
    { path: '/projects', component: ProjectListView, meta: { title: '我的项目' } },
    { path: '/projects/:projectId', redirect: (to) => `/projects/${to.params.projectId}/dashboard` },
    { path: '/projects/:projectId/dashboard', component: DashboardView, meta: { title: '项目概览' } },
    { path: '/projects/:projectId/board', component: TaskBoardView, meta: { title: '任务看板' } },
    { path: '/projects/:projectId/gantt', component: GanttView, meta: { title: '甘特图' } },
    { path: '/projects/:projectId/dependency-graph', component: DependencyGraphView, meta: { title: '依赖图' } },
    { path: '/projects/:projectId/calendar', component: CalendarView, meta: { title: '日历视图' } },
    { path: '/projects/:projectId/member-load', component: MemberLoadView, meta: { title: '成员负载' } },
    { path: '/projects/:projectId/weekly-report', component: WeeklyReportView, meta: { title: 'AI 周报' } },
    { path: '/projects/:projectId/risk-analysis', component: RiskAnalysisView, meta: { title: '延期风险分析' } },
    { path: '/projects/:projectId/plan-comparison', component: PlanComparisonView, meta: { title: '规划对比' } },
    { path: '/projects/:projectId/milestones', component: MilestoneView, meta: { title: '里程碑' } },
    { path: '/projects/:projectId/members', component: ProjectMembersView, meta: { title: '成员管理' } },
    { path: '/projects/:projectId/documents', component: DocumentView, meta: { title: '项目文档' } },
    { path: '/projects/:projectId/knowledge', component: KnowledgeView, meta: { title: '知识问答' } },
    { path: '/projects/:projectId/ai-planning', component: PlanningView, meta: { title: 'AI 任务规划' } },
    { path: '/projects/:projectId/agent', component: AgentView, meta: { title: '项目协作 Agent' } },
    { path: '/projects/:projectId/model-settings', component: ProjectModelSettings, meta: { title: '模型配置' } },
    { path: '/projects/:projectId/integrations', component: ProjectIntegrationsView, meta: { title: '集成与 MCP' } },
    { path: '/projects/:projectId/audit-logs', component: AuditLogView, meta: { title: '操作日志' } },
    { path: '/notifications', component: NotificationView, meta: { title: '通知中心' } },
    { path: '/admin', component: AdminView, meta: { title: '管理中心', systemAdmin: true } },
    { path: '/admin/embedding', component: EmbeddingAdminView, meta: { title: 'AI Infrastructure', systemAdmin: true } },
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
    return '/home'
  }
  if (to.meta.systemAdmin && !auth.currentUser?.systemAdmin) {
    return '/projects'
  }
  return true
})
