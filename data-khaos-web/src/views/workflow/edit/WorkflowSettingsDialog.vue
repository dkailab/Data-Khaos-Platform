<script setup lang="ts">
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { WorkflowDef } from '@/types'

const props = defineProps<{ modelValue: boolean; def: WorkflowDef }>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'save'): void
}>()

const form = reactive({ description: '', cronExpression: '', owner: '', params: '', status: 0 })

watch(() => props.modelValue, (open) => {
  if (!open) return
  form.description = props.def.description || ''
  form.cronExpression = props.def.cronExpression || ''
  form.owner = props.def.owner || ''
  form.params = props.def.params || ''
  form.status = props.def.status ?? 0
})

function apply() {
  if (form.params.trim()) {
    try {
      const parsed = JSON.parse(form.params)
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        ElMessage.warning('运行参数必须是 JSON 对象')
        return
      }
    } catch {
      ElMessage.warning('运行参数不是合法 JSON')
      return
    }
  }
  props.def.description = form.description
  props.def.cronExpression = form.cronExpression.trim()
  props.def.owner = form.owner
  props.def.params = form.params.trim()
  emit('save')
  emit('update:modelValue', false)
}

function statusText() {
  return form.status === 2 ? '已发布' : form.status === 1 ? '已启用' : '未发布/停用'
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="任务流参数设置" width="620px"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <el-form label-position="top" size="small">
      <el-form-item label="当前状态">
        <el-tag :type="form.status === 2 ? 'success' : form.status === 1 ? 'primary' : 'info'">{{ statusText() }}</el-tag>
        <span class="hint">发布/下线在顶部工具栏操作，发布会自动生成版本快照。</span>
      </el-form-item>
      <el-form-item label="Cron 表达式">
        <el-input v-model="form.cronExpression" placeholder="如 0 0 2 * * ?；留空表示仅手动运行" />
        <div class="hint">支持 Spring Cron（秒 分 时 日 月 周）。修改后需保存并由调度轮询生效。</div>
      </el-form-item>
      <el-form-item label="负责人">
        <el-input v-model="form.owner" placeholder="负责人 ID 或姓名" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
      </el-form-item>
      <el-form-item label="运行参数（JSON）">
        <el-input v-model="form.params" type="textarea" :rows="8" class="code-area"
          placeholder='{"bizDate": "2026-11-27", "env": "prod"}' />
        <div class="hint">节点脚本用 ${param} 引用；业务日期运行时会覆盖 bizDate。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" @click="apply">应用并保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint { margin-left: 8px; font-size: 12px; color: #98a2b3; }
.code-area :deep(textarea) { font-family: 'JetBrains Mono', Consolas, monospace; font-size: 12px; line-height: 1.6; }
</style>
