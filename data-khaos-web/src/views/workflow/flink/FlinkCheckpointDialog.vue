<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { getFlinkCheckpoints, type FlinkCheckpointView } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assetId: string; runId: string }>()
const emit = defineEmits<{ close: [] }>()
const view = ref<FlinkCheckpointView>()
const loading = ref(false)
const error = ref('')
const attempt = ref(0)

watch([() => props.project, () => props.assetId, () => props.runId, attempt], async ([project, id, run], _, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''
  view.value = undefined
  try {
    const result = await getFlinkCheckpoints(project, id, run, request.signal)
    if (!request.signal.aborted) view.value = result
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '检查点读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

const summaries = computed(() => [
  ['已完成', view.value?.counts?.completed],
  ['失败', view.value?.counts?.failed],
  ['进行中', view.value?.counts?.inProgress],
  ['恢复次数', view.value?.counts?.restored],
])
const recoveryStatus = computed(() => {
  const completed = view.value?.latestCompleted
  const failed = view.value?.latestFailed
  if (failed && (!completed || failed.id > completed.id)) {
    const point = failed.savepoint ? '保存点' : '检查点'
    const detail = completed ? '最近完成的记录早于本次失败。' : '当前未查到已完成记录。'
    return { type: 'warning' as const, text: `${point} #${failed.id} 失败。${detail}` }
  }
  if (!completed) return { type: 'info' as const, text: '当前未查到已完成记录。' }
  return undefined
})
const labels = { COMPLETED: '已完成', FAILED: '失败', IN_PROGRESS: '进行中' } as const
const formatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })

function time(value?: string | null) {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '—' : formatter.format(parsed)
}
</script>

<template>
  <el-dialog :model-value="true" title="检查点诊断" append-to-body
    width="min(770px, calc(100vw - 24px))" @update:model-value="$event === false && emit('close')">
    <p v-if="loading" role="status">正在读取 Flink 检查点统计。</p>
    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-empty v-else-if="!view?.available" description="引擎尚未提供检查点统计" :image-size="60" />
    <template v-else>
      <dl class="checkpoint-summary">
        <div v-for="[label, count] in summaries" :key="label"><dt>{{ label }}</dt><dd>{{ count ?? '—' }}</dd></div>
      </dl>
      <el-alert v-if="recoveryStatus" :type="recoveryStatus.type" :title="recoveryStatus.text" :closable="false" show-icon />
      <p v-if="view.latestCompleted">最近完成 #{{ view.latestCompleted.id }} · {{ time(view.latestCompleted.triggeredAt) }} 触发</p>
      <p v-if="view.latestFailed">最近失败 #{{ view.latestFailed.id }} · {{ time(view.latestFailed.triggeredAt) }} 触发</p>
      <p v-if="view.latestRestore">最近恢复自{{ view.latestRestore.savepoint ? '保存点' : '检查点' }}
        #{{ view.latestRestore.id }} · {{ time(view.latestRestore.restoredAt) }}</p>
      <el-table :data="view.recent" row-key="id" empty-text="暂无检查点历史">
        <el-table-column prop="id" label="编号" width="72" />
        <el-table-column label="类型" width="95"><template #default="{ row }">{{ row.savepoint ? '保存点' : '检查点' }}</template></el-table-column>
        <el-table-column label="状态" width="88"><template #default="{ row }">{{ labels[row.status as keyof typeof labels] }}</template></el-table-column>
        <el-table-column label="触发时间" min-width="145"><template #default="{ row }">{{ time(row.triggeredAt) }}</template></el-table-column>
        <el-table-column label="耗时 (ms)" min-width="90"><template #default="{ row }">{{ row.durationMs ?? '—' }}</template></el-table-column>
        <el-table-column label="大小 (B)" min-width="88"><template #default="{ row }">{{ row.checkpointedBytes ?? '—' }}</template></el-table-column>
      </el-table>
    </template>
    <template #footer><DkButton :loading="loading" @click="attempt++">刷新诊断</DkButton></template>
  </el-dialog>
</template>

<style scoped>
.checkpoint-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
  gap: 10px;
  margin: 0 0 16px;
}
.checkpoint-summary div { background: var(--dk-soft, #edf2f9); border-radius: 8px; padding: 12px; }
.checkpoint-summary dd { font-size: 20px; font-weight: 650; margin: 4px 0 0; }
</style>
