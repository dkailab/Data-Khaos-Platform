<template>
  <div class="onesql-layout" :class="{ 'theme-dark': dark }">
    <OneSqlMaterialization v-if="materializationAsset" :asset-id="materializationAsset"
      :biz-date="materializationDate" class="materialized-result" />
    <!-- 左侧 Schema 面板 -->
    <aside aria-label="查询资源" class="schema-panel" :class="{ collapsed: schemaCollapsed }">
      <div class="schema-header">
        <span v-if="!schemaCollapsed">查询资源</span>
        <el-button size="small" text :aria-label="schemaCollapsed ? '展开查询资源' : '收起查询资源'" @click="schemaCollapsed = !schemaCollapsed">
          <el-icon><Fold /></el-icon>
        </el-button>
      </div>
      <nav v-if="!schemaCollapsed" class="resource-switch" aria-label="资源类型">
        <button type="button" :aria-pressed="resourceTab === 'schema'" @click="resourceTab = 'schema'">数据库对象</button>
        <button type="button" :aria-pressed="resourceTab === 'history'" @click="resourceTab = 'history'">查询历史</button>
      </nav>
      <div v-if="!schemaCollapsed && resourceTab === 'schema'" class="schema-body">
        <el-input v-model="schemaFilter" size="small" placeholder="搜索表或已展开字段" aria-label="搜索数据库对象" :prefix-icon="Search" class="schema-search" />
        <div class="schema-tree">
          <div v-for="table in filteredTables" :key="table" class="schema-table">
            <button type="button" class="table-name" @click="toggleTable(table)" :aria-expanded="expandedTables.includes(table)" :class="{ expanded: expandedTables.includes(table) }">
              <el-icon><ArrowRight /></el-icon>
              <el-icon class="table-icon"><Grid /></el-icon>
              <span :title="table">{{ table }}</span>
              <span v-if="columnLoading[table]" class="col-loading"><el-icon class="is-loading"><Refresh /></el-icon></span>
            </button>
            <div v-if="expandedTables.includes(table)" class="table-columns">
              <button v-for="col in columnMap[table] || []" :key="col.name" type="button" class="column-item" @click="insertText(col.name)">
                <span class="col-name">
                  {{ col.name }}
                  <span v-if="col.bizName" class="col-biz">{{ col.bizName }}</span>
                </span>
                <span class="col-meta">
                  <el-tag
                    v-if="col.sensitiveLevel && col.sensitiveLevel > 0"
                    :type="col.sensitiveLevel >= 2 ? 'danger' : 'warning'"
                    size="small"
                    effect="plain"
                    class="col-tag"
                  >{{ col.sensitiveLevel >= 2 ? '高敏' : '敏感' }}</el-tag>
                  <el-tag v-if="col.dictTypeName" size="small" effect="plain" class="col-tag dict">字典</el-tag>
                  <span class="col-type">{{ col.type }}</span>
                </span>
              </button>
              <div v-if="!columnLoading[table] && columnMap[table] && columnMap[table].length === 0" class="column-empty">暂无字段</div>
            </div>
          </div>
          <div v-if="schemaLoading" class="resource-state" role="status">正在加载数据库对象…</div>
          <div v-else-if="schemaError" class="resource-state">{{ schemaError }} <DkButton variant="quiet" @click="loadSchema">重试</DkButton></div>
          <el-empty v-else-if="!filteredTables.length" :description="!datasourceId ? '请选择数据源' : '暂无匹配的数据库对象'" :image-size="58" />
        </div>
      </div>
      <section v-else-if="!schemaCollapsed" class="history-panel" aria-label="查询历史">
        <div class="history-header">
          <el-button size="small" text :icon="Refresh" :loading="historyLoading" @click="loadHistory">刷新历史</el-button>
        </div>
        <div class="history-list">
          <button v-for="row in historyList" :key="row.id" type="button" class="history-item" @click="useHistory(row)">
            <div class="history-sql" :title="row.sqlText">{{ row.sqlText }}</div>
            <div class="history-meta">
              <span>{{ row.status === 1 ? '成功' : '失败' }}</span>
              <span v-if="row.costMs != null">{{ row.costMs }} ms</span>
              <span v-if="row.createTime">{{ row.createTime.replace('T', ' ').slice(0, 19) }}</span>
            </div>
          </button>
          <el-empty v-if="!historyLoading && !historyList.length" :description="historyError || '暂无查询历史'" :image-size="58" />
        </div>
      </section>
    </aside>

    <!-- 中间主区域 -->
    <div class="main-panel">
      <header class="workspace-heading"><h1>OneSQL 取数</h1>
        <el-button text circle :icon="dark ? Sunny : Moon" :aria-label="dark ? '切换浅色' : '切换深色'" @click="toggleTheme" />
      </header>
      <div class="toolbar">
        <el-select v-model="datasourceId" aria-label="查询数据源" placeholder="选择数据源" filterable class="source-select"
          @change="selectDatasource">
          <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsName" :value="ds.id!" />
        </el-select>
        <el-input v-model="databaseName" aria-label="查询数据库" placeholder="数据库（可选）" class="database-input" @change="loadSchema" />
        <DkButton variant="primary" :loading="executing" :disabled="explaining" @click="runQuery(false)">
          <template #icon><CaretRight aria-hidden="true" /></template>运行
        </DkButton>
        <DkButton :disabled="executing || explaining || !selectedSql" @click="runQuery(true)">运行选中</DkButton>
        <DkButton variant="quiet" :disabled="!sqlText.trim()" @click="formatSql">格式化</DkButton>
        <DkButton variant="quiet" :disabled="executing || !sqlText.trim()" :loading="explaining" @click="runExplain">执行计划</DkButton>
      </div>
      <el-alert v-if="datasourceError" :title="datasourceError" type="warning" show-icon :closable="false">
        <DkButton variant="quiet" @click="loadDatasources">重试</DkButton>
      </el-alert>

      <div class="editor-container" :style="resultOpen ? { '--editor-height': editorHeight + 'px' } : undefined">
        <div ref="editorRef" class="codemirror-host"></div>
        <div class="editor-shortcuts">
          <span><kbd>Ctrl</kbd>+<kbd>Enter</kbd> 运行</span>
          <span><kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F</kbd> 格式化</span>
        </div>
      </div>
      <div v-if="resultOpen" class="result-divider" role="separator" aria-label="调整编辑区高度"
        aria-orientation="horizontal" tabindex="0" :aria-valuemin="120" :aria-valuemax="580"
        :aria-valuenow="editorHeight" @pointerdown="startResize" @pointermove="resize" @pointerup="endResize"
        @pointercancel="endResize" @keydown="resizeByKey"><span></span></div>

      <section class="result-area" :class="{ 'result-closed': !resultOpen }">
        <div class="result-bar">
          <strong>运行与结果</strong>
          <DkButton variant="quiet" :aria-expanded="resultOpen" aria-controls="onesql-results" @click="resultOpen = !resultOpen">
            {{ resultOpen ? '收起结果' : '展开结果' }}
          </DkButton>
        </div>
        <el-tabs v-show="resultOpen" id="onesql-results" v-model="resultTab">
          <el-tab-pane label="查询结果" name="result">
            <OneSqlQueryResult :result="result" :running="executing" :statement="lastRun" :error="queryError" />
          </el-tab-pane>
          <el-tab-pane label="SQL 诊断" name="diagnose">
            <div class="diagnose-panel">
              <div v-if="diagnoseResult" class="diagnose-issues">
                <template v-if="diagnoseResult.issues?.length">
                  <div v-for="(issue, i) in diagnoseResult.issues" :key="i" class="diagnose-issue" :class="'sev-' + issue.severity">
                    <div class="issue-head">
                      <el-tag :type="issue.severity === 'error' ? 'danger' : issue.severity === 'warning' ? 'warning' : 'info'" size="small" effect="plain">
                        {{ issue.severity === 'error' ? '严重' : issue.severity === 'warning' ? '警告' : '提示' }}
                      </el-tag>
                      <span class="issue-message">{{ issue.message }}</span>
                    </div>
                    <div v-if="issue.suggestion" class="issue-suggestion">建议: {{ issue.suggestion }}</div>
                  </div>
                </template>
                <div v-else class="diagnose-ok">
                  <el-result icon="success" title="SQL 健康状况良好" :sub-title="`未检测到明显问题（${parseResult?.tables?.length || 0} 张表）`" />
                </div>
              </div>
              <div v-else-if="diagnosing" class="diagnose-loading"><el-icon class="is-loading"><Refresh /></el-icon> 正在诊断...</div>
              <div v-else class="diagnose-section">
                <h4>引用的表 ({{ parseResult?.tables?.length || 0 }})</h4>
                <el-tag v-for="t in parseResult?.tables || []" :key="t" size="small" class="diagnose-tag">{{ t }}</el-tag>
                <span v-if="!parseResult?.tables?.length" class="empty-text">-</span>
              </div>
              <div v-if="parseResult?.parseError" class="diagnose-error">
                <el-alert :title="parseResult.parseError" type="warning" show-icon :closable="false" />
              </div>
            </div>
          </el-tab-pane>
          <el-tab-pane label="执行计划" name="explain">
            <div class="explain-panel" v-loading="explaining">
              <div v-if="explainResult" class="explain-summary">
                <el-alert
                  v-if="explainWarnings.length"
                  type="warning"
                  show-icon
                  :closable="false"
                  :title="explainWarnings.join('；')"
                  class="explain-alert"
                />
                <el-table :data="explainRows" size="small" border max-height="360" stripe>
                  <el-table-column type="index" label="#" width="50" />
                  <el-table-column v-for="col in explainResult.columns || []" :key="col.columnName" :prop="col.columnName" :label="col.columnName" min-width="110" show-overflow-tooltip>
                    <template #default="{ row }">
                      <el-tag
                        v-if="col.columnName === 'type'"
                        :type="typeBadge(row.type)"
                        size="small"
                        effect="plain"
                      >{{ row.type }}</el-tag>
                      <span v-else-if="col.columnName" :class="{ 'idx-used': col.columnName === 'key' && row.key, 'idx-none': col.columnName === 'key' && !row.key }">{{ row[col.columnName] }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="explainResult.columns?.length === 0" description="执行计划无返回" />
              </div>
              <el-empty v-else-if="!explaining" description="点击「执行计划」分析访问类型 / 扫描行数 / 索引" :image-size="70" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretRight, Fold, ArrowRight, Grid, Search, Refresh, Moon, Sunny } from '@element-plus/icons-vue'
