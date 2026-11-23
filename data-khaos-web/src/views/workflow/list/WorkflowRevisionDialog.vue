<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { createWorkflowRevision, createWorkflowRevisionDraft, pageWorkflowRevisions } from '@/api/workflow'
import DkButton from '@/paas-ui/DkButton.vue'
import type { WorkflowDef, WorkflowRevision } from '@/types'

const props = defineProps<{ workflow: WorkflowDef & { id: string } }>()
const emit = defineEmits<{ close: []; openDraft: [id: string] }>()
const records = ref<WorkflowRevision[]>([])
const current = ref(1)
const size = 10
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const note = ref('')
const draft = ref<WorkflowRevision>()
const name = ref('')
let closed = false
let requestSequence = 0

async function load() {
  const sequence = ++requestSequence
  error.value = ''
  loading.value = true
  try {
    const page = await pageWorkflowRevisions(props.workflow.id, current.value, size)
    if (closed || sequence !== requestSequence) return
    records.value = page.records
    total.value = Number(page.total)
  } catch (e) {
    if (!closed && sequence === requestSequence) {
      records.value = []
      error.value = e instanceof Error ? e.message : '版本加载失败'
    }
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

async function saveVersion() {
  if (saving.value || !note.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const version = await createWorkflowRevision(props.workflow.id, note.value)
    if (closed) return
    ElMessage.success(`版本 v${version.revisionNumber} 已保存`)
    note.value = ''
    current.value = 1
    await load()
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '保存版本失败'
  } finally {
    saving.value = false
  }
}

function chooseDraft(version: WorkflowRevision) {
  error.value = ''
  draft.value = version
  const suffix = ` · v${version.revisionNumber} 恢复草稿`
  name.value = props.workflow.name.slice(0, 200 - suffix.length) + suffix
}

async function createDraft() {
  if (saving.value || !draft.value || !name.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const workflow = await createWorkflowRevisionDraft(props.workflow.id, draft.value.id, name.value)
    if (closed) return
    if (!workflow.id) throw new Error('服务未返回草稿 ID，请在工作流列表核对')
    ElMessage.success('已创建停用草稿')
    emit('openDraft', workflow.id)
  } catch (e) {
    if (!closed) error.value = e instanceof Error ? e.message : '生成草稿失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
onBeforeUnmount(() => { closed = true })
</script>

<template>
  <el-dialog :model-value="true" :title="`工作流版本 · ${workflow.name}`"
    width="min(820px, calc(100vw - 32px))" @update:model-value="$event === false && emit('close')">
    <p class="version-hint">版本包含已保存的节点、依赖和默认参数。可从历史版本生成独立的停用草稿。</p>
    <el-alert v-if="error" class="version-error" type="error" :title="error" :closable="false" show-icon />
    <form class="version-form" @submit.prevent="saveVersion">
      <el-input v-model="note" maxlength="500" show-word-limit aria-label="版本说明"
        placeholder="填写版本说明" :disabled="saving || loading" />
      <DkButton variant="primary" type="submit" :disabled="!note.trim() || loading || !!draft" :loading="saving && !draft">
        保存版本
      </DkButton>
    </form>
    <el-table v-loading="loading" :data="records" row-key="id" empty-text="暂无版本">
      <el-table-column label="版本" width="76">
        <template #default="{ row }">v{{ row.revisionNumber }}</template>
      </el-table-column>
      <el-table-column prop="note" label="版本说明" min-width="130" show-overflow-tooltip />
      <el-table-column label="图结构" width="118">
        <template #default="{ row }">{{ row.nodeCount }} 节点 / {{ row.edgeCount }} 连线</template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ row.createTime?.replace('T', ' ').slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column prop="createdBy" label="创建人 ID" min-width="110" show-overflow-tooltip />
      <el-table-column label="操作" width="98">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="saving" @click="chooseDraft(row)">恢复为草稿</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="total > size" v-model:current-page="current" :page-size="size" :total="total"
      layout="prev, pager, next" @current-change="load" />
    <form v-if="draft" class="version-form draft-form" @submit.prevent="createDraft">
      <label :for="'workflow-draft-name-' + draft.id">v{{ draft.revisionNumber }} 草稿名称</label>
      <el-input :id="'workflow-draft-name-' + draft.id" v-model="name" maxlength="200" :disabled="saving" />
      <DkButton type="submit" variant="primary" :loading="saving" :disabled="!name.trim()">创建草稿</DkButton>
      <DkButton :disabled="saving" @click="draft = undefined">取消</DkButton>
    </form>
    <template #footer>
      <DkButton :loading="loading" :disabled="saving" @click="load">刷新</DkButton>
    </template>
  </el-dialog>
</template>

<style scoped>
.version-hint { margin: 0 0 16px; color: var(--dk-muted, #52627a); }
.version-error { margin-bottom: 12px; }
.version-form { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 16px; }
.version-form .el-input { flex: 1 1 220px; }
.draft-form { margin: 16px 0 0; }
</style>
