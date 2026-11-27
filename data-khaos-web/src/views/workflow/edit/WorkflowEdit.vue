<template>
  <div class="editor" v-loading="saving">
    <!-- 顶部工具栏 -->
    <div class="topbar">
      <el-button :icon="ArrowLeft" @click="backToList()">返回</el-button>
      <el-input v-model="def.name" placeholder="工作流名称" class="name-input" />
      <el-tag v-if="def.id" type="info">{{ def.code }}</el-tag>
      <div class="spacer" />
      <el-radio-group v-model="def.status" size="default" :disabled="!canSchedule">
        <el-radio-button :value="1">启用</el-radio-button>
        <el-radio-button :value="0">禁用</el-radio-button>
      </el-radio-group>
      <el-button type="primary" :icon="VideoPlay" :loading="running" :disabled="!canSchedule" @click="handleRun">运行</el-button>
      <el-button type="success" :icon="Check" :loading="saving" @click="handleSave">保存</el-button>
    </div>

    <div class="body">
      <!-- 节点类型面板 -->
      <div class="palette">
        <div class="palette-title">节点类型</div>
        <div
          v-for="t in typeList"
          :key="t.type"
          class="palette-item"
          @click="addNode(t.type)"
        >
          <span class="palette-dot" :style="{ background: t.color }" />
          <span class="palette-label">{{ t.label }}</span>
        </div>
        <div class="palette-tip">点击添加节点<br />拖拽 圆点 ➜ 连线</div>
      </div>

      <!-- 画布 -->
      <div class="canvas-wrap" ref="wrapRef" @scroll="onScroll">
        <div class="grid-bg" />
        <div
          class="canvas"
          ref="canvasRef"
          :style="{ width: CANVAS_W + 'px', height: CANVAS_H + 'px' }"
        >
          <svg class="edge-layer">
            <path
              v-for="(e, i) in edges"
              :key="e.fromCode + '-' + e.toCode"
              :d="edgePath(e)"
              class="edge"
              :class="{ selected: selectedEdgeOf(e) }"
              @mousedown.stop="selectEdge(e)"
            />
          </svg>
          <!-- 连线拖拽临时箭头 -->
          <svg class="edge-layer">
            <path v-if="connectState.active" :d="tempPath" class="edge temp" />
          </svg>

          <!-- 节点层 -->
          <div
            v-for="n in nodes"
            :key="n.nodeCode"
            class="node"
            :class="[{ selected: selectedNodeOf(n) }, 'type-' + n.nodeType]"
            :data-code="n.nodeCode"
            :style="{ left: n.posX + 'px', top: n.posY + 'px' }"
            @mousedown.stop="startDrag(n, $event)"
            @click.stop="selectNode(n)"
          >
            <div class="node-head">
              <span class="node-dot" :style="{ background: typeColor(n.nodeType) }" />
              <span class="node-type">{{ typeLabel(n.nodeType) }}</span>
              <el-icon class="node-del" @mousedown.stop @click.stop="removeNode(n)"><Close /></el-icon>
            </div>
            <div class="node-name">{{ n.nodeName || '未命名' }}</div>
            <span
              class="port out"
              @mousedown.stop="startConnect(n, $event)"
              title="拖拽到目标节点创建依赖"
            />
            <span class="port in" title="来源连线" />
          </div>
        </div>
      </div>

      <!-- 属性面板 -->
      <div class="panel">
        <div v-if="activeNode" class="panel-section">
          <div class="panel-title">节点配置</div>
          <el-form label-width="72px" size="small">
            <el-form-item label="名称">
              <el-input v-model="activeNode.nodeName" placeholder="节点名称" />
            </el-form-item>
            <el-form-item label="类型">
              <el-select v-model="activeNode.nodeType" style="width: 100%" @change="onTypeChange">
                <el-option v-for="t in typeList" :key="t.type" :label="t.label" :value="t.type" />
              </el-select>
            </el-form-item>

            <template v-if="needDataSource(activeNode.nodeType)">
              <el-form-item label="数据源">
                <el-select v-model="nodeDsId" filterable placeholder="选择数据源" style="width: 100%">
                  <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsType ? `${ds.dsName} (${ds.dsType})` : ds.dsName" :value="ds.id!" />
                </el-select>
              </el-form-item>
              <el-form-item :label="activeNode.nodeType === 'DATA_OP' ? '算子SQL' : 'SQL'">
                <el-input
                  v-model="nodeSql"
                  type="textarea"
                  :rows="8"
                  :placeholder="'输入 SQL（支持 ${param} 参数）'"
                />
              </el-form-item>
            </template>
            <template v-else>
              <el-form-item :label="activeNode.nodeType === 'PYTHON' ? 'Python 脚本' : 'Shell 脚本'">
                <el-input v-model="nodeScript" type="textarea" :rows="10" placeholder="输入脚本内容（支持 ${param} 参数）" />
              </el-form-item>
            </template>

            <el-form-item label="超时(秒)">
              <el-input-number v-model="activeNode.timeout" :min="0" :max="86400" />
            </el-form-item>
            <el-form-item label="重试次数">
              <el-input-number v-model="activeNode.retryCount" :min="0" :max="10" />
            </el-form-item>
            <el-form-item label="重试间隔s">
              <el-input-number v-model="activeNode.retryInterval" :min="0" :max="3600" />
            </el-form-item>
          </el-form>
          <div class="node-actions">
            <el-button type="danger" size="small" :icon="Close" @click="removeNode(activeNode)">删除节点</el-button>
          </div>
        </div>

        <div v-else-if="activeEdge" class="panel-section">
          <div class="panel-title">连线配置</div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="源节点">{{ nodeNameByCode(activeEdge.fromCode) }}</el-descriptions-item>
            <el-descriptions-item label="目标节点">{{ nodeNameByCode(activeEdge.toCode) }}</el-descriptions-item>
          </el-descriptions>
          <div class="node-actions">
            <el-button type="danger" size="small" :icon="Close" @click="removeEdge(activeEdge.fromCode + '|' + activeEdge.toCode)">删除连线</el-button>
          </div>
        </div>

        <div v-else class="panel-section">
          <div class="panel-title">工作流属性</div>
          <el-form label-width="84px" size="small">
            <el-form-item label="编码">
              <el-input v-model="def.code" placeholder="留空自动生成" :disabled="!!def.id" />
            </el-form-item>
            <el-form-item label="Cron 表达式">
              <el-input v-model="def.cronExpression" :disabled="!canSchedule" placeholder="如 0 0 2 * * ?（留空仅手动）" />
            </el-form-item>
            <el-form-item label="描述">
              <el-input v-model="def.description" type="textarea" :rows="3" placeholder="工作流描述" />
            </el-form-item>
            <el-form-item label="参数(JSON)">
              <el-input v-model="def.params" type="textarea" :rows="5" placeholder='运行参数模板，如 {"bizDate": "2026-08-19"}' />
            </el-form-item>
          </el-form>
          <el-alert type="info" :closable="false" show-icon>
            <template #title>节点脚本中可用 {{ '${param}' }} 引用工作流参数；DAG 支持并行调度，失败时下游自动跳过。</template>
          </el-alert>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { realtimeProjectKey, projectLocation } from '@/views/realtime/projectContext'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Check, Close, VideoPlay } from '@element-plus/icons-vue'
