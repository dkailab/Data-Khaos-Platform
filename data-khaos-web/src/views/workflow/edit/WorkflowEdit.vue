<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Aim, ArrowLeft, Check, CircleCheck, Delete, FolderOpened, Grid, MoreFilled, Plus, Position,
  RefreshRight, Search, Upload, VideoPlay, ZoomIn, ZoomOut,
} from '@element-plus/icons-vue'
import { pageDatasources } from '@/api/datasource'
import { pageTasks as pageDqTasks } from '@/api/dquality'
import { pagePipelineTasks } from '@/api/pipeline'
import { listWorkspaceResources } from '@/api/project-group'
import {
  getWorkflowGraph, getWorkflowSqlFile, listWorkflowOptions, listWorkflowSqlFiles, listWorkflowTasks,
  offlineWorkflow, publishWorkflow, runWorkflow, saveWorkflowGraph,
} from '@/api/workflow'
import type {
  MetaDatasource, WorkflowDef, WorkflowEdge, WorkflowNode, WorkflowSqlFile, WorkflowTaskView,
} from '@/types'
import { projectLocation, realtimeProjectKey } from '@/views/realtime/projectContext'
import WorkflowRevisionDialog from '../list/WorkflowRevisionDialog.vue'
import NodeSettings from './NodeSettings.vue'
import NodeDebugDialog from './NodeDebugDialog.vue'
import DependencyAnalysisDialog from './DependencyAnalysisDialog.vue'
import RunWorkflowDialog from './RunWorkflowDialog.vue'
import SqlFileEditor from './SqlFileEditor.vue'
import SqlFilePicker from './SqlFilePicker.vue'
import WorkflowDependencyDialog from './WorkflowDependencyDialog.vue'
import WorkflowSettingsDialog from './WorkflowSettingsDialog.vue'
import WorkflowTasksDialog from './WorkflowTasksDialog.vue'
import {
  CANVAS_MIN_H, CANVAS_MIN_W, NODE_H, NODE_TYPES, NODE_W, RUN_STATUS_META, createNode, nodeMeta,
  nodeSummary, parseConfig, writeConfig,
} from './nodeCatalog'

const props = defineProps<{ inProjectWorkspace?: boolean }>()
const workspace = inject(realtimeProjectKey, null)
const route = useRoute()
const router = useRouter()

const canSchedule = computed(() => !props.inProjectWorkspace || !!workspace?.canRun.value)

const def = reactive<WorkflowDef>({
  name: '', status: 0, description: '', cronExpression: '', params: '', code: '',
})
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])
const selectedCode = ref<string>()
const selectedEdgeKey = ref<string>()
const datasources = ref<Array<{ id: string; dsName: string; dsType?: string }>>([])
const sqlFiles = ref<WorkflowSqlFile[]>([])
const workflows = ref<WorkflowDef[]>([])
const pipelines = ref<Array<{ id: string; name?: string }>>([])
const qualityTasks = ref<Array<{ id: string; name?: string }>>([])
const taskStatus = reactive<Record<string, WorkflowTaskView>>({})
const resourceKeyword = ref('')
const leftTab = ref<'resource' | 'node'>('resource')
const zoom = ref(1)
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const dirty = ref(false)

const wrapRef = ref<HTMLElement>()
const stageRef = ref<HTMLElement>()
const loadedId = ref('')
let suppressDirty = false

const revisionDialog = ref(false)
const depsDialog = ref(false)
const tasksDialog = ref(false)
const settingsDialog = ref(false)
const analysisVisible = ref(false)
const runDialogVisible = ref(false)
const runScope = ref<string[]>()
const debugVisible = ref(false)
const sqlPickerVisible = ref(false)
const sqlFileEditorVisible = ref(false)
const sqlFileEditorFile = ref<WorkflowSqlFile>()
const sqlFilePrefill = reactive({ name: '', content: '' })

const projectId = computed(() => props.inProjectWorkspace ? workspace?.project.value : def.projectId)

/* ------------------------------ 画布坐标与尺寸 ------------------------------ */

const stageW = computed(() => Math.max(CANVAS_MIN_W, 0,
  ...nodes.value.map(node => (node.posX ?? 0) + NODE_W + 320)))
const stageH = computed(() => Math.max(CANVAS_MIN_H, 0,
  ...nodes.value.map(node => (node.posY ?? 0) + NODE_H + 260)))
const stageWrapStyle = computed(() => ({
  width: `${Math.round(stageW.value * zoom.value)}px`,
  height: `${Math.round(stageH.value * zoom.value)}px`,
}))
const stageStyle = computed(() => ({
  width: `${stageW.value}px`,
  height: `${stageH.value}px`,
  transform: `scale(${zoom.value})`,
}))
const zoomPercent = computed(() => Math.round(zoom.value * 100))

const activeNode = computed(() => nodes.value.find(node => node.nodeCode === selectedCode.value))
const activeEdge = computed(() => edges.value.find(edge =>
  `${edge.fromCode}->${edge.toCode}` === selectedEdgeKey.value))

const edgeList = computed(() => edges.value.flatMap(edge => {
  const path = edgePath(edge.fromCode, edge.toCode)
  return path ? [{ key: `${edge.fromCode}->${edge.toCode}`, path, edge }] : []
}))

function edgePath(fromCode: string, toCode: string) {
  const from = nodes.value.find(node => node.nodeCode === fromCode)
  const to = nodes.value.find(node => node.nodeCode === toCode)
  if (!from || !to) return ''
  const sx = (from.posX ?? 0) + NODE_W
  const sy = (from.posY ?? 0) + NODE_H / 2
  const ex = to.posX ?? 0
  const ey = (to.posY ?? 0) + NODE_H / 2
  const bend = Math.max(60, Math.abs(ex - sx) / 2)
  return `M ${sx} ${sy} C ${sx + bend} ${sy} ${ex - bend} ${ey} ${ex} ${ey}`
}

function clientToStage(event: { clientX: number; clientY: number }) {
  const wrap = wrapRef.value
  if (!wrap) return { x: 0, y: 0 }
  const rect = wrap.getBoundingClientRect()
  return {
    x: (event.clientX - rect.left + wrap.scrollLeft) / zoom.value,
    y: (event.clientY - rect.top + wrap.scrollTop) / zoom.value,
  }
}

/* ------------------------------ 节点增删与拖拽 ------------------------------ */

function nextCode() {
  let sequence = 1
  while (nodes.value.some(node => node.nodeCode === `n${sequence}`)) sequence++
  return `n${sequence}`
}

function addNode(type: string, x?: number, y?: number) {
  const wrap = wrapRef.value
  const fallback = { x: (wrap?.scrollLeft || 0) / zoom.value + 320, y: (wrap?.scrollTop || 0) / zoom.value + 220 }
  const offset = nodes.value.length * 18
  const node = createNode(type, x ?? fallback.x + offset, y ?? fallback.y + offset, nodes.value.length + 1)
  node.nodeCode = nextCode()
  node.wfId = def.id
  nodes.value.push(node)
  selectedCode.value = node.nodeCode
  selectedEdgeKey.value = undefined
}

function addEdge(fromCode: string, toCode: string) {
  if (!fromCode || !toCode || fromCode === toCode) return
  if (edges.value.some(edge => edge.fromCode === fromCode && edge.toCode === toCode)) {
    ElMessage.warning('该依赖已存在')
    return
  }
  if (wouldCycle(fromCode, toCode)) {
    ElMessage.warning('禁止形成环路')
    return
  }
  edges.value.push({ fromCode, toCode })
  selectedCode.value = undefined
  selectedEdgeKey.value = `${fromCode}->${toCode}`
}

