<template>
  <div class="bi-chart-builder" :class="[`theme-${theme}`]">
    <!-- ==================== 顶部栏 ==================== -->
    <header class="cb-header">
      <div class="cb-header-left">
        <el-tooltip content="返回仪表板">
          <el-button :icon="ArrowLeft" circle size="small" @click="goBack" />
        </el-tooltip>
        <el-icon class="cb-logo" :size="18"><DataAnalysis /></el-icon>
        <el-input v-model="title" class="cb-title-input" size="small" placeholder="图表标题" />
        <el-tag v-if="primaryModel" size="small" effect="plain" class="cb-ds-tag">
          <el-icon :size="11"><Coin /></el-icon>
          {{ primaryModel.name }}
          <span class="cb-ds-type" :class="modelTypeClass(primaryModel.modelType)">
            {{ primaryModel.modelType || 'MODEL' }}
          </span>
        </el-tag>
      </div>
      <div class="cb-header-right">
        <el-tooltip content="切换主题">
          <el-button :icon="theme === 'dark' ? Sunny : Moon" circle size="small" @click="toggleTheme" />
        </el-tooltip>
        <el-tooltip content="查看生成的 SQL">
          <el-button :icon="Document" circle size="small" :disabled="!generatedSql" @click="sqlVisible = !sqlVisible" />
        </el-tooltip>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="runQuery(true)">刷新数据</el-button>
        <el-button type="primary" size="small" :icon="Check" :loading="saving" @click="saveAndBack">保存并返回</el-button>
      </div>
    </header>

    <!-- 跨模型冲突警示条 -->
    <div v-if="conflictFields.length" class="cb-conflict-bar">
      <el-icon><WarningFilled /></el-icon>
      <span>
        检测到 <b>{{ conflictFields.length }}</b> 个跨模型字段
        <template v-if="conflictModelNames.length">（{{ conflictModelNames.join('、') }}）</template>
        ：不同模型的指标/维度无法联合查询（模型之间无法连查），查询时将被忽略。
      </span>
      <el-button size="small" type="danger" plain @click="removeConflicts">移除冲突字段</el-button>
    </div>

    <!-- ==================== 上方货架区：指标 / 维度 / 筛选器 ==================== -->
    <section class="cb-shelf">
      <!-- 指标 -->
      <div
        class="cb-shelf-row"
        :class="{ 'drop-active': dropTarget === 'metric' }"
        @dragover.prevent="dropTarget = 'metric'"
        @dragleave="dropTarget = ''"
        @drop="onShelfDrop($event, 'metric')"
      >
        <div class="cb-shelf-label metric">
          <el-icon><TrendCharts /></el-icon>
          <span>指标</span>
        </div>
        <div class="cb-shelf-body">
          <div v-for="(m, i) in selectedMetrics" :key="m.code" class="cb-chip metric" :class="{ conflict: isConflict(m) }">
            <el-icon class="cb-chip-icon"><DataLine /></el-icon>
            <span class="cb-chip-name">{{ m.name }}</span>
            <el-tooltip v-if="isConflict(m)" content="与其他字段不属于同一模型，无法联合查询">
              <el-icon class="cb-chip-warn"><WarningFilled /></el-icon>
            </el-tooltip>
            <span class="cb-metric-type" :class="m.metricType === 'DERIVED' ? 'derived' : 'atomic'">{{ m.metricType === 'DERIVED' ? '派生' : '原子' }}</span>
            <span v-if="m.unit" class="cb-metric-unit">{{ m.unit }}</span>
            <el-icon class="cb-chip-remove" @click="selectedMetrics.splice(i, 1)"><Close /></el-icon>
          </div>
          <div class="cb-shelf-empty" :class="{ hover: dropTarget === 'metric' }">
            {{ selectedMetrics.length ? '' : '拖入或点击指标' }}
          </div>
        </div>
      </div>

      <!-- 维度 -->
      <div
        class="cb-shelf-row"
        :class="{ 'drop-active': dropTarget === 'dimension' }"
        @dragover.prevent="dropTarget = 'dimension'"
        @dragleave="dropTarget = ''"
        @drop="onShelfDrop($event, 'dimension')"
      >
        <div class="cb-shelf-label dimension">
          <el-icon><Menu /></el-icon>
          <span>维度</span>
        </div>
        <div class="cb-shelf-body">
          <div v-for="(d, i) in selectedDims" :key="d.code" class="cb-chip dimension" :class="{ conflict: isConflict(d) }">
            <el-icon class="cb-chip-icon"><Files /></el-icon>
            <span class="cb-chip-name">{{ d.name }}</span>
            <el-tooltip v-if="isConflict(d)" content="与其他字段不属于同一模型，无法联合查询">
              <el-icon class="cb-chip-warn"><WarningFilled /></el-icon>
            </el-tooltip>
            <el-select v-if="d.dimType === 'TIME'" v-model="d.grain" size="small" class="cb-dim-grain">
              <el-option label="日" value="D" />
              <el-option label="月" value="M" />
              <el-option label="年" value="Y" />
            </el-select>
            <el-select v-model="d.sort" size="small" class="cb-dim-sort">
              <el-option label="默认" value="" />
              <el-option label="升序" value="ASC" />
              <el-option label="降序" value="DESC" />
            </el-select>
            <el-icon class="cb-chip-remove" @click="selectedDims.splice(i, 1)"><Close /></el-icon>
          </div>
          <div class="cb-shelf-empty" :class="{ hover: dropTarget === 'dimension' }">
            {{ selectedDims.length ? '' : '拖入或点击维度' }}
          </div>
        </div>
      </div>

      <!-- 筛选器 -->
      <div
        class="cb-shelf-row"
        :class="{ 'drop-active': dropTarget === 'filter' }"
        @dragover.prevent="dropTarget = 'filter'"
        @dragleave="dropTarget = ''"
        @drop="onShelfDrop($event, 'filter')"
      >
        <div class="cb-shelf-label filter">
          <el-icon><Filter /></el-icon>
          <span>筛选器</span>
        </div>
        <div class="cb-shelf-body">
          <div v-for="(f, i) in filters" :key="f.key" class="cb-chip filter" :class="{ conflict: isConflict(f) }">
            <el-icon class="cb-chip-icon"><Filter /></el-icon>
            <span class="cb-chip-name">{{ f.name }}</span>
            <el-tooltip v-if="isConflict(f)" content="与其他字段不属于同一模型，无法联合查询">
              <el-icon class="cb-chip-warn"><WarningFilled /></el-icon>
            </el-tooltip>
            <template v-if="f.dimType === 'TIME'">
              <el-date-picker
                v-model="f.dateRange"
                type="daterange"
                range-separator="~"
                start-placeholder="开始"
                end-placeholder="结束"
                value-format="YYYY-MM-DD"
                size="small"
                class="cb-filter-date"
                @change="onFilterDateChange(f)"
              />
            </template>
            <template v-else>
              <el-select v-model="f.operator" size="small" class="cb-filter-op" @change="onFilterOpChange(f)">
                <el-option v-for="op in FILTER_OPS" :key="op.value" :label="op.label" :value="op.value" />
              </el-select>
              <el-select
                v-model="f.values"
                multiple
                filterable
                allow-create
                default-first-option
                collapse-tags
                size="small"
                class="cb-filter-value"
                :placeholder="valuePlaceholder(f.operator)"
                @focus="loadFilterOptions(f)"
              >
                <el-option v-for="v in f.options" :key="v" :label="v" :value="v" />
              </el-select>
            </template>
            <el-icon class="cb-chip-remove" @click="filters.splice(i, 1)"><Close /></el-icon>
          </div>
          <div class="cb-shelf-empty" :class="{ hover: dropTarget === 'filter' }">
            {{ filters.length ? '' : '拖入维度作为筛选器' }}
          </div>
        </div>
      </div>
    </section>

    <!-- ==================== 主体：左侧资产池 + 中间画布 + 右侧样式 ==================== -->
    <div class="cb-main">
      <!-- 左侧：模型资产池（模型 → 指标 / 维度） -->
      <aside class="cb-aside">
        <div class="cb-aside-title">
          <el-icon><Coin /></el-icon>
          <span>模型资产池</span>
          <span class="cb-aside-sub">指标 / 维度</span>
        </div>
        <div class="cb-search">
          <el-input v-model="searchText" size="small" placeholder="搜索模型 / 指标 / 维度" clearable :prefix-icon="Search" />
        </div>

        <div class="cb-dataset-list">
          <div v-if="!filteredModels.length" class="cb-pool-empty">无匹配模型（需先在「数据集市」发布模型）</div>
          <template v-for="m in filteredModels" :key="m.id">
            <div
              class="cb-dataset-item"
              :class="{ active: expandedModelId === m.id, primary: m.id === primaryModelId }"
              @click="toggleModel(m)"
            >
              <el-icon class="cb-ds-expand" :class="{ open: expandedModelId === m.id }"><ArrowRight /></el-icon>
              <el-icon class="cb-model-icon"><Coin /></el-icon>
              <span class="cb-ds-name" :title="m.name" v-html="highlight(m.name)"></span>
              <span class="cb-ds-type" :class="modelTypeClass(m.modelType)">{{ m.modelType || 'MODEL' }}</span>
              <el-tooltip v-if="m.id !== primaryModelId && primaryModelId" content="与当前模型不同，指标/维度无法联合查询" placement="top">
                <el-icon class="cb-ds-warn"><WarningFilled /></el-icon>
              </el-tooltip>
              <el-tooltip v-else-if="m.id === primaryModelId" content="当前图表主模型" placement="top">
                <el-icon class="cb-ds-primary"><CircleCheckFilled /></el-icon>
              </el-tooltip>
            </div>

            <!-- 模型展开：指标 + 维度 -->
            <div v-if="expandedModelId === m.id" class="cb-field-pool" :class="{ incompatible: m.id !== primaryModelId && !!primaryModelId }">
              <div v-if="!m.loaded" class="cb-pool-loading"><el-icon class="is-loading"><Loading /></el-icon> 加载模型中...</div>
              <div v-else-if="m.id !== primaryModelId && primaryModelId" class="cb-pool-warn">
                <el-icon><WarningFilled /></el-icon>
                该模型与当前图表的指标/维度不来自同一模型，加入后无法联合查询（将高亮标红）
              </div>

              <div v-if="m.loaded" class="cb-pool-group">
                <div class="cb-pool-group-title">
                  <el-icon><Files /></el-icon> 维度
                  <span class="cb-pool-count">{{ poolDims(m).length }}</span>
                </div>
                <div
                  v-for="dim in poolDims(m)"
                  :key="dim.id"
                  class="cb-field-item dimension"
                  draggable="true"
                  @dragstart="onAssetDragStart($event, m, dim, 'DIMENSION')"
                  @click="addDimension(m, dim)"
                >
                  <el-icon><Files /></el-icon>
                  <span class="cb-field-name" :title="dim.dimName" v-html="highlight(dim.dimName || '')"></span>
                  <span class="cb-field-code">{{ dim.dimCode }}</span>
                  <span class="cb-dim-type" :class="dimTypeClass(dim.dimType)">{{ dimTypeLabel(dim.dimType) }}</span>
                  <el-tooltip content="设为筛选器" placement="top">
                    <el-icon class="cb-field-op" @click.stop="addFilter(m, dim)"><Filter /></el-icon>
                  </el-tooltip>
                </div>
                <div v-if="!poolDims(m).length" class="cb-pool-empty">暂无维度</div>
              </div>

              <div v-if="m.loaded" class="cb-pool-group">
                <div class="cb-pool-group-title">
                  <el-icon><TrendCharts /></el-icon> 指标
                  <span class="cb-pool-count">{{ poolMetrics(m).length }}</span>
                </div>
                <div
                  v-for="metric in poolMetrics(m)"
                  :key="metric.id"
                  class="cb-field-item metric"
                  draggable="true"
                  @dragstart="onAssetDragStart($event, m, metric, 'METRIC')"
                  @click="addMetric(m, metric)"
                >
                  <el-icon><DataLine /></el-icon>
                  <span class="cb-field-name" :title="metric.metricName" v-html="highlight(metric.metricName || '')"></span>
                  <span class="cb-field-code">{{ metric.metricCode }}</span>
                  <span class="cb-metric-type" :class="metric.metricType === 'DERIVED' ? 'derived' : 'atomic'">
                    {{ metric.metricType === 'DERIVED' ? '派生' : '原子' }}
                  </span>
                  <el-tooltip v-if="metric.expression" :content="'表达式: ' + metric.expression" placement="top">
                    <el-icon class="cb-field-expr"><View /></el-icon>
                  </el-tooltip>
                </div>
                <div v-if="!poolMetrics(m).length" class="cb-pool-empty">暂无指标</div>
              </div>
            </div>
          </template>
        </div>
      </aside>

      <!-- 中间：图表类型 + 预览画布 -->
      <main class="cb-canvas">
        <!-- 图表类型选择 -->
        <div class="cb-chart-types">
          <div
            v-for="ct in CHART_TYPES"
            :key="ct.type"
            class="cb-chart-type-item"
            :class="{ active: chartType === ct.type }"
            @click="chartType = ct.type"
          >
            <el-icon :size="16"><component :is="ct.icon" /></el-icon>
            <span>{{ ct.label }}</span>
          </div>
        </div>

        <!-- SQL 查看 -->
        <el-collapse-transition>
          <div v-if="sqlVisible && generatedSql" class="cb-sql-box">
            <div class="cb-sql-title">
              <el-icon><Document /></el-icon> 生成的查询 SQL
              <span v-if="result" class="cb-sql-meta">{{ result.rows?.length || 0 }} 行 · {{ costMs }}ms</span>
            </div>
            <pre class="cb-sql-text">{{ generatedSql }}</pre>
          </div>
        </el-collapse-transition>

        <!-- 预览 -->
        <div class="cb-preview" v-loading="loading" element-loading-text="查询中...">
          <ChartRenderer
            v-if="result && (result.rows?.length || result.columns?.length)"
            :item="previewItem"
            :result="result"
            :loading="loading"
            :theme="theme"
          />
          <div v-else-if="!loading" class="cb-preview-empty">
            <el-icon :size="44"><DataLine /></el-icon>
            <template v-if="!primaryModelId">
              <p>从左侧模型资产池选择模型，点击或拖入指标/维度开始绘图</p>
            </template>
            <template v-else-if="!hasQueryFields">
              <p>请至少选择一个指标</p>
            </template>
            <template v-else>
              <p>暂无数据</p>
              <p class="cb-preview-hint">可调整筛选条件或点击「刷新数据」重试</p>
            </template>
          </div>
        </div>
      </main>

      <!-- 右侧：样式配置 -->
      <aside class="cb-style">
        <div class="cb-aside-title">
          <el-icon><Setting /></el-icon>
          <span>图表样式</span>
        </div>
        <div class="cb-style-body">
          <div class="cb-style-group">
            <div class="cb-style-group-title">标题</div>
            <div class="cb-style-row">
              <span>显示标题</span>
              <el-switch v-model="styleCfg.title.show" size="small" />
            </div>
            <div class="cb-style-row">
              <span>对齐</span>
              <el-radio-group v-model="styleCfg.title.align" size="small">
                <el-radio-button value="left">左</el-radio-button>
                <el-radio-button value="center">中</el-radio-button>
              </el-radio-group>
            </div>
            <div class="cb-style-row">
              <span>字号</span>
              <el-input-number v-model="styleCfg.title.fontSize" :min="10" :max="32" size="small" />
            </div>
          </div>

          <div class="cb-style-group">
            <div class="cb-style-group-title">图例 / 标签</div>
            <div class="cb-style-row">
              <span>显示图例</span>
              <el-switch v-model="styleCfg.legend.show" size="small" />
            </div>
            <div class="cb-style-row">
              <span>图例位置</span>
              <el-select v-model="styleCfg.legend.position" size="small" style="width: 90px">
                <el-option label="顶部" value="top" />
                <el-option label="底部" value="bottom" />
                <el-option label="左侧" value="left" />
                <el-option label="右侧" value="right" />
              </el-select>
            </div>
            <div class="cb-style-row">
              <span>数值标签</span>
              <el-switch v-model="styleCfg.labelShow" size="small" />
            </div>
            <div class="cb-style-row">
              <span>小数位</span>
              <el-input-number v-model="styleCfg.decimalDigits" :min="0" :max="6" size="small" />
            </div>
          </div>

          <div class="cb-style-group">
            <div class="cb-style-group-title">配色</div>
            <div class="cb-style-row">
              <span>主题</span>
              <el-select v-model="styleCfg.colorTheme" size="small" style="width: 120px">
                <el-option label="默认蓝" value="default" />
                <el-option label="商务蓝" value="business" />
                <el-option label="科技感" value="tech" />
                <el-option label="暖色调" value="warm" />
                <el-option label="清新绿" value="fresh" />
              </el-select>
            </div>
          </div>

          <div class="cb-style-group">
            <div class="cb-style-group-title">数据</div>
            <div class="cb-style-row">
              <span>行数限制</span>
              <el-input-number v-model="limit" :min="1" :max="10000" :step="100" size="small" />
            </div>
            <div class="cb-style-row">
              <span>自动查询</span>
              <el-switch v-model="autoQuery" size="small" />
            </div>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { type Component, computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, ArrowRight, Check, CircleCheckFilled, Close, Coin, DataAnalysis, DataLine, Document, Files,
  Filter, Grid, Histogram, Loading, Menu, Moon, Odometer, PieChart, Refresh, Search, Setting, Sunny, Tickets,
  TrendCharts, View, WarningFilled,
} from '@element-plus/icons-vue'
import ChartRenderer from '@/components/chart/ChartRenderer.vue'
import { martModelDetail, pageMartMarket, queryMart, dimensionValues } from '@/api/mart'
import { listDashboardItems, saveItem } from '@/api/visual'
import type {
  ChartType, MarketModelDto, MartDimension, MartMetric, QueryResult, VisualDashboardItem,
} from '@/types'

