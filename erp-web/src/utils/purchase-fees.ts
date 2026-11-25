import type { AllocationMethod, PurchaseFee, PurchaseFeeInput } from '../types/purchase-fees'

export interface FeeDraft {
  partnerId: string
  businessDate: string
  allocationMethod: AllocationMethod
  reason: string
  fees: PurchaseFeeInput['fees']
  sources: { receiptLineId: string; label?: string }[]
}
export function feeDraftInput(draft: FeeDraft, version?: number): PurchaseFeeInput {
  if (!/^[1-9][0-9]*$/.test(draft.partnerId)) throw new Error('请选择费用承运商或供应商')
  if (!draft.businessDate) throw new Error('请选择费用日期')
  if (!['amount', 'quantity', 'weight'].includes(draft.allocationMethod))
    throw new Error('请选择有效的分摊方式')
  if (!draft.reason.trim() || draft.reason.trim().length > 500)
    throw new Error('请填写费用原因，最多500字')
  if (!draft.fees.length || draft.fees.length > 50) throw new Error('费用明细须为1到50行')
  if (!draft.sources.length || draft.sources.length > 100) throw new Error('请选择1到100个收货来源')
  if (new Set(draft.sources.map((line) => line.receiptLineId)).size !== draft.sources.length)
    throw new Error('同一收货来源不能重复选择')
  if (draft.sources.some((line) => !/^[1-9][0-9]*$/.test(line.receiptLineId)))
    throw new Error('请选择有效的收货来源')
  for (const [index, fee] of draft.fees.entries()) {
    if (!['freight', 'loading', 'other'].includes(fee.feeType))
      throw new Error(`第${index + 1}行费用类型无效`)
    if (
      !/^[0-9]{1,22}(?:\.[0-9]{1,2})?$/.test(fee.amount.trim()) ||
      /^0+(?:\.0+)?$/.test(fee.amount.trim())
    )
      throw new Error(`第${index + 1}行费用须为正数且最多两位小数`)
    if (!/^0(?:\.[0-9]{1,6})?$/.test(fee.taxRate.trim()))
      throw new Error(`第${index + 1}行税率须在0到1之间且最多六位小数`)
    if ((fee.remark?.length ?? 0) > 200) throw new Error(`第${index + 1}行备注最多200字`)
  }
  return {
    ...(version === undefined ? {} : { version }),
    partnerId: draft.partnerId,
    businessDate: draft.businessDate,
    allocationMethod: draft.allocationMethod,
    reason: draft.reason.trim(),
    fees: draft.fees.map((fee) => ({
      ...fee,
      amount: fee.amount.trim(),
      taxRate: fee.taxRate.trim(),
      remark: fee.remark?.trim(),
    })),
    receiptLineIds: draft.sources.map((source) => source.receiptLineId),
  }
}
export function feeDocumentDraft(document: PurchaseFee): FeeDraft {
  return {
    partnerId: document.partnerId,
    businessDate: document.businessDate,
    allocationMethod: document.allocationMethod,
    reason: document.reason,
    fees: document.lines.map((fee) => ({
      feeType: fee.feeType,
      amount: fee.amount,
      taxRate: fee.taxRate,
      remark: fee.remark ?? '',
    })),
    sources: document.allocations.map((line) => ({
      receiptLineId: line.receiptLineId,
      label: `${line.receiptNo} · ${line.skuName} · ${line.sourceWarehouseName}`,
    })),
  }
}
