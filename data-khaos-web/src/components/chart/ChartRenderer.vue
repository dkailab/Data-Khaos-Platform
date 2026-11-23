<template>
  <div ref="rootEl" class="chart-renderer">
    <!-- 组件标题栏 -->
    <div v-if="showTitle" class="chart-title-bar">
      <span class="chart-title-text">{{ title }}</span>
    </div>

    <div class="chart-body" :class="{ 'has-title': showTitle }">
      <div v-if="loading" class="placeholder loading">
        <el-icon class="loading-icon"><Loading /></el-icon>
        <span>数据加载中...</span>
      </div>
      <div v-else-if="!result || !rows.length" class="placeholder empty">
        <el-icon :size="32"><DataLine /></el-icon>
        <span>暂无数据</span>
        <span class="placeholder-hint">请配置数据源和SQL查询</span>
      </div>

      <!-- 表格 -->
      <div v-else-if="chartType === 'TABLE'" class="table-wrap">
        <el-table :data="rows" border size="small" height="100%" max-height="100%" stripe>
          <el-table-column v-for="c in columns" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip>
            <template v-if="$slots['table-cell']" #default="{ row }">
              <slot name="table-cell" :row="row" :column="c" />
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 指标卡（基础/对比/迷你趋势） -->
      <div v-else-if="chartType === 'NUMBER'" class="metric-card">
        <!-- 指标名称 + 辅助副标题 -->
        <div class="metric-label">
          <span class="metric-name">{{ metricName }}</span>
          <span v-if="metricSubtitle" class="metric-subtitle">{{ metricSubtitle }}</span>
        </div>
        <!-- 主指标数值 + 单位 -->
        <div class="metric-value-row">
          <span class="metric-value" :class="{ 'is-small': metricValue.length > 9 }">{{ metricValue }}</span>
          <span v-if="unitText" class="metric-unit">{{ unitText }}</span>
        </div>
        <!-- 对比（红跌绿涨 / 涨跌率 / 趋势箭头） -->
        <div v-if="isCompare && compareValue !== null" class="metric-compare" :class="compareClass">
          <span class="metric-arrow"><component :is="compareArrow" /></span>
          <span class="metric-compare-label">{{ compareLabel }}</span>
          <span class="metric-compare-value">{{ compareValueText }}</span>
          <span class="metric-compare-rate">{{ compareRateText }}</span>
        </div>
        <!-- 指标趋势迷你图 -->
        <div v-if="spark" ref="sparkEl" class="metric-spark"></div>
      </div>

      <!-- ECharts 图表 -->
      <div v-else ref="chartEl" class="chart-box"></div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { ArrowDownBold, ArrowUpBold, DataLine, Loading } from '@element-plus/icons-vue'
import type { QueryResult, VisualDashboardItem } from '@/types'

const props = defineProps<{
  item: VisualDashboardItem
  result: QueryResult | null
  loading?: boolean
  height?: number
  /** 主题（light/dark），变化时重绘图表以适配深浅色 */
  theme?: 'light' | 'dark'
  /** 是否显示组件标题栏 */
  showTitle?: boolean
}>()

const emit = defineEmits<{ (e: 'drill', payload: { column: string; value: string }): void }>()

interface ChartConfig {
  xAxisColumn?: string
  seriesColumn?: string
  valueColumn?: string
  valueColumn2?: string
  mapName?: string
  // ===== 指标卡配置 =====
  /** 指标名称（为空时用组件标题） */
  metricName?: string
  /** 辅助副标题/说明 */
  metricSubtitle?: string
  /** 统计单位 */
  unit?: string
  /** 千分位 */
  thousand?: boolean
  /** 小数位数 */
  decimals?: number
  /** 基础 basic / 对比 compare */
  metricMode?: 'basic' | 'compare'
  /** 对比模式 prev=上一条 环比 / ref=固定基准列(valueColumn2) */
  compareMode?: 'prev' | 'ref'
  /** 对比标签，如 环比 / 同比 */
  compareLabel?: string
  /** 红跌绿涨反向 */
  reverseColor?: boolean
  /** 迷你趋势图 */
  spark?: boolean
  /** 迷你趋势图数据列（默认 valueColumn） */
  sparkColumn?: string
}

