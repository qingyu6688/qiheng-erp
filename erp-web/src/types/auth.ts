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

export type CodeLoginMethod = 'sms' | 'email'
export type AlternateLoginMethod = CodeLoginMethod | 'wechat'
export type SsoType = 'oidc' | 'ldap' | 'wecom' | 'dingtalk' | 'feishu'
export interface SsoMethod {
  id: SsoType
  type: SsoType
  name: string
}
export type LoginAvailability = Record<AlternateLoginMethod, boolean> & {
  password?: boolean
  sso?: SsoMethod[]
}

export interface CodeRequest {
  tenantCode: string
  method: CodeLoginMethod
  destination: string
}

export interface CodeLoginInput extends CodeRequest {
  code: string
}

export interface LoginBindings {
  sms: string | null
  email: string | null
  wechat: boolean
  methods: LoginAvailability
}
