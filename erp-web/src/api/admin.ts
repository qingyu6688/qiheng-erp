import type { PageResult } from '../types/api'
import type { DataRow } from '../types/admin'
import { request } from './http'

export async function records(
  path: string,
  company = false,
  params: Record<string, unknown> = {},
  companyId?: string,
  signal?: AbortSignal,
): Promise<PageResult<DataRow>> {
  const result = await request<PageResult<DataRow> | DataRow[]>(
    {
      method: 'GET',
      url: '/' + path,
      params,
      headers: companyId ? { 'X-Company-Id': companyId } : undefined,
      signal,
    },
    company,
  )
  return Array.isArray(result) ? { items: result, nextCursor: null } : result
}

export function record(path: string, company = false): Promise<DataRow> {
  return request({ method: 'GET', url: '/' + path }, company)
}

export async function allRecords(
  path: string,
  company = false,
  companyId?: string,
): Promise<DataRow[]> {
  const result: DataRow[] = []
  const visited = new Set<string>()
  let cursor: string | null = null
  do {
    const page = await records(
      path,
      company,
      { limit: 200, cursor: cursor || undefined },
      companyId,
    )
    result.push(...page.items)
    cursor = page.nextCursor
    if (cursor && visited.has(cursor)) throw new Error('资料分页异常，请重新加载')
    if (cursor) visited.add(cursor)
  } while (cursor)
  return result
}

export function writeRecord(
  path: string,
  data: Record<string, unknown>,
  method: 'POST' | 'PUT' | 'DELETE',
  company = false,
  key?: string,
): Promise<DataRow> {
  return request(
    {
      method,
      url: '/' + path,
      data,
      headers: key ? { 'Idempotency-Key': key } : undefined,
    },
    company,
  )
}