const rootEl = ref<HTMLDivElement>()
const chartEl = ref<HTMLDivElement>()
const sparkEl = ref<HTMLDivElement>()
let chart: echarts.ECharts | null = null
let sparkChart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

const showTitle = computed(() => props.showTitle !== false && props.item.title)

const chartType = computed(() => props.item.chartType || 'TABLE')
const title = computed(() => props.item.title || '')
const rows = computed(() => props.result?.rows || [])
const columns = computed(() => (props.result?.columns || []).map((c) => c.columnName).filter((n): n is string => !!n))

function getConfig(): ChartConfig {
  try {
    // 优先使用新的 dataConfig
    if (props.item.dataConfig) {
      const dc = JSON.parse(props.item.dataConfig)
      if (dc && (dc.dimensions?.length || dc.metrics?.length)) {
        return {
          xAxisColumn: dc.dimensions?.[0]?.fieldCode || '',
          valueColumn: dc.metrics?.[0]?.fieldCode || '',
          seriesColumn: dc.dimensions?.[1]?.fieldCode || '',
        }
      }
    }
    // 兼容旧版 config
    const cfg = props.item.config ? JSON.parse(props.item.config) : {}
    return cfg || {}
  } catch {
    return {}
  }
}

function num(v: any): number | null {
  const n = Number(v)
  return isNaN(n) ? null : n
}

function numericColumns(): string[] {
  return columns.value.filter((c) => rows.value.some((r) => num(r[c]) !== null))
}

/** 取 x 轴列：优先配置，否则第一列 */
function xCol(): string {
  return getConfig().xAxisColumn || columns.value[0] || ''
}
/** 取值列：优先配置，否则第一个数值列 */
function valueCol(): string {
  return getConfig().valueColumn || numericColumns()[0] || ''
}
/** 取系列/维度列：优先配置，否则值列外的第一列 */
function seriesCol(): string {
  return getConfig().seriesColumn || columns.value.find((c) => c !== xCol() && c !== valueCol()) || ''
}

/* ==================== 主题感知（深浅主题自适应，满足 BI-Chart-Standard） ==================== */
function readCssVar(name: string, fallback: string): string {
  if (!rootEl.value) return fallback
  return getComputedStyle(rootEl.value).getPropertyValue(name).trim() || fallback
}
/** 读取当前主题下的基础色板，供 ECharts option 使用 */
function themeColors() {
  return {
    text1: readCssVar('--text-1', '#303133'),
    text2: readCssVar('--text-2', '#606266'),
    text3: readCssVar('--text-3', '#909399'),
    line: readCssVar('--card-border', '#ebeef5'),
    grid: readCssVar('--card-border', '#f0f2f5'),
    tooltipBg: readCssVar('--card-bg', '#ffffff'),
  }
}

/** 【标准】tooltip 统一开启 confine，限制在容器内不飞出卡片 */
function baseTooltip(trigger: 'axis' | 'item'): any {
  const t = themeColors()
  return {
    trigger,
    confine: true,
    backgroundColor: t.tooltipBg,
    borderColor: t.line,
    textStyle: { color: t.text1, fontSize: 12 },
  }
}

/** 【标准】折线/柱状图统一 grid 边距，containLabel 防止坐标轴文字被裁切 */
function baseGrid(top = 24, bottom = 8, left = 8, right = 16) {
  return { left, right, top, bottom, containLabel: true }
}

/** 【标准】坐标轴长文字：超过阈值旋转，避免文字重叠 */
function axisLabelOptions(cats: string[]) {
  const t = themeColors()
  const maxLen = 8
  const tooLong = cats.some((c) => String(c).length > maxLen)
  return {
    color: t.text3,
    interval: 0,
    rotate: cats.length > 8 || tooLong ? 30 : 0,
    formatter: (v: string) => (v.length > 12 ? v.slice(0, 11) + '…' : v),
  }
}

function valueAxisLabel() {
  const t = themeColors()
  return { color: t.text3 }
}

/* ==================== 指标卡逻辑（基础/对比/迷你趋势） ==================== */
const cfg = computed(() => getConfig())
const unitText = computed(() => cfg.value.unit || '')
const metricName = computed(() => cfg.value.metricName || title.value || '指标')
const metricSubtitle = computed(() => cfg.value.metricSubtitle || '')
const isCompare = computed(() => cfg.value.metricMode === 'compare')
const spark = computed(() => !!cfg.value.spark)

