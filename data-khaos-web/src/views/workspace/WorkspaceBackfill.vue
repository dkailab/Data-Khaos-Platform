<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { submitWorkspaceBackfill, type WorkflowBackfillReceipt } from '@/api/project-group'
import DkButton from '@/paas-ui/DkButton.vue'
import { useUserStore } from '@/stores/user'
import { createRequestId } from '@/utils/requestId'

const props = defineProps<{ projectId: string; taskId: string; initialDate: string }>()
const emit = defineEmits<{ (e: 'open-run', id: string): void }>()
const user = useUserStore()
const fromDate = ref(props.initialDate)
const toDate = ref(props.initialDate)
const receipt = ref<WorkflowBackfillReceipt>()
const submitting = ref(false)
const failedToConfirm = ref(false)
const requests = new Map<string, string>()
const key = computed(() => 'dk.workspace.backfill:' + JSON.stringify([
  user.userInfo?.id, props.projectId, props.taskId, fromDate.value, toDate.value,
]))
const dayCount = computed(() => {
  if (!fromDate.value || !toDate.value) return 0
  return (Date.parse(toDate.value) - Date.parse(fromDate.value)) / 86_400_000 + 1
})
const valid = computed(() => dayCount.value >= 1 && dayCount.value <= 31)
const complete = computed(() => receipt.value?.items.length === dayCount.value &&
  receipt.value.items.every(item => item.state !== 'REJECTED'))
const labels = { ACCEPTED: '已受理', DUPLICATE: '已找到原运行', REJECTED: '未受理' }
const retryable = computed(() => failedToConfirm.value ||
  receipt.value?.items.some(item => item.state === 'REJECTED'))

watch([fromDate, toDate], () => {
  receipt.value = undefined
  failedToConfirm.value = false
})

function storedRequest(id: string) {
  try {
    return sessionStorage.getItem(id) || requests.get(id)
  } catch {
    return requests.get(id)
  }
}

async function submit() {
  if (!valid.value || submitting.value || complete.value) return
  const id = key.value
  const request = storedRequest(id) || createRequestId()
  requests.set(id, request)
  try {
    sessionStorage.setItem(id, request)
  } catch {
    // The request remains reusable while this form is open.
  }
  submitting.value = true
  failedToConfirm.value = false
  try {
    receipt.value = await submitWorkspaceBackfill(props.projectId, props.taskId, request, fromDate.value, toDate.value)
  } catch {
    failedToConfirm.value = true
  } finally {
    submitting.value = false
  }
}

function newBatch() {
  if (!complete.value) return
  requests.delete(key.value)
  try {
    sessionStorage.removeItem(key.value)
  } catch {
    // The in-memory key has already been removed.
  }
  receipt.value = undefined
}
</script>

<template>
  <section class="backfill" aria-label="工作流补数">
    <h3>工作流补数</h3>
    <form class="backfill-form" @submit.prevent="submit">
      <label>起始业务日期<input v-model="fromDate" type="date" required :disabled="submitting" /></label>
      <label>结束业务日期<input v-model="toDate" type="date" required :min="fromDate || undefined" :disabled="submitting" /></label>
      <DkButton type="submit" variant="primary" :disabled="!valid || complete" :loading="submitting">
        {{ retryable ? '重试原批次' : '提交补数' }}
      </DkButton>
    </form>
    <p v-if="!failedToConfirm" class="hint">
      {{ fromDate && toDate && !valid ? '请选择正序且不超过 31 天的区间。' : '起止日期均计入区间，最多 31 天。' }}
    </p>
    <el-alert v-if="failedToConfirm" title="暂时无法确认提交结果，可用同一批次键重试。" type="warning" :closable="false" show-icon />
    <template v-if="receipt">
      <p class="hint" role="status">批次 <code class="dk-mono">{{ receipt.requestId }}</code></p>
      <el-table :data="receipt.items" size="small">
        <el-table-column prop="bizDate" label="业务日期" width="115" />
        <el-table-column label="受理状态" width="112">
          <template #default="{ row }">{{ labels[row.state as keyof typeof labels] || row.state }}</template>
        </el-table-column>
        <el-table-column label="原因与运行" min-width="135">
          <template #default="{ row }">
            <div v-if="row.rejectMessage" class="reject-message">{{ row.rejectMessage }}</div>
            <el-button v-if="row.runId || row.activeRunId" link type="primary" @click="emit('open-run', row.runId || row.activeRunId)">
              {{ row.runId ? '查看运行' : '查看活跃运行' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <DkButton v-if="complete" variant="quiet" @click="newBatch">开始新批次</DkButton>
    </template>
  </section>
</template>

<style scoped>
.backfill { margin-top:var(--dk-space-5); }
h3 { font-size:15px; margin:0 0 var(--dk-space-2); }
.backfill-form { display:flex; align-items:end; flex-wrap:wrap; gap:var(--dk-space-2); }
label { display:grid; gap:4px; font-size:12px; }
input { min-height:36px; padding:0 var(--dk-space-2); border:1px solid var(--dk-border);
  border-radius:var(--dk-radius-control); color:var(--dk-ink); background:var(--dk-surface); font:inherit; }
.hint { color:var(--dk-muted); font-size:12px; overflow-wrap:anywhere; }
.reject-message { overflow-wrap:anywhere; }
</style>
