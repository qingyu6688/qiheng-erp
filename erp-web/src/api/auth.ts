import type { LoginInput, UserSession } from '../types/auth'
import { request } from './http'

export function currentUser(): Promise<UserSession> {
  return request({ method: 'GET', url: '/auth/me' })
}

export function login(data: LoginInput): Promise<UserSession> {
  return request({ method: 'POST', url: '/auth/login', data })
}

export function logout(): Promise<unknown> {
  return request({ method: 'POST', url: '/auth/logout' })
}

export function changePassword(oldPassword: string, newPassword: string): Promise<unknown> {
  return request({ method: 'PUT', url: '/auth/password', data: { oldPassword, newPassword } })
}