import { pageDatasources } from '@/api/datasource'
import { executeQuery } from '@/api/query'
import { useRoute } from 'vue-router'
import OneSqlMaterialization from './OneSqlMaterialization.vue'
import OneSqlQueryResult from './OneSqlQueryResult.vue'
import DkButton from '@/paas-ui/DkButton.vue'
import {
  sqlComplete, sqlFormat, sqlParse, getSchemaHints, queryHistory,
  sqlDiagnose, sqlExplain, getTableColumns,
} from '@/api/onesql'
import { getTableColumnAnnotations } from '@/api/metadata'
import type { MetaDatasource, QueryResult } from '@/types'
import type { CompletionItem, QueryHistory, SqlDiagnoseResult, SqlParseResult } from '@/api/onesql'

// CodeMirror imports
import { EditorState, StateEffect, Extension } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, placeholder, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from '@codemirror/view'
import { defaultKeymap, indentWithTab, history, historyKeymap } from '@codemirror/commands'
import { sql, MySQL } from '@codemirror/lang-sql'
import { autocompletion, CompletionContext, CompletionResult, Completion } from '@codemirror/autocomplete'
import { oneDark } from '@codemirror/theme-one-dark'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'

// 字段节点形状：数据源物理列 + 元数据标注
interface ColumnNode {
  name: string
  type: string
  bizName?: string
  dictTypeName?: string
  sensitiveLevel?: number
}

