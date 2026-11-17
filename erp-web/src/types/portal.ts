import type { Id } from './api'

export interface PortalUser {
  id: Id
  companyName: string
  customerName: string
  contactName: string
}

export interface PortalRow {
  id?: Id
  documentNo?: string
  status?: string
  [field: string]: unknown
}

export interface PortalStatement {
  items: PortalRow[]
  digest: string
  confirmation?: Record<string, unknown> | null
  summary?: Record<string, unknown>
}

export interface PortalAnswer {
  conversationId: string
  answerId: string
  message: string
  topic: string
  refused: boolean
  handedOff: boolean
  sourcePath?: string | null
  items: PortalRow[]
  summary: Record<string, unknown>
}

export const portalViews = [
  { key: 'home', path: '/', label: '概况' },
  { key: 'orders', path: '/orders', label: '我的订单' },
  { key: 'shipments', path: '/shipments', label: '发货与物流' },
  { key: 'statements', path: '/statements', label: '对账' },
  { key: 'invoices', path: '/invoices', label: '发票' },
  { key: 'assistant', path: '/assistant', label: '在线客服' },
  { key: 'tickets', path: '/tickets', label: '客服工单' },
] as const

export type PortalView = (typeof portalViews)[number]['key']