/** 按 x 轴列排序后的行（用于取最新值/迷你趋势） */
const orderedRows = computed(() => {
  const x = xCol()
  if (!x) return rows.value
  return [...rows.value].sort((a, b) => {
    const va = Date.parse(String(a[x] ?? ''))
    const vb = Date.parse(String(b[x] ?? ''))
    if (!isNaN(va) && !isNaN(vb)) return va - vb
    return String(a[x] ?? '').localeCompare(String(b[x] ?? ''))
  })
})

/** 主指标数值：多行取最新（最后一条），单行取首行 */
const metricValue = computed(() => {
  const col = valueCol()
  if (!col) return '--'
  const list = orderedRows.value
  const row = list.length > 1 ? list[list.length - 1] : list[0]
  const v = num(row?.[col])
  return v === null ? '--' : formatMetric(v)
})

/** 千分位 + 小数位格式化指标值 */
function formatMetric(v: number): string {
  const decimals = cfg.value.decimals ?? 0
  const fixed = decimals > 0 ? v.toFixed(decimals) : String(Math.round(v))
  if (cfg.value.thousand) {
    const parts = fixed.split('.')
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, ',')
    return parts.join('.')
  }
  return fixed
}

/** 对比基准值 + 涨跌率 */
const compareValue = computed<number | null>(() => {
  if (!isCompare.value) return null
  const col = valueCol()
  if (!col) return null
  const list = orderedRows.value
  const latest = num(list[list.length - 1]?.[col])
  if (latest === null) return null
  // ref 模式：用 valueColumn2 作为基准
  if (cfg.value.compareMode === 'ref' && cfg.value.valueColumn2) {
    return num(list[list.length - 1]?.[cfg.value.valueColumn2] ?? list[0]?.[cfg.value.valueColumn2])
  }
  // prev 模式：与上一条对比（环比）
  if (list.length > 1) return num(list[list.length - 2]?.[col])
  return null
})

const compareRate = computed<number | null>(() => {
  const col = valueCol()
  if (!col || compareValue.value === null) return null
  const list = orderedRows.value
  const latest = num(list[list.length - 1]?.[col])
  const base = compareValue.value
  if (latest === null || !base) return null
  return ((latest - base) / Math.abs(base)) * 100
})

const compareLabel = computed(() => cfg.value.compareLabel || (cfg.value.compareMode === 'ref' ? '同比' : '环比'))
const compareValueText = computed(() => {
  const v = compareValue.value
  return v === null ? '--' : (cfg.value.unit || '') + formatMetric(v)
})
const compareRateText = computed(() => {
  const r = compareRate.value
  if (r === null) return '--'
  return (r >= 0 ? '+' : '') + r.toFixed(2) + '%'
})

/** 涨跌颜色：默认红跌绿涨，reverseColor 反向 */
const compareClass = computed(() => {
  const r = compareRate.value
  if (r === null) return ''
  const up = r >= 0
  const greenUp = !cfg.value.reverseColor
  return up === greenUp ? 'metric-up' : 'metric-down'
})
/** 趋势箭头：涨 ↑ 跌 ↓ */
const compareArrow = computed(() => {
  const r = compareRate.value
  if (r === null) return ''
  return r >= 0 ? ArrowUpBold : ArrowDownBold
})

const PALETTE = ['#4f9df9', '#34d399', '#fbbf24', '#f87171', '#a78bfa', '#38bdf8', '#fb7185', '#2dd4bf', '#f97316', '#818cf8']

/** 【标准】根据容器实际尺寸动态计算半径，杜绝写死半径导致卡片缩小时图形溢出 */
function containerSize(): { width: number; height: number } {
  const el = chartEl.value || rootEl.value
  const width = el?.clientWidth || 320
  const height = el?.clientHeight || 260
  return { width, height }
}
/** 环形图动态内外半径（px）：按容器最小边缩放，保证完整落在卡片内 */
function pieRadius(): [string, string] {
  const { width, height } = containerSize()
  const minSide = Math.min(width, height)
  const outer = Math.max(20, Math.floor(minSide * 0.42))
  const inner = Math.floor(outer * 0.55)
  return [`${inner}px`, `${outer}px`]
}
/** 仪表盘动态半径（px）：按容器最小边缩放 */
function gaugeRadius(): string {
  const { width, height } = containerSize()
  const minSide = Math.min(width, height)
  return `${Math.max(20, Math.floor(minSide * 0.46))}px`
}