const editorRef = ref<HTMLElement>()
let editorView: EditorView | null = null

// 状态
const datasources = ref<MetaDatasource[]>([])
const datasourceId = ref('')
const databaseName = ref('')
const sqlText = ref('')
const selectedSql = ref('')
const datasourceError = ref('')
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const parseResult = ref<SqlParseResult | null>(null)
const resultTab = ref('result')
const queryError = ref('')
const lastRun = ref<{ sql: string; source: string; startedAt: string; selection: boolean } | null>(null)
const resultOpen = ref(true)
const editorHeight = ref(285)
let drag: { id: number; y: number; height: number } | undefined

function startResize(event: PointerEvent) {
  if (event.button !== 0 || drag) return
  const divider = event.currentTarget as HTMLElement
  const editor = divider.previousElementSibling
  drag = { id: event.pointerId, y: event.clientY, height: editor?.getBoundingClientRect().height ?? editorHeight.value }
  divider.setPointerCapture(event.pointerId)
}

function setEditorHeight(height: number) {
  editorHeight.value = Math.max(120, Math.min(580, Math.round(height)))
}

function resize(event: PointerEvent) {
  if (drag?.id === event.pointerId) setEditorHeight(drag.height + event.clientY - drag.y)
}

function endResize(event: PointerEvent) {
  if (drag?.id === event.pointerId) drag = undefined
}

function resizeByKey(event: KeyboardEvent) {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return
  event.preventDefault()
  setEditorHeight(editorHeight.value + (event.key === 'ArrowUp' ? -24 : 24))
}

// 明暗主题：整页跟随切换，编辑器与页面保持一致（深色用 oneDark，浅色用默认亮色）
const dark = ref(false)

// Schema 懒加载
const schemaCollapsed = ref(window.matchMedia('(max-width: 760px)').matches)
const resourceTab = ref<'schema' | 'history'>('schema')
const schemaLoading = ref(false)
const schemaError = ref('')
let schemaRequest = 0
const schemaFilter = ref('')
const tables = ref<string[]>([])
const expandedTables = ref<string[]>([])
const columnMap = ref<Record<string, ColumnNode[]>>({})
const columnLoading = ref<Record<string, boolean>>({})

