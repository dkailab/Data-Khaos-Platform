import { get, post, put } from './request'
import type { PageResult } from '@/types'

/**
 * 数仓数据服务 API 发布（PaaS 数据服务层）
 *
 * 管理入口：/api/query/warehouse/v1/projects/{projectId}/apis
 * 运行入口：/api/data/v1/projects/{projectId}/apis/{apiCode}/versions/{version}
 *
 * 约定：request.ts 已解包 R<T>，此处直接返回 data。
 * 编辑、发布、下线必须携带当前 revision，修订号已变化时后端返回冲突提示。
 */

export type WarehouseApiStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE'
export type WarehouseApiDataMode = 'BATCH' | 'REALTIME'

export interface WarehouseApiVersion {
  id: string
  projectId: string
  apiCode: string
  apiVersion: number
  apiName: string
  maxRows: number
  dataMode: WarehouseApiDataMode
  realtimeAssetId?: string
  realtimeBatchAssetId?: string
  observedTimezone?: string
  amountUnit?: string
  freshnessFreshSeconds?: number
  freshnessStaleSeconds?: number
  quietSeconds?: number
  reconcileTolerance?: number | null
  /** 实时契约列映射：为空表示默认地区成交额口径 */
  realtimeKeyColumn?: string | null
  realtimeValueColumn?: string | null
  realtimeCountColumn?: string | null
  realtimeObservedColumn?: string | null
  realtimeDateColumn?: string | null
  realtimeValueDivisor?: number | null
  realtimeProgressTable?: string | null
  realtimeProgressDateColumn?: string | null
  realtimeProgressAdvancedColumn?: string | null
  realtimeProgressCountColumn?: string | null
  realtimeProgressEventTimeColumn?: string | null
  status: WarehouseApiStatus
  revision: number
  createdBy?: string
  createTime?: string
  updatedBy?: string
  updateTime?: string
  publishedBy?: string
  publishedAt?: string
  offlinedBy?: string
  offlinedAt?: string
}

/** 草稿写入模型：实时字段仅在 dataMode=REALTIME 时必填，省略即回落 BATCH 默认。 */
export interface WarehouseApiDraft {
  name: string
  maxRows: number
  dataMode?: WarehouseApiDataMode
  realtimeAssetId?: string
  realtimeBatchAssetId?: string
  observedTimezone?: string
  amountUnit?: string
  freshnessFreshSeconds?: number
  freshnessStaleSeconds?: number
  quietSeconds?: number
  reconcileTolerance?: number | null
  realtimeMapping?: WarehouseApiRealtimeMapping
}

/** 列映射：所有字段可省略，省略即按默认地区成交额口径解释。 */
export interface WarehouseApiRealtimeMapping {
  keyColumn?: string
  valueColumn?: string
  countColumn?: string
  observedColumn?: string
  dateColumn?: string
  valueDivisor?: number
  progressTable?: string
  progressDateColumn?: string
  progressAdvancedColumn?: string
  progressCountColumn?: string
  progressEventTimeColumn?: string
}

export interface WarehouseApiCredential {
  id: string
  appName: string
  issuedBy: string
  issuedAt: string
  expiresAt: string
  revokedBy?: string
  revokedAt?: string
}

/** 签发结果：token 为明文，仅在签发响应中出现一次 */
export interface IssuedWarehouseCredential {
  credential: WarehouseApiCredential
  token: string
}

export interface RegionAmount {
  region: string
  gmv: string
}

/** 固定契约：地区成交额（BATCH 模式） */
export interface OrderRegionGmv {
  bizDate: string
  publicationId: string
  qualitySnapshotId: string
  publishedAt: string
  truncated: boolean
  rows: RegionAmount[]
  dataMode?: 'BATCH'
}

/** 实时契约结果（REALTIME 模式）：双时钟 + 三态/四态可用性 */
export interface RealtimeRegionGmv {
  bizDate: string
  dataMode: 'REALTIME'
  timezone: string
  amountUnit: string
  state: 'PRODUCED' | 'EMPTY' | 'NOT_YET_PRODUCED' | 'IN_PROGRESS'
  observedAt: string | null
  lagSeconds: number | null
  freshness: 'FRESH' | 'LAGGING' | 'STALE' | 'UNKNOWN'
  thresholds: { freshSeconds: number; staleSeconds: number }
  progress: {
    advancedAt: string | null
    lagSeconds: number | null
    eventCount: number
    maxEventTime: string | null
  } | null
  processingLagSeconds: number | null
  truncated: boolean
  rowCount: number
  regions: Array<{ region: string; gmv: string; orderCount: number; observedAt: string | null }>
}

/** 平台身份判别：响应带 dataMode 字段，REALTIME 为实时契约，其余按离线契约处理 */
export type WarehouseApiResult = OrderRegionGmv | RealtimeRegionGmv

