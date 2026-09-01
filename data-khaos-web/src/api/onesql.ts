import request, { get, post } from './request'
import type { PageResult, QueryResult } from '@/types'

/** SQL 补全请求 */
export interface SqlCompleteRequest {
  sql?: string
  cursorPosition?: number
  datasourceId?: string
  databaseName?: string
}

/** SQL 补全项 */
export interface CompletionItem {
  type: 'KEYWORD' | 'TABLE' | 'COLUMN' | 'FUNCTION'
  label: string
  insertText: string
  detail: string
}

/** SQL 补全响应 */
export interface SqlCompleteResult {
  items: CompletionItem[]
}

/** SQL 解析结果 */
export interface SqlParseResult {
  tables: string[]
  columns: string[]
  parseError?: string
}

/** SQL 诊断问题 */
export interface DiagnosisIssue {
  severity: 'info' | 'warning' | 'error'
  rule: string
  message: string
  suggestion: string
}

/** SQL 诊断结果 */
export interface SqlDiagnoseResult {
  healthy: boolean
  issues: DiagnosisIssue[]
}

/** 表结构信息（懒加载，仅表名；列在展开时再取） */
export interface TableHint {
  name: string
}

/** Schema 提示响应 */
export interface SchemaHints {
  tables: string[]
}

/** 列信息（懒加载单表字段） */
export interface ColumnInfo {
  columnName: string
  columnType: string
  columnLength?: number
  isNullable?: number
  isPrimaryKey?: number
  description?: string
}

/** 字段元数据标注（来自元数据中心） */
export interface ColumnMetaAnnotation {
  columnName?: string
  bizName?: string
  dictTypeName?: string
  sensitiveLevel?: number
  description?: string
}

/** SQL 补全 */
export function sqlComplete(data: SqlCompleteRequest) {
  return post<SqlCompleteResult>('/query/onesql/complete', data)
}

/** SQL 格式化 */
export function sqlFormat(sql: string) {
  return post<string>('/query/onesql/format', { sql })
}

/** SQL 解析 */
export function sqlParse(sql: string) {
  return post<SqlParseResult>('/query/onesql/parse', { sql })
}

/** SQL 健康诊断 */
export function sqlDiagnose(sql: string, datasourceId?: string, databaseName?: string) {
  return post<SqlDiagnoseResult>('/query/onesql/diagnose', { sql, datasourceId, databaseName })
}

/** 执行计划 EXPLAIN */
export function sqlExplain(datasourceId: string, sql: string, databaseName?: string) {
  return post<QueryResult>('/query/onesql/explain', { datasourceId, databaseName, sql })
}

/** 获取数据源 Schema 提示（懒加载，仅表名） */
export function getSchemaHints(datasourceId: string, databaseName?: string) {
  return get<SchemaHints>('/query/onesql/hints', { datasourceId, databaseName })
}

/** 懒加载获取单表字段 */
export function getTableColumns(datasourceId: string, table: string, databaseName?: string) {
  return get<ColumnInfo[]>('/query/onesql/columns', { datasourceId, databaseName, table })
}

/** 查询历史（复用原有接口） */
export interface QueryHistory {
  id?: string
  sqlText?: string
  datasourceId?: string
  databaseName?: string
  status?: number
  costMs?: number
  rowCount?: number
  createTime?: string
}

export function queryHistory(params: { current?: number; size?: number }) {
  return get<PageResult<QueryHistory>>('/query/history', params)
}

export { request, get, post }
