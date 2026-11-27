<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deleteWorkflowSqlFile, publishWorkflowSqlFile, saveWorkflowSqlFile } from '@/api/workflow'
import type { WorkflowSqlFile } from '@/types'

const props = defineProps<{
  modelValue: boolean
  projectId?: string
  file?: WorkflowSqlFile
  prefillName?: string
  prefillContent?: string
  datasources: Array<{ id: string; dsName: string; dsType?: string }>
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'saved', file: WorkflowSqlFile): void
  (e: 'deleted', id: string): void
}>()

const form = reactive({ folder: '/SQL文件', name: '', description: '', datasourceId: '', content: '' })
const saving = ref(false)
const current = ref<WorkflowSqlFile>()

const title = computed(() => current.value ? '编辑 SQL 文件' : '新建 SQL 文件')

watch(() => props.modelValue, (open) => {
  if (!open) return
  current.value = props.file
  if (props.file) {
    form.folder = props.file.folder || '/SQL文件'
    form.name = props.file.name
    form.description = props.file.description || ''
    form.datasourceId = props.file.datasourceId || ''
    form.content = props.file.draftContent || ''
  } else {
    form.folder = '/SQL文件'
    form.name = props.prefillName || ''
    form.description = ''
    form.datasourceId = ''
    form.content = props.prefillContent || ''
  }
})

async function save(publish = false) {
  if (!form.name.trim()) {
    ElMessage.warning('请填写文件名')
    return
  }
  saving.value = true
  try {
    const saved = await saveWorkflowSqlFile({
      id: current.value?.id,
      projectId: props.projectId,
      folder: form.folder.trim() || '/SQL文件',
      name: form.name.trim(),
      description: form.description.trim(),
      datasourceId: form.datasourceId,
      content: form.content,
    })
    current.value = saved
    if (publish) {
      const published = await publishWorkflowSqlFile(saved.id!)
      current.value = published
      ElMessage.success('已保存并发布')
    } else {
      ElMessage.success('SQL 文件已保存')
    }
    emit('saved', current.value)
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!current.value?.id) return
  await ElMessageBox.confirm('删除后，引用该文件的任务节点运行时会报错，确认删除？', '删除 SQL 文件',
    { type: 'warning' })
  await deleteWorkflowSqlFile(current.value.id)
  ElMessage.success('已删除')
  emit('deleted', current.value.id)
  emit('update:modelValue', false)
}
</script>

<template>
  <el-dialog :model-value="modelValue" :title="title" width="760px"
    @update:model-value="emit('update:modelValue', $event)" @close="emit('update:modelValue', false)">
    <el-form label-position="top" size="small" class="file-form">
      <div class="row two">
        <el-form-item label="目录">
          <el-input v-model="form.folder" placeholder="/SQL文件/ADS" />
        </el-form-item>
        <el-form-item label="文件名">
          <el-input v-model="form.name" maxlength="200" placeholder="如 ads_sale_brief_d.sql" />
        </el-form-item>
      </div>
      <el-form-item label="说明">
        <el-input v-model="form.description" maxlength="500" placeholder="口径、负责人、使用场景" />
      </el-form-item>
      <el-form-item label="默认数据源">
        <el-select v-model="form.datasourceId" filterable clearable placeholder="节点未指定数据源时使用" style="width: 100%">
          <el-option v-for="ds in datasources" :key="ds.id"
            :label="ds.dsType ? `${ds.dsName} (${ds.dsType})` : ds.dsName" :value="ds.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="SQL 内容">
        <el-input v-model="form.content" type="textarea" :rows="16" class="code-area"
          placeholder="支持 ${参数} 替换；拆分执行模式下按分号拆分" />
      </el-form-item>
      <div v-if="current" class="version-row">
        <el-tag size="small" type="info">草稿 v{{ current.draftVersion ?? 1 }}</el-tag>
        <el-tag v-if="(current.publishedVersion ?? 0) > 0" size="small" type="success">
          已发布 v{{ current.publishedVersion }}
        </el-tag>
        <span class="hint">「最新版本」读取已发布快照；未发布过时回退草稿。</span>
      </div>
    </el-form>
    <template #footer>
      <div class="dialog-footer">
        <el-button v-if="current?.id" type="danger" plain @click="remove">删除</el-button>
        <span class="spacer" />
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button :loading="saving" @click="save(false)">保存</el-button>
        <el-button type="primary" :loading="saving" @click="save(true)">保存并发布</el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.file-form :deep(.el-form-item) { margin-bottom: 12px; }
.row.two { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.code-area :deep(textarea) { font-family: 'JetBrains Mono', 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px; line-height: 1.6; }
.version-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.hint { font-size: 12px; color: #98a2b3; }
.dialog-footer { display: flex; align-items: center; gap: 8px; }
.spacer { flex: 1; }
</style>
