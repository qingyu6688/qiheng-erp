import { ApiError } from '../api/http'
import type { AlternateLoginMethod, CodeLoginMethod } from '../types/auth'

export const methodLabels: Record<AlternateLoginMethod, string> = {
  sms: '手机短信',
  email: '邮箱',
  wechat: '微信',
}

export const alternateMethods: AlternateLoginMethod[] = ['sms', 'email', 'wechat']

export function contactLabel(method: CodeLoginMethod): string {
  return method === 'sms' ? '手机号' : '邮箱地址'
}

export function loginDestination(method: CodeLoginMethod, value: string): string {
  const destination = value.trim()
  if (method === 'sms') {
    if (!/^(?:\+86)?1[3-9]\d{9}$/.test(destination)) {
      throw new ApiError(400, 'VALIDATION_FAILED', '请输入有效的中国大陆手机号。')
    }
    return destination.startsWith('+86') ? destination : '+86' + destination
  }
  if (destination.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(destination)) {
    throw new ApiError(400, 'VALIDATION_FAILED', '请输入有效的邮箱地址。')
  }
  return destination.toLowerCase()
}

export function goToWechat(authorizationUrl: string): void {
  const url = new URL(authorizationUrl)
  if (
    url.origin !== 'https://open.weixin.qq.com' ||
    url.pathname !== '/connect/qrconnect' ||
    url.username ||
    url.password
  ) {
    throw new ApiError(502, 'INVALID_WECHAT_URL', '微信授权地址无效，请联系管理员。')
  }
  window.location.assign(url.href)
}
