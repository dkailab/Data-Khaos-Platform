<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { getFlinkMetrics, getFlinkPressureAlerts, type FlinkMetricsView, type FlinkPressureAlerts } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assetId: string; runId: string }>()
const emit = defineEmits<{ close: [] }>()
const vertices = ref<FlinkMetricsView['vertices']>([])
const requested = ref('')
const selected = ref<string | null>(null)
const sample = ref<FlinkMetricsView['sample']>(null)
const loading = ref(false)
const error = ref('')
const attempt = ref(0)
const pressure = ref<FlinkPressureAlerts>()
const pressureLoading = ref(false)
const pressureError = ref('')
const alertLabels = { OPEN: '告警中', RECOVERED: '已恢复', ENDED: '作业已结束' } as const
const current = computed(() => vertices.value.find(vertex => vertex.id === selected.value))
const selection = computed(() => requested.value || selected.value || '')
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 })
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
const cards = computed(() => [
  { label: '输入吞吐', value: format(sample.value?.recordsInPerSecond, '条/秒') },
  { label: '输出吞吐', value: format(sample.value?.recordsOutPerSecond, '条/秒') },
  { label: '最高反压', value: percent(sample.value?.maxBackpressureMsPerSecond) },
  { label: '平均忙碌', value: percent(sample.value?.avgBusyMsPerSecond) },
  { label: '平均空闲', value: percent(sample.value?.avgIdleMsPerSecond) },
  { label: '最慢输入水位线', value: time(sample.value?.inputWatermark) },
])

function format(value: number | null | undefined, unit: string) {
  return value == null ? '—' : `${number.format(value)} ${unit}`
}

function percent(value: number | null | undefined) {
  return format(value == null ? null : value / 10, '%')
}

function time(value?: string | null) {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '—' : date.format(parsed)
}

watch([requested, attempt], async (_, __, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  loading.value = true
  error.value = ''
  sample.value = null
  try {
    const result = await getFlinkMetrics(props.project, props.assetId, props.runId, selection.value, request.signal)
    if (request.signal.aborted) return
    vertices.value = result.vertices
    selected.value = result.selectedVertexId
    sample.value = result.sample
  } catch (e) {
    if (!request.signal.aborted) error.value = e instanceof Error ? e.message : '运行指标读取失败'
  } finally {
    if (!request.signal.aborted) loading.value = false
  }
}, { immediate: true })

watch(attempt, async (_, __, onCleanup) => {
  const request = new AbortController()
  onCleanup(() => request.abort())
  pressureLoading.value = true
  pressureError.value = ''
  try {
    const result = await getFlinkPressureAlerts(props.project, props.assetId, props.runId, request.signal)
    if (!request.signal.aborted) pressure.value = result
  } catch (e) {
    if (!request.signal.aborted) pressureError.value = e instanceof Error ? e.message : '反压告警读取失败'
  } finally {
    if (!request.signal.aborted) pressureLoading.value = false
  }
}, { immediate: true })
</script>