// SQL 诊断
const diagnosing = ref(false)
const diagnoseResult = ref<SqlDiagnoseResult | null>(null)

// 执行计划
const explaining = ref(false)
const explainResult = ref<QueryResult | null>(null)
const explainRows = ref<Record<string, any>[]>([])
const explainWarnings = ref<string[]>([])

// History
const historyList = ref<QueryHistory[]>([])
const historyLoading = ref(false)
const historyError = ref('')
let historyRequest = 0

// 自定义 SQL 补全
let completionCache: Completion[] = []

const customSqlCompletion = async (context: CompletionContext): Promise<CompletionResult | null> => {
  const word = context.matchBefore(/[\w.]*/)
  if (!word || (word.from === word.to && !context.explicit)) return null

  // 若输入了表限定符（如 t.cost ），只从最后一个点之后开始替换，保留限定符
  const text = word.text
  const lastDot = text.lastIndexOf('.')
  const replaceFrom = word.from + (lastDot >= 0 ? lastDot + 1 : 0)

  // 调用后端补全 API
  try {
    const pos = context.pos
    const res = await sqlComplete({
      sql: editorView?.state.doc.toString() || '',
      cursorPosition: pos,
      datasourceId: datasourceId.value,
      databaseName: databaseName.value || undefined,
    })
    completionCache = (res.items || []).map((item: CompletionItem) => ({
      label: item.label,
      type: item.type === 'TABLE' ? 'variable' : item.type === 'KEYWORD' ? 'keyword' : item.type === 'FUNCTION' ? 'function' : 'property',
      detail: item.detail,
      apply: item.insertText,
    }))
  } catch {
    completionCache = []
  }

  if (completionCache.length === 0) return null

  return {
    from: replaceFrom,
    options: completionCache,
    validFor: /^[\w.]*$/,
  }
}

const filteredTables = computed(() => {
  if (!schemaFilter.value) return tables.value
  const f = schemaFilter.value.toLowerCase()
  return tables.value.filter(t =>
    t.toLowerCase().includes(f) ||
    (columnMap.value[t] || []).some(c => c.name.toLowerCase().includes(f) || (c.bizName || '').toLowerCase().includes(f))
  )
})

// 编辑器基础扩展（颜色通过 CSS 变量跟随明暗主题，深色叠加 oneDark）
function baseEditorTheme(): Extension {
  return EditorView.theme({
    '&': {
      height: '100%',
      fontSize: '14px',
      backgroundColor: 'var(--onesql-editor-bg)',
    },
    '.cm-scroller': {
      overflow: 'auto',
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
    },
    '.cm-content': {
      padding: '8px 0',
      caretColor: 'var(--onesql-fg)',
    },
    '.cm-gutters': {
      backgroundColor: 'var(--onesql-gutter)',
      color: 'var(--onesql-gutter-fg)',
      borderRight: '1px solid var(--onesql-border)',
    },
    '.cm-lineNumbers .cm-gutterElement': {
      color: 'var(--onesql-gutter-fg)',
    },
  })
}

function editorExtensions(): Extension {
  return [
    lineNumbers(),
    placeholder('输入 SQL，例如 SELECT 1'),
    EditorView.contentAttributes.of({ 'aria-label': 'SQL 编辑器', spellcheck: 'false' }),
    highlightActiveLineGutter(),
    highlightActiveLine(),
    drawSelection(),
    rectangularSelection(),
    history(),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    ...(dark.value ? [oneDark] : []),
    sql({ dialect: MySQL, upperCaseKeywords: true }),
    autocompletion({
      override: [customSqlCompletion],
      activateOnTyping: true,
      maxRenderedOptions: 50,
    }),
    keymap.of([
      ...defaultKeymap,
      ...historyKeymap,
      indentWithTab,
      { key: 'Ctrl-Enter', run: () => { void runQuery(false); return true } },
      { key: 'Ctrl-Shift-f', run: () => { formatSql(); return true } },
    ]),
    baseEditorTheme(),
    EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        sqlText.value = update.state.doc.toString()
      }
      if (update.docChanged || update.selectionSet) {
        const selection = update.state.selection.main
        selectedSql.value = update.state.sliceDoc(selection.from, selection.to).trim()
      }
    }),
  ]
}

// 主题切换：重建编辑器，使 oneDark / 亮色跟随
function toggleTheme() {
  dark.value = !dark.value
  if (editorView) {
    editorView.dispatch({ effects: StateEffect.reconfigure.of(editorExtensions()) })
  }
}

