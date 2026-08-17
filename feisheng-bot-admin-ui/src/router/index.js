import { createRouter, createWebHashHistory } from 'vue-router'
const routes = [
  { path: '/login', component: () => import('../views/login/index.vue') },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/dashboard/index.vue'), meta: { title: '统计数据' } },
      { path: 'system/user', component: () => import('../views/system/UserList.vue'), meta: { title: '用户管理' } },
      { path: 'channel', component: () => import('../views/channel/ConfigView.vue'), meta: { title: '渠道配置' } },
      {
        path: 'knowledge',
        component: () => import('../views/knowledge/KnowledgeLayout.vue'),
        redirect: '/knowledge/faq',
        children: [
          { path: 'faq', component: () => import('../views/knowledge/FaqList.vue'), meta: { title: '常见问题管理' } },
          { path: 'upload', component: () => import('../views/knowledge/Upload.vue'), meta: { title: '知识库上传' } },
          { path: 'semantic-units', component: () => import('../views/knowledge/SemanticUnits.vue'), meta: { title: '结构化知识审核' } },
          { path: 'quality-audit', component: () => import('../views/knowledge/QualityAudit.vue'), meta: { title: '知识质量审计' } },
          { path: 'unmatched', component: () => import('../views/knowledge/UnmatchedList.vue'), meta: { title: '未命中问题' } }
        ]
      },
      { path: 'conversation', component: () => import('../views/conversation/List.vue'), meta: { title: '对话监控' } },
      { path: 'conversation/:id', component: () => import('../views/conversation/Detail.vue'), meta: { title: '会话详情' } },
      { path: 'customer', component: () => import('../views/customer/List.vue'), meta: { title: '客户管理' } },
      { path: 'customer/:id', component: () => import('../views/customer/Detail.vue'), meta: { title: '客户详情' } },
      { path: 'ai/model', component: () => import('../views/ai/ModelConfig.vue'), meta: { title: '智能模型' } },
      { path: 'log', component: () => import('../views/log/OperationLog.vue'), meta: { title: '操作日志' } },
      { path: 'ticket', component: () => import('../views/ticket/TicketList.vue'), meta: { title: '工单管理' } },
      { path: 'settings/rules', component: () => import('../views/settings/Rules.vue'), meta: { title: '安全规则' } },
      { path: 'playground', component: () => import('../views/playground/index.vue'), meta: { title: '智能试聊' } },
      { path: 'intent', component: () => import('../views/intent/IntentAdmin.vue'), meta: { title: '意图管理' } },
      { path: 'settings/reply-strategy', component: () => import('../views/settings/ReplyStrategy.vue'), meta: { title: '回复策略' } },
      { path: 'system/role', component: () => import('../views/system/RoleAdmin.vue'), meta: { title: '角色管理' } },
      { path: 'system/permission', component: () => import('../views/system/PermissionAdmin.vue'), meta: { title: '权限管理' } },
    ]
  }
]
const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to, from) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) { return '/login' }
})
export default router
