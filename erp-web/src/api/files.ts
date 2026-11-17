import { request } from './http'

export async function downloadFile(
  url: string,
  name: string,
  company = true,
  signal?: AbortSignal,
): Promise<void> {
  const path = url.startsWith('/api/v1/') ? url.slice('/api/v1'.length) : url
  const blob = await request<Blob>(
    { method: 'GET', url: path, responseType: 'blob', timeout: 60000, signal },
    company,
  )
  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  // 后台财务文件可以是XLSX，列表的旧CSV默认名不能改变实际格式。
  link.download =
    blob.type.split(';')[0] === 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
      ? name.replace(/\.csv$/i, '.xlsx')
      : blob.type.split(';')[0] === 'application/pdf' && name.toLowerCase().endsWith('.csv')
        ? name.replace(/\.csv$/i, '.pdf')
        : name
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000)
}