// 初始化 CodeMirror
function initEditor() {
  if (!editorRef.value) return

  const state = EditorState.create({
    doc: sqlText.value,
    extensions: editorExtensions(),
  })

  editorView = new EditorView({
    state,
    parent: editorRef.value,
  })

  // 加载初始解析与诊断
  runParse()
  runDiagnose()
}

// 执行查询
async function runQuery(selection: boolean) {
  if (executing.value || explaining.value) return
  const sourceId = datasourceId.value
  const database = databaseName.value.trim()
  const sql = (selection ? selectedSql.value : sqlText.value).trim()
  if (!sourceId) { ElMessage.warning('请选择数据源'); return }
  if (!sql) { ElMessage.warning(selection ? '请先选中 SQL' : '请输入 SQL'); return }

  const source = datasources.value.find(row => row.id === sourceId)?.dsName || sourceId
  lastRun.value = {
    sql, source: database ? source + ' / ' + database : source,
    startedAt: new Date().toLocaleTimeString('zh-CN', { hour12: false }), selection,
  }
  executing.value = true
  queryError.value = ''
  result.value = null
  resultTab.value = 'result'
  resultOpen.value = true
  try {
    result.value = await executeQuery({ datasourceId: sourceId, databaseName: database || undefined, sql })
    void runParse()
    void runDiagnose()
  } catch (e) {
    queryError.value = e instanceof Error && e.message ? e.message : '查询失败'
  } finally {
    executing.value = false
    void loadHistory()
  }
}

// 格式化 SQL
async function formatSql() {
  if (!sqlText.value.trim()) return
  try {
    const source = sqlText.value
    const formatted = await sqlFormat(source)
    if (sqlText.value !== source) return
    if (editorView) {
      editorView.dispatch({
        changes: { from: 0, to: editorView.state.doc.length, insert: formatted },
      })
    }
    sqlText.value = formatted
    runParse()
    runDiagnose()
    ElMessage.success('格式化完成')
  } catch {
    ElMessage.warning('格式化失败，请检查 SQL')
  }
}

// 解析 SQL
async function runParse() {
  if (!sqlText.value.trim()) { parseResult.value = null; return }
  try {
    parseResult.value = await sqlParse(sqlText.value)
  } catch {
    parseResult.value = null
  }
}

// SQL 健康诊断
async function runDiagnose() {
  if (!sqlText.value.trim()) { diagnoseResult.value = null; return }
  diagnosing.value = true
  try {
    diagnoseResult.value = await sqlDiagnose(
      sqlText.value,
      datasourceId.value || undefined,
      databaseName.value || undefined,
    )
  } catch {
    diagnoseResult.value = null
  } finally {
    diagnosing.value = false
  }
}

// 执行计划 EXPLAIN
async function runExplain() {
  if (executing.value || explaining.value) return
  if (!datasourceId.value) { ElMessage.warning('请选择数据源'); return }
  if (!sqlText.value.trim()) { ElMessage.warning('请输入 SQL'); return }
  explaining.value = true
  explainResult.value = null
  explainRows.value = []
  explainWarnings.value = []
  try {
    const res = await sqlExplain(datasourceId.value, sqlText.value, databaseName.value || undefined)
    explainResult.value = res
    explainRows.value = res.rows || []
    // 汇总：有无走索引 / 全表扫描
    const warn: string[] = []
    for (const row of res.rows || []) {
      const accessType = String(row.type || '').toUpperCase()
      const key = row.key
      const rows = row.rows
      if (!key && (accessType === 'ALL' || accessType === 'FULL')) {
        warn.push(`表 ${row.table || '?'} 全表扫描（${accessType}），预计扫描 ${rows ?? '?'} 行，未使用索引`)
      } else if (accessType === 'ALL') {
        warn.push(`表 ${row.table || '?'} 访问类型 ALL 全表扫描，预计 ${rows ?? '?'} 行`)
      }
    }
    explainWarnings.value = warn
    if (explainResult.value!.columns?.length) {
      resultOpen.value = true
      resultTab.value = 'explain'
    }
  } catch (e: any) {
    ElMessage.error(e.message || '获取执行计划失败')
  } finally {
    explaining.value = false
  }
}

/** 访问类型 -> 标签配色（ALL 红、全表扫，ref/index 绿，其它默认） */
function typeBadge(t: any): string {
  const type = String(t || '').toUpperCase()
  if (type === 'ALL' || type === 'FULL') return 'danger'
  if (type === 'EQ_REF' || type === 'REF' || type === 'RANGE' || type === 'INDEX' || type === 'CONST' || type === 'SYSTEM') return 'success'
  return 'info'
}

function selectDatasource(id: string) {
  databaseName.value = datasources.value.find(row => row.id === id)?.databaseName || ''
  void loadSchema()
}