function wouldCycle(fromCode: string, toCode: string) {
  const adjacency = new Map<string, string[]>()
  edges.value.forEach(edge => {
    if (!adjacency.has(edge.fromCode)) adjacency.set(edge.fromCode, [])
    adjacency.get(edge.fromCode)!.push(edge.toCode)
  })
  if (!adjacency.has(fromCode)) adjacency.set(fromCode, [])
  adjacency.get(fromCode)!.push(toCode)
  const visited = new Set<string>([toCode])
  const stack = [toCode]
  while (stack.length) {
    const current = stack.pop()!
    for (const next of adjacency.get(current) || []) {
      if (next === fromCode) return true
      if (!visited.has(next)) {
        visited.add(next)
        stack.push(next)
      }
    }
  }
  return false
}

async function removeNode(node: WorkflowNode) {
  const related = edges.value.filter(edge => edge.fromCode === node.nodeCode || edge.toCode === node.nodeCode)
  if (related.length) {
    try {
      await ElMessageBox.confirm(`节点「${node.nodeName}」有 ${related.length} 条依赖，删除后一并移除，确认？`,
        '删除节点', { type: 'warning' })
    } catch {
      return
    }
  }
  nodes.value = nodes.value.filter(item => item.nodeCode !== node.nodeCode)
  edges.value = edges.value.filter(edge => edge.fromCode !== node.nodeCode && edge.toCode !== node.nodeCode)
  if (selectedCode.value === node.nodeCode) selectedCode.value = undefined
  if (selectedEdgeKey.value?.includes(node.nodeCode!)) selectedEdgeKey.value = undefined
}

function removeEdge(fromCode: string, toCode: string) {
  edges.value = edges.value.filter(edge => !(edge.fromCode === fromCode && edge.toCode === toCode))
  if (selectedEdgeKey.value === `${fromCode}->${toCode}`) selectedEdgeKey.value = undefined
}

let dragState: { code: string; startX: number; startY: number; ox: number; oy: number } | null = null
function startDrag(node: WorkflowNode, event: MouseEvent) {
  if ((event.target as HTMLElement).closest('.node-port, .node-remove')) return
  selectNode(node)
  dragState = {
    code: node.nodeCode!, startX: event.clientX, startY: event.clientY,
    ox: node.posX ?? 0, oy: node.posY ?? 0,
  }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}