/* ============ 路由 ============ */
const route = useRoute()
const router = useRouter()
const dashboardId = ref(route.params.dashboardId as string)
const itemId = ref(route.params.itemId as string)

/* ============ UI 状态 ============ */
const theme = ref<'light' | 'dark'>('light')
const saving = ref(false)
const loading = ref(false)
const sqlVisible = ref(false)
const costMs = ref(0)
const title = ref('未命名图表')
const chartType = ref<ChartType>('BAR')
const limit = ref(1000)
const autoQuery = ref(true)
const searchText = ref('')
const dropTarget = ref('')

const CHART_TYPES = [
  { type: 'BAR', label: '柱状图', icon: Histogram },
  { type: 'LINE', label: '折线图', icon: DataLine },
  { type: 'AREA', label: '面积图', icon: TrendCharts },
  { type: 'PIE', label: '饼图', icon: PieChart },
  { type: 'SCATTER', label: '散点图', icon: Grid },
  { type: 'TABLE', label: '表格', icon: Tickets },
  { type: 'NUMBER', label: '指标卡', icon: Odometer },
  { type: 'FUNNEL', label: '漏斗图', icon: TrendCharts },
  { type: 'RADAR', label: '雷达图', icon: Grid },
  { type: 'GAUGE', label: '仪表盘', icon: Odometer },
] as { type: ChartType; label: string; icon: Component }[]