<template>
  <el-dialog :model-value="true" title="Flink 运行指标" append-to-body
    width="min(730px, calc(100vw - 24px))" @update:model-value="$event === false && emit('close')">
    <div v-if="vertices.length" class="metrics-toolbar">
      <label for="flink-execution-vertex">执行节点</label>
      <el-select id="flink-execution-vertex" :model-value="selection" filterable
        @update:model-value="requested = String($event)">
        <el-option v-for="vertex in vertices" :key="vertex.id" :label="vertex.name" :value="vertex.id" />
      </el-select>
    </div>
    <p v-if="loading" role="status">正在读取执行节点指标。</p>
    <el-alert v-else-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-empty v-else-if="!current" description="引擎尚未提供执行节点" :image-size="60" />
    <template v-else>
      <p class="metrics-meta">{{ current.status }} · 并行度 {{ current.parallelism }}
        <span v-if="sample"> · 查询时间 {{ time(sample.sampledAt) }}</span>
      </p>
      <el-empty v-if="!sample" :description="current.status === 'RUNNING' ? '暂未采集到指标，可稍后刷新' : '当前节点没有实时样本'"
        :image-size="60" />
      <template v-else>
        <dl class="metrics-cards">
          <div v-for="card in cards" :key="card.label"><dt>{{ card.label }}</dt><dd>{{ card.value }}</dd></div>
        </dl>
        <section v-if="sample.operators?.length" class="operator-metrics" aria-labelledby="flink-operator-metrics">
          <h3 id="flink-operator-metrics">算子指标</h3>
          <el-table :data="sample.operators" row-key="name" size="small">
            <el-table-column prop="name" label="算子" min-width="135" show-overflow-tooltip />
            <el-table-column label="输入水位线" min-width="134">
              <template #default="{ row }">{{ time(row.inputWatermark) }}</template>
            </el-table-column>
            <el-table-column label="输出水位线" min-width="134">
              <template #default="{ row }">{{ time(row.outputWatermark) }}</template>
            </el-table-column>
            <el-table-column label="源端待取" min-width="100">
              <template #default="{ row }">{{ format(row.pendingRecords, '条') }}</template>
            </el-table-column>
            <el-table-column label="Kafka 最大分区积压" min-width="150">
              <template #default="{ row }">{{ format(row.kafkaMaxLag, '条') }}</template>
            </el-table-column>
          </el-table>
        </section>
        <p v-if="sample.operatorMetricsTruncated" class="metrics-meta">此节点仅展示部分算子指标。</p>
        <p class="metrics-meta">吞吐汇总可用子任务样本。— 表示该指标暂无样本。</p>
      </template>
    </template>
    <section class="operator-metrics" aria-labelledby="flink-pressure-heading">
      <h3 id="flink-pressure-heading">持续反压告警</h3>
      <p v-if="pressureLoading" role="status">正在读取反压告警。</p>
      <el-alert v-else-if="pressureError" type="error" :title="pressureError" :closable="false" show-icon />
      <p v-else-if="pressure && !pressure.enabled" class="metrics-meta">未启用持续反压巡检。</p>
      <el-table v-else-if="pressure?.enabled" :data="pressure.alerts" row-key="id" size="small" empty-text="暂无反压告警">
        <el-table-column label="状态" min-width="95">
          <template #default="{ row }">{{ alertLabels[row.status as keyof typeof alertLabels] }}</template>
        </el-table-column>
        <el-table-column label="峰值节点" min-width="125" show-overflow-tooltip>
          <template #default="{ row }">{{ vertices.find(vertex => vertex.id === row.vertexId)?.name ?? row.vertexId }}</template>
        </el-table-column>
        <el-table-column label="平均反压峰值" min-width="110">
          <template #default="{ row }">{{ format(row.peakPercent, '%') }}</template>
        </el-table-column>
        <el-table-column label="触发时间" min-width="140">
          <template #default="{ row }">{{ time(row.openedAt) }}</template>
        </el-table-column>
        <el-table-column label="关闭时间" min-width="140">
          <template #default="{ row }">{{ time(row.closedAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
    <template #footer><DkButton :loading="loading" @click="attempt++">刷新指标</DkButton></template>
  </el-dialog>
</template>

<style scoped>
.metrics-toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--dk-space-2); }
.metrics-toolbar .el-select { flex: 1; min-width: min(210px, 100%); }
.metrics-meta { color: var(--dk-muted); }
.metrics-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(175px, 100%), 1fr));
  gap: var(--dk-space-2);
  margin: 0;
}
.operator-metrics { margin-top: var(--dk-space-4); min-width: 0; }
.operator-metrics h3 { font-size: 15px; margin: 0 0 var(--dk-space-2); }
.metrics-cards div { background: var(--dk-soft); border-radius: 8px; padding: 12px; }
.metrics-cards dd { font-size: 17px; font-weight: 650; margin: 4px 0 0; }
</style>
