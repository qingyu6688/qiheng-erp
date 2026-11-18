import { request } from './http'
export { alertCenterCount as workbenchAlertCount } from './alerts'
export interface WorkbenchTodo {
  id: string
  kind?: string
  label: string
  documentNo: string
  partnerName?: string
  amount?: string
  businessDate?: string
  createdAt?: string
  url: string
}
export interface WorkbenchSummary {
  asOf: string
  blocks: { key: string; label: string; count: number; url: string }[]
  todos: WorkbenchTodo[]
  todosByType?: Record<string, WorkbenchTodo[]>
  announcements?: { id: string; title: string; level: string; publishAt: string }[]
  inventoryAlerts?: {
    warehouseId: string
    skuId: string
    warehouseName: string
    skuCode: string
    skuName: string
    availableQty: string
    pendingDemandQty: string
  }[]
  periods?: { id: string; period: string; status: string; startDate: string; endDate: string }[]
  recentActions?: { action: string; objectType: string; objectId: string; createdAt: string }[]
}
export function workbenchSummary(signal?: AbortSignal): Promise<WorkbenchSummary> {
  return request({ method: 'GET', url: '/workbench/summary', signal }, true)
}

export interface TradeAmounts {
  today: string
  yesterday: string
  month: string
  year: string
}

export interface TradeSummary {
  asOf: string
  dataSource: 'primary' | 'replica'
  sales?: TradeAmounts
  purchase?: TradeAmounts
  receivable?: string
  inventory?: string
  comparison?: Partial<Record<TradeMetric, { previousMonth: string; previousYear: string }>>
  trend: ({ month: string } & Partial<Record<TradeMetric, string>>)[]
}

export type TradeMetric = 'sales' | 'purchase' | 'receivable' | 'inventory'

export function tradeSummary(signal?: AbortSignal): Promise<TradeSummary> {
  return request(
    { method: 'GET', url: '/workbench/trade-summary', params: { months: 12 }, signal },
    true,
  )
}
