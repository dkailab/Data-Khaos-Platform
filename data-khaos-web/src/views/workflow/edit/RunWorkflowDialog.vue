<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { VideoPlay } from '@element-plus/icons-vue'

const props = defineProps<{
  modelValue: boolean
  workflowName?: string
  scopeLabel?: string
  defaultParams?: Record<string, any>
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'run', payload: { bizDate: string; params: Record<string, any> }): void
}>()

const form = reactive({ bizDate: '', paramsText: '{}' })

watch(() => props.modelValue, (open) => {
  if (!open) return
  const now = new Date()
  form.bizDate = new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
  form.paramsText = JSON.stringify(props.defaultParams || {}, null, 2)
})

function submit() {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(form.bizDate)) {
    ElMessage.warning('业务日期格式应为 YYYY-MM-DD')
    return
  }
  let params: Record<string, any>
  try {
    params = form.paramsText.trim() ? JSON.parse(form.paramsText) : {}
  } catch {
    ElMessage.warning('运行参数不是合法 JSON')
    return
  }
  if (!params || typeof params !== 'object' || Array.isArray(params)) {
    ElMessage.warning('运行参数必须是 JSON 对象')
    return
  }
  emit('run', { bizDate: form.bizDate, params })
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`执行任务流 · ${workflowName || ''}`" width="620px"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <el-alert v-if="scopeLabel" class="scope" type="info" :closable="false" show-icon :title="`运行范围：${scopeLabel}`" />
    <el-form label-position="top" size="small">
      <el-form-item label="业务日期">
        <el-input v-model="form.bizDate" placeholder="YYYY-MM-DD" />
        <div class="hint">业务日期会覆盖工作流参数中的 bizDate，并作为本次运行的幂等维度之一。</div>
      </el-form-item>
      <el-form-item label="运行参数（JSON）">
        <el-input v-model="form.paramsText" type="textarea" :rows="10" class="code-area" />
        <div class="hint">节点脚本用 ${param} 引用；提交时先校验上游任务流依赖是否满足。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :icon="VideoPlay" @click="submit">提交运行</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.scope { margin-bottom: 12px; }
.hint { margin-top: 4px; font-size: 12px; color: #98a2b3; line-height: 1.5; }
.code-area :deep(textarea) { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; line-height: 1.6; }
</style>
