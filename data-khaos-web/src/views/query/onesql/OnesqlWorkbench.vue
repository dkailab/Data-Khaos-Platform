<template>
  <div class="onesql-layout" :class="{ 'theme-dark': dark }">
    <!-- 左侧 Schema 面板 -->
    <div class="schema-panel" :class="{ collapsed: schemaCollapsed }">
      <div class="schema-header">
        <span>数据库结构</span>
        <el-button size="small" text @click="schemaCollapsed = !schemaCollapsed">
          <el-icon><Fold /></el-icon>
        </el-button>
      </div>
      <div v-if="!schemaCollapsed" class="schema-body">
        <el-input v-model="schemaFilter" size="small" placeholder="搜索表/字段" :icon="Search" class="schema-search" />
        <div class="schema-tree">
          <div v-for="table in filteredTables" :key="table" class="schema-table">
            <div class="table-name" @click="toggleTable(table)" :class="{ expanded: expandedTables.includes(table) }">
              <el-icon><ArrowRight /></el-icon>
              <el-icon class="table-icon"><Grid /></el-icon>
              <span :title="table">{{ table }}</span>
              <span v-if="columnLoading[table]" class="col-loading"><el-icon class="is-loading"><Refresh /></el-icon></span>
            </div>
            <div v-if="expandedTables.includes(table)" class="table-columns">
              <div v-for="col in columnMap[table] || []" :key="col.name" class="column-item" @click="insertText(col.name)">
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
              </div>
              <div v-if="!columnLoading[table] && columnMap[table] && columnMap[table].length === 0" class="column-empty">暂无字段</div>
            </div>
          </div>
          <el-empty v-if="filteredTables.length === 0" description="请选择数据源" :image-size="60" />
        </div>
      </div>
    </div>

    <!-- 中间主区域 -->
    <div class="main-panel">
      <!-- 顶部工具栏 -->
      <div class="toolbar">
        <div class="toolbar-left">
          <el-select v-model="datasourceId" placeholder="选择数据源" filterable style="width: 240px" @change="onDatasourceChange">
            <el-option v-for="ds in datasources" :key="ds.id" :label="ds.dsName" :value="ds.id!" />
          </el-select>
          <el-input v-model="databaseName" placeholder="数据库（可选）" style="width: 160px; margin-left: 8px" />
          <el-button type="primary" :loading="executing" :icon="CaretRight" @click="runQuery" style="margin-left: 8px">
            执行
          </el-button>
          <el-button text :icon="Star" @click="formatSql">格式化</el-button>
          <el-button text :icon="Cpu" :disabled="!sqlText.trim()" @click="runExplain" :loading="explaining">执行计划</el-button>
          <el-button text :icon="Download" :disabled="!result || !result.columns?.length" @click="exportCsv">导出</el-button>
        </div>
        <div class="toolbar-right">
          <el-tooltip :content="dark ? '切换浅色' : '切换深色'" placement="bottom">
            <el-button text circle :icon="dark ? Sunny : Moon" @click="toggleTheme" />
          </el-tooltip>
          <el-tag v-if="executionInfo" size="small" :type="executionInfo.type">
            {{ executionInfo.text }}
          </el-tag>
          <span v-if="!executing && result" class="result-meta">
            共 {{ result.rows?.length || 0 }} 行，耗时 {{ result.costMs }} ms
          </span>
        </div>
      </div>

      <!-- 编辑器区域 -->
      <div class="editor-container">
        <div ref="editorRef" class="codemirror-host"></div>
        <!-- 快捷键提示 -->
        <div class="editor-shortcuts">
          <span><kbd>Ctrl</kbd>+<kbd>Enter</kbd> 执行</span>
          <span><kbd>Ctrl</kbd>+<kbd>Shift</kbd>+<kbd>F</kbd> 格式化</span>
        </div>
      </div>

      <!-- 结果显示区域 -->
      <div class="result-area">
        <el-tabs v-model="resultTab">
          <el-tab-pane label="查询结果" name="result">
            <el-table :data="result?.rows || []" size="small" border max-height="360" v-loading="executing" stripe>
              <el-table-column type="index" label="#" width="50" />
              <el-table-column v-for="col in result?.columns || []" :key="col.columnName" :prop="col.columnName" :label="col.columnName" min-width="120" show-overflow-tooltip>
                <template #header>
                  <span :title="col.columnType">{{ col.columnName }}</span>
                  <span class="col-type-badge">{{ col.columnType }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-if="!executing && result && result.columns?.length === 0" description="查询无结果" />
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
                      <span v-else :class="{ 'idx-used': col.columnName === 'key' && row.key, 'idx-none': col.columnName === 'key' && !row.key }">{{ row[col.columnName] }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-if="explainResult.columns?.length === 0" description="执行计划无返回" />
              </div>
              <el-empty v-else-if="!explaining" description="点击「执行计划」分析访问类型 / 扫描行数 / 索引" :image-size="70" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>

    <!-- 右侧历史面板 -->
    <div class="history-panel">
      <div class="history-header">
        <span>查询历史</span>
        <el-button size="small" text @click="loadHistory"><el-icon><Refresh /></el-icon></el-button>
      </div>
      <div class="history-list">
        <div v-for="row in historyList" :key="row.id" class="history-item" @click="useHistory(row)">
          <div class="history-sql">{{ row.sqlText }}</div>
          <div class="history-meta">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '成功' : '失败' }}</el-tag>
            <span>{{ row.costMs }}ms</span>
          </div>
        </div>
        <el-empty v-if="historyList.length === 0" description="暂无历史" :image-size="60" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretRight, Download, Fold, ArrowRight, Grid, Search, Star, Refresh, Cpu, Moon, Sunny } from '@element-plus/icons-vue'
