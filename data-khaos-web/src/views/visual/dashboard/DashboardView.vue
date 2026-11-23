<template>
  <div class="bi-dashboard-view" :class="`theme-${theme}`">
    <!-- 顶部栏 -->
    <header v-if="!fullscreen" class="view-header">
      <div class="header-left">
        <div class="header-back" @click="goBack"><el-icon><ArrowLeft /></el-icon></div>
        <div class="header-logo"><el-icon :size="20"><DataAnalysis /></el-icon></div>
        <span class="header-title">{{ dashboard.name || '仪表板' }}</span>
        <el-tag v-if="dashboard.status === 2" type="success" size="small" effect="plain">v{{ dashboard.version }}</el-tag>
      </div>
      <div class="header-tabs">
        <div
          v-for="board in boards"
          :key="board.id"
          class="tab-item"
          :class="{ active: activeBoardId === board.id }"
          @click="activeBoardId = board.id"
        >
          <el-icon><component :is="board.icon || 'DataAnalysis'" /></el-icon>
          {{ board.name }}
        </div>
      </div>
      <div class="header-right">
        <el-button :icon="Refresh" circle size="small" @click="refreshAll" />
        <el-button :icon="theme === 'dark' ? Sunny : Moon" circle size="small" @click="toggleTheme" />
        <el-button :icon="FullScreen" circle size="small" @click="fullscreen = true" />
        <el-button type="primary" :icon="Edit" size="small" @click="goEdit">编辑</el-button>
      </div>
    </header>

    <!-- 画布区域 -->
    <div ref="canvasWrapperEl" class="view-canvas-wrapper">
      <div ref="canvasEl" class="view-canvas" :style="canvasStyle">
        <div v-if="showGrid" class="grid-bg" :style="gridStyle" />
        <div
          v-for="item in visibleBoardItems"
          :key="item.id"
          class="canvas-item"
          :style="getItemStyle(item)"
        >
          <div class="item-header" :class="{ 'has-title': item.title }">
            <span class="item-title-text">{{ item.title || getDefaultTitle(item.chartType) }}</span>
          </div>
          <div class="item-body">
            <ChartRenderer
              v-if="item.id && results[item.id]"
              :item="item"
              :result="results[item.id]"
              :loading="loadingItems[item.id]"
              :theme="theme"
              @drill="onDrill(item, $event)"
            >
              <template #table-cell="{ row, column }">
                <RouterLink v-if="publicationLink(item, row, column)" :to="publicationLink(item, row, column)!" class="dk-link">
                  {{ row[column] }}
                </RouterLink>
                <template v-else>{{ row[column] }}</template>
              </template>
            </ChartRenderer>
            <div v-else class="item-placeholder">
              <el-icon :size="28"><DataAnalysis /></el-icon>
              <span>数据加载中...</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 下钻弹窗 -->
    <el-dialog v-model="drillDialog" :title="drillTitle" width="800px" top="8vh" destroy-on-close>
      <div v-loading="drillLoading" style="min-height: 200px">
        <el-table v-if="drillResult?.rows?.length" :data="drillResult.rows" border size="small" max-height="400">
          <el-table-column v-for="c in drillColumns" :key="c" :prop="c" :label="c" min-width="120" />
        </el-table>
        <el-empty v-else-if="!drillLoading" description="暂无明细" :image-size="60" />
      </div>
      <template #footer>
        <el-button :icon="Download" @click="exportDrill">导出</el-button>
        <el-button type="primary" @click="drillDialog = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter, type RouteLocationRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, DataAnalysis, Download, Edit, FullScreen, Moon, Refresh, Sunny } from '@element-plus/icons-vue'
import ChartRenderer from '@/components/chart/ChartRenderer.vue'
import { drillItem, executeItem, getDashboard, listBoards, listDashboardItems } from '@/api/visual'
import type { ChartType, DashboardBoard, QueryResult, VisualDashboard, VisualDashboardItem } from '@/types'

const route = useRoute()
const router = useRouter()
const dashboardId = ref(route.params.id as string)

const dashboard = ref<VisualDashboard>({})
const boards = ref<DashboardBoard[]>([])
const activeBoardId = ref('')
const items = ref<VisualDashboardItem[]>([])
const loading = ref(false)
const fullscreen = ref(false)
const theme = ref<'light' | 'dark'>('light')
const showGrid = ref(false)

const canvasWidth = ref(1920)
const canvasHeight = ref(1080)
const canvasBg = ref('#f5f7fa')

const loadingItems = reactive<Record<string, boolean>>({})
const results = reactive<Record<string, QueryResult>>({})
const refreshTimers: Record<string, ReturnType<typeof setInterval>> = {}

const drillDialog = ref(false)
const drillLoading = ref(false)
const drillTitle = ref('')
const drillResult = ref<QueryResult>()
const drillColumns = computed(() => (drillResult.value?.columns || []).map((c) => c.columnName ?? ''))

