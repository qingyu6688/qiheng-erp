export type Id = string
export type Query = Record<string, string | number | boolean | undefined>

export interface Page<T> {
  items: T[]
  nextCursor: Id | null
}

export interface Company {
  id: Id
  code: string
  name: string
}

export interface Session {
  id: Id
  displayName: string
  mustChangePassword: boolean
  permissions: string[]
  companies: Company[]
}

export interface Device {
  id: Id
  token: string
  expiresAt: string
}

export interface PairingCode {
  pairingCode: string
  expiresAt: string
}

export interface ApiResponse<T> {
  data: T
  message?: string
  errorCode?: string
}

export interface ScanMatch {
  type: 'sku' | 'location' | 'batch' | 'serial' | 'box'
  id: Id
  code: string
  name?: string
  skuId?: Id
  warehouseId?: Id
  locationId?: Id
  batchId?: Id
  shipmentId?: Id
  locationCode?: string
}
