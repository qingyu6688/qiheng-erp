import type { Id, ScanMatch } from '../types/api'
import type { StockLine, WorkRow } from '../types/work'
import { remaining } from './quantity'

const stateNames: Record<string, string> = {
  created: '待领取', assigned: '已领取', in_progress: '作业中', done: '已完成',
  short: '已短拣', cancelled: '已取消', draft: '草稿', submitted: '已提交',
  pending: '待处理', posted: '已过账', completed: '已完成', approved: '已通过', rejected: '已驳回',
}

export function statusTitle(value: string): string {
  return stateNames[value] ?? value
}

export function listRow(row: WorkRow): { id: string; title: string; status: string; subtitle?: string } {
  return {
    id: row.id,
    title: row.no ?? row.documentNo ?? row.shipmentNo ?? row.id,
    status: statusTitle(row.status),
    subtitle: row.no ? row.shipmentNo : undefined,
  }
}

export function pendingLine(lines: StockLine[], id: Id): StockLine | undefined {
  return lines.find((line) => line.id === id && remaining(line.planQty, line.doneQty) !== '0')
}

export function lineForScan<T extends StockLine>(lines: T[], result: ScanMatch): T {
  const skuId = result.skuId ?? (result.type === 'sku' ? result.id : undefined)
  const batchId = result.batchId ?? (result.type === 'batch' ? result.id : undefined)
  const matches = lines.filter((line) => line.skuId === skuId && (!batchId || !line.batchId || line.batchId === batchId))
  if (matches.length === 1) return matches[0]!
  throw new Error(matches.length ? '扫码匹配到多行，请按原库位或批次从明细选择' : '扫描商品不属于当前单据')
}