import { pageDatasources } from '@/api/datasource'
import { listWorkspaceResources } from '@/api/project-group'
import { getWorkflowGraph, saveWorkflowGraph, triggerWorkflow } from '@/api/workflow'
import type { MetaDatasource, WorkflowDef, WorkflowEdge, WorkflowNode } from '@/types'

const CANVAS_W = 3200
const CANVAS_H = 2000
const NODE_W = 190
const NODE_H = 60

const typeList = [
  { type: 'SQL', label: 'SQL 脚本', color: '#165dff' },
  { type: 'SHELL', label: 'Shell 脚本', color: '#00b42a' },
  { type: 'PYTHON', label: 'Python 脚本', color: '#722ed1' },
  { type: 'DATA_OP', label: '数据算子', color: '#ff7d00' },
] as const

const props = defineProps<{ inProjectWorkspace?: boolean }>()
const workspace = inject(realtimeProjectKey, null)
const canSchedule = computed(() => !props.inProjectWorkspace || !!workspace?.canRun.value)
const route = useRoute()
const router = useRouter()
function backToList() {
  if (props.inProjectWorkspace) void router.push(projectLocation('/jobs/offline', workspace?.project.value || ''))
  else router.back()
}

const wrapRef = ref<HTMLElement>()
const canvasRef = ref<HTMLElement>()

