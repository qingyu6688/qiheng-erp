import { request } from './http'

export async function downloadFile(url: string, name: string, company = true, signal?: AbortSignal): Promise<void> {
  const path = url.startsWith('/api/v1/') ? url.slice('/api/v1'.length) : url
  const blob = await request<Blob>({ method: 'GET', url: path, responseType: 'blob', timeout: 60000, signal }, company)
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = name
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
