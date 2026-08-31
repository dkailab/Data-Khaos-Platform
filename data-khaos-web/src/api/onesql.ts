import request, { get, post } from './request'
import type { PageResult } from '@/types'

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

/** 表结构信息 */
export interface TableHint {
  name: string
  columns: { name: string; type: string }[]
}

/** Schema 提示响应 */
export interface SchemaHints {
  tables: TableHint[]
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

/** 获取数据源 Schema 提示 */
export function getSchemaHints(datasourceId: string, databaseName?: string) {
  return get<SchemaHints>('/query/onesql/hints', { datasourceId, databaseName })
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
