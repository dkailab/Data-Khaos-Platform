import { get, post } from './request'
import type { PageResult } from '@/types'

export type PlatformServiceState = 'UP' | 'DEGRADED' | 'DOWN' | 'UNKNOWN'

export interface PlatformServiceStatus {
  serviceId: string
  serviceName: string
  status: PlatformServiceState
  registeredCount: number
  reachableCount: number
  checkedAt?: string
  reason?: string
  activeAlertId?: string
}

export interface PlatformAlert {
  id: string
  serviceId: string
  serviceName: string
  openedAt: string
  acknowledgedAt?: string
  recoveredAt?: string
  reason: string
}

export function listServiceStatuses() {
  return get<PlatformServiceStatus[]>('/notify/monitor/services')
}

export function listPlatformAlerts(params: { current: number; size: number; openOnly: boolean }) {
  return get<PageResult<PlatformAlert>>('/notify/monitor/alerts', params)
}

export function acknowledgePlatformAlert(id: string) {
  return post<PlatformAlert>(`/notify/monitor/alerts/${id}/acknowledge`)
}