function onDragMove(event: MouseEvent) {
  if (!dragState) return
  const node = nodes.value.find(item => item.nodeCode === dragState!.code)
  if (!node) return
  node.posX = Math.max(0, Math.round(dragState.ox + (event.clientX - dragState.startX) / zoom.value))
  node.posY = Math.max(0, Math.round(dragState.oy + (event.clientY - dragState.startY) / zoom.value))
}
function onDragEnd() {
  dragState = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

const connect = reactive({ active: false, from: '', x: 0, y: 0 })
const tempPath = computed(() => {
  if (!connect.active) return ''
  const from = nodes.value.find(node => node.nodeCode === connect.from)
  if (!from) return ''
  const sx = (from.posX ?? 0) + NODE_W
  const sy = (from.posY ?? 0) + NODE_H / 2
  const bend = Math.max(60, Math.abs(connect.x - sx) / 2)
  return `M ${sx} ${sy} C ${sx + bend} ${sy} ${connect.x - bend} ${connect.y} ${connect.x} ${connect.y}`
})
function startConnect(node: WorkflowNode, event: MouseEvent) {
  const point = clientToStage(event)
  connect.active = true
  connect.from = node.nodeCode!
  connect.x = point.x
  connect.y = point.y
  window.addEventListener('mousemove', onConnectMove)
  window.addEventListener('mouseup', onConnectEnd)
}
function onConnectMove(event: MouseEvent) {
  const point = clientToStage(event)
  connect.x = point.x
  connect.y = point.y
}
function onConnectEnd(event: MouseEvent) {
  connect.active = false
  window.removeEventListener('mousemove', onConnectMove)
  window.removeEventListener('mouseup', onConnectEnd)
  const element = document.elementFromPoint(event.clientX, event.clientY) as HTMLElement | null
  const target = element?.closest('[data-node-code]') as HTMLElement | null
  const toCode = target?.dataset.nodeCode
  if (!toCode) return
  addEdge(connect.from, toCode)
}

function selectNode(node: WorkflowNode) {
  selectedCode.value = node.nodeCode
  selectedEdgeKey.value = undefined
}
function selectEdge(key: string) {
  selectedEdgeKey.value = key
  selectedCode.value = undefined
}
function clearSelection() {
  selectedCode.value = undefined
  selectedEdgeKey.value = undefined
}

function onPaletteDragStart(type: string, event: DragEvent) {
  event.dataTransfer?.setData('text/dk-node', type)
  event.dataTransfer?.setData('text/plain', type)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy'
}
function onDrop(event: DragEvent) {
  const type = event.dataTransfer?.getData('text/dk-node')
  if (!type) return
  const point = clientToStage(event)
  addNode(type, point.x - NODE_W / 2, point.y - NODE_H / 2)
}

/* ------------------------------ 缩放与布局 ------------------------------ */

function setZoom(value: number) {
  zoom.value = Math.min(1.6, Math.max(0.35, Math.round(value * 100) / 100))
}
function zoomBy(delta: number) {
  setZoom(zoom.value + delta)
}
function resetZoom() {
  setZoom(1)
}
async function fitView() {
  const wrap = wrapRef.value
  if (!wrap || !nodes.value.length) return
  const minX = Math.min(...nodes.value.map(node => node.posX ?? 0))
  const maxX = Math.max(...nodes.value.map(node => (node.posX ?? 0) + NODE_W))
  const minY = Math.min(...nodes.value.map(node => node.posY ?? 0))
  const maxY = Math.max(...nodes.value.map(node => (node.posY ?? 0) + NODE_H))
  const scale = Math.min(
    (wrap.clientWidth - 120) / Math.max(1, maxX - minX),
    (wrap.clientHeight - 120) / Math.max(1, maxY - minY),
    1.15,
  )
  setZoom(scale)
  await nextTick()
  wrap.scrollLeft = Math.max(0, minX * zoom.value - 60)
  wrap.scrollTop = Math.max(0, minY * zoom.value - 60)
}

/** 旧版编辑器节点宽 190、横向间距 180，升级到 216 宽后会重叠；检测到重叠就重新布局。 */
function hasOverlap(list: WorkflowNode[]) {
  for (let i = 0; i < list.length; i++) {
    for (let j = i + 1; j < list.length; j++) {
      const a = list[i]
      const b = list[j]
      if (Math.abs((a.posX ?? 0) - (b.posX ?? 0)) < NODE_W + 24
        && Math.abs((a.posY ?? 0) - (b.posY ?? 0)) < NODE_H + 24) {
        return true
      }
    }
  }
  return false
}

function autoLayout(notify = true, fit = true) {
  if (!nodes.value.length) return
  const indegree = new Map<string, number>()
  const adjacency = new Map<string, string[]>()
  nodes.value.forEach(node => {
    indegree.set(node.nodeCode, 0)
    adjacency.set(node.nodeCode, [])
  })
  edges.value.forEach(edge => {
    if (!indegree.has(edge.fromCode) || !indegree.has(edge.toCode)) return
    adjacency.get(edge.fromCode)!.push(edge.toCode)
    indegree.set(edge.toCode, indegree.get(edge.toCode)! + 1)
  })
  const level = new Map<string, number>()
  const queue: string[] = []
  indegree.forEach((degree, code) => {
    if (degree === 0) {
      level.set(code, 0)
      queue.push(code)
    }
  })
  let head = 0
  while (head < queue.length) {
    const current = queue[head++]
    for (const next of adjacency.get(current) || []) {
      level.set(next, Math.max(level.get(next) ?? 0, (level.get(current) ?? 0) + 1))
      const degree = indegree.get(next)! - 1
      indegree.set(next, degree)
      if (degree === 0) queue.push(next)
    }
  }
  nodes.value.forEach(node => {
    if (!level.has(node.nodeCode)) level.set(node.nodeCode, 0)
  })
  const groups = new Map<number, WorkflowNode[]>()
  nodes.value.forEach(node => {
    const value = level.get(node.nodeCode) ?? 0
    if (!groups.has(value)) groups.set(value, [])
    groups.get(value)!.push(node)
  })
  groups.forEach((list, value) => {
    list.forEach((node, index) => {
      node.posX = 100 + value * 300
      node.posY = 90 + index * 120
    })
  })
  if (notify) ElMessage.success('已按依赖层级整理布局')
  // 打开既有图时只整理坐标、不自动缩放，避免宽图被压到 50% 以下看不清
  if (fit) void nextTick(() => fitView())
}

/* ------------------------------ 资源与状态 ------------------------------ */

const filteredSqlFiles = computed(() => {
  const keyword = resourceKeyword.value.trim().toLowerCase()
  if (!keyword) return sqlFiles.value
  return sqlFiles.value.filter(file => file.name.toLowerCase().includes(keyword)
    || (file.folder || '').toLowerCase().includes(keyword)
    || (file.description || '').toLowerCase().includes(keyword))
})
const sqlGroups = computed(() => {
  const groups = new Map<string, WorkflowSqlFile[]>()
  filteredSqlFiles.value.forEach(file => {
    const folder = file.folder || '/SQL文件'
    if (!groups.has(folder)) groups.set(folder, [])
    groups.get(folder)!.push(file)
  })
  return [...groups.entries()]
    .sort((a, b) => a[0].localeCompare(b[0]))
    .map(([folder, files]) => ({ folder, files: [...files].sort((a, b) => a.name.localeCompare(b.name, 'zh-Hans-CN')) }))
})
const filteredWorkflows = computed(() => {
  const keyword = resourceKeyword.value.trim().toLowerCase()
  return workflows.value
    .filter(workflow => !keyword || workflow.name.toLowerCase().includes(keyword))
    .slice(0, 100)
})
const paletteGroups = computed(() => {
  const groups = new Map<string, typeof NODE_TYPES>()
  NODE_TYPES.forEach(item => {
    if (!groups.has(item.group)) groups.set(item.group, [])
    groups.get(item.group)!.push(item)
  })
  return [...groups.entries()].map(([name, items]) => ({ name, items }))
})

function folderName(path: string) {
  const segments = path.split('/').filter(Boolean)
  return segments[segments.length - 1] || path
}
function statusOf(code: string) {
  return taskStatus[code]?.lastStatus
}
function statusColor(code: string) {
  const status = statusOf(code)
  return status ? (RUN_STATUS_META[status]?.color || '#98a2b3') : '#d0d5dd'
}
function statusText(code: string) {
  const status = statusOf(code)
  return status ? (RUN_STATUS_META[status]?.text || status) : ''
}
const lastRunLabel = computed(() => {
  const list = Object.values(taskStatus).filter(item => item.lastStartTime)
  if (!list.length) return '未运行'
  list.sort((a, b) => String(b.lastStartTime).localeCompare(String(a.lastStartTime)))
  const status = list[0].lastStatus || ''
  return RUN_STATUS_META[status]?.text || status || '未运行'
})
const referenceCount = computed(() => nodes.value.filter(node => {
  const config = parseConfig(node)
  return nodeMeta(node.nodeType).contentKind === 'sql' && config.taskMode === 'EXTERNAL'
}).length)

async function refreshTaskStatus() {
  if (!def.id) return
  try {
    const list = await listWorkflowTasks(def.id)
    Object.keys(taskStatus).forEach(key => delete taskStatus[key])
    list.forEach(item => { taskStatus[item.nodeCode] = item })
  } catch {
    // 任务状态是辅助信息，失败不阻断编辑
  }
}
async function refreshSqlFiles() {
  try {
    sqlFiles.value = await listWorkflowSqlFiles(projectId.value)
  } catch {
    sqlFiles.value = []
  }
}
async function refreshOptions() {
  try {
    workflows.value = await listWorkflowOptions(projectId.value)
  } catch {
    workflows.value = []
  }
}
async function loadDatasources() {
  if (props.inProjectWorkspace && projectId.value) {
    const resources = await listWorkspaceResources(projectId.value)
    datasources.value = resources
      .filter(row => row.resourceType === 'DATASOURCE' && row.available !== false)
      .map(row => ({ id: row.resourceId, dsName: row.name || row.resourceId }))
  } else {
    const page = await pageDatasources({ current: 1, size: 200 })
    datasources.value = page.records.map((row: MetaDatasource) => ({
      id: row.id!, dsName: row.dsName || row.id!, dsType: row.dsType,
    }))
  }
}
async function loadTaskOptions() {
  try {
    const page = await pagePipelineTasks({ current: 1, size: 200 })
    pipelines.value = (page.records || []).map((row: any) => ({ id: row.id, name: row.taskName || row.name }))
  } catch { pipelines.value = [] }
  try {
    const page = await pageDqTasks({ current: 1, size: 200 })
    qualityTasks.value = (page.records || []).map((row: any) => ({ id: row.id, name: row.taskName || row.name }))
  } catch { qualityTasks.value = [] }
}

/* ------------------------------ 加载与保存 ------------------------------ */

function editLocation(id: string) {
  return props.inProjectWorkspace
    ? projectLocation(`/jobs/offline/edit/${encodeURIComponent(id)}`, projectId.value || '')
    : `/workflow/edit/${encodeURIComponent(id)}`
}

async function load() {
  const routeId = String(route.params.id || 'create')
  loadedId.value = routeId
  suppressDirty = true
  loading.value = true
  selectedCode.value = undefined
  selectedEdgeKey.value = undefined
  Object.keys(taskStatus).forEach(key => delete taskStatus[key])
  try {
    if (!routeId || routeId === 'create') {
      Object.assign(def, {
        id: undefined, name: '未命名任务流', code: '', status: 0, description: '', cronExpression: '',
        params: '', publishedRevision: undefined, publishedAt: undefined,
        projectId: props.inProjectWorkspace ? projectId.value : undefined,
      })
      nodes.value = []
      edges.value = []
      const node = createNode('SQL', 460, 280, 1)
      node.nodeCode = nextCode()
      nodes.value.push(node)
      selectedCode.value = node.nodeCode
      zoom.value = 1
    } else {
      const graph = await getWorkflowGraph(routeId)
      if (props.inProjectWorkspace && graph.def.projectId !== projectId.value) {
        ElMessage.error('任务流与当前项目不一致')
        void router.push(projectLocation('/jobs/offline', projectId.value || ''))
        return
      }
      Object.assign(def, graph.def)
      nodes.value = (graph.nodes || []).map(node => ({
        ...node,
        posX: node.posX ?? 0,
        posY: node.posY ?? 0,
        skipFlag: node.skipFlag ?? 0,
        independentSchedule: node.independentSchedule ?? 0,
      }))
      edges.value = (graph.edges || []).map(edge => ({ ...edge }))
      if (nodes.value.length > 1
        && (nodes.value.every(node => !node.posX && !node.posY) || hasOverlap(nodes.value))) {
        autoLayout(false, false)
      }
      void refreshTaskStatus()
      if (!props.inProjectWorkspace) void refreshSqlFiles()
    }
  } catch {
    ElMessage.error('加载任务流失败')
  } finally {
    await nextTick()
    loading.value = false
    suppressDirty = false
    dirty.value = false
  }
}

function validateGraphForRun(): string | null {
  for (const node of nodes.value) {
    if ((node.skipFlag ?? 0) === 1) continue
    const config = parseConfig(node)
    const kind = nodeMeta(node.nodeType).contentKind
    if (!node.nodeName?.trim()) return '存在未命名的节点'
    if (kind === 'sql') {
      if (config.taskMode === 'EXTERNAL' && !config.sqlFileId) {
        return `节点「${node.nodeName}」是外部任务，但未选择 SQL 文件`
      }
      if (config.taskMode !== 'EXTERNAL' && !String(config.sql || '').trim()) {
        return `节点「${node.nodeName}」未填写 SQL`
      }
    }
    if (kind === 'pipeline' && !config.pipelineTaskId) return `节点「${node.nodeName}」未选择同步任务`
    if (kind === 'quality' && !config.qualityTaskId) return `节点「${node.nodeName}」未选择质量任务`
    if (kind === 'nested' && !config.workflowId) return `节点「${node.nodeName}」未选择子任务流`
  }
  return null
}

async function save(silent = false): Promise<boolean> {
  if (saving.value) return false
  if (!def.name.trim()) {
    ElMessage.warning('请填写任务流名称')
    return false
  }
  if (!nodes.value.length) {
    ElMessage.warning('请至少添加一个节点')
    return false
  }
  const codes = new Set<string>()
  for (const node of nodes.value) {
    if (!node.nodeCode) {
      ElMessage.warning('存在未生成编码的节点')
      return false
    }
    if (codes.has(node.nodeCode)) {
      ElMessage.warning(`节点编码重复：${node.nodeCode}`)
      return false
    }
    codes.add(node.nodeCode)
  }
  saving.value = true
  try {
    const saved = await saveWorkflowGraph({
      def: { ...def },
      nodes: nodes.value.map(node => ({ ...node })),
      edges: edges.value.map(edge => ({ ...edge })),
    })
    def.id = saved.id
    def.code = saved.code ?? def.code
    def.status = saved.status ?? def.status
    def.publishedRevision = saved.publishedRevision ?? def.publishedRevision
    def.publishedAt = saved.publishedAt ?? def.publishedAt
    loadedId.value = saved.id!
    suppressDirty = true
    const graph = await getWorkflowGraph(saved.id!)
    Object.assign(def, graph.def)
    nodes.value = (graph.nodes || []).map(node => ({
      ...node, posX: node.posX ?? 0, posY: node.posY ?? 0,
      skipFlag: node.skipFlag ?? 0, independentSchedule: node.independentSchedule ?? 0,
    }))
    edges.value = (graph.edges || []).map(edge => ({ ...edge }))
    if (selectedCode.value && !nodes.value.some(node => node.nodeCode === selectedCode.value)) {
      selectedCode.value = undefined
    }
    await nextTick()
    suppressDirty = false
    dirty.value = false
    if (route.params.id === 'create') {
      void router.replace(editLocation(saved.id!))
    }
    await refreshOptions()
    if (!silent) ElMessage.success('已保存')
    return true
  } catch {
    suppressDirty = false
    return false
  } finally {
    saving.value = false
  }
}

async function ensureSaved() {
  if (!def.id || dirty.value) return save(true)
  return true
}

function workflowParams(): Record<string, any> | null {
  if (!def.params || !def.params.trim()) return {}
  try {
    const parsed = JSON.parse(def.params)
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      ElMessage.warning('运行参数必须是 JSON 对象')
      return null
    }
    return parsed
  } catch {
    ElMessage.warning('运行参数不是合法 JSON，请先在「参数设置」中修正')
    return null
  }
}

