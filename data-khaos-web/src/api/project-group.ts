import { del, get, post, put } from './request'
import type { PageResult, SgProjectGroup } from '@/types'

// ==================== 项目组 CRUD ====================

export function pageProjectGroups(params: { current: number; size: number; orgId?: string; keyword?: string }) {
  return get<PageResult<SgProjectGroup>>('/permission/project-group/page', params)
}

export function createProjectGroup(data: Partial<SgProjectGroup>) {
  return post<void>('/permission/project-group', data)
}

export function updateProjectGroup(id: string, data: Partial<SgProjectGroup>) {
  return put<void>(`/permission/project-group/${id}`, data)
}

export function deleteProjectGroup(id: string) {
  return del<void>(`/permission/project-group/${id}`)
}

// ==================== 成员管理 ====================

export function listMembers(projectGroupId: string) {
  return get<Array<Record<string, any>>>(`/permission/project-group/${projectGroupId}/members`)
}

export function assignMembers(projectGroupId: string, assigns: Array<{ userId: string; projectRoleId: string; primary?: boolean }>) {
  return put<void>(`/permission/project-group/${projectGroupId}/members`, assigns)
}

// ==================== 角色 / 能力位 ====================

export interface SgProjectRole {
  id?: string
  orgId?: string
  projectGroupId?: string
  roleName: string
  roleCode: string
  capabilityFlags?: string
  status?: number
  sortOrder?: number
  createTime?: string
}

export function pageRoles(params: { id: string; current: number; size: number }) {
  const { id, ...rest } = params
  return get<PageResult<SgProjectRole>>(`/permission/project-group/${id}/roles/page`, rest)
}

export function createRole(data: Partial<SgProjectRole>) {
  return post<void>('/permission/project-group/role', data)
}

export function updateRole(id: string, data: Partial<SgProjectRole>) {
  return put<void>(`/permission/project-group/role/${id}`, data)
}

export function deleteRole(id: string) {
  return del<void>(`/permission/project-group/role/${id}`)
}

// ==================== 资源绑定 ====================

export interface SgProjectGroupResource {
  id?: string
  projectGroupId?: string
  resourceType: string
  resourceId: string
}

export function listResources(projectGroupId: string) {
  return get<SgProjectGroupResource[]>(`/permission/project-group/${projectGroupId}/resources`)
}

export function bindResources(projectGroupId: string, resources: Partial<SgProjectGroupResource>[]) {
  return post<void>(`/permission/project-group/${projectGroupId}/resources`, resources)
}

export function deleteResource(id: string) {
  return del<void>(`/permission/project-group/resource/${id}`)
}

// ==================== 权限视图 ====================

export function getUserProjectGroups(userId: string) {
  return get<Array<Record<string, any>>>(`/permission/project-group/user/${userId}`)
}

export function getCurrentGroup(userId: string) {
  return get<Record<string, any>>(`/permission/project-group/user/${userId}/current`)
}

export function getUserCapabilities(userId: string, projectGroupId?: string) {
  return get<string[]>(`/permission/project-group/user/${userId}/capability`, projectGroupId ? { projectGroupId } : undefined)
}

export interface ProjectWorkspace {
  projectGroupId: string
  name: string
  primary: boolean
  roleCode?: string
  capabilities: string[]
}

export interface ProjectMember {
  memberId: string
  userId: string
  projectRoleId: string
  roleName: string
  primary: boolean
}

export interface ProjectWorkspaceResource extends SgProjectGroupResource {
  name?: string
  available?: boolean
}

export function listWorkspaceResources(id: string) {
  return get<ProjectWorkspaceResource[]>(`/permission/project-group/${encodeURIComponent(id)}/workspace/resources`)
}

export function listMyProjectWorkspaces() {
  return get<ProjectWorkspace[]>('/permission/project-group/me')
}

export function getProjectWorkspace(id: string) {
  return get<ProjectWorkspace>(`/permission/project-group/${encodeURIComponent(id)}/workspace`)
}

export function listWorkspaceMembers(id: string) {
  return get<ProjectMember[]>(`/permission/project-group/${encodeURIComponent(id)}/members`)
}

export function listAssignableRoles(id: string) {
  return get<SgProjectRole[]>(`/permission/project-group/${encodeURIComponent(id)}/assignable-roles`)
}

export function setWorkspaceMember(id: string, userId: string, projectRoleId: string) {
  return put<void>(`/permission/project-group/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`, {
    projectRoleId,
  })
}

export function removeWorkspaceMember(id: string, userId: string) {
  return del<void>(`/permission/project-group/${encodeURIComponent(id)}/members/${encodeURIComponent(userId)}`)
}

