import type {
  AlternateLoginMethod,
  CodeLoginInput,
  CodeLoginMethod,
  CodeRequest,
  LoginAvailability,
  LoginBindings,
  LoginInput,
  UserSession,
} from '../types/auth'
import { request } from './http'

export function currentUser(): Promise<UserSession> {
  return request({ method: 'GET', url: '/auth/me' })
}

export function login(data: LoginInput): Promise<UserSession> {
  return request({ method: 'POST', url: '/auth/login', data })
}

export function availableLoginMethods(
  tenantCode: string,
  signal?: AbortSignal,
): Promise<LoginAvailability> {
  return request({ method: 'GET', url: '/auth/methods', params: { tenantCode }, signal })
}

export function sendLoginCode(data: CodeRequest): Promise<void> {
  return request({ method: 'POST', url: '/auth/code/send', data })
}

export function loginByCode(data: CodeLoginInput): Promise<UserSession> {
  return request({ method: 'POST', url: '/auth/code/login', data })
}

export function startWechatLogin(tenantCode: string): Promise<{ authorizationUrl: string }> {
  return request({ method: 'POST', url: '/auth/wechat/start', data: { tenantCode } })
}

export function loginBindings(): Promise<LoginBindings> {
  return request({ method: 'GET', url: '/account/login-methods' })
}

export function sendBindingCode(
  method: CodeLoginMethod,
  destination: string,
  password: string,
): Promise<void> {
  return request({
    method: 'POST',
    url: '/account/login-methods/code/send',
    data: { method, destination, password },
  })
}

export function bindContact(
  method: CodeLoginMethod,
  destination: string,
  code: string,
): Promise<void> {
  return request({
    method: 'PUT',
    url: '/account/login-methods/code',
    data: { method, destination, code },
  })
}

export function removeBinding(method: AlternateLoginMethod, password: string): Promise<void> {
  return request({ method: 'DELETE', url: '/account/login-methods/' + method, data: { password } })
}

export function startWechatBinding(password: string): Promise<{ authorizationUrl: string }> {
  return request({ method: 'POST', url: '/account/login-methods/wechat/start', data: { password } })
}

export function logout(): Promise<unknown> {
  return request({ method: 'POST', url: '/auth/logout' })
}

export function changePassword(oldPassword: string, newPassword: string): Promise<unknown> {
  return request({ method: 'PUT', url: '/auth/password', data: { oldPassword, newPassword } })
}