const def = reactive<WorkflowDef>({ name: '', status: 1, description: '', cronExpression: '', params: '', code: '' })
const nodes = ref<WorkflowNode[]>([])
const edges = ref<WorkflowEdge[]>([])

const selectedCode = ref<string | null>(null)
const datasources = ref<MetaDatasource[]>([])

const saving = ref(false)
const running = ref(false)

let codeSeq = 1
function nextCode(): string {
  while (nodes.value.some((n) => n.nodeCode === 'n' + codeSeq)) codeSeq++
  return 'n' + codeSeq++
}

function addNode(type: string) {
  const { x, y } = centerPos()
  const node: WorkflowNode = {
    nodeCode: nextCode(),
    nodeName: typeLabel(type) + (nodes.value.length + 1),
    nodeType: type,
    configJson: JSON.stringify(type === 'SHELL' || type === 'PYTHON' ? { script: '' } : { datasourceId: '', sql: '' }),
    posX: x,
    posY: y,
    timeout: 0,
    retryCount: 0,
    retryInterval: 0,
  }
  nodes.value.push(node)
  selectedCode.value = node.nodeCode
}

function centerPos() {
  const wrap = wrapRef.value
  const base = { x: CANVAS_W / 2 - NODE_W / 2, y: CANVAS_H / 2 - NODE_H / 2 }
  if (!wrap) return base
  const off = { x: wrap.scrollLeft, y: wrap.scrollTop }
  const vw = wrap.clientWidth
  const vh = wrap.clientHeight
  return {
    x: Math.round(off.x + vw / 2 - NODE_W / 2),
    y: Math.round(off.y + vh / 2 - NODE_H / 2),
  }
}

function typeLabel(t?: string) {
  return typeList.find((x) => x.type === t)?.label ?? t ?? '未知'
}
function typeColor(t?: string) {
  return typeList.find((x) => x.type === t)?.color ?? '#909399'
}
function needDataSource(t: string) {
  return t === 'SQL' || t === 'DATA_OP'
}

const nodeMap = computed(() => {
  const m = new Map<string, WorkflowNode>()
  nodes.value.forEach((n) => m.set(n.nodeCode!, n))
  return m
})

const activeNode = computed(() => {
  if (!selectedCode.value) return undefined
  return nodes.value.find((n) => n.nodeCode === selectedCode.value)
})
const activeEdge = computed(() => {
  if (!selectedCode.value) return undefined
  const [from, to] = selectedCode.value.split('|')
  return edges.value.find((e) => e.fromCode === from && e.toCode === to)
})

// 节点配置绑定（将 configJson 拆分为表单字段）
const nodeDsId = computed<string>({
  get: () => (activeNode.value ? (parseCfg(activeNode.value).datasourceId as string) || '' : ''),
  set: (v) => setCfg('datasourceId', v),
})
const nodeSql = computed<string>({
  get: () => (activeNode.value ? (parseCfg(activeNode.value).sql as string) || '' : ''),
  set: (v) => setCfg('sql', v),
})
const nodeScript = computed<string>({
  get: () => (activeNode.value ? (parseCfg(activeNode.value).script as string) || '' : ''),
  set: (v) => setCfg('script', v),
})

