<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import DkButton from '@/paas-ui/DkButton.vue'
import { getFlinkEventTime, type FlinkEventTimeSample } from '@/api/flinkStudio'
import { diagnoseEventTime, eventTimeLabels as labels, EVENT_TIME_INTERVAL_MS, EVENT_TIME_SAMPLE_LIMIT, type EventTimePoint } from './eventTimeDiagnosis'

const props = defineProps<{
  project: string; assetId: string; runId: string; vertexId: string; vertexStatus?: string
}>()

interface Observation {
  sample: FlinkEventTimeSample
  diagnostic: EventTimePoint
}

const explanations = {
  BASELINE: '等待下一次有效样本以计算输入增量。',
  PROGRESSING: '最慢输入水位线正在推进。',
  IDLE: '此节点在采样区间内未读入新记录。',
  WAITING: '继续观察是否连续三个区间有输入而水位线不变。',
  STALLED: '检查事件时间字段、上游分区空闲配置和下游反压。',
  UNKNOWN: '等待完整的输入计数和水位线样本。',
} as const

const points = ref<Observation[]>([])
const running = ref(false)
const error = ref('')
const latest = computed(() => points.value[points.value.length - 1])
const displayRows = computed(() => [...points.value].reverse())
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 })
const date = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'short', timeStyle: 'medium' })
const terminal = new Set(['FINISHED', 'FAILED', 'CANCELED'])
let request: AbortController | undefined
let timeout: ReturnType<typeof setTimeout> | undefined
let generation = 0
let previous: FlinkEventTimeSample | undefined
let streak = 0
let attempts = 0

function stop() {
  generation++
  running.value = false
  request?.abort()
  clearTimeout(timeout)
}

function reset() {
  stop()
  points.value = []
  error.value = ''
  previous = undefined
  streak = 0
  attempts = 0
}

watch(() => props.vertexId, reset)
onBeforeUnmount(stop)

async function collect(key: number) {
  const controller = new AbortController()
  request = controller
  attempts++
  try {
    const sample = await getFlinkEventTime(props.project, props.assetId, props.runId, props.vertexId, controller.signal)
    if (key !== generation) return
    const diagnostic = diagnoseEventTime(previous, sample, streak)
    points.value = [...points.value, { sample, diagnostic }]
    previous = sample
    streak = diagnostic.stalledIntervals
    error.value = ''
    if (terminal.has(sample.status)) stop()
  } catch (e) {
    if (key !== generation) return
    previous = undefined
    streak = 0
    error.value = e instanceof Error ? e.message : '水位线采样失败'
  }
  if (key !== generation) return
  if (attempts >= EVENT_TIME_SAMPLE_LIMIT) stop()
  else timeout = setTimeout(() => { void collect(key) }, EVENT_TIME_INTERVAL_MS)
}

function start() {
  if (running.value || !props.vertexId || props.vertexStatus !== 'RUNNING') return
  reset()
  running.value = true
  void collect(generation)
}

function time(value: string | null) {
  if (!value) return '—'
  const parsed = new Date(value)
  return Number.isNaN(parsed.getTime()) ? '—' : date.format(parsed)
}

function age(value: number | null) {
  if (value == null) return '—'
  return value < 0 ? '超前 ' + number.format(-value / 1000) + ' 秒' : number.format(value / 1000) + ' 秒'
}
</script>

<template>
  <section class="event-time" aria-labelledby="flink-event-time-heading">
    <div class="heading">
      <h3 id="flink-event-time-heading">水位线停滞诊断</h3>
      <DkButton v-if="running" @click="stop">停止观测</DkButton>
      <DkButton v-else :disabled="!vertexId || vertexStatus !== 'RUNNING'" @click="start">
        {{ points.length ? '重新观测' : '开始观测' }}
      </DkButton>
    </div>
    <p v-if="running" class="muted" role="status">正在观测已选节点 · 每 15 秒采样，最多 12 次。</p>
    <el-alert v-if="error" type="warning" :title="error" :closable="false" show-icon />
    <el-alert v-else-if="latest" :title="labels[latest.diagnostic.state]" :description="explanations[latest.diagnostic.state]"
      :type="latest.diagnostic.state === 'STALLED' ? 'warning' : latest.diagnostic.state === 'PROGRESSING' ? 'success' : 'info'"
      :closable="false" show-icon />
    <p v-else-if="!running" class="muted">
      {{ vertexStatus === 'RUNNING' ? '观测执行节点的输入增量和事件时间变化。' : '选择正在运行的执行节点后开始观测。' }}
    </p>
    <el-table v-if="points.length" :data="displayRows" size="small" max-height="260" class="timeline">
      <el-table-column label="采样时间" min-width="138"><template #default="{ row }">{{ time(row.sample.sampledAt) }}</template></el-table-column>
      <el-table-column label="输入水位线" min-width="138"><template #default="{ row }">{{ time(row.sample.inputWatermark) }}</template></el-table-column>
      <el-table-column label="水位线滞后" min-width="115"><template #default="{ row }">{{ age(row.diagnostic.watermarkAgeMs) }}</template></el-table-column>
      <el-table-column label="输入增量" min-width="90">
        <template #default="{ row }">{{ row.diagnostic.delta == null ? '—' : number.format(row.diagnostic.delta) + ' 条' }}</template>
      </el-table-column>
      <el-table-column label="诊断" min-width="155">
        <template #default="{ row }">{{ labels[row.diagnostic.state as keyof typeof labels] }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.event-time { margin-top: var(--dk-space-4); min-width: 0; }
.heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: var(--dk-space-2); margin-bottom: var(--dk-space-2); }
.heading h3 { margin: 0; font-size: 15px; }
.muted { color: var(--dk-muted); }
.timeline { margin-top: var(--dk-space-2); }
</style>
