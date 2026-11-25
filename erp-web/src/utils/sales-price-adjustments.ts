import type {
  SalesPriceAdjustment,
  SalesPriceAdjustmentInput,
} from '../types/sales-price-adjustments'

export interface SalesPriceDraftLine {
  shipmentLineId: string
  mode: 'unit' | 'amount'
  value: string
  label?: string
}

export interface SalesPriceDraft {
  partnerId: string
  warehouseId: string
  businessDate: string
  reason: string
  lines: SalesPriceDraftLine[]
}

const LINE_LIMIT = 100
const PRICE_PATTERN = /^[0-9]+(?:\.[0-9]{1,6})?$/
const AMOUNT_PATTERN = /^-?[0-9]+(?:\.[0-9]{1,2})?$/
const ZERO_PATTERN = /^-?0+(?:\.0+)?$/

export function salesPriceDraftError(draft: SalesPriceDraft): string {
  if (!draft.partnerId || !draft.warehouseId) return '请选择客户和仓库'
  if (!draft.businessDate) return '请选择调价日期'
  if (!draft.reason.trim() || draft.reason.trim().length > 500) return '请填写调价原因，最多500字'
  if (!draft.lines.length || draft.lines.length > LINE_LIMIT) return '调价明细须为1到100行'
  if (new Set(draft.lines.map((line) => line.shipmentLineId)).size !== draft.lines.length)
    return '同一发货行不能重复调价'
  for (const [index, line] of draft.lines.entries()) {
    if (!/^[1-9][0-9]*$/.test(line.shipmentLineId)) return `第${index + 1}行请选择原发货行`
    const value = line.value.trim()
    if (!(line.mode === 'unit' ? PRICE_PATTERN : AMOUNT_PATTERN).test(value) || value.length > 32)
      return `第${index + 1}行${line.mode === 'unit' ? '含税单价最多六位小数' : '调整金额最多两位小数，可填负数'}`
    if (line.mode === 'amount' && ZERO_PATTERN.test(value))
      return `第${index + 1}行调整金额不能为零`
  }
  return ''
}

export function salesPriceDraftInput(
  draft: SalesPriceDraft,
  version?: number,
): SalesPriceAdjustmentInput {
  const error = salesPriceDraftError(draft)
  if (error) throw new Error(error)
  return {
    ...(version === undefined ? {} : { version }),
    partnerId: draft.partnerId,
    warehouseId: draft.warehouseId,
    businessDate: draft.businessDate,
    reason: draft.reason.trim(),
    lines: draft.lines.map((line) => ({
      shipmentLineId: line.shipmentLineId,
      ...(line.mode === 'unit'
        ? { newUnitPrice: line.value.trim() }
        : { adjustmentAmount: line.value.trim() }),
    })),
  }
}

export function salesPriceDocumentDraft(document: SalesPriceAdjustment): SalesPriceDraft {
  return {
    partnerId: document.partnerId,
    warehouseId: document.warehouseId,
    businessDate: document.businessDate,
    reason: document.reason,
    lines: document.lines.map((line) => ({
      shipmentLineId: line.shipmentLineId,
      mode: line.inputUnitPrice === null ? 'amount' : 'unit',
      value: line.inputUnitPrice ?? line.inputAmount ?? '',
      label: `${line.shipmentNo} · ${line.skuName}`,
    })),
  }
}