function parseCfg(n: WorkflowNode): Record<string, any> {
  try {
    return n.configJson ? JSON.parse(n.configJson) : {}
  } catch {
    return {}
  }
}
function setCfg(key: string, value: any) {
  const n = activeNode.value
  if (!n) return
  const cfg = parseCfg(n)
  cfg[key] = value
  n.configJson = JSON.stringify(cfg)
}
function onTypeChange() {
  const n = activeNode.value
  if (!n) return
  const cfg = parseCfg(n)
  if (needDataSource(n.nodeType)) {
    cfg.sql = cfg.sql ?? ''
    cfg.datasourceId = cfg.datasourceId ?? ''
    delete cfg.script
  } else {
    cfg.script = cfg.script ?? ''
    delete cfg.sql
    delete cfg.datasourceId
  }
  n.configJson = JSON.stringify(cfg)
}

// 边渲染
function nodeByCode(code: string) {
  return nodeMap.value.get(code)
}
function edgePath(e: WorkflowEdge) {
  const f = nodeByCode(e.fromCode)
  const t = nodeByCode(e.toCode)
  if (!f || !t) return ''
  const sx = (f.posX ?? 0) + NODE_W
  const sy = (f.posY ?? 0) + NODE_H / 2
  const ex = t.posX ?? 0
  const ey = (t.posY ?? 0) + NODE_H / 2
  const c = sx + Math.max(40, (ex - sx) / 2)
  return `M ${sx} ${sy} C ${c} ${sy} ${c} ${ey} ${ex} ${ey}`
}
function selectedNodeOf(n: WorkflowNode) {
  return selectedCode.value === n.nodeCode
}
function selectedEdgeOf(e: WorkflowEdge) {
  return selectedCode.value === e.fromCode + '|' + e.toCode
}

function selectNode(n: WorkflowNode) {
  selectedCode.value = n.nodeCode
}
function selectEdge(e: WorkflowEdge) {
  selectedCode.value = e.fromCode + '|' + e.toCode
}

// 节点拖拽
let dragState: { node: WorkflowNode; startX: number; startY: number; ox: number; oy: number } | null = null
function startDrag(n: WorkflowNode, e: MouseEvent) {
  selectNode(n)
  dragState = { node: n, startX: e.clientX, startY: e.clientY, ox: n.posX ?? 0, oy: n.posY ?? 0 }
  window.addEventListener('mousemove', onDragMove)
  window.addEventListener('mouseup', onDragEnd)
}
function onDragMove(e: MouseEvent) {
  if (!dragState) return
  const { node, startX, startY, ox, oy } = dragState
  node.posX = Math.max(0, ox + e.clientX - startX)
  node.posY = Math.max(0, oy + e.clientY - startY)
}
function onDragEnd() {
  dragState = null
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
}

// 连线拖拽
const connectState = reactive({ active: false, from: '', x: 0, y: 0 })
function startConnect(n: WorkflowNode, e: MouseEvent) {
  connectState.active = true
  connectState.from = n.nodeCode!
  connectState.x = e.clientX
  connectState.y = e.clientY
  window.addEventListener('mousemove', onConnectMove)
  window.addEventListener('mouseup', onConnectEnd)
}
function onConnectMove(e: MouseEvent) {
  connectState.x = e.clientX
  connectState.y = e.clientY
}
function canvasPoint(clientX: number, clientY: number) {
  const canvas = canvasRef.value
  if (!canvas) return { x: 0, y: 0 }
  const rect = canvas.getBoundingClientRect()
  return { x: clientX - rect.left, y: clientY - rect.top }
}
const tempPath = computed(() => {
  if (!connectState.active) return ''
  const f = nodeByCode(connectState.from)
  if (!f) return ''
  const p = canvasPoint(connectState.x, connectState.y)
  const sx = (f.posX ?? 0) + NODE_W
  const sy = (f.posY ?? 0) + NODE_H / 2
  const c = sx + Math.max(40, (p.x - sx) / 2)
  return `M ${sx} ${sy} C ${c} ${sy} ${c} ${p.y} ${p.x} ${p.y}`
})
function onConnectEnd(e: MouseEvent) {
  connectState.active = false
  window.removeEventListener('mousemove', onConnectMove)
  window.removeEventListener('mouseup', onConnectEnd)
  const el = document.elementFromPoint(e.clientX, e.clientY) as HTMLElement | null
  const targetEl = el?.closest('.node') as HTMLElement | null
  if (!targetEl) return
  const to = targetEl.dataset.code
  const from = connectState.from
  if (!to || to === from) return
  if (edges.value.some((x) => x.fromCode === from && x.toCode === to)) {
    ElMessage.warning('依赖已存在')
    return
  }
  // 环路检测：若 to 可达 from，则拒绝
  if (wouldCycle(from, to)) {
    ElMessage.warning('禁止形成环')
    return
  }
  edges.value.push({ fromCode: from, toCode: to })
  selectedCode.value = from + '|' + to
}

