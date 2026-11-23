import { del, get, post, put } from './request'
import type { PageResult, WorkflowDef, WorkflowGraph, WorkflowNodeRun, WorkflowRevision, WorkflowRun } from '@/types'

/** 保存工作流图（新增/整体更新） */
export function saveWorkflowGraph(data: WorkflowGraph) {
  return post<WorkflowDef>('/workflow', data)
}

/** 工作流详情（含节点与连线） */
export function getWorkflowGraph(id: string) {
  return get<WorkflowGraph>(`/workflow/${id}`)
}

/** 分页列表 */
export function pageWorkflows(params: Record<string, any>) {
  return get<PageResult<WorkflowDef>>('/workflow/page', params)
}

/** 删除工作流 */
export function deleteWorkflow(id: string) {
  return del<void>(`/workflow/${id}`)
}

/** 更新状态 0:禁用 1:启用 */
export function updateWorkflowStatus(id: string, status: number) {
  return put<void>(`/workflow/${id}/status?status=${status}`)
}

/** 触发一次手动运行 */
export function triggerWorkflow(id: string, params?: Record<string, any>) {
  return post<string>(`/workflow/${id}/trigger`, params || {})
}

/** 运行实例分页列表 */
export function pageWorkflowRuns(params: Record<string, any>) {
  return get<WorkflowRun[]>('/workflow/run/page', params)
}

/** 运行实例详情 */
export function getWorkflowRun(runId: string) {
  return get<WorkflowRun>(`/workflow/run/${runId}`)
}

/** 某次运行的节点执行记录 */
export function listWorkflowRunNodes(runId: string) {
  return get<WorkflowNodeRun[]>(`/workflow/run/${runId}/nodes`)
}

export function pageWorkflowRevisions(id: string, current: number, size: number) {
  return get<PageResult<WorkflowRevision>>(`/workflow/${id}/revisions`, { current, size })
}

export function createWorkflowRevision(id: string, note: string) {
  return post<WorkflowRevision>(`/workflow/${id}/revisions`, { note })
}

export function createWorkflowRevisionDraft(id: string, revisionId: string, name: string) {
  return post<WorkflowDef>(`/workflow/${id}/revisions/${revisionId}/draft`, { name })
}
