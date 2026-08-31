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

export function assignMembers(projectGroupId: string, assigns: Array<{ userId: string; roleId?: string; capabilityFlags?: string[] }>) {
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