async function loadSchema() {
  const request = ++schemaRequest
  tables.value = []
  expandedTables.value = []
  columnMap.value = {}
  columnLoading.value = {}
  schemaError.value = ''
  schemaLoading.value = !!datasourceId.value
  if (!datasourceId.value) return
  try {
    const hints = await getSchemaHints(datasourceId.value, databaseName.value.trim() || undefined)
    if (request === schemaRequest) tables.value = hints.tables || []
  } catch {
    if (request === schemaRequest) schemaError.value = '无法读取数据库对象'
  } finally {
    if (request === schemaRequest) schemaLoading.value = false
  }
}

// 切换表展开（懒加载：点击时才取字段 + 元数据标注）
async function toggleTable(table: string) {
  const idx = expandedTables.value.indexOf(table)
  if (idx >= 0) {
    expandedTables.value.splice(idx, 1)
    return
  }
  expandedTables.value.push(table)
  // 已加载则直接展开
  if (columnMap.value[table] || columnLoading.value[table] || !datasourceId.value) return

  const request = schemaRequest
  columnLoading.value = { ...columnLoading.value, [table]: true }
  try {
    const [rows, annos] = await Promise.all([
      getTableColumns(datasourceId.value, table, databaseName.value || undefined),
      getTableColumnAnnotations(datasourceId.value, table, databaseName.value || undefined),
    ])
    if (request !== schemaRequest) return
    const annoByCol: Record<string, any> = {}
    for (const a of annos || []) {
      if (a.columnName) annoByCol[a.columnName] = a
    }
    const cols: ColumnNode[] = (rows || []).map(c => {
      const a = annoByCol[c.columnName] || {}
      return {
        name: c.columnName,
        type: c.columnType || '',
        bizName: a.bizName || undefined,
        dictTypeName: a.dictTypeName || undefined,
        sensitiveLevel: a.sensitiveLevel || 0,
      }
    })
    columnMap.value = { ...columnMap.value, [table]: cols }
  } catch {
    if (request === schemaRequest) expandedTables.value = expandedTables.value.filter(item => item !== table)
  } finally {
    if (request === schemaRequest) {
      const next = { ...columnLoading.value }
      delete next[table]
      columnLoading.value = next
    }
  }
}

// 插入文本到编辑器
function insertText(text: string) {
  if (!editorView) return
  const selection = editorView.state.selection.main
  editorView.dispatch({
    changes: { from: selection.from, to: selection.to, insert: text },
  })
  editorView.focus()
}

// 加载历史
async function loadHistory() {
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = ''
  try {
    const data = await queryHistory({ current: 1, size: 20 })
    if (request === historyRequest) historyList.value = data.records || []
  } catch {
    if (request === historyRequest) historyError.value = '无法加载查询历史'
  } finally {
    if (request === historyRequest) historyLoading.value = false
  }
}

// 使用历史 SQL
function useHistory(row: QueryHistory) {
  if (editorView) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: row.sqlText || '' },
    })
  }
  sqlText.value = row.sqlText || ''
  datasourceId.value = datasources.value.some(source => source.id === row.datasourceId) ? row.datasourceId || '' : ''
  databaseName.value = row.databaseName || ''
  void loadSchema()
  editorView?.focus()
}

const route = useRoute()
const materializationAsset = computed(() => typeof route.query.materialization === 'string' ? route.query.materialization : '')
const materializationDate = computed(() => typeof route.query.bizDate === 'string' ? route.query.bizDate : '')

async function loadDatasources() {
  datasourceError.value = ''
  try {
    const data = await pageDatasources({ current: 1, size: 100 })
    datasources.value = data.records || []
    if (!datasourceId.value && datasources.value[0]?.id) {
      datasourceId.value = datasources.value[0].id
      selectDatasource(datasourceId.value)
    }
  } catch {
    datasourceError.value = '无法加载数据源'
  }
}

onMounted(() => {
  initEditor()
  void loadDatasources()
  void loadHistory()
})

onBeforeUnmount(() => {
  schemaRequest++
  historyRequest++
  editorView?.destroy()
  editorView = null
})
</script>

