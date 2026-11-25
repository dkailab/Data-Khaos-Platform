import { get, post, put } from './request'
import type { PageResult } from '@/types'

export type FlinkAssetKind = 'JOB' | 'FUNCTION' | 'JAR_JOB'
export type FlinkMode = 'STREAMING' | 'BATCH'
export type FlinkFunctionType = 'UDF' | 'UDTF' | 'UDAF'
export type FlinkLanguage = 'JAVA' | 'SCALA'

export interface FlinkAsset {
  id: string
  code: string
  name: string
  kind: FlinkAssetKind
  currentVersion: number
  currentMode?: FlinkMode
  functionType?: FlinkFunctionType | null
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
  functionType?: FlinkFunctionType | null
  jarJob?: { jarId: string; entryClass: string; arguments: string[] }
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

export function getFlinkVersion(project: string, id: string, version: number) {
  return get<FlinkVersionView>(revision(project, id, version))
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

export interface FlinkCheckpointAlertHistory {
  enabled: boolean
  alerts: {
    id: string
    status: 'OPEN' | 'RECOVERED' | 'ENDED'
    firstFailedCheckpointId: number
    lastFailedCheckpointId: number
    openedAt: string
    closedAt: string | null
    recoveryCheckpointId: number | null
  }[]
}

export function getFlinkCheckpointAlerts(project: string, id: string, runId: string, signal: AbortSignal) {
  return get<FlinkCheckpointAlertHistory>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/checkpoint-alerts`,
    undefined, { signal })
}

export interface FlinkPressureAlerts {
  enabled: boolean
  alerts: {
    id: string
    vertexId: string
    peakPercent: number
    status: 'OPEN' | 'RECOVERED' | 'ENDED'
    openedAt: string
    closedAt: string | null
  }[]
}

export function getFlinkPressureAlerts(project: string, id: string, runId: string, signal: AbortSignal) {
  return get<FlinkPressureAlerts>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/pressure-alerts`,
    undefined, { signal })
}

export interface FlinkKafkaAlerts {
  available: boolean
  watch: {
    vertexId: string
    vertexName: string
    highThreshold: number
    lowThreshold: number
    enabled: boolean
    lastLag: number | null
    sampledAtMs: number
  } | null
  alerts: {
    id: string
    vertexName: string
    peakLag: number
    status: 'OPEN' | 'RECOVERED' | 'ENDED' | 'DISABLED'
    openedAt: string
    closedAt: string | null
  }[]
}

function kafkaAlertsPath(project: string, id: string, runId: string) {
  return `${asset(project, id)}/runs/${encodeURIComponent(runId)}/kafka-alerts`
}

export function getFlinkKafkaAlerts(project: string, id: string, runId: string, signal: AbortSignal) {
  return get<FlinkKafkaAlerts>(kafkaAlertsPath(project, id, runId), undefined, { signal })
}

export function configureFlinkKafkaAlerts(project: string, id: string, runId: string, rule:
  { enabled: false } | { enabled: true; vertexId: string; highThreshold: number; lowThreshold: number }) {
  return put<FlinkKafkaAlerts>(kafkaAlertsPath(project, id, runId), rule)
}

export interface FlinkEventTimeSample {
  vertexId: string
  status: string
  sampledAt: string
  vertexStartedAt: string | null
  parallelism: number
  inputRecords: number | null
  inputWatermark: string | null
}

export function getFlinkEventTime(project: string, id: string, runId: string, vertexId: string, signal: AbortSignal) {
  return get<FlinkEventTimeSample>(`${asset(project, id)}/runs/${encodeURIComponent(runId)}/event-time`,
    { vertexId }, { signal })
}

export interface FlinkEventTimeHistory {
  available: boolean
  watch: { vertexId: string; enabled: boolean } | null
  points: {
    vertexId: string
    sampledAtMs: number
    watermarkMs: number | null
    inputDelta: number | null
    diagnosis: 'BASELINE' | 'PROGRESSING' | 'IDLE' | 'WAITING' | 'STALLED' | 'UNKNOWN'
  }[]
  alerts: {
    id: string
    vertexId: string
    stalledWatermarkMs: number
    status: 'OPEN' | 'RECOVERED' | 'DISABLED' | 'ENDED'
    openedAt: string
    closedAt: string | null
  }[]
}

function eventTimeAlertsPath(project: string, id: string, runId: string) {
  return `${asset(project, id)}/runs/${encodeURIComponent(runId)}/event-time-alerts`
}

export function getFlinkEventTimeAlerts(project: string, id: string, runId: string, signal: AbortSignal) {
  return get<FlinkEventTimeHistory>(eventTimeAlertsPath(project, id, runId), undefined, { signal })
}

export function configureFlinkEventTimeAlerts(project: string, id: string, runId: string, enabled: boolean, vertexId?: string) {
  return put<FlinkEventTimeHistory>(eventTimeAlertsPath(project, id, runId), { enabled, vertexId })
}