/* ---------------- 各图表 option 构建 ---------------- */

function buildBarLike(line: boolean, area: boolean): echarts.EChartsOption {
  const x = xCol()
  const v = valueCol()
  const s = seriesCol()
  const cats = rows.value.map((r) => String(r[x] ?? ''))
  const isGradient = !line // 柱状图使用渐变
  const series: any[] = []
  const pushSeries = (name: string, color: string, data: (number | null)[]) => {
    const base: any = {
      name,
      type: line ? 'line' : 'bar',
      smooth: line,
      symbol: line ? 'circle' : undefined,
      symbolSize: line ? 6 : undefined,
      lineStyle: line ? { width: 2.5, color } : undefined,
      barMaxWidth: 28,
      itemStyle: isGradient
        ? {
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color },
              { offset: 1, color: color + '33' },
            ]),
            borderRadius: [4, 4, 0, 0],
          }
        : { color },
      // 【标准】面积图透明度 0.2-0.3
      areaStyle: area
        ? {
            opacity: 0.25,
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color },
              { offset: 1, color: color + '00' },
            ]),
          }
        : undefined,
      data,
    }
    series.push(base)
  }
  if (s) {
    const groups = new Map<string, any[]>()
    rows.value.forEach((r) => {
      const key = String(r[s] ?? '')
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key)!.push(num(r[v]))
    })
    let gi = 0
    groups.forEach((values, key) => {
      pushSeries(key, PALETTE[gi++ % PALETTE.length], values)
    })
  } else {
    pushSeries(v, PALETTE[0], rows.value.map((r) => num(r[v])))
  }
  return {
    color: PALETTE,
    tooltip: baseTooltip('axis'),
    legend: s
      ? { top: 0, type: 'scroll', textStyle: { color: themeColors().text3, fontSize: 11 }, itemWidth: 12, itemHeight: 8 }
      : undefined,
    grid: baseGrid(s ? 32 : 24),
    xAxis: {
      type: 'category',
      data: cats,
      axisLine: { lineStyle: { color: themeColors().line } },
      axisTick: { show: false },
      axisLabel: axisLabelOptions(cats),
    },
    yAxis: {
      type: 'value',
      axisLabel: valueAxisLabel(),
      splitLine: { lineStyle: { color: themeColors().grid } }, // 【标准】网格线弱化
    },
    series,
  }
}

function buildPie(): echarts.EChartsOption {
  const name = xCol() || seriesCol()
  const v = valueCol()
  const data = rows.value.map((r) => ({ name: String(r[name] ?? ''), value: num(r[v]) ?? 0 }))
  return {
    color: PALETTE,
    tooltip: Object.assign(baseTooltip('item'), { formatter: '{b}: {c} ({d}%)' }),
    legend: { bottom: 0, type: 'scroll', textStyle: { color: themeColors().text3, fontSize: 11 } },
    series: [
      {
        name: title.value,
        type: 'pie',
        // 【标准】动态半径：按容器尺寸计算，卡片缩小时自动缩小不溢出
        radius: pieRadius(),
        center: ['50%', '46%'],
        data,
        // 【标准】标签过多自动合并省略，避免重叠
        label: { formatter: '{b}\n{d}%', overflow: 'truncate', width: 90 },
        labelLayout: { hideOverlap: true },
        emphasis: { scaleSize: 6 },
      },
    ],
  }
}

function buildScatter(): echarts.EChartsOption {
  const x = xCol()
  const y = valueCol()
  const s = seriesCol()
  const series: any[] = []
  if (s) {
    const groups = new Map<string, any[]>()
    rows.value.forEach((r) => {
      const key = String(r[s] ?? '')
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key)!.push([num(r[x]), num(r[y])])
    })
    groups.forEach((data, key) => series.push({ name: key, type: 'scatter', data }))
  } else {
    series.push({ type: 'scatter', data: rows.value.map((r) => [num(r[x]), num(r[y])]) })
  }
  return {
    color: PALETTE,
    tooltip: baseTooltip('item'),
    legend: s ? { top: 0, type: 'scroll', textStyle: { color: themeColors().text3, fontSize: 11 } } : undefined,
    grid: baseGrid(s ? 32 : 24),
    xAxis: { type: 'value', name: x, axisLabel: valueAxisLabel(), splitLine: { lineStyle: { color: themeColors().grid } } },
    yAxis: { type: 'value', name: y, axisLabel: valueAxisLabel(), splitLine: { lineStyle: { color: themeColors().grid } } },
    series,
  }
}