<style scoped>
.onesql-layout:has(.materialized-result) { flex-wrap: wrap; height: auto; }
.materialized-result { flex: 1 0 100%; min-width: 0; }
/* ============ 明暗主题变量（整页跟随切换） ============ */
.onesql-layout {
  --onesql-bg: var(--dk-surface);
  --onesql-border: var(--dk-border);
  --onesql-divider: var(--dk-soft);
  --onesql-hover: var(--dk-sidebar);
  --onesql-col-hover: var(--dk-primary-soft);
  --onesql-fg: var(--dk-ink);
  --onesql-header-fg: var(--dk-ink);
  --onesql-muted: var(--dk-muted);
  --onesql-empty: #c0c4cc;
  --onesql-tag-bg: #f0f2f5;
  --onesql-gutter: var(--dk-sidebar);
  --onesql-gutter-fg: #909399;
  --onesql-editor-bg: var(--dk-surface);
  --onesql-biz: var(--dk-primary);
}
.onesql-layout.theme-dark {
  --onesql-bg: #1e222a;
  --onesql-border: #30343d;
  --onesql-divider: #2a2f3a;
  --onesql-hover: #2a2f3a;
  --onesql-col-hover: #25304a;
  --onesql-fg: #d8dce5;
  --onesql-header-fg: #eaecef;
  --onesql-muted: #8b93a7;
  --onesql-empty: #5c6470;
  --onesql-tag-bg: #2a2f3a;
  --onesql-gutter: #21252b;
  --onesql-gutter-fg: #9da5b4;
  --onesql-editor-bg: #282c34;
  --onesql-biz: #7fb8e6;
}

.onesql-layout {
  display: flex;
  height: max(590px, calc(100dvh - 57px));
  min-width: 0;
  background: var(--onesql-bg);
  color: var(--onesql-fg);
}

/* ============ Schema 面板 ============ */
.schema-panel {
  width: 240px;
  border-right: 1px solid var(--onesql-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.schema-panel.collapsed {
  width: 40px;
}
.schema-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--onesql-border);
  font-weight: 600;
  font-size: 13px;
  color: var(--onesql-header-fg);
}
.schema-body {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.schema-search { margin: 8px; width: auto; }
.schema-tree {
  flex: 1;
  overflow-y: auto;
  padding: 0 4px 8px;
}
.schema-table {
  margin-bottom: 2px;
}
.table-name {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  cursor: pointer;
  border-radius: 4px;
  font-size: 12px;
  color: var(--onesql-fg);
  user-select: none;
}
.table-name:hover {
  background: var(--onesql-hover);
}
.table-name.expanded > :first-child {
  transform: rotate(90deg);
}
.col-loading {
  color: var(--onesql-muted);
  display: inline-flex;
  margin-left: auto;
}
.table-icon {
  color: #167dff;
}
.table-columns {
  padding-left: 20px;
}
.column-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  cursor: pointer;
  border-radius: 3px;
  font-size: 11px;
}
.column-item:hover {
  background: var(--onesql-col-hover);
}
.col-name {
  color: var(--onesql-fg);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  overflow: hidden;
}
.col-biz {
  color: var(--onesql-biz);
  font-size: 10px;
  border: 1px solid currentColor;
  border-radius: 3px;
  padding: 0 3px;
  line-height: 1.4;
  white-space: nowrap;
}
.col-meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.col-tag {
  margin: 0;
  --el-tag-bg-color: var(--onesql-tag-bg);
}
.col-tag.dict {
  --el-tag-border-color: var(--onesql-border);
  --el-tag-text-color: var(--onesql-muted);
}
.col-type {
  color: var(--onesql-muted);
  font-size: 10px;
}
.column-empty {
  padding: 4px 8px;
  color: var(--onesql-empty);
  font-size: 11px;
}

