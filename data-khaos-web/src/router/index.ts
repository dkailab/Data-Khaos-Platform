import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { pinia } from '@/stores'
import { useUserStore } from '@/stores/user'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/Login.vue'),
    meta: { title: '登录' },
  },
  {
    path: '/',
    component: () => import('@/layouts/Layout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/Dashboard.vue'),
        meta: { title: '首页' },
      },
      // 系统管理
      {
        path: 'system/user',
        name: 'SystemUser',
        component: () => import('@/views/system/user/UserList.vue'),
        meta: { title: '用户管理' },
      },
      {
        path: 'system/role',
        name: 'SystemRole',
        component: () => import('@/views/system/role/RoleList.vue'),
        meta: { title: '角色管理' },
      },
      {
        path: 'system/menu',
        name: 'SystemMenu',
        component: () => import('@/views/system/menu/MenuList.vue'),
        meta: { title: '菜单管理' },
      },
      {
        path: 'system/org',
        name: 'SystemOrg',
        component: () => import('@/views/system/org/OrgList.vue'),
        meta: { title: '组织管理' },
      },
      {
        path: 'system/module-config',
        name: 'ModuleConfig',
        component: () => import('@/views/system/moduleconfig/ModuleConfigList.vue'),
        meta: { title: '门户模块配置' },
      },
      // 权限管理
      {
        path: 'permission/policy-row',
        name: 'RowPolicy',
        component: () => import('@/views/permission/row-policy/RowPolicyList.vue'),
        meta: { title: '行权限策略' },
      },
      {
        path: 'permission/policy-column',
        name: 'ColumnPolicy',
        component: () => import('@/views/permission/column-policy/ColumnPolicyList.vue'),
        meta: { title: '列权限策略' },
      },
      {
        path: 'permission/table',
        name: 'TablePermission',
        component: () => import('@/views/permission/table/TablePermissionList.vue'),
        meta: { title: '表权限' },
      },
      // 审批中心
      {
        path: 'approval/apply',
        name: 'ApprovalApply',
        component: () => import('@/views/approval/apply/ApplyList.vue'),
        meta: { title: '我的申请' },
      },
      {
        path: 'approval/todo',
        name: 'ApprovalTodo',
        component: () => import('@/views/approval/todo/TodoList.vue'),
        meta: { title: '待办审批' },
      },
      // 数据源配置
      {
        path: 'datasource/list',
        name: 'DatasourceList',
        component: () => import('@/views/datasource/list/DatasourceList.vue'),
        meta: { title: '数据源配置' },
      },
      // 数据管道
      {
        path: 'pipeline/task',
        name: 'PipelineTask',
        component: () => import('@/views/pipeline/task/TaskList.vue'),
        meta: { title: '数据同步任务' },
      },
      // 元数据中心
      {
        path: 'metadata/structure',
        name: 'MetadataStructure',
        component: () => import('@/views/metadata/structure/Structure.vue'),
        meta: { title: '库表结构' },
      },
      {
        path: 'metadata/lineage',
        name: 'MetadataLineage',
        component: () => import('@/views/metadata/lineage/Lineage.vue'),
        meta: { title: '血缘关系' },
      },
      {
        path: 'metadata/search',
        name: 'MetadataSearch',
        component: () => import('@/views/metadata/search/Search.vue'),
        meta: { title: '元数据搜索' },
      },
      // 数据治理
      {
        path: 'govern/dict',
        name: 'GovernDict',
        component: () => import('@/views/govern/dict/DictList.vue'),
        meta: { title: '数据字典管理' },
      },
      {
        path: 'govern/standard',
        name: 'GovernStandard',
        component: () => import('@/views/govern/standard/StandardList.vue'),
        meta: { title: '数据标准配置' },
      },
      // 数据集市
      {
        path: 'mart/market',
        name: 'MartMarket',
        component: () => import('@/views/mart/market/Market.vue'),
        meta: { title: '模型市场' },
      },
      {
        path: 'mart/market/:id',
        name: 'MartMarketDetail',
        component: () => import('@/views/mart/market/ModelDetail.vue'),
        meta: { title: '模型详情' },
      },
      {
        path: 'mart/model',
        name: 'MartModel',
        component: () => import('@/views/mart/model/ModelList.vue'),
        meta: { title: '模型管理' },
      },
      {
        path: 'mart/metric',
        name: 'MartMetric',
        component: () => import('@/views/mart/metric/MetricList.vue'),
        meta: { title: '指标管理' },
      },
      {
        path: 'mart/dimension',
        name: 'MartDimension',
        component: () => import('@/views/mart/dimension/DimensionList.vue'),
        meta: { title: '维度管理' },
      },
      // SQL 查询
      {
        path: 'query/query',
        name: 'QueryWorkbench',
        component: () => import('@/views/query/query/QueryWorkbench.vue'),
        meta: { title: '查询工作台' },
      },
      // 可视化 - 数据集
      {
        path: 'visual/dataset',
        name: 'VisualDataset',
        component: () => import('@/views/visual/dataset/DatasetList.vue'),
        meta: { title: '数据集管理' },
      },
      // 可视化 - 用户画像
      {
        path: 'visual/portrait',
        name: 'VisualPortrait',
        component: () => import('@/views/visual/portrait/PortraitList.vue'),
        meta: { title: '用户画像' },
      },
      // 可视化 - 仪表板
      {
        path: 'visual/dashboard',
        name: 'VisualDashboard',
        component: () => import('@/views/visual/dashboard/DashboardList.vue'),
        meta: { title: '仪表板管理' },
      },
      {
        path: 'visual/dashboard/edit/:id',
        name: 'VisualDashboardEdit',
        component: () => import('@/views/visual/dashboard/DashboardEditor.vue'),
        meta: { title: '编辑仪表板' },
      },
      {
        path: 'visual/dashboard/view/:id',
        name: 'VisualDashboardView',
        component: () => import('@/views/visual/dashboard/DashboardView.vue'),
        meta: { title: '预览仪表板' },
      },
      {
        path: 'visual/dashboard/chart/:dashboardId/:itemId',
        name: 'VisualChartBuilder',
        component: () => import('@/views/visual/dashboard/ChartBuilder.vue'),
        meta: { title: '图表绘制' },
      },
      {
        path: 'visual/adhoc',
        name: 'VisualAdhoc',
        component: () => import('@/views/visual/adhoc/AdhocWorkbench.vue'),
        meta: { title: '即席分析' },
      },
      // 数据质量
      {
        path: 'dquality/rule',
        name: 'DqRule',
        component: () => import('@/views/dquality/rule/RuleList.vue'),
        meta: { title: '质量规则' },
      },
      {
        path: 'dquality/task',
        name: 'DqTask',
        component: () => import('@/views/dquality/task/TaskList.vue'),
        meta: { title: '质量任务' },
      },
      {
        path: 'dquality/snapshot',
        name: 'DqSnapshot',
        component: () => import('@/views/dquality/snapshot/SnapshotList.vue'),
        meta: { title: '稽核报告' },
      },
      // 调度中心
      {
        path: 'schedule/job',
        name: 'ScheduleJob',
        component: () => import('@/views/schedule/job/JobList.vue'),
        meta: { title: '调度任务' },
      },
      // 工作流编排
      {
        path: 'workflow/list',
        name: 'WorkflowList',
        component: () => import('@/views/workflow/list/WorkflowList.vue'),
        meta: { title: '工作流编排' },
      },
      {
        path: 'workflow/edit/create',
        name: 'WorkflowEditCreate',
        component: () => import('@/views/workflow/edit/WorkflowEdit.vue'),
        meta: { title: '新建工作流' },
      },
      {
        path: 'workflow/edit/:id',
        name: 'WorkflowEdit',
        component: () => import('@/views/workflow/edit/WorkflowEdit.vue'),
        meta: { title: '编排工作流' },
      },
      // 通知中心
      {
        path: 'notification/template',
        name: 'NotifyTemplate',
        component: () => import('@/views/notification/template/TemplateList.vue'),
        meta: { title: '通知模板' },
      },
      {
        path: 'notification/send',
        name: 'NotifySend',
        component: () => import('@/views/notification/send/Send.vue'),
        meta: { title: '发送通知' },
      },
      {
        path: 'notification/subscription',
        name: 'NotifySubscription',
        component: () => import('@/views/notification/subscription/SubscriptionList.vue'),
        meta: { title: '订阅管理' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 登录守卫：未登录强制跳登录页（以 localStorage 为准，不受 store 初始化时序影响）
router.beforeEach((to, _from, next) => {
  const userStore = useUserStore(pinia)
  const hasToken = !!localStorage.getItem('dk_token')

  if (hasToken) {
    if (to.path === '/login') {
      // 已登录访问登录页：回到 redirect 参数或首页
      next((to.query.redirect as string) || '/dashboard')
      return
    }
    next()
    return
  }

  // 未登录：确保 Pinia 状态也被清除（防止拦截器清过后 store 残留）
  if (userStore.userInfo || userStore.token) {
    userStore.reset()
  }
  if (to.path !== '/login') {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  next()
})

router.afterEach((to) => {
  const title = to.meta?.title as string | undefined
  document.title = title ? `${title} - Data-Khaos-Platform 数据治理平台` : 'Data-Khaos-Platform 数据治理平台'
})

export default router
