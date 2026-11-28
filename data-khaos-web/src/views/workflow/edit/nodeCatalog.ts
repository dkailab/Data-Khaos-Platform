import type { WorkflowNode } from '@/types'

export type NodeContentKind = 'sql' | 'script' | 'pipeline' | 'quality' | 'delay' | 'nested' | 'dolphinscheduler'

export interface NodeTypeMeta {
  type: string
  label: string
  group: string
  color: string
  soft: string
  icon: string
  description: string
  contentKind: NodeContentKind
}

/** 画布节点类型目录：左侧面板、节点卡片、默认配置共用一份定义。 */
export const NODE_TYPES: NodeTypeMeta[] = [
  { type: 'SQL', label: 'SQL 脚本', group: '脚本', color: '#215dba', soft: '#e7f0ff',
    icon: 'Document', description: '执行 SQL，可自定义或引用已保存的 SQL 文件', contentKind: 'sql' },
  { type: 'SHELL', label: 'Shell 脚本', group: '脚本', color: '#0e9f6e', soft: '#e3f9f1',
    icon: 'Cpu', description: '执行 Shell 脚本', contentKind: 'script' },
  { type: 'PYTHON', label: 'Python 脚本', group: '脚本', color: '#7c5cff', soft: '#f0ecff',
    icon: 'MagicStick', description: '执行 Python 脚本', contentKind: 'script' },
  { type: 'DATA_OP', label: '数据算子', group: '脚本', color: '#dc6803', soft: '#fff3e0',
    icon: 'SetUp', description: '执行数据加工 SQL', contentKind: 'sql' },
  { type: 'PIPELINE', label: '数据同步', group: '数据处理', color: '#0ba5ec', soft: '#e5f6ff',
    icon: 'Refresh', description: '触发数据同步任务并等待完成', contentKind: 'pipeline' },
  { type: 'QUALITY', label: '数据质量', group: '数据处理', color: '#0e9f6e', soft: '#e3f9f1',
    icon: 'CircleCheck', description: '触发质量稽核并按门禁阻断下游', contentKind: 'quality' },
  { type: 'NESTED', label: '嵌套任务流', group: '其他', color: '#215dba', soft: '#e7f0ff',
    icon: 'Share', description: '触发另一个任务流并等待终态', contentKind: 'nested' },
  { type: 'DOLPHINSCHEDULER', label: 'DolphinScheduler', group: '其他', color: '#215dba', soft: '#e7f0ff',
    icon: 'Share', description: '运行管理员绑定的离线 DAG', contentKind: 'dolphinscheduler' },
  { type: 'DELAY', label: '延时器', group: '其他', color: '#667085', soft: '#f2f4f7',
    icon: 'Clock', description: '等待指定秒数后继续', contentKind: 'delay' },
]

export const NODE_W = 216
export const NODE_H = 66
export const CANVAS_MIN_W = 2200
export const CANVAS_MIN_H = 1200

export function nodeMeta(type?: string): NodeTypeMeta {
  return NODE_TYPES.find(item => item.type === type)
    ?? { type: type || 'UNKNOWN', label: type || '未知节点', group: '其他', color: '#98a2b3', soft: '#f2f4f7',
      icon: 'QuestionFilled', description: '', contentKind: 'script' }
}

export function defaultConfig(type: string): Record<string, any> {
  const meta = nodeMeta(type)
  switch (meta.contentKind) {
    case 'sql':
      return {
        taskMode: 'CUSTOM', datasourceId: '', sql: '', sqlSplitMode: 'NONE',
        externalObject: 'DRAFT', sqlFileId: '',
      }
    case 'pipeline':
      return { pipelineTaskId: '' }
    case 'quality':
      return { qualityTaskId: '', gate: 'BLOCK' }
    case 'nested':
      return { workflowId: '' }
    case 'dolphinscheduler':
      return {}
    case 'delay':
      return { delaySeconds: 60 }
    default:
      return { script: '' }
  }
}

export function createNode(type: string, posX: number, posY: number, index: number): WorkflowNode {
  const meta = nodeMeta(type)
  return {
    nodeCode: '',
    nodeName: `${meta.label}${index}`,
    description: '',
    nodeType: type,
    configJson: JSON.stringify(defaultConfig(type)),
    posX: Math.max(0, Math.round(posX)),
    posY: Math.max(0, Math.round(posY)),
    timeout: type === 'NESTED' ? 3600 : 0,
    retryCount: 0,
    retryInterval: 0,
    skipFlag: 0,
    executorGroup: 'default group',
    independentSchedule: 0,
    precondition: '',
    nodeParams: '',
  }
}

export function parseConfig(node: WorkflowNode): Record<string, any> {
  try {
    return node.configJson ? JSON.parse(node.configJson) : {}
  } catch {
    return {}
  }
}

export function writeConfig(node: WorkflowNode, config: Record<string, any>) {
  node.configJson = JSON.stringify(config)
}

export function nodeSummary(node: WorkflowNode): string {
  const config = parseConfig(node)
  const kind = nodeMeta(node.nodeType).contentKind
  if (kind === 'sql') {
    if (config.taskMode === 'EXTERNAL') {
      return config.sqlFileId ? `引用文件 ${config.sqlFileId.slice(0, 8)}…` : '未选择 SQL 文件'
    }
    return String(config.sql || '').replace(/\s+/g, ' ').trim() || '未填写 SQL'
  }
  if (kind === 'script') return String(config.script || '').split('\n')[0] || '未填写脚本'
  if (kind === 'pipeline') return config.pipelineTaskId ? `同步任务 ${config.pipelineTaskId}` : '未选择同步任务'
  if (kind === 'quality') return config.qualityTaskId ? `质量任务 ${config.qualityTaskId}` : '未选择质量任务'
  if (kind === 'nested') return config.workflowId ? `子任务流 ${config.workflowId}` : '未选择子任务流'
  if (kind === 'delay') return `等待 ${config.delaySeconds ?? 0} 秒`
  return ''
}

export const RUN_STATUS_META: Record<string, { text: string; color: string }> = {
  SUCCESS: { text: '成功', color: '#0e9f6e' },
  RUNNING: { text: '运行中', color: '#215dba' },
  FAILED: { text: '失败', color: '#d92d20' },
  SKIPPED: { text: '已跳过', color: '#98a2b3' },
  PENDING: { text: '等待中', color: '#dc6803' },
  RECOVERY_REQUIRED: { text: '待核查', color: '#b54708' },
  STOP: { text: '已停止', color: '#667085' },
}
