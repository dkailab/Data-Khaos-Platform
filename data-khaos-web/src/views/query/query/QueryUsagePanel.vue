<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getQueryUsage } from '@/api/query'
import DkButton from '@/paas-ui/DkButton.vue'
import type { QueryUsageDay } from '@/types'

const emit = defineEmits<{ selectDay: [day: string] }>()
const days = ref(7)
const rows = ref<QueryUsageDay[]>([])
const loading = ref(false)
const failed = ref(false)
let requestSequence = 0
const series = [
  { key: 'succeeded', label: '成功' },
  { key: 'failed', label: '失败' },
  { key: 'cancelled', label: '取消' },
  { key: 'active', label: '进行中' },
] as const

function count(row: QueryUsageDay) {
  return row.succeeded + row.failed + row.cancelled + row.active
}

const totals = computed(() => rows.value.reduce((total, row) => ({
  submitted: total.submitted + count(row),
  failed: total.failed + row.failed,
  seconds: total.seconds + row.executionSeconds,
  timed: total.timed + row.timedSuccesses,
}), { submitted: 0, failed: 0, seconds: 0, timed: 0 }))
const maxDaily = computed(() => Math.max(1, ...rows.value.map(count)))
const average = computed(() => totals.value.timed
  ? `${(totals.value.seconds / totals.value.timed).toFixed(1)} 秒`
  : '—')

async function load() {
  const sequence = ++requestSequence
  loading.value = true
  failed.value = false
  rows.value = []
  try {
    const result = await getQueryUsage(days.value)
    if (sequence === requestSequence) rows.value = result
  } catch {
    if (sequence === requestSequence) failed.value = true
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

watch(days, load, { immediate: true })
onBeforeUnmount(() => { requestSequence++ })
</script>

<template>
  <section class="usage" aria-labelledby="query-usage-title" :aria-busy="loading">
    <header class="usage-header">
      <div>
        <h3 id="query-usage-title">我的后台查询用量</h3>
        <p>按提交日期统计，选择日期可查看当天的任务。</p>
      </div>
      <div class="usage-actions">
        <el-select v-model="days" aria-label="统计范围">
          <el-option :value="7" label="近 7 天" />
          <el-option :value="14" label="近 14 天" />
          <el-option :value="30" label="近 30 天" />
        </el-select>
        <DkButton :loading="loading" @click="load">刷新用量</DkButton>
      </div>
    </header>

    <el-alert v-if="failed" title="查询用量加载失败，请重试" type="error" :closable="false" show-icon />
    <div v-else-if="loading" class="usage-state" role="status">正在加载查询用量…</div>
    <div v-else-if="!totals.submitted" class="usage-state" role="status">该时间范围内没有后台查询。</div>

    <template v-else>
      <dl class="usage-metrics">
        <div><dt>提交任务</dt><dd>{{ totals.submitted }}</dd></div>
        <div><dt>失败任务</dt><dd>{{ totals.failed }}</dd></div>
        <div><dt>成功任务平均执行耗时</dt><dd>{{ average }}</dd></div>
      </dl>

      <ul class="usage-legend" aria-label="任务状态">
        <li v-for="item in series" :key="item.key"><i :class="item.key" />{{ item.label }}</li>
      </ul>

      <ul class="usage-days" aria-label="每日查询用量">
        <li v-for="row in [...rows].reverse()" :key="row.submittedOn">
          <button type="button" class="usage-day"
            :aria-label="`${row.submittedOn}，${series.map(item => `${item.label} ${row[item.key]} 条`).join('，')}`"
            :title="series.map(item => `${item.label} ${row[item.key]}`).join(' · ')"
            @click="emit('selectDay', row.submittedOn)">
            <span>{{ row.submittedOn }}</span>
            <span class="usage-track" aria-hidden="true">
              <i v-for="item in series" :key="item.key" :class="item.key"
                :style="{ width: `${row[item.key] / maxDaily * 100}%` }" />
            </span>
            <strong>{{ count(row) }}</strong>
          </button>
        </li>
      </ul>
    </template>
  </section>
</template>

<style scoped>
.usage {
  margin-bottom: var(--dk-space-4);
  padding: var(--dk-space-4);
  border: 1px solid var(--dk-border);
  border-radius: var(--dk-radius-panel);
  background: var(--dk-sidebar);
}
.usage-header, .usage-actions, .usage-legend {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--dk-space-3);
}
.usage-header { justify-content: space-between; margin-bottom: var(--dk-space-4); }
.usage-header h3 { font-size: 15px; margin: 0; }
.usage-header p { margin: var(--dk-space-1) 0 0; color: var(--dk-muted); }
.usage-actions :deep(.el-select) { width: 105px; }
.usage-state { padding: var(--dk-space-3) 0; color: var(--dk-muted); }
.usage-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(160px, 100%), 1fr));
  gap: var(--dk-space-2);
  margin: 0 0 var(--dk-space-4);
}
.usage-metrics div {
  padding: var(--dk-space-3);
  border-radius: var(--dk-radius-control);
  background: var(--dk-surface);
}
.usage-metrics dt, .usage-legend { color: var(--dk-muted); }
.usage-metrics dd { margin: var(--dk-space-1) 0 0; font-size: 20px; font-weight: 650; }
.usage-legend, .usage-days { list-style: none; padding: 0; }
.usage-legend { margin: 0 0 var(--dk-space-2); }
.usage-legend li { display: flex; align-items: center; gap: var(--dk-space-1); }
.usage-legend i { width: 9px; aspect-ratio: 1; border-radius: 2px; }
.usage-days { max-height: 280px; overflow-y: auto; margin: 0; padding: 3px; }
.usage-day {
  display: grid;
  grid-template-columns: max-content minmax(24px, 1fr) max-content;
  gap: var(--dk-space-3);
  align-items: center;
  width: 100%;
  padding: var(--dk-space-1) var(--dk-space-2);
  border: 0;
  border-radius: var(--dk-radius-control);
  background: transparent;
  color: var(--dk-secondary);
  text-align: left;
  font: inherit;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}
.usage-day:hover { background: var(--dk-primary-soft); }
.usage-day:focus-visible { outline-offset: -2px; }
.usage-track {
  display: flex;
  overflow: hidden;
  height: 8px;
  border-radius: 4px;
  background: var(--dk-soft);
}
.succeeded { background: var(--dk-chart-series); }
.failed { background: var(--dk-chart-failure); }
.cancelled { background: var(--dk-muted); }
.active { background: var(--dk-primary); }
</style>
