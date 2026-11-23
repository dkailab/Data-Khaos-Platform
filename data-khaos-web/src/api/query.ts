import service, { get, post } from './request'
import type { PageResult, QueryExecuteRequest, QueryHistory, QueryJob, QueryJobStatus, QueryResult, QueryUsageDay } from '@/types'

/** 执行 SQL（自动审核 + 可选表权限校验） */
export function executeQuery(data: QueryExecuteRequest) {
  return post<QueryResult>('/query/execute', data)
}

/** 导出查询结果为 CSV */
export async function exportQueryResult(data: QueryExecuteRequest) {
  const resp = await service.post('/query/export', data, { responseType: 'text' })
  const csv = (resp as any).data as string
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'query_result.csv'
  a.click()
  URL.revokeObjectURL(url)
}

/** 查询历史（分页） */
export function queryHistory(params: Record<string, any>) {
  return get<PageResult<QueryHistory>>('/query/history', params)
}

/** 查询历史详情 */
export function queryHistoryDetail(id: string) {
  return get<QueryHistory>(`/query/history/${id}`)
}


export function submitQueryJob(request: QueryExecuteRequest & { requestId: string }) {
  return post<QueryJob>('/query/jobs', request)
}

export function listQueryJobs(params: { current: number; size: number; status?: QueryJobStatus; submittedOn?: string }) {
  return get<PageResult<QueryJob>>('/query/jobs', params)
}

export function readQueryJobResult(id: string) {
  return get<QueryResult>(`/query/jobs/${id}/result`)
}

export function cancelQueryJob(id: string) {
  return post<QueryJob>(`/query/jobs/${id}/cancel`)
}

export function getQueryUsage(days: number) {
  return get<QueryUsageDay[]>('/query/jobs/usage', { days })
}


export interface MaterializationPreview {
  tableName: string
  bizDate: string
  publicationId: string
  qualitySnapshotId: string
  publishedAt: string
  cacheHit: boolean
  result: QueryResult
}

export function previewMaterialization(assetId: string, bizDate: string, refresh: boolean, signal: AbortSignal) {
  return get<MaterializationPreview>(`/query/onesql/materializations/${encodeURIComponent(assetId)}/preview`,
    { bizDate, refresh }, { signal })
}
