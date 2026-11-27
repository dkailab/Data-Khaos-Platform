<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { getWorkflowDependencyGraph, listWorkflowUpstreams, saveWorkflowUpstreams } from '@/api/workflow'
import type { WorkflowDef, WorkflowDependencyGraph } from '@/types'

const props = defineProps<{
  modelValue: boolean
  wfId?: string
  workflowName?: string
  workflows: WorkflowDef[]
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved'): void
}>()

interface DepRow {
  upstreamWfId: string
  dependStrategy: 'STRICT' | 'LOOSE' | 'OFF'
  paramMapping: string
  enabled: boolean
}

const rows = ref<DepRow[]>([])
const loading = ref(false)
const saving = ref(false)
const graphLoading = ref(false)
const graph = ref<WorkflowDependencyGraph>()
const graphDepth = ref(3)
const addValue = ref('')
const batchStrategy = ref<'STRICT' | 'LOOSE' | 'OFF'>('STRICT')
const mappingIndex = ref(-1)
const mappingRows = ref<Array<{ key: string; value: string }>>([])
const mappingVisible = ref(false)

const candidates = computed(() => props.workflows.filter(workflow =>
  workflow.id && workflow.id !== props.wfId && !rows.value.some(row => row.upstreamWfId === workflow.id)))

watch(() => props.modelValue, (open) => {
  if (open) void load()
})

async function load() {
  rows.value = []
  graph.value = undefined
  if (!props.wfId) return
  loading.value = true
  try {
    const data = await listWorkflowUpstreams(props.wfId)
    rows.value = data.map(item => ({
      upstreamWfId: item.upstreamWfId,
      dependStrategy: item.dependStrategy,
      paramMapping: item.paramMapping || '',
      enabled: item.enabled,
    }))
  } finally {
    loading.value = false
  }
}

function addRow() {
  if (!addValue.value) return
  rows.value.push({ upstreamWfId: addValue.value, dependStrategy: 'STRICT', paramMapping: '', enabled: true })
  addValue.value = ''
}
function removeRow(index: number) {
  rows.value.splice(index, 1)
}
function nameOf(id: string) {
  return props.workflows.find(workflow => workflow.id === id)?.name || id
}

function openMapping(index: number) {
  mappingIndex.value = index
  mappingRows.value = []
  const raw = rows.value[index].paramMapping
  if (raw) {
    try {
      const object = JSON.parse(raw)
      mappingRows.value = Object.keys(object).map(key => ({ key, value: String(object[key] ?? '') }))
    } catch {
      mappingRows.value = []
    }
  }
  if (!mappingRows.value.length) mappingRows.value.push({ key: 'bizDate', value: 'bizDate' })
  mappingVisible.value = true
}
function applyMapping() {
  const object: Record<string, string> = {}
  for (const row of mappingRows.value) {
    if (row.key.trim()) object[row.key.trim()] = row.value.trim()
  }
  const index = mappingIndex.value
  if (index >= 0 && rows.value[index]) {
    rows.value[index].paramMapping = Object.keys(object).length ? JSON.stringify(object) : ''
  }
  mappingVisible.value = false
}
function applyBatchStrategy() {
  rows.value.forEach(row => { row.dependStrategy = batchStrategy.value })
}
function mappingSummary(raw: string) {
  if (!raw) return '未配置'
  try {
    const object = JSON.parse(raw)
    return Object.keys(object).map(key => `${key}←${object[key]}`).join('，')
  } catch {
    return '配置异常'
  }
}

async function save(showMessage = true) {
  if (!props.wfId) return false
  saving.value = true
  try {
    await saveWorkflowUpstreams(props.wfId, rows.value.map(row => ({
      upstreamWfId: row.upstreamWfId,
      dependStrategy: row.dependStrategy,
      paramMapping: row.paramMapping || undefined,
      enabled: row.enabled,
    })))
    if (showMessage) ElMessage.success('任务流依赖已保存')
    emit('saved')
    return true
  } catch {
    return false
  } finally {
    saving.value = false
  }
}

