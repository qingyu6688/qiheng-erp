import type { SalesDocument } from '../types/sales'
import type {
  RefundCreditItem,
  SourcelessCreditRefundInput,
  SourcelessReturnInput,
} from '../types/sourceless-returns'
import { invoiceCents } from './inbound-invoices'

const ID = /^[1-9]\d{0,18}$/
function decimal(value: string, integers: number, scale: number, positive = false): boolean {
  if (value.length > 128 || !/^\d+(?:\.\d+)?$/.test(value)) return false
  const [whole = '', fraction = ''] = value.split('.')
  return (
    whole.replace(/^0+/, '').length <= integers &&
    fraction.length <= scale &&
    (!positive || /[1-9]/.test(value))
  )
}
export function newReturnLine(): SourcelessReturnInput['lines'][number] {
  return {
    skuId: '',
    unitId: '',
    qty: '1',
    unitPrice: '',
    taxRate: '0.13',
    qualityResult: 'pending',
  }
}
export function returnDraft(day: string, value?: SalesDocument): SourcelessReturnInput {
  return {
    customerId: value?.customerId ?? '',
    warehouseId: value?.warehouseId ?? '',
    businessDate: value?.businessDate ?? day,
    reason: value?.reason ?? '',
    legacyReference: value?.legacyReference ?? '',
    salesUserId: value?.salesUserId === '0' ? null : value?.salesUserId,
    version: value?.version,
    lines: value?.lines?.map((line) => ({
      skuId: line.skuId ?? '',
      unitId: line.unitId ?? '',
      qty: line.qty ?? '',
      unitPrice: line.unitPrice ?? '',
      taxRate: line.taxRate ?? '0',
      qualityResult:
        line.qualityResult === 'passed' || line.qualityResult === 'failed'
          ? line.qualityResult
          : 'pending',
      stockSelection: line.stockSelection,
      manualUnitCost: line.manualUnitCost,
      manualCostReason: line.manualCostReason,
    })) ?? [newReturnLine()],
  }
}
export function returnInput(draft: SourcelessReturnInput, costs: boolean): SourcelessReturnInput {
  return {
    ...draft,
    reason: draft.reason.trim(),
    legacyReference: draft.legacyReference?.trim() || null,
    lines: draft.lines.map((line) => {
      const { manualUnitCost, manualCostReason, ...other } = line
      return costs
        ? {
            ...other,
            manualUnitCost: manualUnitCost?.trim() || null,
            manualCostReason: manualCostReason?.trim() || null,
          }
        : other
    }),
  }
}
export function returnError(input: SourcelessReturnInput): string {
  if (!ID.test(input.customerId) || !ID.test(input.warehouseId)) return '请选择客户和退回仓库'
  if (!input.businessDate) return '请选择退货日期'
  if (!input.reason.trim() || input.reason.trim().length > 200) return '请填写200字以内的退货原因'
  if ((input.legacyReference?.length ?? 0) > 500) return '历史依据最多500字'
  if (!input.lines.length || input.lines.length > 500) return '退货明细须为1到500行'
  for (const [index, line] of input.lines.entries()) {
    const prefix = `第${index + 1}行`
    if (!ID.test(line.skuId) || !ID.test(line.unitId)) return prefix + '请选择商品和单位'
    if (!decimal(line.qty, 18, 6, true)) return prefix + '数量须大于零，最多六位小数'
    if (!decimal(line.unitPrice, 16, 8)) return prefix + '含税单价须为非负数，最多八位小数'
    const [whole = '', fraction = ''] = line.taxRate.split('.')
    if (
      !decimal(line.taxRate, 1, 6) ||
      BigInt(whole || '0') > 1n ||
      (BigInt(whole || '0') === 1n && /[1-9]/.test(fraction))
    )
      return prefix + '税率须为0到1，最多六位小数'
    if (!['pending', 'passed', 'failed'].includes(line.qualityResult))
      return prefix + '请选择有效质检结果'
    if (
      line.manualUnitCost != null &&
      (!decimal(line.manualUnitCost, 16, 8) || !line.manualCostReason?.trim())
    )
      return prefix + '补填成本须为非负数并提供依据'
    if ((line.manualCostReason?.length ?? 0) > 200) return prefix + '成本依据最多200字'
    if (line.manualUnitCost == null && line.manualCostReason) return prefix + '请同时填写成本与依据'
  }
  return ''
}
export function creditAmount(sources: RefundCreditItem[]): string {
  const cents = sources.reduce((sum, row) => sum + invoiceCents(row.openAmount), 0n)
  return `${cents / 100n}.${(cents % 100n).toString().padStart(2, '0')}`
}
export function refundError(
  input: SourcelessCreditRefundInput,
  sources: RefundCreditItem[],
): string {
  if (!ID.test(input.customerId) || !ID.test(input.cashAccountId)) return '请选择客户和退款账户'
  if (!input.businessDate) return '请选择退款日期'
  if (!input.reason.trim() || input.reason.trim().length > 200) return '请填写200字以内的退款原因'
  if (
    !input.creditItemIds.length ||
    input.creditItemIds.length > 200 ||
    new Set(input.creditItemIds).size !== input.creditItemIds.length
  )
    return '请选择1到200项不重复的退货信用额'
  if (input.creditItemIds.some((id) => !ID.test(id))) return '信用额来源编号无效'
  if (!decimal(input.amount, 22, 2, true)) return '退款金额须大于零，最多两位小数'
  if (
    sources.length !== input.creditItemIds.length ||
    sources.some(
      (row) =>
        row.partnerId !== input.customerId ||
        (row.sourceStatus && row.sourceStatus !== 'posted') ||
        invoiceCents(row.openAmount) === 0n,
    )
  )
    return '所选来源已变更或无可用余额，请重新选择'
  if (
    invoiceCents(input.amount) >
    sources.reduce((sum, row) => sum + invoiceCents(row.openAmount), 0n)
  )
    return '退款金额不能超过所选信用额可用余额'
  return ''
}
