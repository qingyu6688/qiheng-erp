import { newIdempotencyKey, request } from './http'
import type { DataRow } from '../types/admin'

export interface Page<T> {
  items: T[]
  nextCursor: string | null
}
export interface BusinessUser {
  id: string
  name: string
}
export interface AreaNode {
  id: string
  name: string
  parentId: string
  children?: AreaNode[]
}
export interface DictionaryItem {
  id: string
  value: string
  label: string
  color: string
  enabled: boolean | number
}
export interface Dictionaries {
  version: string
  unchanged: boolean
  items?: Record<string, DictionaryItem[]>
}
export interface MessageRow extends DataRow {
  title: string
  content: string
  category: string
  link?: string
  readAt?: string
  createdAt: string
}
export interface ReceivedNotice extends DataRow {
  title: string
  content: string
  level: string
  confirmed: boolean | number
}
export interface Subscription {
  category: string
  channel: string
  enabled: boolean | number
  version: number
}

export function platformPage(
  path: string,
  params: Record<string, unknown> = {},
  company = false,
): Promise<Page<DataRow>> {
  return request({ url: '/sys/' + path, params }, company)
}
export function platformList(path: string): Promise<DataRow[]> {
  return request({ url: '/sys/' + path })
}
export function platformGet<T>(path: string): Promise<T> {
  return request({ url: '/sys/' + path })
}
export function platformWrite<T>(
  path: string,
  data: unknown,
  method = 'PUT',
  key = newIdempotencyKey(),
  company = false,
): Promise<T> {
  return request(
    { url: '/sys/' + path, method, data, headers: { 'Idempotency-Key': key } },
    company,
  )
}
export function businessUsers(keyword = ''): Promise<Page<BusinessUser>> {
  return request({ url: '/org/business-users', params: { keyword, limit: 200 } }, true)
}
export function areaTree(): Promise<{ version: string; items: AreaNode[] }> {
  return request({ url: '/areas/tree' })
}
export function dictionaries(types: string, version?: string): Promise<Dictionaries> {
  return request({ url: '/dicts', params: { types, version } })
}
export function inbox(params: Record<string, unknown> = {}): Promise<Page<MessageRow>> {
  return request({ url: '/notify/messages', params }, true)
}
export function unreadCounts(): Promise<Record<string, number>> {
  return request({ url: '/notify/unread-count' }, true)
}
export function readMessage(id: string): Promise<{ id: string; read: boolean }> {
  return request({ url: '/notify/messages/' + id + '/read', method: 'PUT' }, true)
}
export function receivedNotice(id: string): Promise<ReceivedNotice> {
  return request({ url: '/notify/notices/' + id }, true)
}
export function importantNotices(): Promise<{ id: string; title: string }[]> {
  return request({ url: '/notify/important-notices' }, true)
}
export function subscriptions(): Promise<Subscription[]> {
  return request({ url: '/notify/subscriptions' })
}
export function saveSubscription(
  input: Subscription,
  key = newIdempotencyKey(),
): Promise<Subscription> {
  return request({
    url: '/notify/subscriptions',
    method: 'PUT',
    data: input,
    headers: { 'Idempotency-Key': key },
  })
}

export function confirmAnnouncement(id: string): Promise<{ id: string; confirmed: boolean }> {
  return request({ url: '/notify/notices/' + id + '/confirm', method: 'PUT' }, true)
}