const FILTER_OPS = [
  { value: 'EQ', label: '=' },
  { value: 'NE', label: '≠' },
  { value: 'GT', label: '>' },
  { value: 'GTE', label: '≥' },
  { value: 'LT', label: '<' },
  { value: 'LTE', label: '≤' },
  { value: 'LIKE', label: '包含' },
  { value: 'IN', label: '属于' },
  { value: 'NOT_IN', label: '不属于' },
  { value: 'BETWEEN', label: '区间' },
]

/* ============ 模型资产池 ============ */
interface PoolModel {
  id: string
  name: string
  modelType?: string
  layerCode?: string
  metricCount?: number
  dimensionCount?: number
  datasourceId?: string
  metrics: MartMetric[]
  dimensions: MartDimension[]
  loaded: boolean
}

const models = ref<PoolModel[]>([])
const expandedModelId = ref('')

const filteredModels = computed(() => {
  const s = searchText.value.trim().toLowerCase()
  if (!s) return models.value
  return models.value.filter(
    (m) =>
      m.name.toLowerCase().includes(s) ||
      m.metrics.some((x) => (x.metricName || '').toLowerCase().includes(s) || (x.metricCode || '').toLowerCase().includes(s)) ||
      m.dimensions.some((x) => (x.dimName || '').toLowerCase().includes(s) || (x.dimCode || '').toLowerCase().includes(s)),
  )
})

