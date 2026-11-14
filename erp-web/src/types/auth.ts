import type { Id } from './api'

export interface Company {
  id: Id
  code: string
  name: string
  allWarehouses: boolean
  warehouseIds: Id[]
}

export interface UserSession {
  id: Id
  username: string
  displayName: string
  mustChangePassword: boolean
  permissions: string[]
  companies: Company[]
}

export interface LoginInput {
  tenantCode: string
  username: string
  password: string
}