/** 调试弹窗用的默认参数：解析失败时静默回退，避免打开弹窗就弹警告。 */
const debugParams = computed(() => {
  if (!def.params || !def.params.trim()) return {}
  try {
    const parsed = JSON.parse(def.params)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
})

function today() {
  const now = new Date()
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}
function newRequestId() {
  const cryptoObject = window.crypto
  if (cryptoObject && typeof cryptoObject.randomUUID === 'function') return 'web-' + cryptoObject.randomUUID()
  return 'web-' + Date.now() + '-' + Math.random().toString(16).slice(2)
}

const runScopeLabel = computed(() => runScope.value?.length
  ? `节点「${activeNode.value?.nodeName || runScope.value[0]}」及其全部下游`
  : '整个任务流')

async function openRunDialog(startCodes?: string[]) {
  if (!(await ensureSaved())) return
  const problem = validateGraphForRun()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const params = workflowParams()
  if (params === null) return
  runScope.value = startCodes
  runDialogVisible.value = true
}

async function executeRun(payload: { bizDate: string; params: Record<string, any> }) {
  running.value = true
  try {
    const result = await runWorkflow(def.id!, {
      requestId: newRequestId(), bizDate: payload.bizDate, params: payload.params, startCodes: runScope.value,
    })
    if (result.state === 'REJECTED') {
      ElMessage.warning(result.rejectMessage || '运行被拒绝')
    } else if (result.state === 'DUPLICATE') {
      ElMessage.info(`该请求已受理，运行 ID：${result.runId || '—'}`)
    } else {
      ElMessage.success(`已提交运行：${result.runId || '—'}（业务日期 ${payload.bizDate}）`)
      window.setTimeout(() => void refreshTaskStatus(), 3000)
    }
  } finally {
    running.value = false
  }
}

function runDownstream() {
  if (!activeNode.value) {
    ElMessage.warning('请先选择一个节点')
    return
  }
  void openRunDialog([activeNode.value.nodeCode!])
}

async function publish() {
  if (!(await save(true))) return
  const problem = validateGraphForRun()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  try {
    await ElMessageBox.confirm(
      `发布会自动生成版本快照并允许调度运行，确认发布？`, '发布任务流', { type: 'warning' })
  } catch {
    return
  }
  const result = await publishWorkflow(def.id!)
  suppressDirty = true
  def.status = result.status
  def.publishedRevision = result.revisionNumber
  def.publishedAt = result.publishedAt
  await nextTick()
  suppressDirty = false
  dirty.value = false
  ElMessage.success(`已发布 v${result.revisionNumber}`)
  await refreshOptions()
}

async function offline() {
  try {
    await ElMessageBox.confirm('下线后停止调度，已产生的运行记录保留，确认下线？', '下线任务流', { type: 'warning' })
  } catch {
    return
  }
  const result = await offlineWorkflow(def.id!)
  suppressDirty = true
  def.status = result.status
  def.publishedAt = undefined
  await nextTick()
  suppressDirty = false
  dirty.value = false
  ElMessage.success('已下线')
  await refreshOptions()
}

/* ------------------------------ 对话框动作 ------------------------------ */

async function openSqlFileEditor(file?: WorkflowSqlFile, prefill?: { name?: string; content?: string }) {
  let target = file
  if (file?.id) {
    try {
      target = await getWorkflowSqlFile(file.id)
    } catch {
      ElMessage.error('读取 SQL 文件失败，可能已被删除')
      return
    }
  }
  sqlFileEditorFile.value = target
  sqlFilePrefill.name = prefill?.name || ''
  sqlFilePrefill.content = prefill?.content || ''
  sqlFileEditorVisible.value = true
}
function openNewSqlFile() {
  openSqlFileEditor(undefined)
}
function saveNodeAsFile(content: string) {
  const name = (activeNode.value?.nodeName || 'sql').replace(/[\\/:*?"<>|]/g, '_').slice(0, 120)
  openSqlFileEditor(undefined, { name: name.endsWith('.sql') ? name : name + '.sql', content })
}
async function onFileSaved(file: WorkflowSqlFile) {
  await refreshSqlFiles()
  const node = activeNode.value
  if (node && file.id) {
    const config = parseConfig(node)
    if (nodeMeta(node.nodeType).contentKind === 'sql' && config.taskMode === 'EXTERNAL' && !config.sqlFileId) {
      config.sqlFileId = file.id
      writeConfig(node, config)
    }
  }
}
async function onFileDeleted(id: string) {
  await refreshSqlFiles()
  nodes.value.forEach(node => {
    const config = parseConfig(node)
    if (config.sqlFileId === id) {
      config.sqlFileId = ''
      writeConfig(node, config)
    }
  })
}
function onSqlFileSelected(file: WorkflowSqlFile) {
  const node = activeNode.value
  if (!node) return
  const config = parseConfig(node)
  config.taskMode = 'EXTERNAL'
  config.sqlFileId = file.id
  writeConfig(node, config)
}

async function openDeps() {
  if (!(await ensureSaved())) return
  depsDialog.value = true
}
async function openTasks() {
  if (!def.id) {
    ElMessage.warning('请先保存任务流')
    return
  }
  tasksDialog.value = true
}
async function openVersions() {
  if (!(await ensureSaved())) return
  revisionDialog.value = true
}
async function openDebug(code?: string) {
  if (!code) {
    ElMessage.warning('请先选择要调试的节点')
    return
  }
  // 调试读取服务端已保存的节点配置，先落盘避免「改了 SQL 却跑了旧内容」
  if (!(await ensureSaved())) return
  selectedCode.value = code
  debugVisible.value = true
}

async function onCommand(command: string) {
  if (command === 'versions') await openVersions()
  else if (command === 'deps') await openDeps()
  else if (command === 'settings') settingsDialog.value = true
  else if (command === 'tasks') await openTasks()
  else if (command === 'analysis') analysisVisible.value = true
  else if (command === 'runs') {
    if (props.inProjectWorkspace) void router.push(projectLocation('/jobs/offline', projectId.value || ''))
    else void router.push('/workflow/list')
  } else if (command === 'offline') await offline()
}

async function switchWorkflow(id: string) {
  if (id === def.id) return
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前修改尚未保存，切换后丢失，确认继续？', '未保存的修改', { type: 'warning' })
    } catch {
      return
    }
  }
  void router.push(editLocation(id))
}

async function backToList() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前修改尚未保存，确认离开？', '未保存的修改', { type: 'warning' })
    } catch {
      return
    }
  }
  if (props.inProjectWorkspace) void router.push(projectLocation('/jobs/offline', projectId.value || ''))
  else void router.push('/workflow/list')
}