function buildHeatmap(): echarts.EChartsOption {
  const x = xCol()
  const y = seriesCol() || columns.value[1] || x
  const v = valueCol()
  const xCats = rows.value.map((r) => String(r[x] ?? ''))
  const yCats = [...new Set(rows.value.map((r) => String(r[y] ?? '')))]
  const data = rows.value.map((r) => [xCats.indexOf(String(r[x] ?? '')), yCats.indexOf(String(r[y] ?? '')), num(r[v]) ?? 0])
  const tooltip = baseTooltip('item')
  tooltip.position = 'top'
  return {
    tooltip,
    grid: { left: 8, right: 16, top: 24, bottom: 48, containLabel: true },
    xAxis: { type: 'category', data: xCats, axisLabel: axisLabelOptions(xCats), splitArea: { show: false } },
    yAxis: { type: 'category', data: yCats, axisLabel: valueAxisLabel() },
    visualMap: {
      min: 0,
      max: Math.max(...data.map((d) => d[2]), 1),
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      textStyle: { color: themeColors().text3 },
    },
    series: [{ type: 'heatmap', data, label: { show: true, fontSize: 10 } }],
  }
}

function buildGauge(): echarts.EChartsOption {
  const v = valueCol()
  const v2 = getConfig().valueColumn2 ?? ''
  const val = num(rows.value[0]?.[v]) ?? num(rows.value[0]?.[v2]) ?? 0
  const max = Math.max(...rows.value.map((r) => num(r[v]) ?? 0), 100)
  const t = themeColors()
  // 【标准】动态字体：随容器缩放自动降级，避免卡片缩小时溢出
  const detailFont = Math.max(12, Math.floor(containerSize().height * 0.09))
  return {
    tooltip: baseTooltip('item'),
    series: [
      {
        type: 'gauge',
        max,
        // 【标准】动态半径：按容器尺寸计算，杜绝写死半径导致卡片缩小时图形溢出
        radius: gaugeRadius(),
        detail: { formatter: '{value}', fontSize: detailFont, color: t.text1 },
        data: [{ value: val, name: v }],
        axisLabel: { color: t.text3, fontSize: 10 },
        axisLine: { lineStyle: { width: 14, color: [[1, t.grid]] } },
        pointer: { width: 5 },
        progress: { show: true, width: 14 },
      },
    ],
  }
}

function buildTreemap(): echarts.EChartsOption {
  const name = xCol() || seriesCol()
  const v = valueCol()
  const data = rows.value.map((r) => ({ name: String(r[name] ?? ''), value: num(r[v]) ?? 0 }))
  return {
    tooltip: Object.assign(baseTooltip('item'), { formatter: '{b}: {c}' }),
    series: [
      {
        type: 'treemap',
        data,
        breadcrumb: { show: false },
        label: { show: true, fontSize: 11, color: '#fff', overflow: 'truncate', width: 60 },
        itemStyle: { borderColor: themeColors().tooltipBg },
      },
    ],
  }
}

function buildBoxplot(): echarts.EChartsOption {
  const cols = numericColumns()
  const cats = cols
  const data = cols.map((c) => {
    const vals = rows.value.map((r) => num(r[c])).filter((n): n is number => n !== null).sort((a, b) => a - b)
    return boxSummary(vals)
  })
  return {
    color: PALETTE,
    tooltip: baseTooltip('item'),
    grid: baseGrid(24),
    xAxis: { type: 'category', data: cats, axisLabel: axisLabelOptions(cats) },
    yAxis: { type: 'value', axisLabel: valueAxisLabel(), splitLine: { lineStyle: { color: themeColors().grid } } },
    series: [{ type: 'boxplot', data }],
  }
}

function boxSummary(arr: number[]): number[] {
  if (!arr.length) return [0, 0, 0, 0, 0]
  const q = (p: number) => {
    const idx = (arr.length - 1) * p
    const lo = Math.floor(idx)
    const hi = Math.ceil(idx)
    return arr[lo] + (arr[hi] - arr[lo]) * (idx - lo)
  }
  return [arr[0], q(0.25), q(0.5), q(0.75), arr[arr.length - 1]]
}

