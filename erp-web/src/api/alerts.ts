import { request } from './http'
import type { Id, PageResult } from '../types/api'

export type AlertLevel = 'info' | 'warning' | 'critical'
export type AlertState = 'open' | 'acknowledged' | 'resolved' | 'ignored'
export type AlertSchedule = 'realtime' | 'hourly' | 'daily'
export type AlertChannel = 'site' | 'email' | 'sms'
export type AlertAction = 'acknowledge' | 'ignore' | 'resolve'

export interface AlertRecord {
  id: Id
  ruleCode: string
  targetId: Id
  targetUrl: string
  warehouseId?: Id
  title: string
  detail: string
  level: AlertLevel
  status: AlertState
  occurrence: number
  firstSeenAt: string
  lastSeenAt: string
  handledAt?: string
  handleReason?: string
  version: number
}

export interface AlertQuery {
  rule?: string
  level?: AlertLevel
  status?: AlertState
  cursor?: Id
}

export interface AlertReceiver {
  type: 'owner' | 'department-leader' | 'user' | 'role' | 'role-code'
  id: Id | null
}

export interface AlertRuleUpdate {
  threshold: string | null
  enabled: boolean
  schedule: AlertSchedule
  severity: AlertLevel
  receivers: AlertReceiver[]
  channels: AlertChannel[]
  version: number
}

export interface AlertRule extends AlertRuleUpdate {
  code: string
  name: string
  thresholdUnit: string | null
  nextRunAt: string | null
}

export interface AlertSafetyRow {
  id: Id
  code: string
  name: string
  quantity: string
  version: number
}

export interface AlertReceiverOption {
  id: Id
  name: string
}

export function alertCenterCount(signal?: AbortSignal): Promise<{ total: number }> {
  return request({ method: 'GET', url: '/alerts/unhandled-count', signal }, true)
}

export function listAlerts(
  params: AlertQuery,
  signal?: AbortSignal,
): Promise<PageResult<AlertRecord>> {
  return request({ url: '/alerts', params, signal }, true)
}

export function alertDetail(id: Id, signal?: AbortSignal): Promise<AlertRecord> {
  return request({ url: '/alerts/' + encodeURIComponent(id), signal }, true)
}

export function handleAlert(
  id: Id,
  action: AlertAction,
  body: { version: number; reason?: string },
  key: string,
): Promise<AlertRecord> {
  return request(
    {
      url: '/alerts/' + encodeURIComponent(id) + '/' + action,
      method: 'POST',
      data: body,
      headers: { 'Idempotency-Key': key },
    },
    true,
  )
}

export function listAlertRules(signal?: AbortSignal): Promise<{ items: AlertRule[] }> {
  return request({ url: '/alerts/rules', signal }, true)
}

export function saveAlertRule(code: string, data: AlertRuleUpdate): Promise<AlertRule> {
  return request({ url: '/alerts/rules/' + encodeURIComponent(code), method: 'PUT', data }, true)
}

export function alertReceiverOptions(
  type: 'user' | 'role',
  keyword: string,
  signal?: AbortSignal,
): Promise<{ items: AlertReceiverOption[] }> {
  return request({ url: '/alerts/receiver-options', params: { type, keyword }, signal }, true)
}

export function listSafetyStock(
  keyword: string,
  cursor?: Id,
  signal?: AbortSignal,
): Promise<PageResult<AlertSafetyRow>> {
  return request({ url: '/alerts/safety-stock', params: { keyword, cursor }, signal }, true)
}

export function saveSafetyStock(
  id: Id,
  version: number,
  quantity: string,
): Promise<AlertSafetyRow> {
  return request(
    {
      url: '/alerts/safety-stock/' + encodeURIComponent(id),
      method: 'PUT',
      data: { version, quantity },
    },
    true,
  )
}
