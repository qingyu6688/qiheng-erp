import type { Id, ScanMatch } from '../types/api'
import type { StockLine, WorkRow } from '../types/work'
import { remaining } from './quantity'

export type StockTaskType = 'putaway' | 'pick'
export type FixedScanField = 'from' | 'to' | 'batch'

const MAX_PUTAWAY_LOCATION_LENGTH = 48
const BEIJING_OFFSET_MS = 8 * 3_600_000

export function formatSnapshotTime(value?: string | null): string {
  if (!value || !/(?:Z|[+-]\d{2}:\d{2})$/.test(value)) return ''
  const time = Date.parse(value)
  if (!Number.isFinite(time)) return ''
  return new Date(time + BEIJING_OFFSET_MS).toISOString().slice(0, 19).replace('T', ' ')
}

function sameCode(left: string, right: string): boolean {
  return left.length === right.length && (left.toUpperCase() === right.toUpperCase() || left.toLowerCase() === right.toLowerCase())
}

/** 核对任务行已有的编码；可替换库位的启用状态和容量仍由提交接口判定。 */
export function scanMismatch(type: StockTaskType, field: FixedScanField, input: string, line: StockLine): string {
  let code = input.trim()
  if (field === 'batch') {
    if (line.batchNo == null || line.batchNo === '') return ''
    const prefix = 'B:' + line.skuCode + '|'
    const matchesSku = sameCode(code.slice(0, prefix.length), prefix)
    // 上架只把大写 B: 识别为标签；拣货按完整前缀匹配，其余输入照原文比较。
    if (type === 'putaway' && code.startsWith('B:') && !matchesSku) {
      return '扫描批次的商品编码与作业行不一致，请重扫'
    }
    if (matchesSku && (type === 'pick' || code.startsWith('B:'))) code = code.slice(prefix.length)
    return code === line.batchNo ? '' : '扫描的批次号与作业行不一致，请重扫'
  }

  const tagged = type === 'putaway' ? code.startsWith('L:') : /^L:/i.test(code)
  if (tagged) code = code.slice(2)
  if (type === 'putaway') {
    if (!code || code.length > MAX_PUTAWAY_LOCATION_LENGTH || /\p{Cc}/u.test(code)) return '库位编码无效，请重扫'
    if (field === 'to') return ''
  }
  const expected = field === 'from' ? line.fromLocationCode : line.toLocationCode
  if (!expected || sameCode(code, expected)) return ''
  return field === 'from' ? '扫描的源库位与作业行不一致，请重扫' : '扫描的发货库位与作业行不一致，请重扫'
}

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
