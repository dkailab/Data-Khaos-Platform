import { del, get, post, put } from './request'
import type {
  NodeDebugResult, PageResult, WorkflowDef, WorkflowDependencyGraph, WorkflowGraph, WorkflowNodeRun,
  WorkflowPublishResult, WorkflowRecovery, WorkflowRevision, WorkflowRun, WorkflowSqlFile,
  WorkflowTaskView, WorkflowUpstreamView,
} from '@/types'

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

export function getDolphinSchedulerNodeState(runId: string, nodeRunId: string) {
  return get<string>(`/workflow/run/${runId}/nodes/${nodeRunId}/dolphinscheduler-state`)
}

/** 从失败的 DolphinScheduler 节点恢复；远端实例编号保持不变。 */
export function recoverDolphinSchedulerNode(runId: string, nodeRunId: string) {
  return post<WorkflowRecovery>(`/workflow/run/${runId}/nodes/${nodeRunId}/recover`)
}

/** 最近一次 DolphinScheduler 恢复记录 */
export function getDolphinSchedulerRecovery(runId: string, nodeRunId: string) {
  return get<WorkflowRecovery | null>(`/workflow/run/${runId}/nodes/${nodeRunId}/recovery`)
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

/* ------------------------------ 数据开发工作台 ------------------------------ */

/** 运行受理结果（startCodes 局部运行同样返回该结构） */
export interface WorkflowRunAccept {
  state: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED'
  runId?: string
  bizDate?: string
  rejectCode?: string
  rejectMessage?: string
  activeRunId?: string
}

/** 任务流下拉选项 */
export function listWorkflowOptions(projectId?: string) {
  return get<WorkflowDef[]>('/workflow/options', { projectId })
}

/** 发布（自动固化版本快照） */
export function publishWorkflow(id: string) {
  return post<WorkflowPublishResult>(`/workflow/${id}/publish`)
}

/** 下线 */
export function offlineWorkflow(id: string) {
  return post<WorkflowPublishResult>(`/workflow/${id}/offline`)
}

/** 任务列表（含最近运行状态） */
export function listWorkflowTasks(id: string) {
  return get<WorkflowTaskView[]>(`/workflow/${id}/tasks`)
}

/** 单节点调试 */
export function debugWorkflowNode(id: string, code: string, params: Record<string, any>) {
  return post<NodeDebugResult>(`/workflow/${id}/nodes/${encodeURIComponent(code)}/debug`, params,
    { timeout: 120000 })
}

/** 带业务日期与运行范围的运行受理 */
export function runWorkflow(id: string, payload: {
  requestId: string; bizDate: string; params?: Record<string, any>; startCodes?: string[]
}) {
  return post<WorkflowRunAccept>(`/workflow/${id}/runs`, payload)
}

/** SQL 文件资源列表 */
export function listWorkflowSqlFiles(projectId?: string, keyword?: string) {
  return get<WorkflowSqlFile[]>('/workflow/sql-file/list', { projectId, keyword })
}

export function getWorkflowSqlFile(id: string) {
  return get<WorkflowSqlFile>(`/workflow/sql-file/${id}`)
}

export function saveWorkflowSqlFile(payload: {
  id?: string; projectId?: string; folder?: string; name: string; description?: string
  datasourceId?: string; content?: string
}) {
  return post<WorkflowSqlFile>('/workflow/sql-file', payload)
}

export function publishWorkflowSqlFile(id: string) {
  return post<WorkflowSqlFile>(`/workflow/sql-file/${id}/publish`)
}

export function deleteWorkflowSqlFile(id: string) {
  return del<void>(`/workflow/sql-file/${id}`)
}

/** 上游任务流依赖 */
export function listWorkflowUpstreams(id: string) {
  return get<WorkflowUpstreamView[]>(`/workflow/${id}/upstreams`)
}

export function saveWorkflowUpstreams(id: string, items: Array<{
  upstreamWfId: string; dependStrategy: string; paramMapping?: string; enabled?: boolean
}>) {
  return put<WorkflowUpstreamView[]>(`/workflow/${id}/upstreams`, items)
}

/** 跨任务流依赖图 */
export function getWorkflowDependencyGraph(id: string, depth = 3) {
  return get<WorkflowDependencyGraph>(`/workflow/${id}/upstreams/graph`, { depth })
}
