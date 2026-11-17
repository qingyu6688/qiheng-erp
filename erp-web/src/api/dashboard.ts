import { request } from './http'
import type { DashboardData, DashboardLayout, DashboardMetric } from '../types/dashboard'

export async function dashboardMetrics(): Promise<DashboardMetric[]> {
  const result = await request<{ items: DashboardMetric[] }>({ url: '/dashboard/metrics' }, true)
  return result.items
}

export function dashboardLayout(): Promise<DashboardLayout> {
  return request({ url: '/dashboard/layout' }, true)
}

export function saveDashboardLayout(version: number, metrics: string[]): Promise<DashboardLayout> {
  return request({ url: '/dashboard/layout', method: 'PUT', data: { version, metrics } }, true)
}

export function dashboardData(
  period: string,
  metrics: string[],
  refresh: boolean,
): Promise<DashboardData> {
  return request(
    { url: '/dashboard/data', params: { period, metrics: metrics.join(','), refresh } },
    true,
  )
}