function wouldCycle(from: string, to: string) {
  const adj = new Map<string, string[]>()
  edges.value.forEach((e) => {
    if (!adj.has(e.fromCode)) adj.set(e.fromCode, [])
    adj.get(e.fromCode)!.push(e.toCode)
  })
  if (!adj.has(from)) adj.set(from, [])
  adj.get(from)!.push(to)
  const visited = new Set<string>([to])
  const stack = [to]
  while (stack.length) {
    const cur = stack.pop()!
    for (const next of adj.get(cur) || []) {
      if (next === from) return true
      if (!visited.has(next)) {
        visited.add(next)
        stack.push(next)
      }
    }
  }
  return false
}

function removeNode(n: WorkflowNode) {
  nodes.value = nodes.value.filter((x) => x.nodeCode !== n.nodeCode)
  edges.value = edges.value.filter((e) => e.fromCode !== n.nodeCode && e.toCode !== n.nodeCode)
  if (selectedCode.value === n.nodeCode) selectedCode.value = null
}
function removeEdge(key: string) {
  const [from, to] = key.split('|')
  edges.value = edges.value.filter((e) => !(e.fromCode === from && e.toCode === to))
  if (selectedCode.value === key) selectedCode.value = null
}
function nodeNameByCode(code: string) {
  return nodeByCode(code)?.nodeName || code
}
function onScroll() {}