/* ------------------------------ 键盘与生命周期 ------------------------------ */

function onKeydown(event: KeyboardEvent) {
  const target = event.target as HTMLElement
  const editing = target && (['INPUT', 'TEXTAREA'].includes(target.tagName) || target.isContentEditable)
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 's') {
    event.preventDefault()
    void save()
    return
  }
  if (editing) return
  if (event.key === 'Delete' || event.key === 'Backspace') {
    if (activeEdge.value) {
      event.preventDefault()
      removeEdge(activeEdge.value.fromCode, activeEdge.value.toCode)
    } else if (activeNode.value) {
      event.preventDefault()
      void removeNode(activeNode.value)
    }
  }
  if (event.key === 'Escape') clearSelection()
}

watch([nodes, edges, () => def.name, () => def.description, () => def.params, () => def.cronExpression], () => {
  if (!suppressDirty) dirty.value = true
}, { deep: true })

watch(() => route.params.id, (id) => {
  const target = String(id || 'create')
  if (target !== loadedId.value) void load()
})

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await Promise.all([loadDatasources().catch(() => { datasources.value = [] }), refreshSqlFiles(), refreshOptions()])
  await load()
  void loadTaskOptions()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
  window.removeEventListener('mousemove', onConnectMove)
  window.removeEventListener('mouseup', onConnectEnd)
})
</script>

