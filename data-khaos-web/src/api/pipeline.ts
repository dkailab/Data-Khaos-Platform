import { del, get, post, put } from './request'
import type { PageResult, PipelineEngineInfo, PipelineInstance, PipelineTask } from '@/types'

/** 分页查询管道任务 */
export function pagePipelineTasks(params: Record<string, any>) {
  return get<PageResult<PipelineTask>>('/pipeline/task/page', params)
}

/** 任务详情 */
export function getPipelineTask(id: string) {
  return get<PipelineTask>(`/pipeline/task/${id}`)
}

/** 新增任务 */
export function createPipelineTask(data: PipelineTask) {
  return post<void>('/pipeline/task', data)
}

/** 修改任务（后端为 PUT 无路径ID） */
export function updatePipelineTask(data: PipelineTask) {
  return put<void>('/pipeline/task', data)
}

/** 删除任务 */
export function deletePipelineTask(id: string) {
  return del<void>(`/pipeline/task/${id}`)
}

/** 手动触发运行 */
export function runPipelineTask(id: string, bizDate?: string) {
  return post<PipelineInstance>(`/pipeline/task/${encodeURIComponent(id)}/run`, undefined, { params: { bizDate } })
}

/** 引擎列表（可扩展） */
export function listPipelineEngines() {
  return get<PipelineEngineInfo[]>('/pipeline/task/engines')
}

/** 分页查询执行实例 */
export function pagePipelineInstances(params: Record<string, any>) {
  return get<PageResult<PipelineInstance>>('/pipeline/instance/page', params)
}

/** 某任务的历史实例 */
export function listPipelineInstanceByTask(taskId: string) {
  return get<PipelineInstance[]>(`/pipeline/instance/task/${taskId}`)
}
export interface PipelinePreflight {
  targetDatabaseName: string
  targetTable: string
  bizDate?: string
  writeMode: 'APPEND' | 'REPLACE_BY_BIZ_DATE'
  batchSize: number
  sampleFound: boolean
  mappings: {
    sourceColumn: string
    sourceType: string
    targetColumn: string
    targetType: string
    partitionColumn: boolean
  }[]
  warnings: string[]
}

export function preflightPipelineTask(id: string, bizDate?: string, signal?: AbortSignal) {
  return post<PipelinePreflight>(`/pipeline/task/${encodeURIComponent(id)}/preflight`, { bizDate }, { signal })
}
