import { request } from './http'

export interface Capability {
  code: string
  label: string
}
export interface AppInput {
  name: string
  companyIds: string[]
  scopes: string[]
  ipWhitelist: string[]
  rateLimitPerMinute: number
  enabled: boolean
  expiresAt: string | null
  version?: number
}
export interface OpenApp extends AppInput {
  id: string
  appKey: string
  secret?: string
}
export interface HookInput {
  appId: string
  url: string
  events: string[]
  enabled: boolean
  version?: number
}
export interface OpenHook extends HookInput {
  id: string
  consecutiveFailures: number
  secret?: string
}
export interface OpenCall {
  id: string
  appId: string
  companyId: string | null
  method: string
  pathTemplate: string
  status: number
  latencyMs: number
  createdAt: string
}
export interface OpenDelivery {
  id: string
  webhookId: string
  eventId: string
  companyId: string | null
  eventType: string
  status: string
  attempts: number
  nextAttemptAt: string | null
  lastStatusCode: number | null
  createdAt: string
}
export interface OpenSecret {
  id: string
  appKey?: string
  secret: string
}

const base = '/open-platform'
export const openCapabilities = (): Promise<{ scopes: Capability[]; events: Capability[] }> =>
  request({ url: base + '/capabilities' })
export const openApps = (): Promise<OpenApp[]> => request({ url: base + '/apps' })
export const openHooks = (): Promise<OpenHook[]> => request({ url: base + '/webhooks' })
export const openCalls = (appId?: string): Promise<OpenCall[]> =>
  request({ url: base + '/calls', params: { appId } })
export const openDeliveries = (webhookId?: string): Promise<OpenDelivery[]> =>
  request({ url: base + '/deliveries', params: { webhookId } })
export const openSpec = (): Promise<Record<string, unknown>> => request({ url: base + '/spec' })
export const writeOpenApp = (data: AppInput, id?: string): Promise<OpenApp> =>
  request({ method: id ? 'PUT' : 'POST', url: base + '/apps' + (id ? '/' + id : ''), data })
export const writeOpenHook = (data: HookInput, id?: string): Promise<OpenHook> =>
  request({ method: id ? 'PUT' : 'POST', url: base + '/webhooks' + (id ? '/' + id : ''), data })
export const rotateOpenSecret = (kind: 'apps' | 'webhooks', id: string): Promise<OpenSecret> =>
  request({ method: 'POST', url: `${base}/${kind}/${id}/rotate-secret` })
export const testOpenHook = (id: string): Promise<unknown> =>
  request({ method: 'POST', url: `${base}/webhooks/${id}/test` })
export const retryOpenDelivery = (id: string): Promise<unknown> =>
  request({ method: 'POST', url: `${base}/deliveries/${id}/retry` })