function poolDims(m: PoolModel): MartDimension[] {
  const s = searchText.value.trim().toLowerCase()
  return s
    ? m.dimensions.filter((d) => (d.dimName || '').toLowerCase().includes(s) || (d.dimCode || '').toLowerCase().includes(s))
    : m.dimensions
}

function poolMetrics(m: PoolModel): MartMetric[] {
  const s = searchText.value.trim().toLowerCase()
  return s
    ? m.metrics.filter((x) => (x.metricName || '').toLowerCase().includes(s) || (x.metricCode || '').toLowerCase().includes(s))
    : m.metrics
}

async function toggleModel(m: PoolModel) {
  if (expandedModelId.value === m.id) {
    expandedModelId.value = ''
    return
  }
  expandedModelId.value = m.id
  await ensureModelLoaded(m)
}

async function ensureModelLoaded(m: PoolModel) {
  if (m.loaded) return
  m.loaded = true
  try {
    const detail = await martModelDetail(m.id)
    m.metrics = detail.metrics || []
    m.dimensions = detail.dimensions || []
    m.datasourceId = detail.model?.datasourceId
  } catch (e: any) {
    ElMessage.error(e?.message || '加载模型资产失败')
  }
}

function modelTypeClass(type?: string): string {
  const t = (type || '').toUpperCase()
  if (t === 'SNOWFLAKE') return 'snowflake'
  return 'star'
}

function dimTypeLabel(t?: string): string {
  if (t === 'TIME') return '时间'
  if (t === 'ORG') return '组织'
  return '通用'
}

function dimTypeClass(t?: string): string {
  if (t === 'TIME') return 'time'
  if (t === 'ORG') return 'org'
  return 'common'
}

function escapeHtml(t: string): string {
  return t.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!))
}

function highlight(text: string): string {
  const s = searchText.value.trim()
  if (!s) return escapeHtml(text)
  const idx = text.toLowerCase().indexOf(s.toLowerCase())
  if (idx < 0) return escapeHtml(text)
  return (
    escapeHtml(text.slice(0, idx)) +
    '<span class="cb-hl">' + escapeHtml(text.slice(idx, idx + s.length)) + '</span>' +
    escapeHtml(text.slice(idx + s.length))
  )
}

/* ============ 已选字段（指标 / 维度 / 筛选器，模型语义） ============ */
interface SelField {
  code: string
  name: string
  modelId: string
  modelName: string
}

interface SelDim extends SelField { dimType: string; grain: string; sort: string }
interface SelMetric extends SelField { metricType: string; unit: string }
interface SelFilter extends SelField {
  dimId: string
  dimType: string
  operator: string
  values: string[]
  valueInput: string
  dateRange: [string, string] | null
  key: string
  options: string[]
  optionsLoaded: boolean
}

const selectedDims = ref<SelDim[]>([])
const selectedMetrics = ref<SelMetric[]>([])
const filters = ref<SelFilter[]>([])

function genKey(): string {
  return `f_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`
}

/** 主模型：第一个被选择字段所属模型（联查兼容性基准） */
const primaryModelId = computed(
  () => selectedDims.value[0]?.modelId || selectedMetrics.value[0]?.modelId || filters.value[0]?.modelId || '',
)
const primaryModel = computed(() => models.value.find((m) => m.id === primaryModelId.value))

const conflictFields = computed(() => {
  const pid = primaryModelId.value
  if (!pid) return [] as SelField[]
  return [...selectedDims.value, ...selectedMetrics.value, ...filters.value].filter((f) => f.modelId !== pid)
})

const conflictModelNames = computed(() => {
  const ids = new Set(conflictFields.value.map((f) => f.modelId))
  return models.value.filter((m) => ids.has(m.id)).map((m) => m.name)
})

function isConflict(f: SelField): boolean {
  return !!primaryModelId.value && f.modelId !== primaryModelId.value
}

function checkConflict(modelId: string, name?: string) {
  if (primaryModelId.value && modelId !== primaryModelId.value) {
    ElMessage.warning(`「${name}」来自其他模型，与当前字段无法联合查询`)
  }
}

function removeConflicts() {
  const pid = primaryModelId.value
  selectedDims.value = selectedDims.value.filter((f) => f.modelId === pid)
  selectedMetrics.value = selectedMetrics.value.filter((f) => f.modelId === pid)
  filters.value = filters.value.filter((f) => f.modelId === pid)
  ElMessage.success('已移除冲突字段')
}

/** 添加指标 / 维度 / 筛选器到货架 */
function addMetric(m: PoolModel, metric: MartMetric) {
  if (selectedMetrics.value.some((x) => x.code === metric.metricCode && x.modelId === m.id)) return
  selectedMetrics.value.push({
    code: metric.metricCode || '',
    name: metric.metricName || metric.metricCode || '',
    modelId: m.id,
    modelName: m.name,
    metricType: metric.metricType || 'ATOMIC',
    unit: metric.unit || '',
  })
  checkConflict(m.id, metric.metricName)
}

function addDimension(m: PoolModel, dim: MartDimension) {
  if (selectedDims.value.some((x) => x.code === dim.dimCode && x.modelId === m.id)) return
  selectedDims.value.push({
    code: dim.dimCode || '',
    name: dim.dimName || dim.dimCode || '',
    modelId: m.id,
    modelName: m.name,
    dimType: dim.dimType || 'COMMON',
    grain: '',
    sort: '',
  })
  checkConflict(m.id, dim.dimName)
}

