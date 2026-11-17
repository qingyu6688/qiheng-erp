import { request } from './http'
import type { LoginInput, SsoType, UserSession } from '../types/auth'

export interface MfaSetup {
  secret: string
  uri: string
}
export interface MfaStatus {
  enabled: boolean
  required: boolean
}
export interface SsoProvider {
  id: SsoType
  enabled: boolean
  name: string
  config: Record<string, string>
  version: number
  secretConfigured?: boolean
}
export interface SecurityPolicy {
  mfaRequiredRoles: string[]
  passwordLoginAllowed: boolean
  ssoAutoProvision: boolean
  ssoDefaultRoleId: string | null
  version: number
}

export const ldapLogin = (data: LoginInput): Promise<UserSession> =>
  request({ method: 'POST', url: '/auth/ldap/login', data })
export const ssoStart = (id: SsoType, tenantCode: string): string =>
  `/api/v1/auth/sso/${encodeURIComponent(id)}/start?${new URLSearchParams({ tenantCode })}`
export const loginMfaStatus = (): Promise<{ step: 'verify' | 'enroll' }> =>
  request({ url: '/auth/mfa/status' })
export const loginMfaSetup = (): Promise<MfaSetup> =>
  request({ method: 'POST', url: '/auth/mfa/setup' })
export const verifyLoginMfa = (code: string): Promise<UserSession> =>
  request({ method: 'POST', url: '/auth/mfa/verify', data: { code } })
export const activateLoginMfa = (
  code: string,
): Promise<{ user: UserSession; recoveryCodes: string[] }> =>
  request({ method: 'POST', url: '/auth/mfa/activate', data: { code } })

export const accountMfa = (): Promise<MfaStatus> => request({ url: '/account/mfa' })
export const setupAccountMfa = (): Promise<MfaSetup> =>
  request({ method: 'POST', url: '/account/mfa/setup' })
export const activateAccountMfa = (code: string): Promise<{ recoveryCodes: string[] }> =>
  request({ method: 'POST', url: '/account/mfa/activate', data: { code } })
export const disableAccountMfa = (code: string): Promise<void> =>
  request({ method: 'POST', url: '/account/mfa/disable', data: { code } })
export const listProviders = (): Promise<SsoProvider[]> => request({ url: '/login-settings/sso' })
export const saveProvider = (
  id: SsoType,
  data: Omit<SsoProvider, 'id' | 'secretConfigured'> & { secret?: string },
): Promise<SsoProvider> => request({ method: 'PUT', url: '/login-settings/sso/' + id, data })
export const testProvider = (id: SsoType): Promise<unknown> =>
  request({ method: 'POST', url: '/login-settings/sso/' + id + '/test' })
export const readSecurityPolicy = (): Promise<SecurityPolicy> =>
  request({ url: '/sys/security-policy' })
export const saveSecurityPolicy = (data: SecurityPolicy): Promise<SecurityPolicy> =>
  request({ method: 'PUT', url: '/sys/security-policy', data })
export const bindIdentity = (user: string, method: SsoType, subject: string): Promise<void> =>
  request({
    method: 'PUT',
    url: `/sys/users/${user}/login-identities/${method}`,
    data: { subject },
  })
export const unbindIdentity = (user: string, method: SsoType): Promise<void> =>
  request({ method: 'DELETE', url: `/sys/users/${user}/login-identities/${method}` })
export const resetUserMfa = (user: string): Promise<void> =>
  request({ method: 'POST', url: `/sys/users/${user}/mfa/reset` })