export type ProjectActivityKind = 'WORKFLOW' | 'PIPELINE'
export type ProjectRunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'STOP' | 'UNKNOWN' | 'RECOVERY_REQUIRED'
export type ProjectRunTrigger = 'MANUAL' | 'SCHEDULE' | 'BACKFILL' | 'WORKFLOW' | 'CRON'

export interface ProjectWorkspaceTask {
  id: string
  kind: ProjectActivityKind
  name: string
  status: string
  updatedAt?: string
}

export interface ProjectWorkspaceRun {
  id: string
  kind: ProjectActivityKind
  taskId: string
  taskName?: string
  status?: string
  bizDate?: string
  startedAt?: string
  durationMs?: number
  triggerType?: string
  alertAcknowledgedAt?: string
}

interface ActivityPage {
  current: number
  size: number
  kind?: ProjectActivityKind
}

export function pageWorkspaceTasks(id: string, params: ActivityPage & { keyword?: string }) {
  return get<PageResult<ProjectWorkspaceTask>>(`/permission/project-group/${encodeURIComponent(id)}/workspace/tasks`, params)
}

export function pageWorkspaceRuns(id: string, params: ActivityPage & {
  bizDate?: string; status?: ProjectRunStatus; taskId?: string; triggerType?: ProjectRunTrigger
  alertsOnly?: boolean; unacknowledgedOnly?: boolean
}) {
  return get<PageResult<ProjectWorkspaceRun>>(`/permission/project-group/${encodeURIComponent(id)}/workspace/runs`, params)
}

export interface ProjectWorkspaceTaskDetail extends ProjectWorkspaceTask {
  engine?: string
  sourceName?: string
  targetName?: string
  workspaceRunnable: boolean
  nodes: { code: string; name?: string; type?: string }[]
}

export interface ProjectWorkspaceRunDetail extends ProjectWorkspaceRun {
  endedAt?: string
  failureReason?: string
  recoveryCode?: string
  recoveryResolvedAt?: string
  recoveryResolvedBy?: string
  recoveryNote?: string
  nodes: {
    code: string
    name?: string
    type?: string
    status?: string
    durationMs?: number
    startedAt?: string
    endedAt?: string
    refType?: string
    refId?: string
  }[]
}

export function getWorkspaceTask(id: string, kind: ProjectActivityKind, taskId: string) {
  return get<ProjectWorkspaceTaskDetail>(
    `/permission/project-group/${encodeURIComponent(id)}/workspace/tasks/${kind}/${encodeURIComponent(taskId)}`,
  )
}

export function getWorkspaceRun(id: string, kind: ProjectActivityKind, runId: string) {
  return get<ProjectWorkspaceRunDetail>(
    `/permission/project-group/${encodeURIComponent(id)}/workspace/runs/${kind}/${encodeURIComponent(runId)}`,
  )
}

export interface WorkspacePipelineRunReceipt {
  runId: string
  bizDate: string
  state: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED'
  status: number
}

export function submitWorkspacePipelineRun(projectId: string, taskId: string, requestId: string, bizDate: string) {
  return post<WorkspacePipelineRunReceipt>(
    `/pipeline/workspaces/${encodeURIComponent(projectId)}/tasks/${encodeURIComponent(taskId)}/runs`,
    { requestId, bizDate },
  )
}


export interface WorkflowBackfillReceipt {
  requestId: string
  items: {
    bizDate?: string
    state: 'ACCEPTED' | 'DUPLICATE' | 'REJECTED'
    runId?: string
    activeRunId?: string
    rejectCode?: string
    rejectMessage?: string
  }[]
}

export function submitWorkspaceBackfill(projectId: string, taskId: string, requestId: string, fromDate: string, toDate: string) {
  return post<WorkflowBackfillReceipt>(
    `/workflow/${encodeURIComponent(taskId)}/backfills`,
    { requestId, fromDate, toDate },
    { headers: { 'X-Project-Group-Id': projectId } },
  )
}


export function acknowledgeWorkspaceRun(projectId: string, runId: string) {
  return post<void>(`/permission/project-group/${encodeURIComponent(projectId)}/workspace/runs/WORKFLOW/${encodeURIComponent(runId)}/acknowledge`)
}

export function resolveWorkspaceRun(projectId: string, runId: string, note: string) {
  return post<void>(
    `/permission/project-group/${encodeURIComponent(projectId)}/workspace/runs/WORKFLOW/${encodeURIComponent(runId)}/resolve-recovery`,
    { note },
  )
}
