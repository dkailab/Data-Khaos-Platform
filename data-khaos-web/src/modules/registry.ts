/**
 * 门户可插拔模块注册表（前端 Manifest）
 *
 * 设计说明：
 * - 每个功能模块在此注册（moduleKey 唯一），维护其展示名称、所属分类、图标、默认路由。
 * - 模块是否展示由后端 module_display_config（管理员可配置）决定，前端在 store 中将
 *   本注册表与后端配置合并后渲染导航与门户地图。
 * - `defaultMandatory` 标记系统必须模块（后端同样校验，管理员不可取消）。
 * - `path` 为空表示该功能尚未建设（导航中置灰「待建设」）。
 *
 * 新增模块：在此追加一条注册即可（可插拔），无需改动 Layout / 路由渲染逻辑。
 */
import type { ModuleCategory } from '@/types'

export interface FeatureDef {
  /** 模块唯一标识（与 module_display_config.module_key 对应） */
  key: string
  /** 展示名称 */
  title: string
  /** 路由路径（空=待建设） */
  path?: string
  /** 系统必须模块？（后端同时强校验，此处仅用于前端禁用展示开关） */
  mandatory?: boolean
  /** 图标(Element Plus 图标名) */
  icon?: string
}

export interface CategoryDef {
  key: ModuleCategory
  title: string
  icon: string
  features: FeatureDef[]
}

/** 六大业务分类 + 系统管理（系统管理单独渲染在右上角） */
export const CATEGORIES: CategoryDef[] = [
  {
    key: 'ingress',
    title: '数据接入',
    icon: 'Connection',
    features: [
      { key: 'ds_list', title: '数据源配置', path: '/datasource/list', mandatory: true, icon: 'Connection' },
      { key: 'ds_conn', title: '数据库连接配置', path: '/datasource/list' },
      { key: 'ds_sync', title: '数据同步任务', path: '/pipeline/task', icon: 'DataLine' },
      { key: 'ds_realtime', title: '实时数据接入' },
      { key: 'ds_offline', title: '离线数据采集' },
      { key: 'ds_api', title: '接口数据接入' },
      { key: 'ds_file', title: '文件数据导入' },
    ],
  },
  {
    key: 'dev',
    title: '数据开发',
    icon: 'EditPen',
    features: [
      { key: 'dev_sql', title: 'SQL 开发编辑器', path: '/query/query', mandatory: true, icon: 'EditPen' },
      { key: 'dev_schedule', title: '任务调度管理', path: '/schedule/job' },
      { key: 'dev_cron', title: '定时任务配置', path: '/schedule/job' },
      { key: 'dev_script', title: '数据脚本管理' },
      { key: 'dev_workflow', title: '工作流编排', path: '/workflow/list', icon: 'Share' },
      { key: 'dev_monitor', title: '任务监控' },
      { key: 'dev_version', title: '脚本版本管理' },
    ],
  },
  {
    key: 'govern',
    title: '数据治理',
    icon: 'Odometer',
    features: [
      { key: 'gov_quality', title: '数据质量校验', path: '/dquality/rule', icon: 'Odometer' },
      { key: 'gov_meta', title: '元数据管理', path: '/metadata/list' },
      { key: 'gov_lineage', title: '数据血缘分析', path: '/metadata/lineage' },
      { key: 'gov_dict', title: '数据字典管理', path: '/govern/dict', icon: 'Collection' },
      { key: 'gov_std', title: '数据标准配置', path: '/govern/standard', icon: 'DataLine' },
      { key: 'gov_mask', title: '数据脱敏管理' },
      { key: 'gov_dedup', title: '重复数据清洗' },
    ],
  },
  {
    key: 'asset',
    title: '数据资产',
    icon: 'DataBoard',
    features: [
      { key: 'asset_table', title: '数据表资产', path: '/metadata/structure' },
      { key: 'asset_metric', title: '指标资产', path: '/mart/metric' },
      { key: 'asset_perm', title: '资产权限管理', path: '/permission/table' },
      { key: 'asset_catalog', title: '资产目录查询' },
      { key: 'asset_label', title: '标签资产' },
      { key: 'asset_hot', title: '资产热度分析' },
      { key: 'asset_search', title: '资产检索' },
    ],
  },
  {
    key: 'service',
    title: '数据服务',
    icon: 'Share',
    features: [
      { key: 'svc_dataset', title: '数据集管理', path: '/visual/dataset', icon: 'Collection' },
      { key: 'svc_portrait', title: '用户画像', path: '/visual/portrait', icon: 'User' },
      { key: 'svc_report', title: '报表服务', path: '/visual/dashboard' },
      { key: 'svc_adhoc', title: '自助取数', path: '/visual/adhoc' },
      { key: 'svc_market', title: '模型市场', path: '/mart/market' },
      { key: 'svc_api', title: '数据接口服务' },
      { key: 'svc_publish', title: 'API 发布管理' },
      { key: 'svc_share', title: '数据共享服务' },
      { key: 'svc_export', title: '数据导出服务' },
    ],
  },
  {
    key: 'ops',
    title: '监控运维',
    icon: 'Monitor',
    features: [
      { key: 'ops_monitor', title: '任务运行监控', icon: 'Monitor' },
      { key: 'ops_alert', title: '数据告警中心', path: '/notification/send' },
      { key: 'ops_log', title: '日志查询' },
      { key: 'ops_resource', title: '系统资源监控' },
      { key: 'ops_perm', title: '权限管理', path: '/permission/table' },
      { key: 'ops_user', title: '用户管理', path: '/system/user' },
      { key: 'ops_audit', title: '操作审计' },
    ],
  },
]

/** 系统管理（右上角独立入口，均为必须模块） */
export const SYSTEM_CATEGORY: CategoryDef = {
  key: 'system',
  title: '系统管理',
  icon: 'Setting',
  features: [
    { key: 'sys_user', title: '用户管理', path: '/system/user', mandatory: true, icon: 'User' },
    { key: 'sys_role', title: '角色管理', path: '/system/role', mandatory: true, icon: 'Avatar' },
    { key: 'sys_menu', title: '菜单管理', path: '/system/menu', mandatory: true, icon: 'Menu' },
    { key: 'sys_org', title: '组织管理', path: '/system/org', mandatory: true, icon: 'OfficeBuilding' },
    { key: 'sys_approval', title: '审批中心', path: '/approval/apply' },
    { key: 'sys_notify', title: '通知中心', path: '/notification/template' },
  ],
}