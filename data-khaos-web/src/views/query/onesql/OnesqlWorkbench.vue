<template>
  <div class="onesql-layout">
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
          <div v-for="table in filteredTables" :key="table.name" class="schema-table">
            <div class="table-name" @click="toggleTable(table)" :class="{ expanded: expandedTables.includes(table.name) }">
              <el-icon><ArrowRight /></el-icon>
              <el-icon class="table-icon"><Grid /></el-icon>
              <span :title="table.name">{{ table.name }}</span>
            </div>
            <div v-if="expandedTables.includes(table.name)" class="table-columns">
              <div v-for="col in table.columns" :key="col.name" class="column-item" @click="insertText(col.name)">
                <span class="col-name">{{ col.name }}</span>
                <span class="col-type">{{ col.type }}</span>
              </div>
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
          <el-button text :icon="Download" :disabled="!result || !result.columns?.length" @click="exportCsv">导出</el-button>
        </div>
        <div class="toolbar-right">
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
            <div class="diagnose-panel" v-if="parseResult">
              <div class="diagnose-section">
                <h4>引用的表 ({{ parseResult.tables?.length || 0 }})</h4>
                <el-tag v-for="t in parseResult.tables || []" :key="t" size="small" class="diagnose-tag">{{ t }}</el-tag>
                <span v-if="!parseResult.tables?.length" class="empty-text">-</span>
              </div>
              <div class="diagnose-section">
                <h4>SELECT 列 ({{ parseResult.columns?.length || 0 }})</h4>
                <el-tag v-for="c in parseResult.columns || []" :key="c" size="small" type="success" class="diagnose-tag">{{ c }}</el-tag>
                <span v-if="!parseResult.columns?.length" class="empty-text">-</span>
              </div>
              <div v-if="parseResult.parseError" class="diagnose-error">
                <el-alert :title="parseResult.parseError" type="warning" show-icon :closable="false" />
              </div>
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
import { CaretRight, Download, Fold, ArrowRight, Grid, Search, Star, Refresh } from '@element-plus/icons-vue'
import { pageDatasources } from '@/api/datasource'
import { executeQuery } from '@/api/query'
import { sqlComplete, sqlFormat, sqlParse, getSchemaHints, queryHistory } from '@/api/onesql'
import type { MetaDatasource, QueryResult } from '@/types'
import type { TableHint, CompletionItem, QueryHistory } from '@/api/onesql'

// CodeMirror imports
import { EditorState } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from '@codemirror/view'
import { defaultKeymap, indentWithTab, history, historyKeymap } from '@codemirror/commands'
import { sql, MySQL } from '@codemirror/lang-sql'
import { autocompletion, CompletionContext, CompletionResult, Completion } from '@codemirror/autocomplete'
import { oneDark } from '@codemirror/theme-one-dark'
import { syntaxHighlighting, defaultHighlightStyle } from '@codemirror/language'

const editorRef = ref<HTMLElement>()
let editorView: EditorView | null = null

// 状态
const datasources = ref<MetaDatasource[]>([])
const datasourceId = ref('')
const databaseName = ref('')
const sqlText = ref('SELECT * FROM demo_fact_order LIMIT 100')
const executing = ref(false)
const result = ref<QueryResult | null>(null)
const parseResult = ref<any>(null)
const resultTab = ref('result')
const executionInfo = ref<{ text: string; type: string } | null>(null)

// Schema
const schemaCollapsed = ref(false)
const schemaFilter = ref('')
const tables = ref<TableHint[]>([])
const expandedTables = ref<string[]>([])

// History
const historyList = ref<QueryHistory[]>([])

// 自定义 SQL 补全
let completionCache: Completion[] = []

const customSqlCompletion = async (context: CompletionContext): Promise<CompletionResult | null> => {
  const word = context.matchBefore(/[\w.]*/)
  if (!word || (word.from === word.to && !context.explicit)) return null

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
      type: item.type === 'TABLE' ? 'variable' : item.type === 'KEYWORD' ? 'keyword' : 'property',
      detail: item.detail,
      apply: item.insertText,
    }))
  } catch {
    completionCache = []
  }

  if (completionCache.length === 0) return null

  return {
    from: word.from,
    options: completionCache,
    validFor: /^[\w.]*$/,
  }
}

