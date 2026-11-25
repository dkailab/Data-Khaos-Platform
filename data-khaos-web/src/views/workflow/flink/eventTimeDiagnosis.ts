import type { FlinkEventTimeSample } from '@/api/flinkStudio'

export const EVENT_TIME_INTERVAL_MS = 15_000
export const EVENT_TIME_SAMPLE_LIMIT = 12

export type EventTimeState = 'BASELINE' | 'PROGRESSING' | 'IDLE' | 'WAITING' | 'STALLED' | 'UNKNOWN'

export const eventTimeLabels = {
  BASELINE: '建立采样基线', PROGRESSING: '水位线推进', IDLE: '未观察到新输入',
  WAITING: '有输入，水位线未推进', STALLED: '有输入，水位线持续停滞', UNKNOWN: '指标暂不可判定',
} as const

export interface EventTimePoint {
  state: EventTimeState
  delta: number | null
  stalledIntervals: number
  watermarkAgeMs: number | null
}

export function diagnoseEventTime(
  previous: FlinkEventTimeSample | undefined,
  current: FlinkEventTimeSample,
  priorStreak: number,
): EventTimePoint {
  const sampledAt = Date.parse(current.sampledAt)
  const watermark = current.inputWatermark == null ? NaN : Date.parse(current.inputWatermark)
  const watermarkAgeMs = Number.isFinite(sampledAt) && Number.isFinite(watermark) ? sampledAt - watermark : null
  const result = (state: EventTimeState, delta: number | null = null, stalledIntervals = 0): EventTimePoint =>
    ({ state, delta, stalledIntervals, watermarkAgeMs })
  if (current.status !== 'RUNNING' || current.vertexStartedAt == null || current.parallelism < 1
    || current.inputRecords == null || !Number.isSafeInteger(current.inputRecords) || current.inputRecords < 0
    || watermarkAgeMs == null) return result('UNKNOWN')
  if (!previous || previous.vertexId !== current.vertexId || previous.status !== 'RUNNING'
    || previous.vertexStartedAt !== current.vertexStartedAt || previous.parallelism !== current.parallelism
    || previous.inputRecords == null || !Number.isSafeInteger(previous.inputRecords)
    || previous.inputWatermark == null) return result('BASELINE')

  const previousWatermark = Date.parse(previous.inputWatermark)
  const elapsed = sampledAt - Date.parse(previous.sampledAt)
  const delta = current.inputRecords - previous.inputRecords
  if (!Number.isFinite(elapsed) || elapsed < EVENT_TIME_INTERVAL_MS / 2 || elapsed > EVENT_TIME_INTERVAL_MS * 3
    || delta < 0 || !Number.isFinite(previousWatermark) || watermark < previousWatermark) return result('BASELINE')
  if (watermark > previousWatermark) return result('PROGRESSING', delta)
  if (delta === 0) return result('IDLE', delta)
  const streak = priorStreak + 1
  return result(streak >= 3 ? 'STALLED' : 'WAITING', delta, streak)
}
