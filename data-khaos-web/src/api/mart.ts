import { del, get, post, put } from './request'
import type {
  MarketModelDto,
  MartDimension,
  MartDimLevel,
  MartMetric,
  MartModel,
  MartModelRel,
  MartQueryRequest,
  MartQueryResult,
  MartWarehouseLayer,
  PageResult,
  QueryResult,
} from '@/types'

/* ==================== 数仓分层 ==================== */

export function listMartLayers() {
  return get<MartWarehouseLayer[]>('/mart/layer/list')
}

/* ==================== 模型 ==================== */

export function pageMartModels(params: Record<string, any>) {
  return get<PageResult<MartModel>>('/mart/model/page', params)
}

/** 模型市场分页（仅已发布，含统计） */
export function pageMartMarket(params: Record<string, any>) {
  return get<PageResult<MarketModelDto>>('/mart/market/page', params)
}

export function martModelDetail(id: string) {
  return get<{ model: MartModel; metrics: MartMetric[]; dimensions: MartDimension[]; rels: MartModelRel[] }>(
    `/mart/model/${id}`,
  )
}

export function createMartModel(data: MartModel) {
  return post<void>('/mart/model', data)
}

export function updateMartModel(data: MartModel) {
  return put<void>('/mart/model', data)
}

export function deleteMartModel(id: string) {
  return del<void>(`/mart/model/${id}`)
}

/** 发布模型 */
export function publishMartModel(id: string) {
  return post<void>(`/mart/model/${id}/publish`)
}

/** 下线模型 */
export function offlineMartModel(id: string) {
  return post<void>(`/mart/model/${id}/offline`)
}

/** 预览模型数据（事实表前 100 行） */
export function previewMartModel(id: string) {
  return get<QueryResult>(`/mart/model/${id}/preview`)
}

/* ==================== 指标 ==================== */

export function pageMartMetrics(params: Record<string, any>) {
  return get<PageResult<MartMetric>>('/mart/metric/page', params)
}

export function createMartMetric(data: MartMetric) {
  return post<void>('/mart/metric', data)
}

export function updateMartMetric(data: MartMetric) {
  return put<void>('/mart/metric', data)
}

export function deleteMartMetric(id: string) {
  return del<void>(`/mart/metric/${id}`)
}

/* ==================== 维度 ==================== */

export function pageMartDimensions(params: Record<string, any>) {
  return get<PageResult<MartDimension>>('/mart/dimension/page', params)
}

export function createMartDimension(data: MartDimension) {
  return post<void>('/mart/dimension', data)
}

export function updateMartDimension(data: MartDimension) {
  return put<void>('/mart/dimension', data)
}

export function deleteMartDimension(id: string) {
  return del<void>(`/mart/dimension/${id}`)
}

/** 维度层级列表 */
export function listDimLevels(dimId: string) {
  return get<MartDimLevel[]>(`/mart/dimension/${dimId}/levels`)
}

/** 保存维度层级（全量替换） */
export function saveDimLevels(dimId: string, levels: MartDimLevel[]) {
  return post<void>(`/mart/dimension/${dimId}/levels`, levels)
}

/* ==================== 语义查询（BI 画布） ==================== */

/** 语义查询：模型 + 指标 + 维度 + 筛选 + 排序 → 服务端生成 SQL 并执行 */
export function queryMart(data: MartQueryRequest) {
  return post<MartQueryResult>('/mart/query', data)
}

/** 维度取值（枚举筛选器下拉用） */
export function dimensionValues(dimId: string, limit = 100) {
  return get<string[]>(`/mart/dimension/${dimId}/values`, { limit })
}

/* ==================== 模型关联 ==================== */

export function listModelRels(modelId: string) {
  return get<MartModelRel[]>(`/mart/model/${modelId}/rel`)
}

export function saveModelRel(modelId: string, rel: MartModelRel) {
  return post<void>(`/mart/model/${modelId}/rel`, rel)
}

export function deleteModelRel(id: string) {
  return del<void>(`/mart/rel/${id}`)
}