/* ============ 主面板 ============ */
.main-panel {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.onesql-layout:has(.materialized-result) .main-panel,
.onesql-layout:has(.materialized-result) .schema-panel { min-height: max(590px, calc(100dvh - 57px)); }

.workspace-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px 6px;
}
.workspace-heading h1 { margin: 0; color: var(--onesql-header-fg); font-size: 19px; }
.toolbar {
  display: flex;
  align-items: center;
  padding: 5px 14px 10px;
  border-bottom: 1px solid var(--onesql-border);
  gap: 8px;
  flex-wrap: wrap;
}
.source-select { width: min(200px, 100%); }
.database-input { width: min(155px, 100%); }
.resource-switch { display: flex; border-bottom: 1px solid var(--onesql-border); padding: 0 4px; }
.resource-switch button { flex: 1; padding: 8px 3px; border: 0; border-bottom: 2px solid transparent; background: transparent; color: var(--onesql-muted); cursor: pointer; font: inherit; }
.resource-switch button[aria-pressed='true'] { color: var(--onesql-biz); border-bottom-color: var(--onesql-biz); font-weight: 600; }
.resource-state { padding: 10px; color: var(--onesql-muted); font-size: 12px; }
.table-name, .column-item {
  width: 100%; background: transparent; border: 0; text-align: start; font-family: inherit;
}
/* ============ 编辑器 ============ */
.editor-container {
  flex: 0 1 var(--editor-height, 100%);
  min-height: 120px;
  border-bottom: 1px solid var(--onesql-border);
  position: relative;
  display: flex;
  flex-direction: column;
}
.codemirror-host {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
.editor-shortcuts {
  display: flex;
  gap: 12px;
  padding: 4px 12px;
  background: var(--onesql-gutter);
  border-top: 1px solid var(--onesql-border);
  font-size: 11px;
  color: var(--onesql-muted);
}
kbd {
  display: inline-block;
  padding: 1px 4px;
  font-size: 10px;
  background: var(--onesql-bg);
  border: 1px solid var(--onesql-border);
  border-radius: 3px;
  box-shadow: 0 1px 1px rgba(0,0,0,.05);
}

/* ============ 结果区域 ============ */
.result-divider {
  touch-action: none;
  cursor: row-resize;
  height: 9px;
  flex: none;
  display: grid;
  place-items: center;
  background: var(--onesql-gutter);
  border-bottom: 1px solid var(--onesql-border);
}
.result-divider span { width: 35px; height: 3px; border-radius: 4px; background: var(--onesql-muted); }
.result-divider:focus-visible { outline: 2px solid var(--onesql-biz); outline-offset: -2px; }
.result-area { flex: 1 0 235px; min-height: 0; overflow: hidden; display: flex; flex-direction: column; }
.result-area.result-closed { flex: none; }
.result-bar { display: flex; justify-content: space-between; align-items: center; padding: 0 12px; background: var(--onesql-gutter); }
.result-bar strong { font-size: 13px; color: var(--onesql-header-fg); }
.result-area :deep(.el-tabs) { display: flex; flex: 1; flex-direction: column; min-height: 0; }
.result-area :deep(.el-tabs__header) { margin: 0; padding: 0 12px; }
.result-area :deep(.el-tabs__content) { flex: 1; min-height: 0; padding: 8px 12px; box-sizing: border-box; overflow: auto; }
.result-area :deep(.el-tab-pane) { height: 100%; min-height: 0; }
/* ============ 诊断面板 ============ */
.diagnose-panel {
  padding: 12px;
}
.diagnose-issues {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.diagnose-issue {
  padding: 8px 12px;
  border: 1px solid var(--onesql-border);
  border-radius: 6px;
  background: var(--onesql-bg);
}
.diagnose-issue.sev-error {
  border-left: 3px solid #f56c6c;
}
.diagnose-issue.sev-warning {
  border-left: 3px solid #e6a23c;
}
.diagnose-issue.sev-info {
  border-left: 3px solid #909399;
}
.issue-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.issue-message {
  font-size: 13px;
  color: var(--onesql-fg);
  line-height: 1.5;
}
.issue-suggestion {
  margin-top: 4px;
  padding-left: 4px;
  font-size: 12px;
  color: var(--onesql-muted);
}
.diagnose-ok :deep(.el-result__title) {
  color: var(--onesql-fg);
}
.diagnose-loading {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--onesql-muted);
  font-size: 13px;
  padding: 12px;
}
.diagnose-section {
  margin-bottom: 16px;
}
.diagnose-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--onesql-muted);
}
.diagnose-tag {
  margin: 0 4px 4px 0;
}
.diagnose-error {
  margin-top: 12px;
}
.empty-text {
  color: var(--onesql-empty);
  font-size: 12px;
}

/* ============ 执行计划 ============ */
.explain-panel {
  padding: 8px 0;
}
.explain-alert {
  margin-bottom: 10px;
}
.idx-used {
  color: #67c23a;
  font-weight: 600;
}
.idx-none {
  color: #f56c6c;
}

/* ============ 历史面板 ============ */
.history-panel {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
}
.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--onesql-border);
  font-weight: 600;
  font-size: 13px;
  color: var(--onesql-header-fg);
}
.history-list {
  flex: 1;
  overflow-y: auto;
}
.history-item {
  display: block;
  width: 100%;
  font: inherit;
  text-align: left;
  border: 0;
  background: transparent;
  padding: 8px 10px;
  border-bottom: 1px solid var(--onesql-divider);
  cursor: pointer;
  transition: background .15s;
}
.history-item:hover {
  background: var(--onesql-hover);
}
.history-sql {
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
  color: var(--onesql-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 4px;
}
.history-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  color: var(--onesql-muted);
}
@media (max-width: 760px) {
  .onesql-layout { height: auto; flex-direction: column; }
  .schema-panel, .schema-panel.collapsed { width: 100%; border-right: 0; border-bottom: 1px solid var(--onesql-border); }
  .schema-panel .schema-body, .schema-panel .history-panel { max-height: 250px; }
  .main-panel, .onesql-layout:has(.materialized-result) .main-panel { height: max(620px, calc(100dvh - 100px)); }
  .onesql-layout:has(.materialized-result) .schema-panel { min-height: 0; }
  .onesql-layout:has(.materialized-result) { flex-wrap: nowrap; }
  .materialized-result { flex: none; }
}
</style>