async function generateGraph() {
  if (!props.wfId) return
  if (!(await save(false))) return
  graphLoading.value = true
  try {
    graph.value = await getWorkflowDependencyGraph(props.wfId, graphDepth.value)
  } finally {
    graphLoading.value = false
  }
}

const layout = computed(() => {
  if (!graph.value) return undefined
  const groups = new Map<number, WorkflowDependencyGraph['nodes']>()
  for (const node of graph.value.nodes) {
    if (!groups.has(node.level)) groups.set(node.level, [])
    groups.get(node.level)!.push(node)
  }
  const maxLevel = Math.max(...graph.value.nodes.map(node => node.level), 0)
  const maxCount = Math.max(...[...groups.values()].map(list => list.length), 1)
  const width = (maxLevel + 1) * 250 + 120
  const height = maxCount * 96 + 60
  const points = new Map<string, { x: number; y: number; node: WorkflowDependencyGraph['nodes'][number] }>()
  for (const [level, list] of groups) {
    const offset = (height - list.length * 96) / 2
    list.forEach((node, index) => {
      points.set(node.wfId, {
        x: (maxLevel - level) * 250 + 60,
        y: offset + index * 96 + 30,
        node,
      })
    })
  }
  const edges = graph.value.edges.flatMap(edge => {
    const from = points.get(edge.fromWfId)
    const to = points.get(edge.toWfId)
    if (!from || !to) return []
    const x1 = from.x + 180
    const y1 = from.y + 18
    const x2 = to.x
    const y2 = to.y + 18
    const c = x1 + Math.max(30, (x2 - x1) / 2)
    return [{ path: `M ${x1} ${y1} C ${c} ${y1} ${c} ${y2} ${x2} ${y2}`, strategy: edge.strategy }]
  })
  return { width, height, points: [...points.values()], edges }
})
</script>

