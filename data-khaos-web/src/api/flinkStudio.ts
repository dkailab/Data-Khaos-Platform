import { get, post, put } from './request'
import type { PageResult } from '@/types'

export type FlinkAssetKind = 'JOB' | 'FUNCTION'
export type FlinkMode = 'STREAMING' | 'BATCH'
export type FlinkLanguage = 'JAVA' | 'SCALA'

export interface FlinkAsset {
  id: string
  code: string
  name: string
  kind: FlinkAssetKind
  currentVersion: number
  currentMode?: FlinkMode
  updateTime: string
}

export interface FunctionRef {
  assetId: string
  version: number
}

export interface FlinkDefinition {
  mode?: FlinkMode
  parallelism?: number
  sql?: string
  functions?: FunctionRef[]
  language?: FlinkLanguage
  className?: string
  jarUri?: string
}

export interface FlinkRevision {
  version: number
  name: string
  note: string
  createdBy: string
  restoredFrom?: number
  createTime: string
}

export interface FlinkVersionView {
  assetId: string
  code: string
  kind: FlinkAssetKind
  currentVersion: number
  revision: FlinkRevision
  definition: FlinkDefinition
}

interface EditInput {
  name: string
  note: string
  definition: FlinkDefinition
}

const base = (project: string) => `/workflow/flink/projects/${encodeURIComponent(project)}/assets`
const asset = (project: string, id: string) => `${base(project)}/${encodeURIComponent(id)}`
const revision = (project: string, id: string, version: number) => `${asset(project, id)}/versions/${version}`

export function listFlinkAssets(project: string, kind: FlinkAssetKind, current: number, size: number, keyword?: string) {
  return get<PageResult<FlinkAsset>>(base(project), { kind, current, size, keyword: keyword || undefined })
}

export function createFlinkAsset(project: string, input: EditInput & { code: string; kind: FlinkAssetKind }) {
  return post<FlinkVersionView>(base(project), input)
}

export function saveFlinkAsset(project: string, id: string, input: EditInput & { expectedVersion: number }) {
  return put<FlinkVersionView>(`${asset(project, id)}/draft`, input)
}

export function listFlinkVersions(project: string, id: string, current: number, size: number) {
  return get<PageResult<FlinkRevision>>(`${asset(project, id)}/versions`, { current, size })
}

export function getFlinkVersion(project: string, id: string, version: number, signal?: AbortSignal) {
  return get<FlinkVersionView>(revision(project, id, version), undefined, { signal })
}

export function restoreFlinkVersion(project: string, id: string, version: number, expectedVersion: number, note: string) {
  return post<FlinkVersionView>(`${revision(project, id, version)}/restore`, { expectedVersion, note })
}

export function exportFlinkSql(project: string, id: string, version: number) {
  return get<string>(`${revision(project, id, version)}/sql`)
}

export type FlinkRunState = 'PREPARING' | 'SUBMITTING' | 'RECONCILING' | 'SUBMITTED' | 'CANCEL_REQUESTED' |
  'STOP_REQUESTED' | 'STOPPED' | 'FINISHED' | 'FAILED' | 'CANCELED'

export interface FlinkRun {
  id: string
  version: number
  operatorId: string
  state: FlinkRunState
  engineJobId?: string
  engineState?: string
  message?: string
  recoverable: boolean
  restoredFromRunId?: string
  createTime: string
  updateTime: string
}

export function listFlinkRuns(project: string, id: string, current: number, size: number) {
  return get<{ enabled: boolean; page: PageResult<FlinkRun> }>(`${asset(project, id)}/runs`, { current, size })
}

export function submitFlinkRun(project: string, id: string, version: number, requestId: string) {
  return post<FlinkRun>(`${revision(project, id, version)}/runs`, { requestId })
}

export function refreshFlinkRun(project: string, id: string, runId: string) {
  return post<FlinkRun>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/refresh`, {})
}

export function cancelFlinkRun(project: string, id: string, runId: string) {
  return post<FlinkRun>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/cancel`, {})
}

export function stopFlinkRun(project: string, id: string, runId: string) {
  return post<FlinkRun>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/stop`, {})
}

export function resumeFlinkRun(project: string, id: string, runId: string, requestId: string) {
  return post<FlinkRun>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/resume`, { requestId })
}

export interface FlinkCheckpointEntry {
  id: number
  status: 'COMPLETED' | 'FAILED' | 'IN_PROGRESS'
  savepoint: boolean
  triggeredAt?: string
  durationMs?: number
  checkpointedBytes?: number
}

export interface FlinkCheckpointView {
  available: boolean
  counts?: { total?: number; completed?: number; failed?: number; inProgress?: number; restored?: number }
  latestCompleted?: FlinkCheckpointEntry
  latestFailed?: FlinkCheckpointEntry
  latestRestore?: { id: number; restoredAt?: string; savepoint: boolean }
  recent: FlinkCheckpointEntry[]
}

export function getFlinkCheckpoints(project: string, id: string, runId: string, signal: AbortSignal) {
  return get<FlinkCheckpointView>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/checkpoints`, undefined, { signal })
}

export interface FlinkMetricsView {
  vertices: { id: string; name: string; status: string; parallelism: number }[]
  selectedVertexId: string | null
  sample: {
    sampledAt: string
    recordsInPerSecond: number | null
    recordsOutPerSecond: number | null
    maxBackpressureMsPerSecond: number | null
    avgBusyMsPerSecond: number | null
    avgIdleMsPerSecond: number | null
    inputWatermark: string | null
    operators?: {
      name: string
      inputWatermark: string | null
      outputWatermark: string | null
      kafkaMaxLag: number | null
      pendingRecords: number | null
    }[]
    operatorMetricsTruncated?: boolean
  } | null
}

export function getFlinkMetrics(project: string, id: string, runId: string, vertexId: string, signal: AbortSignal) {
  return get<FlinkMetricsView>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/metrics`,
    vertexId ? { vertexId } : undefined, { signal })
}