const canvasEl = ref<HTMLDivElement>()
const canvasWrapperEl = ref<HTMLDivElement>()

const canvasStyle = computed(() => ({
  width: `${canvasWidth.value}px`,
  height: `${canvasHeight.value}px`,
  backgroundColor: canvasBg.value,
  position: 'relative' as const,
}))

const gridStyle = computed(() => ({
  backgroundSize: '10px 10px',
  backgroundImage: `linear-gradient(to right, rgba(0,0,0,0.04) 1px, transparent 1px), linear-gradient(to bottom, rgba(0,0,0,0.04) 1px, transparent 1px)`,
}))

const boardItems = computed(() => items.value.filter((i) => i.boardId === activeBoardId.value))
const visibleBoardItems = computed(() => boardItems.value.filter((i) => i.visible !== 0))

function getItemStyle(item: VisualDashboardItem) {
  return {
    left: `${item.posX || 0}px`,
    top: `${item.posY || 0}px`,
    width: `${item.width || 400}px`,
    height: `${item.height || 300}px`,
    backgroundColor: item.bgColor || 'var(--card-bg, #ffffff)',
    borderRadius: `${item.borderRadius ?? 6}px`,
    border: `${item.borderWidth ?? 1}px solid var(--card-border, #e4e7ed)`,
    zIndex: item.zIndex || 1,
    position: 'absolute' as const,
    overflow: 'hidden',
    display: 'flex',
    flexDirection: 'column' as const,
  }
}

const publicationLinks = computed(() => {
  const links = new Map<string, { publicationAssetId?: string; runEvidenceColumn?: string }>()
  for (const item of items.value) {
    if (!item.id || !item.config) continue
    try {
      const config = JSON.parse(item.config)
      if (typeof config?.publicationAssetId === 'string') links.set(item.id, config)
    } catch {
      // Older items may contain non-JSON configuration.
    }
  }
  return links
})

function publicationLink(item: VisualDashboardItem, row: Record<string, unknown>, column: string): RouteLocationRaw | undefined {
  const config = publicationLinks.value.get(item.id || '')
  if (!config?.publicationAssetId) return
  const date = row.stat_date || row.biz_date
  const id = config.runEvidenceColumn === column ? row[column] : undefined
  if ((!id && column !== 'stat_date') || typeof date !== 'string') return
  return {
    name: 'AssetDetail',
    params: { tableId: config.publicationAssetId },
    query: { tab: 'publication', bizDate: date, runId: typeof id === 'string' ? id : undefined },
  }
}

function getDefaultTitle(type?: string) {
  const map: Record<string, string> = {
    BAR: '柱状图', LINE: '折线图', PIE: '饼图', AREA: '面积图', SCATTER: '散点图',
    HEATMAP: '热力图', GAUGE: '仪表盘', TREEMAP: '树形图', BOXPLOT: '箱型图',
    MAP: '地图', TABLE: '表格', NUMBER: '指标卡', FUNNEL: '漏斗图', RADAR: '雷达图',
  }
  return map[type || 'TABLE'] || '组件'
}

async function loadAll() {
  loading.value = true
  try {
    dashboard.value = await getDashboard(dashboardId.value)
    boards.value = (await listBoards(dashboardId.value) || []).flatMap(board =>
      board.id ? [{ ...board, id: board.id, name: board.boardName || '分析板' }] : [],
    )
    if (boards.value.length) {
      activeBoardId.value = boards.value[0].id
      // 解析画布配置
      const firstBoard = boards.value[0]
      canvasWidth.value = firstBoard.canvasWidth || 1920
      canvasHeight.value = firstBoard.canvasHeight || 1080
      canvasBg.value = firstBoard.canvasBg || '#f5f7fa'
    }
    items.value = (await listDashboardItems(dashboardId.value)) || []
    fitCanvas()
    clearAllTimers()
    for (const item of items.value) {
      await refreshItem(item)
      startItemAutoRefresh(item)
    }
  } finally {
    loading.value = false
  }
}

async function refreshItem(item: VisualDashboardItem) {
  if (!item.id || !item.datasourceId || !item.querySql) return
  loadingItems[item.id] = true
  try {
    results[item.id] = await executeItem(item.id)
  } catch { /* ignore */ } finally {
    loadingItems[item.id] = false
  }
}

function startItemAutoRefresh(item: VisualDashboardItem) {
  const sec = dashboard.value.refreshInterval || 60
  if (sec <= 0 || !item.id) return
  refreshTimers[item.id] = setInterval(() => refreshItem(item), sec * 1000)
}

function clearAllTimers() {
  Object.values(refreshTimers).forEach((t) => clearInterval(t))
  Object.keys(refreshTimers).forEach((k) => delete refreshTimers[k])
}

function fitCanvas() {
  if (!canvasWrapperEl.value || !canvasEl.value) return
  const wrapper = canvasWrapperEl.value
  const wScale = wrapper.clientWidth / canvasWidth.value
  const hScale = wrapper.clientHeight / canvasHeight.value
  const scale = Math.min(wScale, hScale, 1) * 0.95
  canvasEl.value.style.transform = `scale(${scale})`
  canvasEl.value.style.transformOrigin = 'top center'
}

