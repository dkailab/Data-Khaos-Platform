<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import {
  ArrowRight, CircleCheck, Clock, Close, Connection, DataAnalysis, DataBoard,
  Grid, Histogram, Menu, Operation, Search, Timer, Warning,
} from '@element-plus/icons-vue'
import DkButton from '../paas-ui/DkButton.vue'
import DkPageHeader from '../paas-ui/DkPageHeader.vue'
import DkStatus from '../paas-ui/DkStatus.vue'
import '../paas-ui/tokens.css'
import './preview.css'

const navigation = [
  { id: 'overview', label: '运行概览', icon: DataBoard },
  { id: 'trend', label: '执行趋势', icon: Histogram },
  { id: 'lineage', label: '产出链路', icon: Connection },
  { id: 'runs', label: '运行记录', icon: Timer },
]
const activeSection = ref('overview')
const menuOpen = ref(false)
const period = ref<7 | 14>(14)
const daily = [
  [34, 1], [47, 1], [38, 0], [58, 2], [48, 1], [62, 1], [50, 2],
  [72, 1], [57, 1], [65, 2], [75, 1], [61, 2], [77, 0], [84, 1],
].map(([success, failed], index) => ({ date: index + 6, success, failed }))
const visibleDays = computed(() => daily.slice(-period.value))
const activeDay = ref<number>()
const chartNote = computed(() => visibleDays.value.find(day => day.date === activeDay.value))
const stages = [
  { layer: 'ODS', asset: '原始订单', status: 'success' },
  { layer: 'DWD', asset: '订单明细', status: 'success' },
  { layer: 'DWS', asset: '地区日汇总', status: 'failed' },
  { layer: 'ADS', asset: '地区成交额', status: 'blocked' },
] as const
const runs = [
  { id: 'run_10428', name: '订单离线加工', key: 'orders_daily', date: '2026-11-19', status: 'failed', time: '09:42:05', owner: '交易数据组', trigger: '定时', evidence: '[09:42:05] workflow started · bizDate=2026-11-19\n[09:43:13] ERROR dws_reconciliation · amount check failed\n[09:43:13] BLOCKED ads_order_amount_by_region', duration: '1 分 08 秒', detail: 'DWS 金额对账未通过，ADS 地区成交额未发布。' },
  { id: 'run_10427', name: '订单源表同步', key: 'orders_ingest', date: '2026-11-19', status: 'success', time: '09:36:10', owner: '交易数据组', trigger: '定时', evidence: '[09:36:52] ODS partition published', duration: '42 秒', detail: '已将源订单同步至 ODS 业务分区。' },
  { id: 'run_10426', name: '商品维表更新', key: 'products_dim', date: '2026-11-19', status: 'running', time: '09:31:40', owner: '商品数据组', trigger: '手动', evidence: '[09:31:40] dimension refresh started', duration: '—', detail: '正在写入商品维表。' },
  { id: 'run_10425', name: '仓库日终检查', key: 'warehouse_check', date: '2026-11-18', status: 'success', time: '23:50:00', owner: '平台运维组', trigger: '定时', evidence: '[23:52:12] warehouse checks passed', duration: '2 分 12 秒', detail: '日终质量检查已通过。' },
] as const
const metrics = [
  { label: '今日运行', value: '89', context: '昨日结束 77 次', icon: Operation, tone: 'primary' },
  { label: '运行成功率', value: '98.8%', context: '84 / 85 已结束', icon: CircleCheck, tone: 'green' },
  { label: '运行中', value: '4', context: '当前执行实例', icon: Clock, tone: 'primary' },
  { label: '质量告警', value: '1', context: '订单金额对账', icon: Warning, tone: 'amber' },
]
const search = ref('')
const status = ref('')
const owner = ref('')
const owners = [...new Set(runs.map(run => run.owner))]
const runHeading = ref<HTMLElement | null>(null)
const showLog = ref(false)
const visibleRuns = computed(() => {
  const query = search.value.trim().toLowerCase()
  return runs.filter(run => (!status.value || run.status === status.value)
    && (!owner.value || run.owner === owner.value)
    && (!query || `${run.name} ${run.key} ${run.id}`.toLowerCase().includes(query)))
})
const dialog = ref<HTMLDialogElement | null>(null)
const selectedRun = ref<(typeof runs)[number]>()
let detailTrigger: HTMLElement | null = null

function restoreDetailFocus() {
  detailTrigger?.focus()
}

async function showFailedRuns() {
  search.value = ''
  owner.value = ''
  status.value = 'failed'
  activeSection.value = 'runs'
  await nextTick()
  runHeading.value?.focus()
  runHeading.value?.scrollIntoView({ block: 'start' })
}

