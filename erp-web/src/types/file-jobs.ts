export interface FileJob {
  id: string
  jobType?: string
  exportType?: string
  status: string
  totalRows?: number
  successRows?: number
  failedRows?: number
  checkpointRow?: number
  rowCount?: number
  createdAt?: string
  finishedAt?: string
  expireAt?: string
  errorMessage?: string
  canRetry?: boolean
  attempts?: number
  nextAttemptAt?: string
  downloadUrl?: string
  targets?: { rowNo: number; targetType: string; targetId: string }[]
}
export interface ImportError { id: string; rowNo: number; columnName: string; message: string }
export const importSpecs = [{ type: 'sku', title: '商品 SKU', permission: 'sku:create' }, { type: 'partner', title: '往来单位', permission: 'partner:create' }, { type: 'opening_stock', title: '库存期初', permission: 'stock_adjustment:create' }]
export const jobStatus: Record<string, string> = { pending: '等待处理', running: '正在处理', succeeded: '已完成', partial: '部分成功', failed: '失败', expired: '文件已过期' }
export function csvCell(value: unknown): string { const text = String(value ?? ''); return '"' + (/^[\s]*[=+\-@\t\r]/.test(text) ? "'" + text : text).replaceAll('"', '""') + '"' }
