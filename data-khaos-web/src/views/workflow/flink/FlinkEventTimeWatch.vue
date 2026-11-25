<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import {
  configureFlinkEventTimeAlerts, getFlinkEventTimeAlerts, type FlinkEventTimeHistory, type FlinkMetricsView,
} from '@/api/flinkStudio'
import { eventTimeLabels } from './eventTimeDiagnosis'

const props = defineProps<{
  project: string; assetId: string; runId: string; refresh: number; canConfigure: boolean
  vertex?: FlinkMetricsView['vertices'][number]; vertices: FlinkMetricsView['vertices']
}>()
const history = ref<FlinkEventTimeHistory>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const statusLabels = { OPEN: '告警中', RECOVERED: '已恢复', DISABLED: '巡检已停用', ENDED: '作业或节点已结束' } as const
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
const number = new Intl.NumberFormat('zh-CN')
const watchNode = computed(() => history.value?.watch?.vertexId)
const canStart = computed(() => props.canConfigure && props.vertex?.status === 'RUNNING' && !history.value?.watch?.enabled)

watch(() => props.refresh, async (_, __, onCleanup) => {
  if (saving.value) return
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''
  try {
    const result = await getFlinkEventTimeAlerts(props.project, props.assetId, props.runId, request.signal)
    if (!request.signal.aborted) history.value = result
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '水位线巡检记录读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

async function configure(enabled: boolean) {
  if (!props.canConfigure || saving.value || loading.value || (enabled && !canStart.value)) return
  saving.value = true
  error.value = ''
  try {
    history.value = await configureFlinkEventTimeAlerts(props.project, props.assetId, props.runId, enabled,
      enabled ? props.vertex?.id : undefined)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '水位线巡检配置失败'
  } finally {
    saving.value = false
  }
}

function node(id: string) {
  return props.vertices.find(vertex => vertex.id === id)?.name ?? id
}

function time(value: string | number | null) {
  if (value == null) return '—'
  const timestamp = new Date(value)
  return Number.isNaN(timestamp.getTime()) ? '—' : date.format(timestamp)
}
</script>

<template>
  <section class="event-watch" aria-labelledby="flink-event-watch-heading">
    <div class="heading">
      <h3 id="flink-event-watch-heading">水位线巡检与告警</h3>
      <template v-if="history?.available && canConfigure">
        <DkButton v-if="history.watch?.enabled" :loading="saving" :disabled="loading" @click="configure(false)">停用水位线巡检</DkButton>
        <DkButton v-else :loading="saving" :disabled="loading || !canStart" @click="configure(true)">启用水位线巡检</DkButton>
      </template>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <p v-else-if="loading" class="muted" role="status">正在读取巡检记录。</p>
    <p v-else-if="history && !history.available" class="muted">当前环境未启用水位线巡检。</p>
    <template v-if="history?.available">
      <p v-if="history.watch?.enabled && watchNode" class="muted">巡检节点 · {{ node(watchNode) }}</p>
      <p v-else-if="canStart && vertex" class="muted">可为当前节点启用后台巡检 · {{ vertex.name }}</p>
      <p v-else class="muted">当前运行没有启用水位线巡检。</p>
      <el-tabs>
        <el-tab-pane label="告警历史">
          <el-table :data="history.alerts" row-key="id" size="small" max-height="260" empty-text="暂无水位线告警">
            <el-table-column label="状态" min-width="104">
              <template #default="{ row }">{{ statusLabels[row.status as keyof typeof statusLabels] }}</template>
            </el-table-column>
            <el-table-column label="执行节点" min-width="134" show-overflow-tooltip>
              <template #default="{ row }">{{ node(row.vertexId) }}</template>
            </el-table-column>
            <el-table-column label="停滞水位线" min-width="136">
              <template #default="{ row }">{{ time(row.stalledWatermarkMs) }}</template>
            </el-table-column>
            <el-table-column label="触发时间" min-width="140"><template #default="{ row }">{{ time(row.openedAt) }}</template></el-table-column>
            <el-table-column label="关闭时间" min-width="140"><template #default="{ row }">{{ time(row.closedAt) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="历史采样">
          <el-table :data="history.points" size="small" max-height="260" empty-text="暂无后台巡检样本">
            <el-table-column label="采样时间" min-width="140"><template #default="{ row }">{{ time(row.sampledAtMs) }}</template></el-table-column>
            <el-table-column label="执行节点" min-width="125" show-overflow-tooltip>
              <template #default="{ row }">{{ node(row.vertexId) }}</template>
            </el-table-column>
            <el-table-column label="输入水位线" min-width="138"><template #default="{ row }">{{ time(row.watermarkMs) }}</template></el-table-column>
            <el-table-column label="输入增量" min-width="95">
              <template #default="{ row }">{{ row.inputDelta == null ? '—' : number.format(row.inputDelta) + ' 条' }}</template>
            </el-table-column>
            <el-table-column label="诊断" min-width="155">
              <template #default="{ row }">{{ eventTimeLabels[row.diagnosis as keyof typeof eventTimeLabels] }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
</template>

<style scoped>
.event-watch { margin-top: var(--dk-space-4); min-width: 0; }
.heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--dk-space-2); }
.heading h3 { margin: 0; font-size: 15px; }
.muted { color: var(--dk-muted); overflow-wrap: anywhere; }
</style>
