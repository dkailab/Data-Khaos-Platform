import { del, get, post, put } from './request'
import type { NotifyRecord, NotifySubscription, NotifyTemplate, PageResult, SendRequest } from '@/types'

/* ==================== 模板 ==================== */

export function pageTemplates(params: Record<string, any>) {
  return get<PageResult<NotifyTemplate>>('/notify/template/page', params)
}

export function createTemplate(data: NotifyTemplate) {
  return post<void>('/notify/template', data)
}

export function updateTemplate(data: NotifyTemplate) {
  return put<void>('/notify/template', data)
}

export function deleteTemplate(id: string) {
  return del<void>(`/notify/template/${id}`)
}

/* ==================== 发送 / 记录 ==================== */

export function sendNotify(data: SendRequest) {
  return post<NotifyRecord>('/notify/send', data)
}

export function pageRecords(params: Record<string, any>) {
  return get<PageResult<NotifyRecord>>('/notify/record/page', params)
}

/* ==================== 订阅 ==================== */

export function userSubscriptions(userId: string) {
  return get<NotifySubscription[]>(`/notify/subscription/user/${userId}`)
}

export function subscribeNotify(data: NotifySubscription) {
  return post<void>('/notify/subscription', data)
}

export function unsubscribeNotify(id: string) {
  return del<void>(`/notify/subscription/${id}`)
}

export interface InboxMessage {
  id: string
  title: string
  content: string
  sendTime: string | null
}

export function pageInbox(current: number, size: number, signal: AbortSignal) {
  return get<PageResult<InboxMessage>>('/notify/inbox', { current, size }, { signal })
}