function addFilter(m: PoolModel, dim: MartDimension) {
  if (filters.value.some((x) => x.code === dim.dimCode && x.modelId === m.id)) return
  filters.value.push({
    code: dim.dimCode || '',
    name: dim.dimName || dim.dimCode || '',
    modelId: m.id,
    modelName: m.name,
    dimId: dim.id || '',
    dimType: dim.dimType || 'COMMON',
    operator: dim.dimType === 'TIME' ? 'BETWEEN' : 'IN',
    values: [],
    valueInput: '',
    dateRange: null,
    key: genKey(),
    options: [],
    optionsLoaded: false,
  })
  checkConflict(m.id, dim.dimName)
}

/* 拖拽 */
function onAssetDragStart(e: DragEvent, m: PoolModel, asset: MartDimension | MartMetric, kind: 'DIMENSION' | 'METRIC') {
  const code = kind === 'DIMENSION' ? (asset as MartDimension).dimCode : (asset as MartMetric).metricCode
  e.dataTransfer?.setData('application/json', JSON.stringify({ modelId: m.id, code, kind }))
  e.dataTransfer!.effectAllowed = 'copy'
}

function onShelfDrop(e: DragEvent, target: 'dimension' | 'metric' | 'filter') {
  dropTarget.value = ''
  const raw = e.dataTransfer?.getData('application/json')
  if (!raw) return
  try {
    const { modelId, code, kind } = JSON.parse(raw)
    const m = models.value.find((x) => x.id === modelId)
    if (!m) return
    if (target === 'filter') {
      const dim = m.dimensions.find((x) => x.dimCode === code)
      if (dim) addFilter(m, dim)
    } else if (target === 'dimension') {
      const dim = m.dimensions.find((x) => x.dimCode === code)
      if (dim) addDimension(m, dim)
    } else {
      const metric = m.metrics.find((x) => x.metricCode === code)
      if (metric) addMetric(m, metric)
    }
  } catch { /* ignore */ }
}

/* 筛选器值 */
function valuePlaceholder(op: string): string {
  if (op === 'BETWEEN') return '选择两个值'
  if (op === 'IN' || op === 'NOT_IN') return '选择一个或多个值'
  return '选择或输入一个值'
}

function onFilterDateChange(f: SelFilter) {
  f.values = f.dateRange ? [...f.dateRange] : []
}

function onFilterOpChange(f: SelFilter) {
  f.values = []
  f.dateRange = null
}

async function loadFilterOptions(f: SelFilter) {
  if (f.optionsLoaded) return
  f.optionsLoaded = true
  try {
    f.options = (await dimensionValues(f.dimId, 200)) || []
  } catch {
    f.options = []
  }
}

/* ============ 查询 ============ */
const result = ref<QueryResult | null>(null)
const generatedSql = ref('')

const hasQueryFields = computed(
  () =>
    selectedDims.value.some((d) => d.modelId === primaryModelId.value) ||
    selectedMetrics.value.some((m) => m.modelId === primaryModelId.value),
)

const previewItem = computed<VisualDashboardItem>(() => ({
  id: 'preview',
  title: title.value,
  chartType: chartType.value,
  dataConfig: JSON.stringify(buildDataConfig()),
  styleConfig: JSON.stringify(styleCfg),
}))

/** 组装保存/预览共用的 dataConfig（模型语义 + 兼容 ChartRenderer 的列名映射） */
function buildDataConfig() {
  const pid = primaryModelId.value
  return {
    mode: 'MODEL',
    modelId: pid,
    modelName: primaryModel.value?.name || '',
    dimensions: selectedDims.value
      .filter((d) => d.modelId === pid)
      .map((d) => ({ fieldCode: d.code, fieldName: d.name, dimType: d.dimType, grain: d.grain, sort: d.sort })),
    metrics: selectedMetrics.value
      .filter((m) => m.modelId === pid)
      .map((m) => ({ fieldCode: m.code, fieldName: m.name, metricType: m.metricType, unit: m.unit })),
    filters: filters.value
      .filter((f) => f.modelId === pid)
      .map((f) => ({ dimCode: f.code, fieldName: f.name, dimType: f.dimType, operator: f.operator, values: f.values })),
    limit: limit.value,
  }
}

async function runQuery(manual = false) {
  if (!primaryModelId.value) {
    if (manual) ElMessage.warning('请先从左侧模型资产池选择指标/维度')
    return
  }
  if (!hasQueryFields.value) {
    if (manual) ElMessage.warning('请至少选择一个指标')
    return
  }
  loading.value = true
  const start = performance.now()
  try {
    const pid = primaryModelId.value
    const dims = selectedDims.value.filter((d) => d.modelId === pid)
    const mets = selectedMetrics.value.filter((m) => m.modelId === pid)
    const fts = filters.value.filter((f) => f.modelId === pid && f.values.length > 0)
    const r = await queryMart({
      modelId: pid,
      metrics: mets.map((m) => ({ metricCode: m.code })),
      dimensions: dims.map((d) => ({ dimCode: d.code, grain: d.grain || undefined })),
      filters: fts.map((f) => ({ dimCode: f.code, operator: f.operator, values: f.values })),
      sorts: dims.filter((d) => d.sort).map((d) => ({ code: d.code, direction: d.sort })),
      limit: limit.value,
    })
    result.value = r.result
    generatedSql.value = r.sql
    costMs.value = Math.round(performance.now() - start)
    if (conflictFields.value.length) {
      ElMessage.warning(`已忽略 ${conflictFields.value.length} 个跨模型冲突字段`)
    }
  } catch (e: any) {
    result.value = null
    if (manual) ElMessage.error(e?.message || '查询失败')
  } finally {
    loading.value = false
  }
}

/* 选择变化自动查询（去抖 800ms） */
let autoTimer: ReturnType<typeof setTimeout> | null = null
watch(
  () => [
    selectedDims.value.map((d) => `${d.modelId}:${d.code}:${d.grain}:${d.sort}`).join(','),
    selectedMetrics.value.map((m) => `${m.modelId}:${m.code}`).join(','),
    filters.value.map((f) => `${f.modelId}:${f.code}:${f.operator}:${f.values.join('|')}`).join(','),
    limit.value,
  ],
  () => {
    if (!autoQuery.value) return
    if (autoTimer) clearTimeout(autoTimer)
    autoTimer = setTimeout(() => runQuery(false), 800)
  },
)