<template>
  <el-dialog :model-value="modelValue" width="980px" top="6vh"
    :title="`任务流配置 · ${workflowName || ''}`"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <div class="dep-head">
      <div class="dep-intro">
        <strong>上游依赖</strong>
        <span>上游按策略成功后才允许当前任务流运行；STRICT 校验同业务日，LOOSE 只要求最近成功。</span>
      </div>
      <div class="dep-actions">
        <el-select v-model="addValue" filterable placeholder="选择上游任务流" size="small" style="width: 220px">
          <el-option v-for="workflow in candidates" :key="workflow.id" :label="workflow.name" :value="workflow.id!" />
        </el-select>
        <el-button size="small" type="primary" :icon="Plus" :disabled="!addValue" @click="addRow">添加上游任务流</el-button>
      </div>
    </div>

    <div v-if="rows.length" class="batch-bar">
      <span>批量依赖策略</span>
      <el-select v-model="batchStrategy" size="small" style="width: 150px">
        <el-option label="同业务日成功" value="STRICT" />
        <el-option label="最近成功即可" value="LOOSE" />
        <el-option label="仅展示" value="OFF" />
      </el-select>
      <el-button size="small" @click="applyBatchStrategy">应用到全部</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" size="small" border empty-text="尚未配置上游任务流">
      <el-table-column label="上游任务流" min-width="190">
        <template #default="{ row }">
          <span class="dep-name">{{ nameOf(row.upstreamWfId) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="依赖策略" width="170">
        <template #default="{ row }">
          <el-select v-model="row.dependStrategy" size="small">
            <el-option label="同业务日成功" value="STRICT" />
            <el-option label="最近成功即可" value="LOOSE" />
            <el-option label="仅展示" value="OFF" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="参数映射" min-width="180">
        <template #default="{ row, $index }">
          <el-button link type="primary" size="small" @click="openMapping($index)">
            {{ mappingSummary(row.paramMapping) }}
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80">
        <template #default="{ row }">
          <el-switch v-model="row.enabled" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ $index }">
          <el-button link type="danger" size="small" @click="removeRow($index)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div class="graph-bar">
      <span class="graph-title">依赖图</span>
      <el-input-number v-model="graphDepth" :min="1" :max="5" size="small" controls-position="right" />
      <span class="unit">深度</span>
      <el-button size="small" :loading="graphLoading" @click="generateGraph">生成依赖图</el-button>
      <span class="hint">生成前会先保存当前依赖配置。</span>
    </div>

    <div v-if="layout" class="graph-wrap">
      <svg :viewBox="`0 0 ${layout.width} ${layout.height}`" :style="{ width: layout.width + 'px', height: layout.height + 'px' }">
        <defs>
          <marker id="dep-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" fill="#12b76a" />
          </marker>
        </defs>
        <path v-for="(edge, index) in layout.edges" :key="index" :d="edge.path" class="graph-edge"
          marker-end="url(#dep-arrow)" />
        <g v-for="point in layout.points" :key="point.node.wfId">
          <rect :x="point.x" :y="point.y" width="180" height="38" rx="7"
            :class="['graph-node', { root: point.node.root, disabled: point.node.status === 0 }]" />
          <circle :cx="point.x + 12" :cy="point.y + 19" r="4"
            :fill="point.node.status === 0 ? '#98a2b3' : point.node.status === 2 ? '#0e9f6e' : '#215dba'" />
          <text :x="point.x + 24" :y="point.y + 23" class="graph-label">
            {{ point.node.name.length > 13 ? point.node.name.slice(0, 13) + '…' : point.node.name }}
          </text>
        </g>
      </svg>
      <div v-if="!layout.edges.length" class="graph-empty">当前任务流没有已启用的上游依赖。</div>
    </div>

    <template #footer>
      <el-button :loading="saving" @click="save()">保存依赖</el-button>
      <el-button type="primary" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>

    <el-dialog v-model="mappingVisible" title="参数映射" width="480px" append-to-body>
      <p class="hint">左侧为当前任务流参数，右侧为上游任务流参数；显式传入的同名参数不会被覆盖。</p>
      <div class="mapping-list">
        <div v-for="(row, index) in mappingRows" :key="index" class="mapping-row">
          <el-input v-model="row.key" placeholder="本地参数" />
          <span class="arrow">←</span>
          <el-input v-model="row.value" placeholder="上游参数" />
          <el-button @click="mappingRows.splice(index, 1)">删除</el-button>
        </div>
        <el-button size="small" :icon="Plus" @click="mappingRows.push({ key: '', value: '' })">添加映射</el-button>
      </div>
      <template #footer>
        <el-button @click="mappingVisible = false">取消</el-button>
        <el-button type="primary" @click="applyMapping">确定</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.dep-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.dep-intro { display: flex; flex-direction: column; gap: 4px; max-width: 500px; }
.dep-intro strong { font-size: 14px; color: var(--dk-ink); }
.dep-intro span { font-size: 12px; color: #667085; line-height: 1.6; }
.dep-actions { display: flex; gap: 8px; align-items: center; }
.dep-name { font-size: 13px; }
.batch-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; font-size: 12px; color: #667085; }
.graph-bar { display: flex; align-items: center; gap: 8px; margin: 16px 0 10px; flex-wrap: wrap; }
.graph-title { font-size: 13px; font-weight: 600; color: #344054; }
.unit { font-size: 12px; color: #98a2b3; }
.hint { font-size: 12px; color: #98a2b3; }
.graph-wrap { border: 1px solid #eaecf0; border-radius: 8px; background: #fcfcfd; overflow: auto; padding: 10px; max-height: 420px; }
.graph-edge { fill: none; stroke: #12b76a; stroke-width: 1.6; opacity: .75; }
.graph-node { fill: #fff; stroke: #d0d5dd; stroke-width: 1.2; }
.graph-node.root { fill: #e7f0ff; stroke: #215dba; }
.graph-node.disabled { fill: #f2f4f7; stroke: #d0d5dd; }
.graph-label { font-size: 11px; fill: #344054; }
.graph-empty { padding: 20px; text-align: center; font-size: 12px; color: #98a2b3; }
.mapping-list { display: grid; gap: 8px; }
.mapping-row { display: grid; grid-template-columns: 1fr 20px 1fr 60px; gap: 6px; align-items: center; }
.arrow { text-align: center; color: #98a2b3; }
</style>
