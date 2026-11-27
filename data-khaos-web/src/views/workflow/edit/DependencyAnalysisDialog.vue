<script setup lang="ts">
import { computed } from 'vue'
import type { WorkflowEdge, WorkflowNode } from '@/types'
import { nodeMeta, parseConfig } from './nodeCatalog'

const props = defineProps<{
  modelValue: boolean
  nodes: WorkflowNode[]
  edges: WorkflowEdge[]
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const analysis = computed(() => {
  const indegree = new Map<string, number>()
  const outdegree = new Map<string, number>()
  props.nodes.forEach(node => {
    indegree.set(node.nodeCode, 0)
    outdegree.set(node.nodeCode, 0)
  })
  let dangling = 0
  props.edges.forEach(edge => {
    if (!indegree.has(edge.fromCode) || !indegree.has(edge.toCode)) {
      dangling++
      return
    }
    indegree.set(edge.toCode, indegree.get(edge.toCode)! + 1)
    outdegree.set(edge.fromCode, outdegree.get(edge.fromCode)! + 1)
  })
  const roots = props.nodes.filter(node => (indegree.get(node.nodeCode) ?? 0) === 0)
  const ends = props.nodes.filter(node => (outdegree.get(node.nodeCode) ?? 0) === 0)
  const isolated = props.nodes.filter(node =>
    (indegree.get(node.nodeCode) ?? 0) === 0 && (outdegree.get(node.nodeCode) ?? 0) === 0)

  const issues: string[] = []
  if (!props.nodes.length) issues.push('任务流还没有节点')
  if (dangling) issues.push(`${dangling} 条连线引用了不存在的节点`)
  props.nodes.forEach(node => {
    if ((node.skipFlag ?? 0) === 1) return
    const config = parseConfig(node)
    const kind = nodeMeta(node.nodeType).contentKind
    if (kind === 'sql') {
      if (config.taskMode === 'EXTERNAL' && !config.sqlFileId) {
        issues.push(`「${node.nodeName}」外部任务未选择 SQL 文件`)
      } else if (config.taskMode !== 'EXTERNAL' && !String(config.sql || '').trim()) {
        issues.push(`「${node.nodeName}」未填写 SQL`)
      }
    }
    if (kind === 'pipeline' && !config.pipelineTaskId) issues.push(`「${node.nodeName}」未选择同步任务`)
    if (kind === 'quality' && !config.qualityTaskId) issues.push(`「${node.nodeName}」未选择质量任务`)
    if (kind === 'nested' && !config.workflowId) issues.push(`「${node.nodeName}」未选择子任务流`)
  })
  return {
    roots: roots.map(node => node.nodeName),
    ends: ends.map(node => node.nodeName),
    isolated: isolated.map(node => node.nodeName),
    issues,
  }
})
</script>

<template>
  <el-dialog :model-value="modelValue" title="依赖解析" width="640px"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="节点数">{{ nodes.length }}</el-descriptions-item>
      <el-descriptions-item label="依赖数">{{ edges.length }}</el-descriptions-item>
      <el-descriptions-item label="起始节点">{{ analysis.roots.length }}</el-descriptions-item>
    </el-descriptions>

    <div class="block">
      <div class="block-title">起始节点</div>
      <div class="chips">
        <el-tag v-for="name in analysis.roots" :key="name" size="small">{{ name }}</el-tag>
        <span v-if="!analysis.roots.length" class="muted">无</span>
      </div>
    </div>
    <div class="block">
      <div class="block-title">结束节点</div>
      <div class="chips">
        <el-tag v-for="name in analysis.ends" :key="name" size="small" type="success">{{ name }}</el-tag>
        <span v-if="!analysis.ends.length" class="muted">无</span>
      </div>
    </div>
    <div v-if="analysis.isolated.length" class="block">
      <div class="block-title">孤立节点</div>
      <div class="chips">
        <el-tag v-for="name in analysis.isolated" :key="name" size="small" type="warning">{{ name }}</el-tag>
      </div>
      <div class="hint">孤立节点没有上下游依赖，运行时会与其它分支并行开始。</div>
    </div>

    <div class="block">
      <div class="block-title">待补全配置</div>
      <ul v-if="analysis.issues.length" class="issues">
        <li v-for="issue in analysis.issues" :key="issue">{{ issue }}</li>
      </ul>
      <div v-else class="ok">依赖结构与节点配置未发现问题。</div>
    </div>

    <template #footer>
      <el-button type="primary" @click="emit('update:modelValue', false)">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.block { margin-top: 14px; }
.block-title { font-size: 12px; color: #667085; margin-bottom: 6px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.muted { font-size: 12px; color: #98a2b3; }
.hint { margin-top: 6px; font-size: 12px; color: #98a2b3; }
.issues { margin: 0; padding-left: 18px; font-size: 12.5px; color: #b54708; line-height: 1.8; }
.ok { font-size: 12.5px; color: #0e9f6e; }
</style>
