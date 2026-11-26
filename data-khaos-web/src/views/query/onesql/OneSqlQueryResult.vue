<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Download } from '@element-plus/icons-vue'
import DkButton from '@/paas-ui/DkButton.vue'
import type { QueryResult } from '@/types'

const props = defineProps<{
  result: QueryResult | null
  running: boolean
  statement: { sql: string; source: string; startedAt: string; selection: boolean } | null
  error: string
}>()
const current = ref(1)
const size = ref(25)
const rows = computed(() => props.result?.rows ?? [])
const columns = computed(() => props.result?.columns ?? [])
const visibleRows = computed(() => rows.value.slice((current.value - 1) * size.value, current.value * size.value))

watch([() => props.result, size], () => { current.value = 1 })

function csvCell(value: unknown) {
  const text = value == null ? '' : String(value)
  const cell = typeof value === 'string' && (/^[\s\uFEFF]*[=+\-@]/.test(text) || /^[\t\r\n]/.test(text)) ? "'" + text : text
  return /[",\r\n]/.test(cell) ? '"' + cell.replace(/"/g, '""') + '"' : cell
}

function exportCsv() {
  if (!columns.value.length) return
  const keys = columns.value.map(column => column.columnName ?? '')
  const lines = [keys.map(csvCell).join(','), ...rows.value.map(row => keys.map(key => csvCell(row[key])).join(','))]
  const blob = new Blob(['\uFEFF', lines.join('\r\n'), '\r\n'], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'onesql-result.csv'
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>

<template>
  <section class="query-result" aria-label="本次查询结果">
    <div v-if="statement" class="execution">
      <div class="execution-meta" aria-live="polite">
        <strong :class="{ failed: error }">{{ running ? '执行中' : error ? '执行失败' : '执行成功' }}</strong>
        <span>{{ statement.source }}</span>
        <span>{{ statement.startedAt }}</span>
        <span v-if="statement.selection">选中 SQL</span>
        <span v-if="result?.costMs != null">{{ result.costMs }} ms</span>
      </div>
      <details class="execution-sql">
        <summary><code>{{ statement.sql }}</code><span>查看 SQL</span></summary>
        <pre class="dk-mono">{{ statement.sql }}</pre>
      </details>
    </div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div v-else-if="running" class="result-state" role="status">正在查询，完成后将在这里显示结果。</div>
    <div v-else-if="result && columns.length" class="query-grid">
      <el-table :data="visibleRows" size="small" stripe height="100%" empty-text="查询完成，返回 0 行">
        <el-table-column type="index" label="#" width="52" :index="(index: number) => (current - 1) * size + index + 1" />
        <el-table-column v-for="(column, index) in columns" :key="index" :label="column.columnName" min-width="128" show-overflow-tooltip>
          <template #header>
            <div>{{ column.columnName }}</div>
            <div v-if="column.columnType" class="column-type">{{ column.columnType }}</div>
          </template>
          <template #default="{ row }">
            <span v-if="row[column.columnName ?? ''] == null" class="null-value">NULL</span>
            <span v-else>{{ row[column.columnName ?? ''] }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div v-else class="result-state">{{ result?.update ? '写入操作已完成。' : result ? '查询完成，未返回表格列。' : '执行 SQL 后，结果会显示在这里。' }}</div>
    <footer v-if="result && !error && !running" class="result-footer">
      <span v-if="result.update && result.rowCount != null">影响 {{ result.rowCount }} 行</span>
      <span v-else-if="!result.update">{{ rows.length }} 行已返回</span>
      <DkButton v-if="columns.length" variant="quiet" @click="exportCsv"><template #icon><Download aria-hidden="true" /></template>下载 CSV</DkButton>
      <el-pagination v-if="rows.length > 0" v-model:current-page="current" v-model:page-size="size" :page-sizes="[25, 50, 100]"
        :total="rows.length" layout="sizes, prev, pager, next" :pager-count="5" small />
    </footer>
  </section>
</template>

<style scoped>
.query-result { height: 100%; display: flex; flex-direction: column; gap: 8px; min-height: 0; min-width: 0; }
.execution { padding: 8px 10px; border: 1px solid var(--onesql-border); border-radius: 8px; }
.execution-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 6px 12px; color: var(--onesql-muted); font-size: 12px; }
.execution-meta strong { color: var(--onesql-fg); }
.execution-meta strong.failed { color: var(--el-color-danger); }
.execution-sql summary { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 5px; cursor: pointer; }
.execution-sql summary code { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.execution-sql summary span { color: var(--onesql-biz); font-size: 12px; white-space: nowrap; }
.execution-sql pre { margin: 6px 0 0; padding: 6px; background: var(--onesql-gutter); max-height: 90px; overflow: auto; }
.query-grid { flex: 1; min-height: 100px; }
.column-type, .null-value { color: var(--onesql-muted); font-size: 11px; }
.column-type { font-weight: normal; }
.result-state { display: grid; min-height: 90px; flex: 1; place-items: center; color: var(--onesql-muted); text-align: center; }
.result-footer { display: flex; align-items: center; flex-wrap: wrap; gap: 5px 10px; font-size: 12px; color: var(--onesql-muted); }
.result-footer :deep(.el-pagination) { margin-inline-start: auto; }
</style>