/** 实时与批 ADS 逐地区对账结果 */
export interface RealtimeReconcile {
  bizDate: string
  matched: boolean
  realtime: { state: string; observedAt: string | null; lagSeconds: number | null; freshness: string; regionCount: number; amountUnit: string; processingLagSeconds: number | null }
  batch: { state: string; observedAt: string | null; lagSeconds: number | null; freshness: string; regionCount: number; amountUnit: string; processingLagSeconds?: number | null }
  regions: Array<{
    region: string
    realtimeGmv: string | null
    batchGmv: string | null
    realtimeOrderCount: number | null
    batchOrderCount: number | null
    verdict: 'MATCHED' | 'WITHIN_TOLERANCE' | 'ONLY_REALTIME' | 'ONLY_BATCH' | 'MISMATCH'
    gmvDiffRate?: string | null
    orderDiffRate?: string | null
  }>
  mode: 'STRICT' | 'TOLERANCE'
  tolerance: string | null
  matchRate: string
}

/** 受管物化分区就绪状态 */
export interface WarehouseMaterializationStatus {
  assetId: string
  bizDate: string
  publicationId: string
  qualitySnapshotId: string
  publishedAt: string
}

function versionPath(projectId: string, apiCode: string, version: number) {
  return `/query/warehouse/v1/projects/${encodeURIComponent(projectId)}/apis/${encodeURIComponent(apiCode)}/versions/${version}`
}

/** 分页查看项目 API 版本 */
export function listWarehouseApis(projectId: string, current: number, size: number) {
  return get<PageResult<WarehouseApiVersion>>(
    `/query/warehouse/v1/projects/${encodeURIComponent(projectId)}/apis`,
    { current, size },
  )
}

/** 创建固定地区成交额 API 草稿（revision 初始为 1） */
export function createWarehouseApiDraft(projectId: string, apiCode: string, version: number, draft: WarehouseApiDraft) {
  return post<WarehouseApiVersion>(versionPath(projectId, apiCode, version), draft)
}

/** 编辑草稿，需携带当前 revision */
export function editWarehouseApiDraft(
  projectId: string, apiCode: string, version: number, revision: number, draft: WarehouseApiDraft,
) {
  return put<WarehouseApiVersion>(versionPath(projectId, apiCode, version), draft, { params: { revision } })
}

/** 发布指定修订号的草稿 */
export function publishWarehouseApi(projectId: string, apiCode: string, version: number, revision: number) {
  return post<WarehouseApiVersion>(`${versionPath(projectId, apiCode, version)}/publish`, undefined, {
    params: { revision },
  })
}

/** 下线已发布的版本 */
export function offlineWarehouseApi(projectId: string, apiCode: string, version: number, revision: number) {
  return post<WarehouseApiVersion>(`${versionPath(projectId, apiCode, version)}/offline`, undefined, {
    params: { revision },
  })
}

/** 为已发布版本签发应用凭据，明文仅在响应中出现一次 */
export function issueWarehouseApiCredential(
  projectId: string, apiCode: string, version: number, appName: string, ttlHours: number,
) {
  return post<IssuedWarehouseCredential>(`${versionPath(projectId, apiCode, version)}/credentials`, {
    appName,
    ttlHours,
  })
}

/** 分页查看凭据签发与吊销记录 */
export function listWarehouseApiCredentials(
  projectId: string, apiCode: string, version: number, current: number, size: number,
) {
  return get<PageResult<WarehouseApiCredential>>(`${versionPath(projectId, apiCode, version)}/credentials`, {
    current,
    size,
  })
}

/** 吊销指定版本的调用凭据 */
export function revokeWarehouseApiCredential(
  projectId: string, apiCode: string, version: number, credentialId: string,
) {
  return post<void>(
    `${versionPath(projectId, apiCode, version)}/credentials/${encodeURIComponent(credentialId)}/revoke`,
  )
}

/** 以平台登录身份试跑已发布版本（机器调用走 Bearer 凭据，见运行示例） */
export function invokeWarehouseApi(projectId: string, apiCode: string, version: number, bizDate: string) {
  return get<WarehouseApiResult>(
    `/data/v1/projects/${encodeURIComponent(projectId)}/apis/${encodeURIComponent(apiCode)}/versions/${version}`,
    { bizDate },
  )
}

/** 实时契约与批 ADS 逐地区对账（草稿态也可跑，便于发布前核对口径） */
export function reconcileWarehouseApi(projectId: string, apiCode: string, version: number, bizDate: string) {
  return get<RealtimeReconcile>(
    `${versionPath(projectId, apiCode, version)}/reconcile`,
    { bizDate },
  )
}

/** 检查某业务日的受管物化分区是否就绪 */
export function getMaterializationStatus(assetId: string, bizDate: string) {
  return get<WarehouseMaterializationStatus>(
    `/query/warehouse/v1/materializations/${encodeURIComponent(assetId)}/status`,
    { bizDate },
  )
}

/** 运行地址（用于页面展示请求示例） */
export function warehouseApiDataUrl(projectId: string, apiCode: string, version: number, bizDate: string) {
  const base = (import.meta.env.VITE_API_BASE as string) || '/api'
  return `${base}/data/v1/projects/${encodeURIComponent(projectId)}/apis/` +
    `${encodeURIComponent(apiCode)}/versions/${version}?bizDate=${encodeURIComponent(bizDate)}`
}