// 保存
async function handleSave() {
  if (saving.value) return
  if (props.inProjectWorkspace && (!workspace?.project.value || def.projectId !== workspace.project.value)) {
    return ElMessage.warning('工作流与当前项目不一致')
  }
  if (!def.name) return ElMessage.warning('请填写工作流名称')
  if (nodes.value.length === 0) return ElMessage.warning('请至少添加一个节点')
  saving.value = true
  try {
    const graph = {
      def: { ...def },
      nodes: nodes.value.map((n) => ({ ...n })),
      edges: edges.value.map((e) => ({ ...e })),
    }
    const saved = await saveWorkflowGraph(graph)
    def.id = saved.id
    def.code = saved.code ?? def.code
    def.status = saved.status ?? def.status
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}

async function handleRun() {
  if (!def.id) {
    ElMessage.warning('请先保存工作流')
    return
  }
  running.value = true
  try {
    await ElMessageBox.confirm('确认运行当前工作流吗？', '提示', { type: 'warning' })
    await triggerWorkflow(def.id)
    ElMessage.success('已触发运行，可在运行记录中查看')
  } finally {
    running.value = false
  }
}

// 加载
async function load() {
  const id = route.params.id as string
  if (!id || id === 'create') {
    def.name = '新建工作流'
    if (props.inProjectWorkspace) {
      def.projectId = workspace?.project.value
      def.status = 0
    }
    if (nodes.value.length === 0) {
      addNode('SQL')
    }
    return
  }
  try {
    const graph = await getWorkflowGraph(id)
    if (props.inProjectWorkspace && graph.def.projectId !== workspace?.project.value) {
      ElMessage.error('工作流与当前项目不一致')
      backToList()
      return
    }
    Object.assign(def, graph.def)
    nodes.value = graph.nodes || []
    edges.value = graph.edges || []
  } catch {
    ElMessage.error('加载工作流失败')
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Delete' || e.key === 'Backspace') {
    const target = e.target as HTMLElement
    if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
    if (activeEdge.value) removeEdge(activeEdge.value.fromCode + '|' + activeEdge.value.toCode)
    else if (activeNode.value) removeNode(activeNode.value)
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  try {
    if (props.inProjectWorkspace && workspace?.project.value) {
      const resources = await listWorkspaceResources(workspace.project.value)
      datasources.value = resources.filter(row => row.resourceType === 'DATASOURCE' && row.available !== false)
        .map(row => ({ id: row.resourceId, dsName: row.name || row.resourceId }))
    } else {
      const data = await pageDatasources({ current: 1, size: 200 })
      datasources.value = data.records
    }
  } catch {
    datasources.value = []
  }
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('mousemove', onDragMove)
  window.removeEventListener('mouseup', onDragEnd)
  window.removeEventListener('mousemove', onConnectMove)
  window.removeEventListener('mouseup', onConnectEnd)
})
</script>

<style scoped>
.editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #f2f3f5;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: #fff;
  border-bottom: 1px solid #e5e6eb;
  flex-shrink: 0;
}
.name-input {
  width: 220px;
}
.spacer {
  flex: 1;
}
.body {
  flex: 1;
  display: flex;
  min-height: 0;
}
.palette {
  width: 170px;
  background: #fff;
  border-right: 1px solid #e5e6eb;
  padding: 12px;
  flex-shrink: 0;
  overflow-y: auto;
}
.palette-title {
  font-weight: 600;
  font-size: 13px;
  color: #1d2129;
  margin-bottom: 10px;
}
.palette-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  margin-bottom: 8px;
  border: 1px solid #e5e6eb;
  border-radius: 8px;
  cursor: pointer;
  background: #fafbfc;
  transition: all 0.15s;
}
.palette-item:hover {
  border-color: #165dff;
  background: #eef4ff;
}
.palette-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.palette-label {
  font-size: 13px;
  color: #333;
}
.palette-tip {
  margin-top: 16px;
  font-size: 12px;
  color: #a9aeb8;
  line-height: 1.7;
}
.canvas-wrap {
  flex: 1;
  overflow: auto;
  position: relative;
  background: #f7f8fa;
}
.grid-bg {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(circle, #d5d8de 1px, transparent 1px);
  background-size: 20px 20px;
}
.canvas {
  position: relative;
}
.edge-layer {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.edge {
  fill: none;
  stroke: #94a3b8;
  stroke-width: 2;
  pointer-events: stroke;
  cursor: pointer;
}
.edge.selected {
  stroke: #165dff;
  stroke-width: 3;
}
.edge.temp {
  stroke-dasharray: 6 4;
  stroke: #165dff;
  opacity: 0.8;
}
.node {
  position: absolute;
  width: 190px;
  height: 60px;
  background: #fff;
  border: 2px solid #c9cdd4;
  border-radius: 10px;
  box-shadow: 0 2px 8px rgba(31, 35, 41, 0.08);
  cursor: move;
  user-select: none;
}
.node.selected {
  border-color: #165dff;
  box-shadow: 0 0 0 3px rgba(22, 93, 255, 0.18);
}
.node-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px 0;
}
.node-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.node-type {
  font-size: 11px;
  font-weight: 600;
  color: #4e5969;
}
.node-del {
  margin-left: auto;
  color: #a9aeb8;
  cursor: pointer;
  font-size: 13px;
}
.node-del:hover {
  color: #f53f3f;
}
.node-name {
  padding: 4px 12px;
  font-size: 13px;
  color: #1d2129;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.port {
  position: absolute;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #165dff;
  top: 50%;
  margin-top: -6px;
}
.port.out {
  right: -6px;
  cursor: crosshair;
}
.port.in {
  left: -6px;
  pointer-events: none;
}
.type-SQL .port.in,
.type-DATA_OP .port.in {
  pointer-events: none;
}
.panel {
  width: 300px;
  background: #fff;
  border-left: 1px solid #e5e6eb;
  padding: 14px;
  flex-shrink: 0;
  overflow-y: auto;
}
.panel-title {
  font-weight: 600;
  font-size: 13px;
  color: #1d2129;
  margin-bottom: 12px;
}
.node-actions {
  margin-top: 10px;
  text-align: right;
}
</style>