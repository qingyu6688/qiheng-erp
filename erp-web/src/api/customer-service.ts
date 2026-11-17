import { request } from './http'
import type { DataRow } from '../types/admin'
import type { Id, PageResult } from '../types/api'

export interface TicketDetail {
  ticket: DataRow
  replies: DataRow[]
}
export interface PortalAccountInput {
  partnerId: Id
  contactId: Id
  username: string
  password: string
}
export interface TicketAssist {
  customerName?: string
  recentOrders?: DataRow[]
  recentShipments?: DataRow[]
  openReceivable?: string
  replySuggestion?: string
  suggestionSource?: string
}

export function customerTickets(cursor?: string): Promise<PageResult<DataRow>> {
  return request({ url: '/cs/tickets', params: { cursor } }, true)
}

export function customerTicket(id: Id): Promise<TicketDetail> {
  return request({ url: '/cs/tickets/' + encodeURIComponent(id) }, true)
}

export function ticketAssist(id: Id): Promise<TicketAssist> {
  return request({ url: '/cs/tickets/' + encodeURIComponent(id) + '/assist' }, true)
}

export function ticketAction(
  id: Id,
  action: 'assign' | 'reply' | 'resolve' | 'close',
  data: Record<string, unknown>,
): Promise<TicketDetail> {
  return request(
    { method: 'POST', url: '/cs/tickets/' + encodeURIComponent(id) + '/' + action, data },
    true,
  )
}

export function portalAccounts(cursor?: string): Promise<PageResult<DataRow>> {
  return request({ url: '/portal-users', params: { cursor } }, true)
}

export function addPortalAccount(input: PortalAccountInput): Promise<DataRow> {
  return request({ method: 'POST', url: '/portal-users', data: input }, true)
}

export function updatePortalAccount(
  id: Id,
  input: { version: number; status: string; password?: string },
): Promise<DataRow> {
  return request(
    { method: 'PUT', url: '/portal-users/' + encodeURIComponent(id), data: input },
    true,
  )
}

export async function portalContactChoices(partnerId: Id): Promise<DataRow[]> {
  const result = await request<{ items?: DataRow[] } | DataRow[]>(
    { url: '/crm/customers/' + encodeURIComponent(partnerId) + '/contacts' },
    true,
  )
  return Array.isArray(result) ? result : (result.items ?? [])
}

export interface SensitiveWord {
  id: Id
  word: string
  enabled: boolean
  version: number
}
export function sensitiveWords(): Promise<SensitiveWord[]> {
  return request({ url: '/cs/sensitive-words' }, true)
}
export function saveSensitiveWord(
  input: { word: string; enabled: boolean; version?: number },
  id?: Id,
): Promise<SensitiveWord> {
  return request(
    {
      url: id ? '/cs/sensitive-words/' + encodeURIComponent(id) : '/cs/sensitive-words',
      method: id ? 'PUT' : 'POST',
      data: input,
    },
    true,
  )
}
export function removeSensitiveWord(id: Id, version: number): Promise<unknown> {
  return request(
    { url: '/cs/sensitive-words/' + encodeURIComponent(id), method: 'DELETE', params: { version } },
    true,
  )
}
