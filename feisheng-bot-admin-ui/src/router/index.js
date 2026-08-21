import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth.js'

const accessibleRoutes = [
  ['dashboard:view', '/dashboard'],
  ['channel:view', '/channel'],
  ['conversation:view', '/conversation'],
  ['playground:view', '/playground'],
  ['customer:view', '/customer'],
  ['intent:view', '/intent'],
  ['ai:model:view', '/ai/model'],
  ['ticket:view', '/ticket'],
  ['log:view', '/log'],
  ['knowledge:faq:list', '/knowledge/faq'],
  ['knowledge:upload:view', '/knowledge/upload'],
  ['knowledge:semantic:view', '/knowledge/semantic-units'],
  ['knowledge:quality:view', '/knowledge/quality-audit'],
  ['knowledge:unmatched:view', '/knowledge/unmatched'],
  ['system:user:list', '/system/user'],
  ['system:permission:assign', '/system/permission'],
  ['settings:rules:view', '/settings/rules'],
  ['settings:reply-strategy:view', '/settings/reply-strategy']
]

const routes = [
  { path: '/login', component: () => import('../views/login/index.vue') },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', component: () => import('../views/dashboard/index.vue'), meta: { title: '统计数据', permission: 'dashboard:view' } },
      { path: 'system/user', component: () => import('../views/system/UserList.vue'), meta: { title: '用户管理', permission: 'system:user:list' } },
      { path: 'system/permission', component: () => import('../views/system/PermissionAdmin.vue'), meta: { title: '权限分配', permission: 'system:permission:assign' } },
      { path: 'channel', component: () => import('../views/channel/ConfigView.vue'), meta: { title: '渠道配置', permission: 'channel:view' } },
      {
        path: 'knowledge',
        component: () => import('../views/knowledge/KnowledgeLayout.vue'),
        redirect: '/knowledge/faq',
        children: [
          { path: 'faq', component: () => import('../views/knowledge/FaqList.vue'), meta: { title: '常见问题管理', permission: 'knowledge:faq:list' } },
          { path: 'upload', component: () => import('../views/knowledge/Upload.vue'), meta: { title: '知识库上传', permission: 'knowledge:upload:view' } },
          { path: 'semantic-units', component: () => import('../views/knowledge/SemanticUnits.vue'), meta: { title: '结构化知识审核', permission: 'knowledge:semantic:view' } },
          { path: 'quality-audit', component: () => import('../views/knowledge/QualityAudit.vue'), meta: { title: '知识质量审计', permission: 'knowledge:quality:view' } },
          { path: 'unmatched', component: () => import('../views/knowledge/UnmatchedList.vue'), meta: { title: '未命中问题', permission: 'knowledge:unmatched:view' } }
        ]
      },
      { path: 'conversation', component: () => import('../views/conversation/List.vue'), meta: { title: '对话监控', permission: 'conversation:view' } },
      { path: 'conversation/:id', component: () => import('../views/conversation/Detail.vue'), meta: { title: '会话详情', permission: 'conversation:view' } },
      { path: 'customer', component: () => import('../views/customer/List.vue'), meta: { title: '客户管理', permission: 'customer:view' } },
      { path: 'customer/:id', component: () => import('../views/customer/Detail.vue'), meta: { title: '客户详情', permission: 'customer:view' } },
      { path: 'ai/model', component: () => import('../views/ai/ModelConfig.vue'), meta: { title: '智能模型', permission: 'ai:model:view' } },
      { path: 'log', component: () => import('../views/log/OperationLog.vue'), meta: { title: '操作日志', permission: 'log:view' } },
      { path: 'ticket', component: () => import('../views/ticket/TicketList.vue'), meta: { title: '工单管理', permission: 'ticket:view' } },
      { path: 'settings/rules', component: () => import('../views/settings/Rules.vue'), meta: { title: '安全规则', permission: 'settings:rules:view' } },
      { path: 'playground', component: () => import('../views/playground/index.vue'), meta: { title: '智能试聊', permission: 'playground:view' } },
      { path: 'intent', component: () => import('../views/intent/IntentAdmin.vue'), meta: { title: '意图管理', permission: 'intent:view' } },
      { path: 'settings/reply-strategy', component: () => import('../views/settings/ReplyStrategy.vue'), meta: { title: '回复策略', permission: 'settings:reply-strategy:view' } },
      { path: 'forbidden', component: { template: '<el-empty description="暂无可访问模块" />' }, meta: { title: '暂无权限' } }
    ]
  }
]

const router = createRouter({ history: createWebHashHistory(), routes })

function firstAccessiblePath(permissions) {
  return accessibleRoutes.find(([permission]) => permissions.includes(permission))?.[1] || '/forbidden'
}

router.beforeEach(async to => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login') return

  const auth = useAuthStore()
  try {
    if (!auth.userInfo) await auth.fetchUserInfo()
  } catch {
    return '/login'
  }

  const requiredPermission = to.meta.permission
  if (requiredPermission && !auth.hasPermission(requiredPermission)) {
    return firstAccessiblePath(auth.permissions)
  }
})

export default router
