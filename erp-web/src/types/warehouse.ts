import type { Id } from './api'

export type WarehouseStatus = 'active' | 'disabled'
export type WarehouseCommand = 'disable' | 'enable'
export interface WarehouseInput {
  code: string
  name: string
  address: string | null
}
export interface Warehouse extends WarehouseInput {
  id: Id
  status: WarehouseStatus
  version: number
  allowedActions: string[]
}
export interface WarehouseQuery {
  keyword?: string
  status?: WarehouseStatus
  cursor?: string
  limit: number
  withTotal: boolean
}