<template>
  <div class="studio" v-loading="loading">
    <header class="studio-top">
      <div class="top-left">
        <el-button text :icon="ArrowLeft" @click="backToList">返回</el-button>
        <el-input v-model="def.name" class="name-input" placeholder="任务流名称" maxlength="200" />
        <el-tag v-if="def.code" size="small" type="info">{{ def.code }}</el-tag>
        <el-tag v-if="dirty" size="small" type="warning">未保存</el-tag>
        <el-tag v-if="def.status === 2" size="small" type="success">
          已发布{{ def.publishedRevision ? ` v${def.publishedRevision}` : '' }}
        </el-tag>
        <el-tag v-else-if="def.status === 1" size="small" type="primary">已启用</el-tag>
        <el-tag v-else size="small" type="info">未发布</el-tag>
      </div>
      <div class="top-actions">
        <el-button :icon="CircleCheck" :disabled="!activeNode" @click="openDebug(activeNode?.nodeCode)">调试</el-button>
        <el-button :icon="VideoPlay" :loading="running" :disabled="!canSchedule" @click="openRunDialog()">运行</el-button>
        <el-button :icon="Position" :disabled="!activeNode || !canSchedule" @click="runDownstream">运行及下游</el-button>
        <el-button :icon="Upload" :disabled="!canSchedule" @click="publish">发布</el-button>
        <el-dropdown trigger="click" @command="onCommand">
          <el-button :icon="MoreFilled">更多</el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="versions">版本管理</el-dropdown-item>
              <el-dropdown-item command="deps">任务流配置</el-dropdown-item>
              <el-dropdown-item command="settings">参数设置</el-dropdown-item>
              <el-dropdown-item command="tasks">任务列表</el-dropdown-item>
              <el-dropdown-item command="analysis">解析依赖</el-dropdown-item>
              <el-dropdown-item command="runs">运行记录</el-dropdown-item>
              <el-dropdown-item command="offline" divided :disabled="def.status !== 2">下线</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <el-button type="primary" :icon="Check" :loading="saving" @click="save()">保存</el-button>
      </div>
    </header>

    <div class="studio-body">
      <aside class="left">
        <div class="left-tabs">
          <button type="button" :class="{ active: leftTab === 'resource' }" @click="leftTab = 'resource'">资源</button>
          <button type="button" :class="{ active: leftTab === 'node' }" @click="leftTab = 'node'">节点</button>
        </div>
        <div v-show="leftTab === 'resource'" class="side-block resource">
          <div class="side-head">
            <span>资源</span>
            <div class="side-tools">
              <el-button link :icon="RefreshRight" title="刷新资源" @click="refreshSqlFiles(); refreshOptions()" />
              <el-button link :icon="Plus" title="新建 SQL 文件" @click="openNewSqlFile" />
            </div>
          </div>
          <el-input v-model="resourceKeyword" size="small" placeholder="搜索资源" clearable :prefix-icon="Search" />
          <div class="resource-group">
            <div class="group-title">SQL 文件 <span>{{ filteredSqlFiles.length }}</span></div>
            <div v-if="!sqlGroups.length" class="empty-hint">暂无 SQL 文件</div>
            <div v-for="group in sqlGroups" :key="group.folder" class="folder-group">
              <div class="folder-row" :title="group.folder">
                <el-icon><FolderOpened /></el-icon>
                <span>{{ folderName(group.folder) }}</span>
              </div>
              <button v-for="file in group.files" :key="file.id" type="button" class="file-item"
                :title="`${file.folder}/${file.name}`" @click="openSqlFileEditor(file)">
                <span class="file-name">{{ file.name }}</span>
                <span class="file-ver">v{{ file.draftVersion ?? 1 }}</span>
              </button>
            </div>
          </div>
          <div class="resource-group">
            <div class="group-title">任务流 <span>{{ filteredWorkflows.length }}</span></div>
            <button v-for="workflow in filteredWorkflows" :key="workflow.id" type="button" class="wf-item"
              :class="{ current: workflow.id === def.id }" @click="switchWorkflow(workflow.id!)">
              <span class="wf-dot" :class="`s${workflow.status ?? 0}`" />
              <span class="wf-name">{{ workflow.name }}</span>
            </button>
            <div v-if="!filteredWorkflows.length" class="empty-hint">当前项目暂无任务流</div>
          </div>
        </div>

        <div v-show="leftTab === 'node'" class="side-block palette">
          <div class="side-head">
            <span>节点</span>
            <span class="drag-hint">拖拽到画布</span>
          </div>
          <div v-for="group in paletteGroups" :key="group.name" class="palette-group">
            <div class="palette-title">{{ group.name }}</div>
            <div v-for="item in group.items" :key="item.type" class="palette-item" draggable="true"
              :title="item.description" @dragstart="onPaletteDragStart(item.type, $event)" @click="addNode(item.type)">
              <span class="palette-icon" :style="{ color: item.color, background: item.soft }">
                <el-icon><component :is="item.icon" /></el-icon>
              </span>
              <span class="palette-label">{{ item.label }}</span>
              <span class="palette-grip">⠿</span>
            </div>
          </div>
        </div>
      </aside>

      <main class="canvas-shell">
        <div class="canvas-toolbar">
          <el-button-group size="small">
            <el-button :icon="ZoomOut" title="缩小" @click="zoomBy(-0.1)" />
            <el-button class="zoom-value" @click="resetZoom">{{ zoomPercent }}%</el-button>
            <el-button :icon="ZoomIn" title="放大" @click="zoomBy(0.1)" />
          </el-button-group>
          <el-button size="small" :icon="Aim" @click="fitView">适应画布</el-button>
          <el-button size="small" :icon="Grid" @click="autoLayout">整理布局</el-button>
          <span class="canvas-hint">从节点右侧圆点拖出连线；Delete 删除选中项</span>
        </div>

        <div ref="wrapRef" class="canvas-wrap" @dragover.prevent @drop="onDrop">
          <div class="stage-wrap" :style="stageWrapStyle">
            <div ref="stageRef" class="stage" :style="stageStyle" @mousedown.self="clearSelection">
              <div class="grid-bg" />
              <svg class="edge-layer" :width="stageW" :height="stageH">
                <defs>
                  <marker id="dk-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
                    <path d="M0,0 L9,4.5 L0,9 z" fill="#12b76a" />
                  </marker>
                  <marker id="dk-arrow-active" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
                    <path d="M0,0 L9,4.5 L0,9 z" fill="#215dba" />
                  </marker>
                </defs>
                <path v-for="item in edgeList" :key="item.key" :d="item.path" class="edge"
                  :class="{ selected: selectedEdgeKey === item.key, failed: statusOf(item.edge.fromCode) === 'FAILED' }"
                  :marker-end="selectedEdgeKey === item.key ? 'url(#dk-arrow-active)' : 'url(#dk-arrow)'"
                  @mousedown.stop="selectEdge(item.key)" />
                <path v-if="connect.active" :d="tempPath" class="edge temp" />
              </svg>

              <div v-for="node in nodes" :key="node.nodeCode" class="node-card"
                :class="{ selected: selectedCode === node.nodeCode, skipped: node.skipFlag === 1,
                  failed: statusOf(node.nodeCode!) === 'FAILED' }"
                :data-node-code="node.nodeCode"
                :style="{ left: `${node.posX ?? 0}px`, top: `${node.posY ?? 0}px` }"
                :title="nodeSummary(node)"
                @mousedown.stop="startDrag(node, $event)" @click.stop="selectNode(node)">
                <span class="node-icon" :style="{ color: nodeMeta(node.nodeType).color, background: nodeMeta(node.nodeType).soft }">
                  <el-icon><component :is="nodeMeta(node.nodeType).icon" /></el-icon>
                </span>
                <div class="node-main">
                  <div class="node-title">{{ node.nodeName || '未命名节点' }}</div>
                  <div class="node-sub">
                    <span>{{ nodeMeta(node.nodeType).label }}</span>
                    <span v-if="node.skipFlag === 1" class="node-flag">跳过</span>
                    <span v-if="statusOf(node.nodeCode!)" class="node-status"
                      :style="{ color: statusColor(node.nodeCode!) }">· {{ statusText(node.nodeCode!) }}</span>
                  </div>
                </div>
                <span v-if="statusOf(node.nodeCode!)" class="node-signal"
                  :style="{ background: statusColor(node.nodeCode!) }" />
                <span class="node-remove" title="删除节点" @mousedown.stop @click.stop="removeNode(node)">×</span>
                <span class="node-port in" />
                <span class="node-port out" title="拖拽创建依赖" @mousedown.stop="startConnect(node, $event)" />
              </div>
            </div>
          </div>
        </div>

        <div class="canvas-status">
          <span>{{ nodes.length }} 个节点 · {{ edges.length }} 条依赖</span>
          <span v-if="activeNode">已选中「{{ activeNode.nodeName }}」</span>
          <span class="legend">
            <i class="legend-dot" style="background:#0e9f6e" />成功
            <i class="legend-dot" style="background:#d92d20" />失败
            <i class="legend-dot" style="background:#d0d5dd" />未运行
          </span>
        </div>
      </main>

      <aside class="right">
        <NodeSettings v-if="activeNode" :wf-id="def.id" :node="activeNode" :nodes="nodes" :edges="edges"
          :datasources="datasources" :sql-files="sqlFiles" :workflows="workflows"
          :pipelines="pipelines" :quality-tasks="qualityTasks"
          :last-status="taskStatus[activeNode.nodeCode]?.lastStatus"
          :last-start-time="taskStatus[activeNode.nodeCode]?.lastStartTime"
          :last-duration-ms="taskStatus[activeNode.nodeCode]?.lastDurationMs"
          :last-error-message="taskStatus[activeNode.nodeCode]?.lastErrorMessage"
          @delete="removeNode(activeNode!)"
          @close="clearSelection"
          @choose-file="sqlPickerVisible = true"
          @save-as-file="saveNodeAsFile"
          @debug="openDebug(activeNode?.nodeCode)"
          @run-downstream="runDownstream"
          @add-upstream="code => addEdge(code, activeNode!.nodeCode!)"
          @remove-upstream="code => removeEdge(code, activeNode!.nodeCode!)"
          @open-workflow-deps="openDeps" />
        <div v-else class="overview">
          <div class="overview-head">
            <h3>任务流</h3>
            <el-button link :icon="RefreshRight" title="刷新运行状态" @click="refreshTaskStatus" />
          </div>
          <div class="overview-name">{{ def.name || '未命名任务流' }}</div>
          <div class="overview-tags">
            <el-tag size="small" :type="def.status === 2 ? 'success' : def.status === 1 ? 'primary' : 'info'">
              {{ def.status === 2 ? '已发布' : def.status === 1 ? '已启用' : '未发布' }}
            </el-tag>
            <el-tag v-if="def.cronExpression" size="small" type="info">{{ def.cronExpression }}</el-tag>
          </div>
          <p class="overview-desc">{{ def.description || '在左侧拖入节点开始编排，右侧配置节点参数。' }}</p>

          <div class="stat-grid">
            <div class="stat"><strong>{{ nodes.length }}</strong><span>节点</span></div>
            <div class="stat"><strong>{{ edges.length }}</strong><span>依赖</span></div>
            <div class="stat"><strong>{{ referenceCount }}</strong><span>引用文件</span></div>
            <div class="stat"><strong>{{ lastRunLabel }}</strong><span>最近运行</span></div>
          </div>

          <div v-if="activeEdge" class="edge-card">
            <div class="edge-title">已选依赖</div>
            <div class="edge-line">
              {{ nodes.find(node => node.nodeCode === activeEdge?.fromCode)?.nodeName || activeEdge.fromCode }}
              <span class="edge-arrow">→</span>
              {{ nodes.find(node => node.nodeCode === activeEdge?.toCode)?.nodeName || activeEdge.toCode }}
            </div>
            <el-button size="small" type="danger" plain :icon="Delete"
              @click="removeEdge(activeEdge.fromCode, activeEdge.toCode)">删除依赖</el-button>
          </div>

          <div class="overview-actions">
            <el-button size="small" @click="openDeps">任务流配置</el-button>
            <el-button size="small" @click="settingsDialog = true">参数设置</el-button>
            <el-button size="small" @click="openVersions">版本管理</el-button>
            <el-button size="small" @click="openTasks">任务列表</el-button>
          </div>

          <div class="node-list">
            <div class="node-list-head">节点清单</div>
            <button v-for="node in nodes" :key="node.nodeCode" type="button" class="node-list-item"
              @click="selectNode(node)">
              <span class="node-list-dot" :style="{ background: nodeMeta(node.nodeType).color }" />
              <span class="node-list-name">{{ node.nodeName }}</span>
              <span class="node-list-status" :style="{ color: statusColor(node.nodeCode!) }">
                {{ statusText(node.nodeCode!) || '—' }}
              </span>
            </button>
            <div v-if="!nodes.length" class="empty-hint">还没有节点，从左侧拖入。</div>
          </div>
        </div>
      </aside>
    </div>

    <WorkflowRevisionDialog v-if="revisionDialog && def.id" :workflow="{ id: def.id, name: def.name }"
      @close="revisionDialog = false" @open-draft="id => router.push(editLocation(id))" />
    <WorkflowDependencyDialog v-model="depsDialog" :wf-id="def.id" :workflow-name="def.name"
      :workflows="workflows" @saved="refreshTaskStatus" />
    <WorkflowTasksDialog v-model="tasksDialog" :wf-id="def.id" :workflow-name="def.name" />
    <WorkflowSettingsDialog v-model="settingsDialog" :def="def" @save="save(true)" />
    <NodeDebugDialog v-model="debugVisible" :wf-id="def.id" :node="activeNode"
      :default-params="debugParams" />
    <RunWorkflowDialog v-model="runDialogVisible" :workflow-name="def.name" :scope-label="runScopeLabel"
      :default-params="debugParams" @run="executeRun" />
    <DependencyAnalysisDialog v-model="analysisVisible" :nodes="nodes" :edges="edges" />
    <SqlFilePicker v-model="sqlPickerVisible" :files="sqlFiles" @select="onSqlFileSelected" />
    <SqlFileEditor v-model="sqlFileEditorVisible" :project-id="projectId" :file="sqlFileEditorFile"
      :prefill-name="sqlFilePrefill.name" :prefill-content="sqlFilePrefill.content"
      :datasources="datasources" @saved="onFileSaved" @deleted="onFileDeleted" />
  </div>
