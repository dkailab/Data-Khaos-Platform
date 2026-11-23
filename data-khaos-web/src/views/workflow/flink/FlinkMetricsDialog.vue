<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { getFlinkMetrics, type FlinkMetricsView } from '@/api/flinkStudio'

const props = defineProps<{ project: string; assetId: string; runId: string }>()
const emit = defineEmits<{ close: [] }>()
const vertices = ref<FlinkMetricsView['vertices']>([])
const requested = ref('')
const selected = ref<string | null>(null)
const sample = ref<FlinkMetricsView['sample']>(null)
const loading = ref(false)
const error = ref('')
const attempt = ref(0)
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
        <p class="metrics-meta">吞吐汇总可用子任务样本。— 表示该指标暂无样本。</p>
      </template>
    </template>
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
.metrics-cards div { background: var(--dk-soft); border-radius: 8px; padding: 12px; }
.metrics-cards dd { font-size: 17px; font-weight: 650; margin: 4px 0 0; }
</style>