async function openRun(run: (typeof runs)[number], event: MouseEvent) {
  detailTrigger = event.currentTarget as HTMLElement
  showLog.value = false
  selectedRun.value = run
  await nextTick()
  dialog.value?.showModal()
}
</script>

<template>
  <div class="dk-paas preview-shell">
    <a class="skip-link" href="#overview">跳到主要内容</a>
    <aside class="sidebar" :class="{ 'sidebar-open': menuOpen }">
      <a class="brand" href="#overview" @click="activeSection = 'overview'; menuOpen = false">
        <span class="brand-mark"><DataAnalysis aria-hidden="true" /></span>
        <span><strong>Data Khaos</strong><small>数据平台</small></span>
      </a>
      <p class="sidebar-label">数据运维</p>
      <nav aria-label="数据运维">
        <a
          v-for="item in navigation" :key="item.id" :href="`#${item.id}`"
          :class="{ selected: activeSection === item.id }"
          :aria-current="activeSection === item.id ? 'location' : undefined"
          @click="activeSection = item.id; menuOpen = false"
        >
          <component :is="item.icon" aria-hidden="true" />{{ item.label }}
        </a>
      </nav>
      <div class="sidebar-footer"><Grid aria-hidden="true" /><span>订单数仓<small>演示工作空间</small></span></div>
    </aside>

    <div class="workspace">
      <div class="topbar">
        <div class="crumbs">
          <button class="mobile-menu" type="button" aria-label="切换导航" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen"><Menu /></button>
          <span>工作空间</span><ArrowRight aria-hidden="true" /><strong>订单数仓</strong>
        </div>
        <div class="preview-actions">
          <span class="preview-label">演示数据</span>
          <a class="dk-link" href="/dashboard">进入数据门户 <ArrowRight aria-hidden="true" /></a>
        </div>
      </div>

      <main id="overview" class="main-content">
        <DkPageHeader title="运行概览" description="查看离线任务、质量异常和下游产出。">
          <template #actions>
            <span class="as-of dk-numeric"><Clock aria-hidden="true" />2026-11-19 · CST</span>
          </template>
        </DkPageHeader>

        <section class="alert-banner" aria-label="待处理告警">
          <div class="alert-body">
            <Warning aria-hidden="true" />
            <div><strong>订单金额对账失败</strong><span>DWS 地区日汇总异常，ADS 地区成交额已阻断</span></div>
          </div>
          <DkButton @click="showFailedRuns">查看失败实例<template #icon><ArrowRight aria-hidden="true" /></template></DkButton>
        </section>

        <section class="metrics" aria-label="运行指标">
          <article v-for="metric in metrics" :key="metric.label" class="dk-panel metric">
            <div class="metric-head">
              <span>{{ metric.label }}</span>
              <span class="metric-icon" :class="metric.tone"><component :is="metric.icon" aria-hidden="true" /></span>
            </div>
            <strong class="dk-numeric">{{ metric.value }}</strong>
            <span class="metric-context">{{ metric.context }}</span>
          </article>
        </section>

        <div class="insight-grid">
          <section id="trend" class="dk-panel insight">
            <div class="section-heading">
              <div><h2>执行趋势</h2><p>每日结束的任务数</p></div>
              <div class="segmented" aria-label="趋势时间范围" role="group">
                <button v-for="days in ([7, 14] as const)" :key="days" type="button" :aria-pressed="period === days" @click="period = days; activeDay = undefined">{{ days }} 天</button>
              </div>
            </div>
            <div class="chart-meta" aria-live="polite">
              <span v-if="chartNote" class="dk-numeric">11 月 {{ chartNote.date }} 日 · {{ chartNote.success }} 成功 · {{ chartNote.failed }} 失败</span>
              <template v-else><span class="legend success">成功</span><span class="legend failed">失败</span></template>
            </div>
            <div class="chart">
              <div class="axis dk-numeric" aria-hidden="true"><span v-for="value in [100, 75, 50, 25, 0]" :key="value">{{ value }}</span></div>
              <div class="plot" :style="{ '--columns': visibleDays.length }">
                <button
                  v-for="(day, index) in visibleDays" :key="day.date" class="bar-slot" type="button"
                  :aria-label="`11 月 ${day.date} 日，${day.success} 成功，${day.failed} 失败`"
                  :aria-pressed="activeDay === day.date"
                  @click="activeDay = activeDay === day.date ? undefined : day.date"
                >
                  <span class="bar" :class="{ latest: index === visibleDays.length - 1 }" :style="{ height: `${day.success + day.failed}%` }">
                    <span v-if="day.failed" class="bar-fail" :style="{ height: `${day.failed / (day.success + day.failed) * 100}%` }"></span>
                  </span>
                </button>
              </div>
              <div class="axis-dates dk-numeric" aria-hidden="true"><span>11.{{ visibleDays[0].date.toString().padStart(2, '0') }}</span><span>11.19</span></div>
            </div>
          </section>

          <section id="lineage" class="dk-panel insight lineage">
            <div class="section-heading">
              <div><h2>订单产出链路</h2><p>业务日期 2026-11-19</p></div>
              <Connection class="lineage-icon" aria-hidden="true" />
            </div>
            <ol class="stages">
              <li v-for="stage in stages" :key="stage.layer">
                <span class="layer dk-mono">{{ stage.layer }}</span><strong>{{ stage.asset }}</strong>
                <DkStatus :status="stage.status" />
              </li>
            </ol>
          </section>
        </div>

        <section id="runs" class="dk-panel runs-panel">
          <div class="section-heading runs-heading">
            <div><h2 ref="runHeading" tabindex="-1">最近运行</h2><p>查看任务实例和执行结果</p></div>
            <div class="filters">
              <label class="search"><Search aria-hidden="true" /><input v-model="search" type="search" placeholder="搜索任务或运行 ID" aria-label="搜索任务或运行 ID" /></label>
              <select v-model="owner" aria-label="按负责人筛选">
                <option value="">全部负责人</option>
                <option v-for="name in owners" :key="name">{{ name }}</option>
              </select>
              <select v-model="status" aria-label="按运行状态筛选">
                <option value="">全部状态</option><option value="success">成功</option><option value="failed">失败</option><option value="running">运行中</option>
              </select>
            </div>
          </div>
          <div class="table-scroll" tabindex="0" role="region" aria-label="最近运行列表">
            <table>
              <thead><tr><th scope="col">任务</th><th scope="col">业务日期</th><th scope="col">运行状态</th><th scope="col">负责人</th><th scope="col">触发方式</th><th scope="col">开始时间</th><th scope="col">耗时</th><th scope="col">操作</th></tr></thead>
              <tbody>
                <tr v-for="run in visibleRuns" :key="run.id">
                  <td><strong>{{ run.name }}</strong><small class="dk-mono">{{ run.key }}</small></td>
                  <td class="dk-numeric">{{ run.date }}</td><td><DkStatus :status="run.status" /></td>
                  <td>{{ run.owner }}</td><td>{{ run.trigger }}</td><td class="dk-numeric">{{ run.time }}</td><td class="dk-numeric">{{ run.duration }}</td>
                  <td><DkButton variant="quiet" :aria-label="`查看 ${run.name} 详情`" @click="openRun(run, $event)">详情</DkButton></td>
                </tr>
                <tr v-if="!visibleRuns.length"><td class="empty" colspan="8">没有匹配的运行记录</td></tr>
              </tbody>
            </table>
          </div>
        </section>
      </main>
    </div>

    <dialog ref="dialog" aria-labelledby="run-title" @close="restoreDetailFocus">
      <div v-if="selectedRun" class="dialog-content">
        <div class="section-heading">
          <div><h2 id="run-title">{{ selectedRun.name }}</h2><p class="dk-mono">{{ selectedRun.id }}</p></div>
          <DkButton aria-label="关闭运行详情" @click="dialog?.close()"><template #icon><Close aria-hidden="true" /></template></DkButton>
        </div>
        <dl>
          <dt>业务日期</dt><dd>{{ selectedRun.date }}</dd>
          <dt>运行状态</dt><dd><DkStatus :status="selectedRun.status" /></dd>
          <dt>负责人</dt><dd>{{ selectedRun.owner }}</dd>
          <dt>触发方式</dt><dd>{{ selectedRun.trigger }}</dd>
          <dt>执行结果</dt><dd>{{ selectedRun.detail }}</dd>
        </dl>
        <div class="segmented" role="group" aria-label="运行证据">
          <button type="button" :aria-pressed="!showLog" @click="showLog = false">影响范围</button>
          <button type="button" :aria-pressed="showLog" @click="showLog = true">执行日志</button>
        </div>
        <section class="run-evidence" aria-live="polite">
          <template v-if="showLog"><h3>执行日志 · UTC+8</h3><pre class="dk-mono">{{ selectedRun.evidence }}</pre></template>
          <template v-else>
            <h3>下游产出</h3>
            <p>{{ selectedRun.status === 'failed' ? 'ADS 地区成交额 · 当日分区未发布' : '该样例没有关联的下游阻断记录。' }}</p>
          </template>
        </section>
        <DkButton @click="dialog?.close()">关闭</DkButton>
      </div>
    </dialog>
  </div>
</template>
