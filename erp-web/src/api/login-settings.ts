import type { LoginSettings, LoginSettingsInput } from '../types/login-settings'
import { request } from './http'

export function getLoginSettings(signal?: AbortSignal): Promise<LoginSettings> {
  return request({ method: 'GET', url: '/login-settings', signal })
}

export function saveLoginSettings(data: LoginSettingsInput): Promise<LoginSettings> {
  return request({ method: 'PUT', url: '/login-settings', data })
}
