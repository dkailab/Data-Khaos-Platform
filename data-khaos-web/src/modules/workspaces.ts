import type { CategoryDef, FeatureDef } from './registry'

interface WorkspaceLink {
  title: string
  path: string
  icon: string
  match?: string
}

export interface Workspace {
  key: string
  title: string
  icon: string
  sections: { title: string; links: WorkspaceLink[] }[]
}

const portal: Workspace = {
  key: 'portal',
  title: '数据门户',
  icon: 'DataAnalysis',
  sections: [{ title: '门户导航', links: [
    { title: '门户首页', path: '/dashboard', icon: 'House' },
  ] }],
}

const dataMap = { title: '数据地图', path: '/assets', icon: 'Collection', match: '/assets/' }

const lineage: Workspace = {
  key: 'lineage',
  title: '数据血缘',
  icon: 'Share',
  sections: [
    { title: '血缘查询', links: [{ title: '表血缘分析', path: '/metadata/lineage', icon: 'Connection' }] },
    { title: '关系维护', links: [
      { title: 'SQL 自动分析', path: '/metadata/lineage?mode=sql', icon: 'MagicStick' },
      { title: '手工录入', path: '/metadata/lineage?mode=manual', icon: 'EditPen' },
    ] },
  ],
}

function visibleLinks(features: FeatureDef[], icon: string) {
  const seen = new Set<string>()
  return features.flatMap(feature => {
    if (!feature.path || seen.has(feature.path) || ['gov_lineage', 'ops_user', 'ops_perm'].includes(feature.key)) return []
    seen.add(feature.path)
    return [{
      title: feature.title,
      path: feature.path,
      icon: feature.icon || icon,
      match: feature.path === '/workflow/list' ? '/workflow/edit/' : `${feature.path}/`,
    }]
  })
}

export function buildWorkspaces(categories: CategoryDef[], system: CategoryDef, canConfig: boolean): Workspace[] {
  const list = [portal]
  for (const category of [...categories, system]) {
    const links = visibleLinks(category.features, category.icon)
    if (category.key === 'asset') {
      links.unshift(dataMap)
    }
    if (category.key === 'system' && canConfig) {
      links.push({ title: '门户模块配置', path: '/system/module-config', icon: 'Setting', match: '/system/module-config/' })
    }
    if (links.length) {
      list.push({
        key: category.key,
        title: category.title,
        icon: category.icon,
        sections: [{ title: category.title, links }],
      })
    }
    if (category.features.some(feature => feature.key === 'gov_lineage')) list.push(lineage)
  }
  if (!list.some(workspace => workspace.key === 'asset')) {
    list.push({ key: 'asset', title: '数据资产', icon: 'DataBoard',
      sections: [{ title: '数据资产', links: [dataMap] }] })
  }
  return list
}

export function workspaceHome(workspace: Workspace) {
  return workspace.sections[0].links[0].path
}

export function findWorkspace(workspaces: Workspace[], path: string) {
  const matches = (link: WorkspaceLink) => link.path === path || (link.match && path.startsWith(link.match))
  return workspaces.find(workspace => workspace.sections.some(section => section.links.some(matches))) || portal
}

const productGroups = [
  { key: 'engineering', title: '数据工程', products: ['ingress', 'dev', 'ops'] },
  { key: 'governance', title: '资产与治理', products: ['asset', 'lineage', 'govern'] },
  { key: 'consumption', title: '分析与平台', products: ['service', 'system'] },
]

export function groupProducts(workspaces: Workspace[]) {
  return productGroups.map(group => ({
    key: group.key,
    title: group.title,
    workspaces: group.products.flatMap(key => workspaces.filter(workspace => workspace.key === key)),
  })).filter(group => group.workspaces.length)
}

const descriptions: Record<string, string> = {
  ingress: '配置数据源，创建和管理同步任务。',
  dev: '编写 SQL，组织工作流与调度任务。',
  ops: '处理数据告警，进入日常运维。',
  asset: '查找数据表、字段、模型和指标。',
  lineage: '追溯表的来源、去向与转换关系。',
  govern: '管理元数据、质量规则、标准和字典。',
  service: '使用数据集、模型市场和 BI 仪表板。',
  system: '管理组织、权限、审批和通知。',
}

export function describeWorkspace(key: string) {
  return descriptions[key] || ''
}