async function refreshAll() {
  await loadAll()
  ElMessage.success('刷新完成')
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
}

function goBack() { router.back() }

function goEdit() {
  router.push({ name: 'VisualDashboardEdit', params: { id: dashboardId.value } })
}

async function onDrill(item: VisualDashboardItem, payload: { column: string; value: string }) {
  if (!item.id) return
  drillTitle.value = `${item.title} · ${payload.column} = ${payload.value}`
  drillDialog.value = true
  drillLoading.value = true
  drillResult.value = undefined
  try { drillResult.value = await drillItem(item.id, { column: payload.column, value: payload.value }) }
  catch { drillDialog.value = false }
  finally { drillLoading.value = false }
}

function resultToCSV(result: QueryResult): string {
  if (!result?.columns?.length) return ''
  const cols = result.columns.map((c) => c.columnName ?? '')
  const esc = (v: any) => { if (v == null) return ''; const s = String(v); return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s }
  return [cols.join(','), ...result.rows.map((row) => cols.map((c) => esc(row[c])).join(','))].join('\n')
}

function downloadCSV(content: string, filename: string) {
  const blob = new Blob(['\ufeff' + content], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a'); a.href = url; a.download = filename; a.click()
  URL.revokeObjectURL(url)
}

function exportDrill() {
  if (drillResult.value) downloadCSV(resultToCSV(drillResult.value), `${drillTitle.value}.csv`)
}

watch(fullscreen, () => { nextTick(() => fitCanvas()) })
watch(activeBoardId, () => {
  const board = boards.value.find((b) => b.id === activeBoardId.value)
  if (board) {
    canvasWidth.value = board.canvasWidth || 1920
    canvasHeight.value = board.canvasHeight || 1080
    canvasBg.value = board.canvasBg || '#f5f7fa'
    nextTick(() => fitCanvas())
  }
})

onMounted(() => {
  loadAll()
  window.addEventListener('resize', fitCanvas)
})

onBeforeUnmount(() => {
  clearAllTimers()
  window.removeEventListener('resize', fitCanvas)
})
</script>

<style scoped>
.bi-dashboard-view {
  --bg: #f5f7fa;
  --card-bg: #ffffff;
  --card-border: #e4e7ed;
  --text-1: #303133;
  --text-2: #606266;
  --text-3: #909399;
  --header-bg: #ffffff;
  --accent: #4f9df9;

  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg);
  color: var(--text-1);
  overflow: hidden;
}

.bi-dashboard-view.theme-dark {
  --bg: #0f1117;
  --card-bg: #1a1d27;
  --card-border: #2a2f3a;
  --text-1: #e5eaf3;
  --text-2: #a3abb9;
  --text-3: #6b7280;
  --header-bg: #1a1d27;
}

/* Header */
.view-header {
  display: flex;
  align-items: center;
  height: 48px;
  background: var(--header-bg);
  border-bottom: 1px solid var(--card-border);
  padding: 0 12px;
  flex-shrink: 0;
  gap: 12px;
}

.header-left { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.header-back { cursor: pointer; padding: 4px; border-radius: 4px; }
.header-back:hover { background: var(--card-border); }
.header-logo { color: var(--accent); }
.header-title { font-size: 15px; font-weight: 600; }

.header-tabs { flex: 1; display: flex; align-items: center; gap: 2px; overflow-x: auto; }
.tab-item {
  display: flex; align-items: center; gap: 6px;
  padding: 6px 14px; border-radius: 6px 6px 0 0;
  font-size: 13px; color: var(--text-2); cursor: pointer;
  border-bottom: 2px solid transparent; white-space: nowrap;
  transition: all 0.15s;
}
.tab-item:hover { background: var(--card-border); }
.tab-item.active { color: var(--accent); border-bottom-color: var(--accent); background: var(--bg); }

.header-right { display: flex; align-items: center; gap: 6px; flex-shrink: 0; }

/* Canvas */
.view-canvas-wrapper {
  flex: 1;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 24px;
}

.view-canvas {
  position: relative;
  box-shadow: 0 4px 20px rgba(0,0,0,0.06);
  border-radius: 4px;
  flex-shrink: 0;
}

.grid-bg { position: absolute; inset: 0; pointer-events: none; }

.canvas-item {
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
  transition: box-shadow 0.2s;
}

.item-header {
  display: flex; align-items: center; gap: 6px;
  padding: 4px 8px; background: var(--card-bg);
  border-bottom: 1px solid var(--card-border);
  flex-shrink: 0; min-height: 28px;
}
.item-title-text { font-size: 12px; font-weight: 500; color: var(--text-2); }

.item-body { flex: 1; min-height: 0; overflow: hidden; }

.item-placeholder {
  width: 100%; height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; color: var(--text-3); font-size: 12px;
}
</style>
