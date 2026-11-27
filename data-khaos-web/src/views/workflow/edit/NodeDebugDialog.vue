<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'
import { debugWorkflowNode } from '@/api/workflow'
import type { NodeDebugResult, WorkflowNode } from '@/types'
import { nodeMeta } from './nodeCatalog'

const props = defineProps<{
  modelValue: boolean
  wfId?: string
  node?: WorkflowNode
  defaultParams?: Record<string, any>
}>()
const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const form = reactive({ bizDate: '', paramsText: '{}' })
const running = ref(false)
const result = ref<NodeDebugResult>()
const meta = ref(nodeMeta(props.node?.nodeType))

watch(() => props.modelValue, (open) => {
  if (!open) return
  const today = new Date()
  const local = new Date(today.getTime() - today.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
  form.bizDate = local
  form.paramsText = JSON.stringify(props.defaultParams || {}, null, 2)
  result.value = undefined
  meta.value = nodeMeta(props.node?.nodeType)
})

async function run() {
  if (!props.wfId || !props.node) {
    ElMessage.warning('请先保存任务流')
    return
  }
  let params: Record<string, any>
  try {
    params = form.paramsText.trim() ? JSON.parse(form.paramsText) : {}
  } catch {
    ElMessage.warning('参数不是合法 JSON')
    return
  }
  params.bizDate = form.bizDate
  running.value = true
  result.value = undefined
  try {
    result.value = await debugWorkflowNode(props.wfId, props.node.nodeCode!, params)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" width="700px" :title="`调试 · ${node?.nodeName || ''}`"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <div class="debug-head">
      <span class="type-chip" :style="{ color: meta.color, background: meta.soft }">{{ meta.label }}</span>
      <span class="hint">同步执行当前节点，不创建运行实例、不推进下游。</span>
    </div>
    <el-form label-position="top" size="small">
      <div class="row">
        <el-form-item label="业务日期">
          <el-input v-model="form.bizDate" placeholder="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="节点参数（JSON）">
          <el-input v-model="form.paramsText" type="textarea" :rows="4" class="code-area" />
        </el-form-item>
      </div>
    </el-form>
    <el-alert v-if="result?.error" type="error" :title="result.error" :closable="false" show-icon />
    <div v-if="result" class="result">
      <div class="result-meta">
        <el-tag :type="result.success ? 'success' : 'danger'" size="small">
          {{ result.success ? '执行成功' : '执行失败' }}
        </el-tag>
        <span>耗时 {{ result.durationMs }} ms</span>
        <span v-if="result.success">影响/结果行数 {{ result.rows }}</span>
      </div>
      <pre v-if="result.log" class="log">{{ result.log }}</pre>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button type="primary" :icon="VideoPlay" :loading="running" @click="run">执行调试</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.debug-head { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.type-chip { padding: 3px 8px; border-radius: 6px; font-size: 12px; font-weight: 600; }
.hint { font-size: 12px; color: #98a2b3; }
.row { display: grid; grid-template-columns: 160px 1fr; gap: 10px; }
.code-area :deep(textarea) { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; }
.result { margin-top: 12px; border: 1px solid #eaecf0; border-radius: 8px; overflow: hidden; }
.result-meta { display: flex; align-items: center; gap: 12px; padding: 8px 10px; background: #f9fafb;
  font-size: 12px; color: #667085; }
.log { margin: 0; padding: 10px; max-height: 320px; overflow: auto; background: #101828; color: #d1e9ff;
  font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
</style>