</template>

<style scoped>
/* 顶栏 + 项目页头约 140px；用视口高度约束，避免画布 1200px 舞台把整页撑高 */
.studio { display: flex; flex-direction: column; width: 100%; max-width: 100%; min-width: 0;
  height: calc(100vh - 140px); min-height: 620px; background: #f5f7fa; color: #1d2129; overflow: hidden; }
.studio-top { display: flex; align-items: center; justify-content: space-between; gap: 14px;
  padding: 9px 14px; background: #fff; border-bottom: 1px solid #e4e7ec; flex-wrap: wrap; }
.top-left { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1 1 420px; }
.name-input { width: 260px; }
.name-input :deep(.el-input__inner) { font-weight: 600; }
.top-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }

.studio-body { flex: 1; display: flex; min-width: 0; min-height: 0; }
.left { width: 252px; flex: none; background: #fff; border-right: 1px solid #e4e7ec;
  display: flex; flex-direction: column; min-height: 0; overflow-y: auto; }
.left-tabs { display: flex; gap: 4px; padding: 8px 10px 0; border-bottom: 1px solid #f2f4f7; }
.left-tabs button { flex: 1; border: 0; background: transparent; padding: 7px 0; font-size: 12.5px;
  color: #667085; cursor: pointer; border-bottom: 2px solid transparent; }
.left-tabs button.active { color: var(--dk-primary); border-bottom-color: var(--dk-primary); font-weight: 600; }
.side-block { padding: 10px 12px; }
.side-block + .side-block { border-top: 1px solid #f2f4f7; }
.side-head { display: flex; align-items: center; justify-content: space-between; gap: 8px;
  font-size: 13px; font-weight: 600; color: #344054; margin-bottom: 8px; }
.side-tools { display: flex; gap: 2px; }
.drag-hint { font-size: 11px; color: #98a2b3; font-weight: 400; }
.resource-group { margin-top: 12px; }
.group-title { display: flex; align-items: center; justify-content: space-between; font-size: 12px;
  color: #667085; margin-bottom: 6px; }
.group-title span { color: #98a2b3; }
.folder-group { margin-bottom: 4px; }
.folder-row { display: flex; align-items: center; gap: 6px; padding: 4px 6px; font-size: 12px;
  color: #475467; background: #f8fafc; border-radius: 5px; }
.folder-row .el-icon { color: #98a2b3; font-size: 13px; }
.file-item { display: flex; align-items: center; justify-content: space-between; gap: 6px; width: 100%;
  border: 0; background: transparent; padding: 5px 6px 5px 20px; font-size: 12px; color: #475467;
  cursor: pointer; border-radius: 5px; text-align: left; }
.file-item:hover { background: var(--dk-primary-soft); color: var(--dk-primary); }
.file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-ver { flex: none; font-size: 10.5px; color: #98a2b3; }
.wf-item { display: flex; align-items: center; gap: 7px; width: 100%; border: 0; background: transparent;
  padding: 6px; font-size: 12px; color: #475467; cursor: pointer; border-radius: 5px; text-align: left; }
.wf-item:hover { background: #f5f8ff; }
.wf-item.current { background: var(--dk-primary-soft); color: var(--dk-primary); font-weight: 600; }
.wf-dot { width: 7px; height: 7px; border-radius: 50%; background: #98a2b3; flex: none; }
.wf-dot.s1 { background: #215dba; }
.wf-dot.s2 { background: #0e9f6e; }
.wf-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty-hint { font-size: 12px; color: #b0b7c3; padding: 6px; }

.palette-group { margin-top: 8px; }
.palette-title { font-size: 11px; color: #98a2b3; margin: 6px 0 4px; }
.palette-item { display: flex; align-items: center; gap: 8px; padding: 6px 7px; border: 1px solid #eef1f5;
  border-radius: 7px; margin-bottom: 5px; cursor: grab; background: #fcfcfd; user-select: none; }
.palette-item:hover { border-color: var(--dk-primary); background: #f5f8ff; }
.palette-item:active { cursor: grabbing; }
.palette-icon { width: 26px; height: 26px; border-radius: 6px; display: grid; place-items: center; flex: none; }
.palette-icon .el-icon { font-size: 14px; }
.palette-label { flex: 1; font-size: 12.5px; color: #344054; }
.palette-grip { color: #c4c9d4; font-size: 12px; }

/* width:0 + flex-grow：避免画布 2200px 的舞台把整页撑宽，右栏被挤到视口外 */
.canvas-shell { flex: 1 1 0; width: 0; min-width: 0; min-height: 0; display: flex; flex-direction: column;
  position: relative; background: #f7f8fa; }
.canvas-toolbar { display: flex; align-items: center; gap: 8px; padding: 8px 12px; background: #fff;
  border-bottom: 1px solid #eef1f5; flex-wrap: wrap; }
.zoom-value { min-width: 58px; font-variant-numeric: tabular-nums; }
.canvas-hint { font-size: 11.5px; color: #98a2b3; margin-left: auto; }
.canvas-wrap { flex: 1; min-width: 0; min-height: 0; overflow: auto; position: relative; }
.stage-wrap { position: relative; }
.stage { position: relative; transform-origin: 0 0; }
.grid-bg { position: absolute; inset: 0; background-image: radial-gradient(circle, #d8dde6 1px, transparent 1px);
  background-size: 20px 20px; pointer-events: none; }
.edge-layer { position: absolute; inset: 0; pointer-events: none; }
.edge { fill: none; stroke: #12b76a; stroke-width: 1.8; opacity: .72; pointer-events: stroke;
  cursor: pointer; }
.edge:hover { opacity: 1; }
.edge.selected { stroke: #215dba; stroke-width: 2.4; opacity: 1; }
.edge.failed { stroke: #f04438; }
.edge.temp { stroke: #215dba; stroke-dasharray: 6 4; opacity: .8; }
.node-card { position: absolute; width: 216px; height: 66px; display: flex; align-items: center; gap: 9px;
  padding: 0 10px; background: #fff; border: 1px solid #e4e7ec; border-radius: 9px;
  box-shadow: 0 2px 6px rgba(16, 24, 40, .06); cursor: move; user-select: none; }
.node-card:hover { border-color: #b2c7ea; box-shadow: 0 4px 12px rgba(16, 24, 40, .1); }
.node-card.selected { border-color: var(--dk-primary); box-shadow: 0 0 0 3px rgba(33, 93, 186, .16); }
.node-card.failed { border-color: #fda29b; }
.node-card.skipped { opacity: .62; border-style: dashed; }
.node-icon { width: 34px; height: 34px; border-radius: 8px; display: grid; place-items: center; flex: none; }
.node-icon .el-icon { font-size: 17px; }
.node-main { min-width: 0; flex: 1; }
.node-title { font-size: 12.5px; font-weight: 600; color: #1d2129; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.node-sub { display: flex; align-items: center; gap: 5px; margin-top: 3px; font-size: 10.5px;
  color: #98a2b3; white-space: nowrap; overflow: hidden; }
.node-flag { padding: 0 4px; border-radius: 3px; background: #f2f4f7; color: #667085; }
.node-status { font-weight: 600; }
.node-signal { position: absolute; left: 8px; top: 8px; width: 6px; height: 6px; border-radius: 50%; }
.node-remove { position: absolute; right: 6px; top: 3px; font-size: 14px; line-height: 1; color: #b0b7c3;
  cursor: pointer; opacity: 0; }
.node-card:hover .node-remove { opacity: 1; }
.node-remove:hover { color: #d92d20; }
.node-port { position: absolute; width: 11px; height: 11px; border-radius: 50%; background: #fff;
  border: 2px solid #12b76a; top: 50%; margin-top: -5.5px; }
.node-port.in { left: -6px; border-color: #b0b7c3; pointer-events: none; }
.node-port.out { right: -6px; cursor: crosshair; }
.node-port.out:hover { background: #12b76a; }
.canvas-status { display: flex; align-items: center; gap: 14px; padding: 5px 12px; background: #fff;
  border-top: 1px solid #eef1f5; font-size: 11.5px; color: #98a2b3; }
.legend { margin-left: auto; display: inline-flex; align-items: center; gap: 5px; }
.legend-dot { width: 7px; height: 7px; border-radius: 50%; display: inline-block; margin-left: 6px; }

.right { width: 350px; flex: none; border-left: 1px solid #e4e7ec; background: #fff; min-height: 0;
  overflow: hidden; display: flex; flex-direction: column; }
.overview { padding: 14px; overflow-y: auto; }
.overview-head { display: flex; align-items: center; justify-content: space-between; }
.overview-head h3 { margin: 0; font-size: 14px; font-weight: 600; }
.overview-name { margin-top: 10px; font-size: 16px; font-weight: 700; color: #1d2129; }
.overview-tags { display: flex; gap: 6px; margin-top: 8px; flex-wrap: wrap; }
.overview-desc { margin: 10px 0 0; font-size: 12.5px; color: #667085; line-height: 1.65; }
.stat-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin: 14px 0; }
.stat { background: #f8fafc; border: 1px solid #eef1f5; border-radius: 8px; padding: 8px 10px; }
.stat strong { display: block; font-size: 15px; color: #1d2129; }
.stat span { font-size: 11px; color: #98a2b3; }
.edge-card { border: 1px solid #e4e7ec; border-radius: 8px; padding: 10px; margin-bottom: 12px;
  background: #fcfcfd; }
.edge-title { font-size: 11.5px; color: #98a2b3; margin-bottom: 4px; }
.edge-line { font-size: 12.5px; color: #344054; margin-bottom: 8px; }
.edge-arrow { color: #12b76a; margin: 0 4px; }
.overview-actions { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; margin-bottom: 14px; }
.node-list { border-top: 1px solid #eef1f5; padding-top: 10px; }
.node-list-head { font-size: 12px; color: #667085; margin-bottom: 6px; }
.node-list-item { display: flex; align-items: center; gap: 7px; width: 100%; border: 0; background: transparent;
  padding: 6px; border-radius: 5px; font-size: 12px; color: #475467; cursor: pointer; text-align: left; }
.node-list-item:hover { background: #f5f8ff; }
.node-list-dot { width: 7px; height: 7px; border-radius: 50%; flex: none; }
.node-list-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-list-status { flex: none; font-size: 11px; }

@media (max-width: 1320px) {
  .right { width: 320px; }
}
@media (max-width: 1120px) {
  .left { width: 210px; }
  .right { width: 300px; }
}
</style>
