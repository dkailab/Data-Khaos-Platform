import { del, get, post, put } from './request'
import type { MetaDictItem, MetaDictType, MetaStandard, PageResult } from '@/types'

// ---------------- 数据字典类型 ----------------

/** 字典类型分页 */
export function pageDictTypes(params: Record<string, any>) {
  return get<PageResult<MetaDictType>>('/meta/dict/type/page', params)
}

/** 字典类型全部列表（下拉） */
export function listDictTypes(keyword?: string) {
  return get<MetaDictType[]>('/meta/dict/type/list', { keyword })
}

export function createDictType(data: MetaDictType) {
  return post<void>('/meta/dict/type', data)
}

export function updateDictType(id: string, data: MetaDictType) {
  return put<void>(`/meta/dict/type/${id}`, data)
}

export function deleteDictType(id: string) {
  return del<void>(`/meta/dict/type/${id}`)
}

// ---------------- 数据字典项 ----------------

/** 字典项分页 */
export function pageDictItems(params: Record<string, any>) {
  return get<PageResult<MetaDictItem>>('/meta/dict/item/page', params)
}

/** 按字典类型编码取全部启用项 */
export function listDictItemsByType(typeCode: string) {
  return get<MetaDictItem[]>('/meta/dict/item/list', { typeCode })
}

export function createDictItem(data: MetaDictItem) {
  return post<void>('/meta/dict/item', data)
}

export function updateDictItem(id: string, data: MetaDictItem) {
  return put<void>(`/meta/dict/item/${id}`, data)
}

export function deleteDictItem(id: string) {
  return del<void>(`/meta/dict/item/${id}`)
}

// ---------------- 数据标准 ----------------

/** 数据标准分页 */
export function pageStandards(params: Record<string, any>) {
  return get<PageResult<MetaStandard>>('/meta/standard/page', params)
}

/** 全部启用标准（下拉） */
export function listStandards() {
  return get<MetaStandard[]>('/meta/standard/list')
}

export function createStandard(data: MetaStandard) {
  return post<void>('/meta/standard', data)
}

export function updateStandard(id: string, data: MetaStandard) {
  return put<void>(`/meta/standard/${id}`, data)
}

export function deleteStandard(id: string) {
  return del<void>(`/meta/standard/${id}`)
}