/* ============ 样式配置 ============ */
interface BuilderStyleCfg {
  title: { show: boolean; text: string; fontSize: number; align: string }
  legend: { show: boolean; position: string }
  colorTheme: string
  labelShow: boolean
  decimalDigits: number
}

const styleCfg = reactive<BuilderStyleCfg>({
  title: { show: true, text: '', fontSize: 15, align: 'left' },
  legend: { show: true, position: 'top' },
  colorTheme: 'default',
  labelShow: false,
  decimalDigits: 2,
})

/* ============ 保存 ============ */
async function saveAndBack() {
  if (!primaryModelId.value) {
    ElMessage.warning('请先配置图表数据')
    return
  }
  if (!generatedSql.value) {
    await runQuery(true)
    if (!generatedSql.value) return
  }
  const model = primaryModel.value
  if (!model?.datasourceId) {
    ElMessage.error('模型未关联数据源，无法保存')
    return
  }
  saving.value = true
  try {
    const payload: VisualDashboardItem = {
      id: itemId.value === 'new' ? undefined : itemId.value,
      dashboardId: dashboardId.value,
      boardId: (route.query.boardId as string) || existingItem.value?.boardId,
      title: title.value,
      chartType: chartType.value,
      datasourceId: model.datasourceId,
      querySql: generatedSql.value,
      dataConfig: JSON.stringify(buildDataConfig()),
      styleConfig: JSON.stringify(styleCfg),
      posX: existingItem.value?.posX ?? 60,
      posY: existingItem.value?.posY ?? 60,
      width: existingItem.value?.width ?? 420,
      height: existingItem.value?.height ?? 300,
      zIndex: existingItem.value?.zIndex ?? 1,
    }
    await saveItem(payload)
    ElMessage.success('图表已保存')
    router.push(`/visual/dashboard/edit/${dashboardId.value}`)
  } catch (e: any) {
    ElMessage.error(e?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push(`/visual/dashboard/edit/${dashboardId.value}`)
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

/* ============ 加载已有组件配置 ============ */
const existingItem = ref<VisualDashboardItem | null>(null)

async function init() {
  try {
    const page = await pageMartMarket({ current: 1, size: 200 })
    models.value = (page.records || []).map((m: MarketModelDto) => ({
      id: m.id || '',
      name: m.modelName || '',
      modelType: m.modelType,
      layerCode: m.layerCode,
      metricCount: m.metricCount,
      dimensionCount: m.dimensionCount,
      metrics: [],
      dimensions: [],
      loaded: false,
    }))
  } catch (e: any) {
    ElMessage.error(e?.message || '加载模型资产池失败')
    return
  }

  if (itemId.value === 'new') {
    title.value = '未命名图表'
    try {
      const raw = sessionStorage.getItem(`cb_draft_${dashboardId.value}`)
      if (raw) {
        const draft = JSON.parse(raw)
        if (draft.title) title.value = draft.title
        if (draft.chartType) chartType.value = draft.chartType as ChartType
        existingItem.value = {
          boardId: draft.boardId,
          posX: draft.posX ?? 60,
          posY: draft.posY ?? 60,
          width: draft.width ?? 420,
          height: draft.height ?? 300,
          styleConfig: draft.styleConfig,
        } as VisualDashboardItem
        if (draft.styleConfig) {
          try {
            const sc = JSON.parse(draft.styleConfig)
            Object.assign(styleCfg, sc)
            styleCfg.title = { show: true, fontSize: 15, align: 'left', ...(sc.title || {}) }
            styleCfg.legend = { show: true, position: 'top', ...(sc.legend || {}) }
          } catch { /* ignore */ }
        }
        sessionStorage.removeItem(`cb_draft_${dashboardId.value}`)
      }
    } catch { /* ignore */ }
    if (models.value.length) expandedModelId.value = models.value[0].id
    return
  }

  // 编辑已有组件：恢复配置
  try {
    const items = (await listDashboardItems(dashboardId.value)) || []
    const item = items.find((i) => i.id === itemId.value)
    if (!item) {
      ElMessage.warning('组件不存在，将创建新图表')
      itemId.value = 'new'
      return
    }
    existingItem.value = item
    title.value = item.title || '未命名图表'
    chartType.value = (item.chartType as ChartType) || 'BAR'
    if (item.styleConfig) {
      try {
        const sc = JSON.parse(item.styleConfig)
        Object.assign(styleCfg, sc)
        styleCfg.title = { show: true, fontSize: 15, align: 'left', ...(sc.title || {}) }
        styleCfg.legend = { show: true, position: 'top', ...(sc.legend || {}) }
      } catch { /* ignore */ }
    }
    if (item.dataConfig) {
      try {
        const dc = JSON.parse(item.dataConfig)
        limit.value = dc.limit || 1000
        if (dc.mode === 'MODEL' && dc.modelId) {
          await restoreModelConfig(dc)
        } else {
          ElMessage.info('该图表为旧版数据集配置，请重新选择模型资产')
        }
      } catch { /* ignore */ }
    }
    if (hasQueryFields.value) runQuery(false)
  } catch (e: any) {
    ElMessage.error(e?.message || '加载组件配置失败')
  }
}

async function restoreModelConfig(dc: any) {
  const mid = dc.modelId as string
  const m = models.value.find((x) => x.id === mid)
  if (!m) return
  await ensureModelLoaded(m)
  expandedModelId.value = mid
  const base = { modelId: mid, modelName: m.name }
  const dimTypeOf = (code: string) => m.dimensions.find((x) => x.dimCode === code)?.dimType || 'COMMON'
  const metricTypeOf = (code: string) => m.metrics.find((x) => x.metricCode === code)?.metricType || 'ATOMIC'
  selectedDims.value = (dc.dimensions || [])
    .map((d: any) => ({ ...base, code: d.fieldCode, name: d.fieldName || d.fieldCode, dimType: dimTypeOf(d.fieldCode), grain: d.grain || '', sort: d.sort || '' }))
    .filter((d: SelDim) => !!d.code)
  selectedMetrics.value = (dc.metrics || [])
    .map((x: any) => ({ ...base, code: x.fieldCode, name: x.fieldName || x.fieldCode, metricType: metricTypeOf(x.fieldCode), unit: x.unit || '' }))
    .filter((x: SelMetric) => !!x.code)
  filters.value = (dc.filters || [])
    .map((f: any) => ({
      ...base,
      code: f.dimCode,
      name: f.fieldName || f.dimCode,
      dimId: m.dimensions.find((x) => x.dimCode === f.dimCode)?.id || '',
      dimType: f.dimType || dimTypeOf(f.dimCode),
      operator: f.operator || (f.dimType === 'TIME' ? 'BETWEEN' : 'IN'),
      values: f.values || [],
      valueInput: (f.values || []).join(','),
      dateRange:
        (f.dimType || dimTypeOf(f.dimCode)) === 'TIME' && Array.isArray(f.values) && f.values.length >= 2
          ? [f.values[0], f.values[1]]
          : null,
      key: genKey(),
      options: [],
      optionsLoaded: false,
    }))
    .filter((f: SelFilter) => !!f.code)
}

onMounted(init)
onBeforeUnmount(() => {
  if (autoTimer) clearTimeout(autoTimer)
})
</script>

<style scoped>
.bi-chart-builder {
  --bg: #f5f7fa;
  --card-bg: #ffffff;
  --card-border: #e4e7ed;
  --text-1: #303133;
  --text-2: #606266;
  --text-3: #909399;
  --header-bg: #ffffff;
  --aside-bg: #fafbfc;
  --shelf-bg: #ffffff;
  --accent: #4f9df9;
  --dim-color: #4f9df9;
  --metric-color: #13c2c2;
  --filter-color: #9254de;
  --danger: #f56c6c;
  --warning: #e6a23c;

  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text-1);
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  overflow: hidden;
}

.bi-chart-builder.theme-dark {
  --bg: #0f1117;
  --card-bg: #1a1d27;
  --card-border: #2a2f3a;
  --text-1: #e5eaf3;
  --text-2: #a3abb9;
  --text-3: #6b7280;
  --header-bg: #1a1d27;
  --aside-bg: #151821;
  --shelf-bg: #1a1d27;
}

/* ============ 顶部栏 ============ */
.cb-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 48px;
  padding: 0 12px;
  background: var(--header-bg);
  border-bottom: 1px solid var(--card-border);
  flex-shrink: 0;
}

.cb-header-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.cb-logo { color: var(--accent); }
.cb-title-input { width: 220px; }
.cb-title-input :deep(.el-input__wrapper) { background: transparent; box-shadow: none; font-weight: 600; font-size: 14px; }
.cb-ds-tag { display: inline-flex; align-items: center; gap: 4px; max-width: 260px; }
.cb-header-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }

/* ============ 冲突警示条 ============ */
.cb-conflict-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  background: rgba(230, 162, 60, 0.12);
  color: var(--warning);
  border-bottom: 1px solid rgba(230, 162, 60, 0.3);
  font-size: 13px;
  flex-shrink: 0;
}
.cb-conflict-bar b { color: var(--danger); }
.cb-conflict-bar .el-button { margin-left: auto; }

/* ============ 货架区 ============ */
.cb-shelf {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 12px;
  background: var(--shelf-bg);
  border-bottom: 1px solid var(--card-border);
  flex-shrink: 0;
}

.cb-shelf-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-height: 34px;
  border-radius: 6px;
  padding: 2px 4px;
  transition: background 0.15s, box-shadow 0.15s;
}
.cb-shelf-row.drop-active {
  background: rgba(79, 157, 249, 0.08);
  box-shadow: inset 0 0 0 1.5px var(--accent);
}

.cb-shelf-label {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 76px;
  height: 28px;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  border-radius: 4px;
  padding: 0 8px;
}
.cb-shelf-label.dimension { color: var(--dim-color); background: rgba(79, 157, 249, 0.1); }
.cb-shelf-label.metric { color: var(--metric-color); background: rgba(19, 194, 194, 0.1); }
.cb-shelf-label.filter { color: var(--filter-color); background: rgba(146, 84, 222, 0.1); }

.cb-shelf-body {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  flex: 1;
  min-height: 28px;
}

.cb-shelf-empty {
  font-size: 12px;
  color: var(--text-3);
  padding: 4px 10px;
  border: 1px dashed var(--card-border);
  border-radius: 4px;
}
.cb-shelf-empty.hover { border-color: var(--accent); color: var(--accent); }

/* 字段 chip */
.cb-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px;
  border-radius: 4px;
  font-size: 12.5px;
  cursor: default;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.cb-chip.dimension { background: rgba(79, 157, 249, 0.12); color: var(--dim-color); }
.cb-chip.metric { background: rgba(19, 194, 194, 0.12); color: var(--metric-color); }
.cb-chip.filter { background: rgba(146, 84, 222, 0.12); color: var(--filter-color); }
.cb-chip.conflict {
  border-color: var(--danger);
  background: rgba(245, 108, 108, 0.14);
  color: var(--danger);
  animation: cb-pulse 1.6s infinite;
}
@keyframes cb-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.35); }
  50% { box-shadow: 0 0 0 4px rgba(245, 108, 108, 0); }
}

.cb-chip-icon { font-size: 12px; }
.cb-chip-name { font-weight: 500; white-space: nowrap; }
.cb-chip-warn { color: var(--danger); font-size: 13px; }
.cb-chip-remove { cursor: pointer; font-size: 13px; opacity: 0.55; }
.cb-chip-remove:hover { opacity: 1; color: var(--danger); }

.cb-dim-sort { width: 68px; }
.cb-dim-grain { width: 60px; }
.cb-metric-agg { width: 92px; }
.cb-filter-op { width: 78px; }
.cb-filter-value { width: 170px; }
.cb-filter-date { width: 220px; }
.cb-chip .el-select :deep(.el-input__wrapper),
.cb-chip .el-select :deep(.el-input__inner) { background: transparent; }

.cb-metric-type {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 5px;
  border-radius: 3px;
  flex-shrink: 0;
}
.cb-metric-type.atomic { background: rgba(19, 194, 194, 0.16); color: var(--metric-color); }
.cb-metric-type.derived { background: rgba(146, 84, 222, 0.16); color: var(--filter-color); }
.cb-metric-unit { font-size: 11px; opacity: 0.7; flex-shrink: 0; }

/* ============ 主体 ============ */
.cb-main { display: flex; flex: 1; min-height: 0; }

/* 左侧资产池 */
.cb-aside {
  width: 262px;
  flex-shrink: 0;
  background: var(--aside-bg);
  border-right: 1px solid var(--card-border);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.cb-aside-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-2);
  flex-shrink: 0;
}
.cb-aside-sub { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--text-3); }

.cb-search { padding: 0 12px 8px; flex-shrink: 0; }

.cb-dataset-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }

.cb-dataset-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: var(--text-2);
  transition: background 0.12s;
}
.cb-dataset-item:hover { background: var(--card-border); }
.cb-dataset-item.active { background: rgba(79, 157, 249, 0.12); color: var(--accent); }
.cb-dataset-item.primary .cb-ds-name { color: var(--accent); font-weight: 600; }

