import { get, post, put } from './request'
import type { MetaColumn, MetaDatabase, MetaTable, MetaTableLineage, PageResult } from '@/types'

/** 全量同步数据源元数据 */
export function syncMetadata(datasourceId: string) {
  return post<void>(`/meta/sync/${datasourceId}`)
}

/** 同步单库元数据 */
export function syncDatabase(datasourceId: string, database: string) {
  return post<void>(`/meta/sync/${datasourceId}/${database}`)
}

/** 同步单表字段 */
export function syncTable(datasourceId: string, database: string, table: string) {
  return post<void>(`/meta/sync/${datasourceId}/${database}/${table}`)
}

/** 结构树（库 -> 表 -> 字段） */
export function getStructure(datasourceId: string) {
  return get<any[]>(`/meta/structure/${datasourceId}`)
}

/** 已采集的数据库列表 */
export function listMetaDatabases(datasourceId: string) {
  return get<MetaDatabase[]>(`/meta/database/list/${datasourceId}`)
}

/** 分页查询表 */
export function pageMetaTables(params: Record<string, any>) {
  return get<PageResult<MetaTable>>('/meta/table/page', params)
}

/** 分页查询字段 */
export function pageMetaColumns(params: Record<string, any>) {
  return get<PageResult<MetaColumn>>('/meta/column/page', params)
}

/** 按数据源+库+表获取字段及元数据标注（业务名/字典/敏感级） */
export function getTableColumnAnnotations(datasourceId: string, table: string, database?: string) {
  return get<MetaColumn[]>(`/meta/table-columns`, { datasourceId, database, table })
}

/** 更新字段业务元数据（业务名/说明/字典关联/敏感级） */
export function updateMetaColumn(id: string, data: MetaColumn) {
  return put<void>(`/meta/column/${id}`, data)
}

/** 数据标准落标校验 */
export function checkColumnStandard(columnId: string, stdCode: string) {
  return get<Record<string, any>>(`/meta/column/${columnId}/standard-check`, { stdCode })
}

/** 检索（表/字段） */
export function searchMetadata(keyword: string) {
  return get<any[]>('/meta/search', { keyword })
}

/** 查询表血缘 */
export function getLineage(tableId: string) {
  return get<MetaTableLineage[]>(`/meta/lineage/${tableId}`)
}

/** 记录血缘关系 */
export function saveLineage(data: MetaTableLineage) {
  return post<void>('/meta/lineage', data)
}

/** SQL 血缘自动分析：解析 INSERT/CREATE TABLE AS ... SELECT 写入血缘 */
export function analyzeSqlLineage(datasourceId: string, database: string, sql: string) {
  return post<MetaTableLineage[]>('/meta/lineage/analyze', void 0, {
    params: { datasourceId, database, sql },
  })
}