let mapCache: Record<string, any> = {}

async function buildMap(): Promise<echarts.EChartsOption | null> {
  try {
    const mapName = getConfig().mapName || 'china'
    if (!mapCache[mapName]) {
      const geo = await fetchMapGeo(mapName)
      if (!geo) return null
      echarts.registerMap(mapName, geo)
      mapCache[mapName] = true
    }
    const name = xCol() || seriesCol()
    const v = valueCol()
    const data = rows.value.map((r) => ({ name: String(r[name] ?? ''), value: num(r[v]) ?? 0 }))
    return {
      tooltip: Object.assign(baseTooltip('item'), { formatter: '{b}: {c}' }),
      visualMap: {
        min: 0,
        max: Math.max(...data.map((d) => d.value), 1),
        left: 'left',
        top: 'bottom',
        calculable: true,
        textStyle: { color: themeColors().text3 },
      },
      series: [{ type: 'map', map: mapName, roam: true, label: { show: true }, data }],
    }
  } catch {
    return null
  }
}

async function fetchMapGeo(name: string): Promise<any | null> {
  // DataV 行政区划 GeoJSON，name 需为 adcode；默认提供常用映射
  const adcodeMap: Record<string, string> = {
    china: '100000_full',
    中国: '100000_full',
    世界: 'world',
  }
  const code = adcodeMap[name] || name
  const url = `https://geo.datav.aliyun.com/areas_v3/bound/${code}.json`
  const resp = await fetch(url)
  if (!resp.ok) return null
  return resp.json()
}

/* ---------------- 渲染 ---------------- */

async function render() {
  if (chartType.value === 'TABLE') return
  if (chartType.value === 'NUMBER') {
    renderSpark()
    return
  }
  await nextTick()
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  let option: echarts.EChartsOption | null = null
  switch (chartType.value) {
    case 'BAR':
      option = buildBarLike(false, false)
      break
    case 'LINE':
      option = buildBarLike(true, false)
      break
    case 'AREA':
      option = buildBarLike(true, true)
      break
    case 'PIE':
      option = buildPie()
      break
    case 'SCATTER':
      option = buildScatter()
      break
    case 'HEATMAP':
      option = buildHeatmap()
      break
    case 'GAUGE':
      option = buildGauge()
      break
    case 'TREEMAP':
      option = buildTreemap()
      break
    case 'BOXPLOT':
      option = buildBoxplot()
      break
    case 'MAP':
      option = await buildMap()
      break
  }
  if (option) {
    chart.setOption(option, true)
  } else {
    chart.clear()
  }
  // 注册点击下钻：点击数据点派发 { column, value }
  chart.off('click')
  chart.on('click', (params: any) => {
    const p = params as any
    const value = p?.name ?? p?.value
    if (value == null || value === '') return
    const isCate = chartType.value === 'PIE' || chartType.value === 'TREEMAP'
    const column = isCate ? (xCol() || seriesCol()) : xCol()
    emit('drill', { column, value: String(value) })
  })
}

/** 【标准】容器尺寸变化时同步 resize 图表（兼容卡片拖拽缩放）
 * 对于 pie/gauge 依赖容器尺寸计算半径，必须重新渲染才能更新半径 */
function resize() {
  // 饼图/仪表盘 需要重新计算半径 → 全量重绘
  if (chart && (chartType.value === 'PIE' || chartType.value === 'GAUGE')) {
    render()
    return
  }
  chart?.resize()
  sparkChart?.resize()
}

/** 渲染指标迷你趋势图（sparkline） */
async function renderSpark() {
  await nextTick()
  if (!spark.value || !sparkEl.value) {
    sparkChart?.dispose()
    sparkChart = null
    return
  }
  const col = cfg.value.sparkColumn || valueCol()
  const data = orderedRows.value
    .map((r) => num(r[col]))
    .filter((n): n is number => n !== null)
  if (!sparkChart) sparkChart = echarts.init(sparkEl.value)
  if (!data.length) {
    sparkChart.clear()
    return
  }
  const up = data[data.length - 1] >= data[0]
  const lineColor = cfg.value.reverseColor ? (up ? '#f56c6c' : '#34d399') : (up ? '#34d399' : '#f56c6c')
  const sparkTooltip = baseTooltip('axis')
  sparkTooltip.formatter = (p: any) => `${title.value}<br/>${p[0]?.value ?? ''}`
  sparkChart.setOption(
    {
      grid: { left: 0, right: 0, top: 2, bottom: 0 },
      xAxis: { type: 'category', show: false, boundaryGap: false, data: data.map((_, i) => i) },
      yAxis: { type: 'value', show: false, min: 'dataMin', max: 'dataMax' },
      tooltip: sparkTooltip,
      series: [
        {
          type: 'line',
          data,
          smooth: true,
          showSymbol: false,
          lineStyle: { width: 2, color: lineColor },
          areaStyle: {
            opacity: 0.25,
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: lineColor + '55' },
              { offset: 1, color: lineColor + '05' },
            ]),
          },
        },
      ],
    },
    true,
  )
}