import { pageDatasources } from '@/api/datasource'
import { executeQuery } from '@/api/query'
import {
  sqlComplete, sqlFormat, sqlParse, getSchemaHints, queryHistory,
  sqlDiagnose, sqlExplain, getTableColumns,
} from '@/api/onesql'
import { getTableColumnAnnotations } from '@/api/metadata'
import type { MetaDatasource, QueryResult } from '@/types'
import type { CompletionItem, QueryHistory, SqlDiagnoseResult, SqlParseResult } from '@/api/onesql'

// CodeMirror imports
import { EditorState, StateEffect, Extension } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from '@codemirror/view'
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
const sqlText = ref('SELECT * FROM demo_fact_order LIMIT 100')
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const parseResult = ref<SqlParseResult | null>(null)
const resultTab = ref('result')
const executionInfo = ref<{ text: string; type: string } | null>(null)

// 明暗主题：整页跟随切换，编辑器与页面保持一致（深色用 oneDark，浅色用默认亮色）
const dark = ref(false)

// Schema 懒加载
const schemaCollapsed = ref(false)
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
      { key: 'Ctrl-Enter', run: () => { runQuery(); return true } },
      { key: 'Ctrl-Shift-f', run: () => { formatSql(); return true } },
    ]),
    baseEditorTheme(),
    EditorView.updateListener.of((update) => {
      if (update.docChanged) {
        sqlText.value = update.state.doc.toString()
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
async function runQuery() {
  if (!datasourceId.value) { ElMessage.warning('请选择数据源'); return }
  if (!sqlText.value.trim()) { ElMessage.warning('请输入 SQL'); return }

  executing.value = true
  executionInfo.value = null
  result.value = null

  try {
    const res = await executeQuery({
      datasourceId: datasourceId.value,
      databaseName: databaseName.value || undefined,
      sql: sqlText.value,
    })
    result.value = res
    executionInfo.value = { text: '执行成功', type: 'success' }
    resultTab.value = 'result'
    runParse()
    runDiagnose()
    loadHistory()
  } catch (e: any) {
    executionInfo.value = { text: '执行失败', type: 'danger' }
    ElMessage.error(e.message || '查询失败')
  } finally {
    executing.value = false
  }
}

// 格式化 SQL
async function formatSql() {
  if (!sqlText.value.trim()) return
  try {
    const formatted = await sqlFormat(sqlText.value)
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

// 导出 CSV
function exportCsv() {
  if (!result.value?.columns?.length) return
  const cols = result.value.columns.map((c: any) => c.columnName || c.column_name)
  let csv = '\uFEFF' + cols.join(',') + '\n'
  for (const row of result.value.rows || []) {
    csv += cols.map((c: string) => {
      const v = String(row[c] ?? '')
      return v.includes(',') || v.includes('"') ? `"${v.replace(/"/g, '""')}"` : v
    }).join(',') + '\n'
  }
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `onesql_result_${Date.now()}.csv`
  a.click()
  URL.revokeObjectURL(url)
}

// 数据源变更
async function onDatasourceChange() {
  tables.value = []
  expandedTables.value = []
  columnMap.value = {}
  columnLoading.value = {}
  if (!datasourceId.value) return
  try {
    const hints = await getSchemaHints(datasourceId.value, databaseName.value || undefined)
    tables.value = hints.tables || []
  } catch {
    tables.value = []
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
  if (columnMap.value[table]) return
  if (!datasourceId.value) return

  columnLoading.value = { ...columnLoading.value, [table]: true }
  try {
    const [rows, annos] = await Promise.all([
      getTableColumns(datasourceId.value, table, databaseName.value || undefined),
      getTableColumnAnnotations(datasourceId.value, table, databaseName.value || undefined),
    ])
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
    columnMap.value = { ...columnMap.value, [table]: [] }
  } finally {
    const next = { ...columnLoading.value }
    delete next[table]
    columnLoading.value = next
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
  try {
    const data = await queryHistory({ current: 1, size: 20 })
    historyList.value = data.records || []
  } catch {
    // ignore
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
  datasourceId.value = row.datasourceId || ''
  databaseName.value = row.databaseName || ''
}

// 生命周期
onMounted(async () => {
  // 加载数据源
  const data = await pageDatasources({ current: 1, size: 100 })
  datasources.value = data.records || []
  if (datasources.value.length > 0) {
    datasourceId.value = datasources.value[0].id!
    onDatasourceChange()
  }
  loadHistory()
  await nextTick()
  initEditor()
})

onBeforeUnmount(() => {
  editorView?.destroy()
})
</script>

<style scoped>
/* ============ 明暗主题变量（整页跟随切换） ============ */
.onesql-layout {
  --onesql-bg: #fff;
  --onesql-border: #e4e7ed;
  --onesql-divider: #f0f2f5;
  --onesql-hover: #f5f7fa;
  --onesql-col-hover: #ecf5ff;
  --onesql-fg: #303133;
  --onesql-header-fg: #303133;
  --onesql-muted: #909399;
  --onesql-empty: #c0c4cc;
  --onesql-tag-bg: #f0f2f5;
  --onesql-gutter: #fafafa;
  --onesql-gutter-fg: #909399;
  --onesql-editor-bg: #ffffff;
  --onesql-biz: #165dff;
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
  height: calc(100vh - 60px);
  background: var(--onesql-bg);
  color: var(--onesql-fg);
}

/* ============ Schema 面板 ============ */
.schema-panel {
  width: 260px;
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
.schema-search {
  margin: 8px;
}
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
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid var(--onesql-border);
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}
.result-meta {
  color: var(--onesql-muted);
  font-size: 12px;
}

/* ============ 编辑器 ============ */
.editor-container {
  height: 280px;
  border-bottom: 1px solid var(--onesql-border);
  position: relative;
  display: flex;
  flex-direction: column;
}
.codemirror-host {
  flex: 1;
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
.result-area {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
:deep(.el-tabs__content) {
  flex: 1;
  overflow: auto;
  padding: 8px;
}
.col-type-badge {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  background: var(--onesql-tag-bg);
  border-radius: 3px;
  color: var(--onesql-muted);
}

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
  width: 240px;
  border-left: 1px solid var(--onesql-border);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
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
</style>