.cb-ds-expand { font-size: 12px; color: var(--text-3); transition: transform 0.15s; }
.cb-ds-expand.open { transform: rotate(90deg); }
.cb-model-icon { font-size: 12px; color: var(--text-3); }
.cb-ds-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.cb-ds-type {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 3px;
  letter-spacing: 0.4px;
  flex-shrink: 0;
}
.cb-ds-type.star { background: #4f9df922; color: #4f9df9; }
.cb-ds-type.snowflake { background: #a78bfa22; color: #a78bfa; }
.cb-ds-type.doris { background: #ff7a4522; color: #ff7a45; }
.cb-ds-type.hive { background: #e6a23c22; color: #e6a23c; }
.cb-ds-type.mysql { background: #4f9df922; color: #4f9df9; }
.cb-ds-type.other { background: #90939922; color: #909399; }

.cb-ds-warn { color: var(--warning); font-size: 13px; flex-shrink: 0; }
.cb-ds-primary { color: var(--accent); font-size: 13px; flex-shrink: 0; }

/* 字段池 */
.cb-field-pool {
  padding: 2px 4px 8px 22px;
  animation: cb-slide-in 0.18s ease;
}
@keyframes cb-slide-in { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: none; } }

.cb-pool-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px;
  font-size: 12px;
  color: var(--text-3);
}

.cb-pool-warn {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 8px;
  padding: 6px 8px;
  border-radius: 4px;
  background: rgba(245, 108, 108, 0.1);
  border: 1px solid rgba(245, 108, 108, 0.35);
  color: var(--danger);
  font-size: 12px;
  line-height: 1.5;
}

.cb-pool-group { margin-bottom: 6px; }
.cb-pool-group-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  padding: 6px 4px 4px;
}
.cb-pool-count {
  background: var(--card-border);
  border-radius: 8px;
  padding: 0 6px;
  font-size: 10px;
}

.cb-field-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 12.5px;
  cursor: pointer;
  color: var(--text-2);
  transition: background 0.12s, transform 0.12s;
}
.cb-field-item:hover { background: var(--card-border); transform: translateX(2px); }
.cb-field-item.dimension:hover { color: var(--dim-color); }
.cb-field-item.metric:hover { color: var(--metric-color); }
.cb-field-item.dimension .el-icon { color: var(--dim-color); font-size: 12px; }
.cb-field-item.metric .el-icon { color: var(--metric-color); font-size: 12px; }

.cb-field-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cb-field-code { font-size: 10.5px; color: var(--text-3); font-family: 'SF Mono', Menlo, monospace; }
.cb-field-op { font-size: 12px; color: var(--filter-color); opacity: 0; flex-shrink: 0; }
.cb-field-item:hover .cb-field-op { opacity: 1; }
.cb-field-expr { font-size: 12px; color: var(--text-3); opacity: 0; flex-shrink: 0; }
.cb-field-item:hover .cb-field-expr { opacity: 1; }

/* 维度类型徽标 */
.cb-dim-type {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 4px;
  border-radius: 3px;
  flex-shrink: 0;
}
.cb-dim-type.time { background: rgba(79, 157, 249, 0.14); color: #4f9df9; }
.cb-dim-type.org { background: rgba(230, 162, 60, 0.14); color: #e6a23c; }
.cb-dim-type.common { background: rgba(144, 147, 153, 0.14); color: #909399; }

/* 搜索高亮 */
.cb-hl { background: #fff3b0; color: #b7791f; border-radius: 2px; padding: 0 1px; }
.theme-dark .cb-hl { background: #4a3a12; color: #fbbf24; }

.cb-pool-empty { padding: 8px; font-size: 12px; color: var(--text-3); text-align: center; }

/* ============ 中间画布 ============ */
.cb-canvas {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

.cb-chart-types {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 8px 12px;
  background: var(--shelf-bg);
  border-bottom: 1px solid var(--card-border);
  overflow-x: auto;
  flex-shrink: 0;
}

.cb-chart-type-item {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  border-radius: 5px;
  font-size: 12.5px;
  color: var(--text-2);
  cursor: pointer;
  white-space: nowrap;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.cb-chart-type-item:hover { background: var(--card-border); }
.cb-chart-type-item.active {
  color: var(--accent);
  background: rgba(79, 157, 249, 0.12);
  border-color: rgba(79, 157, 249, 0.45);
}

.cb-sql-box {
  margin: 8px 12px 0;
  border: 1px solid var(--card-border);
  border-radius: 6px;
  background: var(--card-bg);
  overflow: hidden;
  flex-shrink: 0;
}
.cb-sql-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 600;
  color: var(--text-2);
  border-bottom: 1px solid var(--card-border);
}
.cb-sql-meta { margin-left: auto; color: var(--text-3); font-weight: 400; }
.cb-sql-text {
  margin: 0;
  padding: 10px;
  font-family: 'SF Mono', Menlo, monospace;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow-y: auto;
}

.cb-preview {
  flex: 1;
  margin: 10px 12px 12px;
  border: 1px solid var(--card-border);
  border-radius: 8px;
  background: var(--card-bg);
  min-height: 0;
  overflow: hidden;
  position: relative;
  isolation: isolate;
}

.cb-preview-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  font-size: 13px;
}
.cb-preview-empty .el-icon { color: var(--card-border); }
.cb-preview-hint { font-size: 12px; opacity: 0.8; }

/* ============ 右侧样式面板 ============ */
.cb-style {
  width: 252px;
  flex-shrink: 0;
  background: var(--aside-bg);
  border-left: 1px solid var(--card-border);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.cb-style-body { flex: 1; overflow-y: auto; padding: 4px 12px 16px; }

.cb-style-group { margin-bottom: 14px; }
.cb-style-group-title {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-3);
  padding: 8px 0 6px;
  border-bottom: 1px solid var(--card-border);
  margin-bottom: 6px;
}
.cb-style-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 5px 0;
  font-size: 12.5px;
  color: var(--text-2);
}

/* 滚动条 */
.cb-dataset-list::-webkit-scrollbar,
.cb-style-body::-webkit-scrollbar,
.cb-sql-text::-webkit-scrollbar,
.cb-chart-types::-webkit-scrollbar { width: 5px; height: 5px; }
.cb-dataset-list::-webkit-scrollbar-thumb,
.cb-style-body::-webkit-scrollbar-thumb,
.cb-sql-text::-webkit-scrollbar-thumb,
.cb-chart-types::-webkit-scrollbar-thumb { background: var(--card-border); border-radius: 3px; }
</style>