const filteredTables = computed(() => {
  if (!schemaFilter.value) return tables.value
  const f = schemaFilter.value.toLowerCase()
  return tables.value.filter(t =>
    t.name.toLowerCase().includes(f) ||
    t.columns.some(c => c.name.toLowerCase().includes(f))
  )
})

// 初始化 CodeMirror
async function initEditor() {
  if (!editorRef.value) return

  const theme = EditorView.theme({
    '&': {
      height: '100%',
      fontSize: '14px',
    },
    '.cm-scroller': {
      overflow: 'auto',
      fontFamily: "'JetBrains Mono', 'Fira Code', Consolas, monospace",
    },
    '.cm-content': {
      padding: '8px 0',
    },
    '.cm-gutters': {
      backgroundColor: '#fafafa',
      borderRight: '1px solid #e4e7ed',
    },
  })

  const state = EditorState.create({
    doc: sqlText.value,
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      drawSelection(),
      rectangularSelection(),
      history(),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      oneDark,
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
      theme,
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          sqlText.value = update.state.doc.toString()
        }
      }),
    ],
  })

  editorView = new EditorView({
    state,
    parent: editorRef.value,
  })

  // 加载初始诊断
  runParse()
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
  if (!datasourceId.value) return
  try {
    const hints = await getSchemaHints(datasourceId.value, databaseName.value || undefined)
    tables.value = hints.tables || []
  } catch {
    tables.value = []
  }
}

// 切换表展开
function toggleTable(table: TableHint) {
  const idx = expandedTables.value.indexOf(table.name)
  if (idx >= 0) expandedTables.value.splice(idx, 1)
  else expandedTables.value.push(table.name)
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
.onesql-layout {
  display: flex;
  height: calc(100vh - 60px);
  background: #fff;
}

/* ============ Schema 面板 ============ */
.schema-panel {
  width: 260px;
  border-right: 1px solid #e4e7ed;
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
  border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
  font-size: 13px;
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
  user-select: none;
}
.table-name:hover {
  background: #f5f7fa;
}
.table-name.expanded > :first-child {
  transform: rotate(90deg);
}
.table-icon {
  color: #409eff;
}
.table-columns {
  padding-left: 20px;
}
.column-item {
  display: flex;
  justify-content: space-between;
  padding: 3px 8px;
  cursor: pointer;
  border-radius: 3px;
  font-size: 11px;
}
.column-item:hover {
  background: #ecf5ff;
}
.col-name {
  color: #303133;
}
.col-type {
  color: #909399;
  font-size: 10px;
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
  border-bottom: 1px solid #e4e7ed;
  gap: 8px;
  flex-wrap: wrap;
}
.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 4px;
}
.result-meta {
  color: #909399;
  font-size: 12px;
}

/* ============ 编辑器 ============ */
.editor-container {
  height: 280px;
  border-bottom: 1px solid #e4e7ed;
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
  background: #fafafa;
  border-top: 1px solid #e4e7ed;
  font-size: 11px;
  color: #909399;
}
kbd {
  display: inline-block;
  padding: 1px 4px;
  font-size: 10px;
  background: #fff;
  border: 1px solid #dcdfe6;
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
  background: #f0f2f5;
  border-radius: 3px;
  color: #909399;
}

/* ============ 诊断面板 ============ */
.diagnose-panel {
  padding: 12px;
}
.diagnose-section {
  margin-bottom: 16px;
}
.diagnose-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: #606266;
}
.diagnose-tag {
  margin: 0 4px 4px 0;
}
.diagnose-error {
  margin-top: 12px;
}
.empty-text {
  color: #c0c4cc;
  font-size: 12px;
}

/* ============ 历史面板 ============ */
.history-panel {
  width: 240px;
  border-left: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.history-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
  font-size: 13px;
}
.history-list {
  flex: 1;
  overflow-y: auto;
}
.history-item {
  padding: 8px 10px;
  border-bottom: 1px solid #f0f2f5;
  cursor: pointer;
  transition: background .15s;
}
.history-item:hover {
  background: #f5f7fa;
}
.history-sql {
  font-size: 11px;
  font-family: 'JetBrains Mono', monospace;
  color: #303133;
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
  color: #909399;
}
</style>