watch(
  () => [props.result, props.item, chartType.value, props.theme],
  () => render(),
  { deep: true },
)

onMounted(() => {
  render()
  window.addEventListener('resize', resize)
  // 【标准】监听容器 resize（ResizeObserver），小卡片拖拽缩放时自动适配
  ro = rootEl.value ? new ResizeObserver(resize) : null
  if (ro) ro.observe(rootEl.value!)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  // 【标准】销毁 ResizeObserver 与 echarts 实例，防止内存泄漏
  ro?.disconnect()
  ro = null
  chart?.dispose()
  chart = null
  sparkChart?.dispose()
  sparkChart = null
})
</script>

<style scoped>
.chart-renderer {
  width: 100%;
  height: 100%;
  position: relative;
  display: flex;
  flex-direction: column;
  background: transparent;
}

/* ============ 标题栏 ============ */
.chart-title-bar {
  display: flex;
  align-items: center;
  padding: 6px 10px;
  border-bottom: 1px solid var(--card-border, #ebeef5);
  background: var(--card-bg, #ffffff);
  flex-shrink: 0;
}

.chart-title-text {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-1, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ============ 主体 ============ */
.chart-body {
  flex: 1;
  min-height: 0;
  position: relative;
}

.chart-body.has-title {
  height: calc(100% - 33px);
}

.chart-box {
  width: 100%;
  height: 100%;
}

.table-wrap {
  height: 100%;
  overflow: hidden;
}

/* ============ 占位符 ============ */
.placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3, #909399);
  font-size: 13px;
}

.placeholder.empty {
  background: linear-gradient(135deg, rgba(79, 157, 249, 0.02), rgba(52, 211, 153, 0.02));
}

.placeholder .placeholder-hint {
  font-size: 11px;
  opacity: 0.6;
}

.loading-icon {
  animation: spin 1s linear infinite;
  font-size: 24px;
  color: var(--accent, #4f9df9);
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* ============ 指标卡 ============ */
.metric-card {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 12px 16px;
  box-sizing: border-box;
  overflow: hidden;
}

.metric-label {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 8px;
}

.metric-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2, #606266);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-subtitle {
  font-size: 11px;
  color: var(--text-3, #909399);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-value-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  line-height: 1;
}

.metric-value {
  font-size: clamp(28px, 4vw, 42px);
  font-weight: 700;
  color: var(--text-1, #303133);
  letter-spacing: -0.5px;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  overflow-wrap: anywhere;
}

.metric-value.is-small { font-size: clamp(24px, 3.2vw, 34px); }

.metric-unit {
  font-size: 14px;
  color: var(--text-3, #909399);
  font-weight: 500;
}

.metric-compare {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  font-size: 12px;
  font-weight: 500;
}

.metric-arrow { display: inline-flex; align-items: center; }
.metric-compare-label { color: var(--text-3, #909399); }
.metric-compare-value { margin-left: 2px; }
.metric-compare-rate { font-weight: 700; }

.metric-up { color: #34d399; }
.metric-down { color: #f56c6c; }

.metric-spark {
  height: 36px;
  margin-top: 8px;
  width: 100%;
}

/* ============ 响应式 ============ */
@media (max-width: 600px) {
  .metric-value { font-size: 32px; }
  .metric-value.is-small { font-size: 28px; }
  .metric-name { font-size: 12px; }
  .metric-unit { font-size: 12px; }
  .metric-card { padding: 8px 12px; }
  .chart-title-bar { padding: 4px 8px; }
  .chart-title-text { font-size: 12px; }
}
</style>