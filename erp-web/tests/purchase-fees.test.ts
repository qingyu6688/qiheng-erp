import { describe, expect, it } from 'vitest'
import { feeDocumentDraft, feeDraftInput } from '../src/utils/purchase-fees'
import type { FeeDraft } from '../src/utils/purchase-fees'
import type { PurchaseFee } from '../src/types/purchase-fees'

function draft(): FeeDraft {
  return {
    partnerId: '9007199254740993',
    businessDate: '2026-11-23',
    allocationMethod: 'amount',
    reason: '  运输费用  ',
    fees: [{ feeType: 'freight', amount: ' 11.30 ', taxRate: '0.13', remark: '  同城运费  ' }],
    sources: [{ receiptLineId: '9007199254740995', label: '收货单' }],
  }
}
describe('采购费用录入', () => {
  it('保留长标识与金额精度，承运商不用于过滤原收货供应商', () => {
    expect(feeDraftInput(draft(), 3)).toEqual({
      version: 3,
      partnerId: '9007199254740993',
      businessDate: '2026-11-23',
      allocationMethod: 'amount',
      reason: '运输费用',
      fees: [{ feeType: 'freight', amount: '11.30', taxRate: '0.13', remark: '同城运费' }],
      receiptLineIds: ['9007199254740995'],
    })
  })
  it('拒绝零金额、负数、指数、超精度及无效税率', () => {
    for (const amount of ['0', '-1', '1e3', '1.001', '10000000000000000000000']) {
      const value = draft()
      value.fees[0]!.amount = amount
      expect(() => feeDraftInput(value)).toThrow()
    }
    for (const taxRate of ['1', '-0.1', '0.0000001', 'NaN']) {
      const value = draft()
      value.fees[0]!.taxRate = taxRate
      expect(() => feeDraftInput(value)).toThrow()
    }
  })
  it('限制来源去重和数量，避免重复分摊与超大表单', () => {
    const value = draft()
    value.sources.push({ ...value.sources[0]! })
    expect(() => feeDraftInput(value)).toThrow('重复')
    value.sources = Array.from({ length: 101 }, (_, index) => ({
      receiptLineId: String(index + 1),
    }))
    expect(() => feeDraftInput(value)).toThrow('100')
  })
  it('重新编辑保留税率、承运商和全部来源仓描述', () => {
    const document = {
      partnerId: '10',
      businessDate: '2026-11-23',
      reason: '运费',
      allocationMethod: 'weight',
      lines: [{ feeType: 'loading', amount: '10.00', taxRate: '0.06', remark: '卸货' }],
      allocations: [
        { receiptLineId: '20', receiptNo: 'GR-1', skuName: '钢材', sourceWarehouseName: '二号仓' },
      ],
    } as PurchaseFee
    const value = feeDocumentDraft(document)
    expect(value.sources[0]?.label).toBe('GR-1 · 钢材 · 二号仓')
    expect(value.fees[0]).toMatchObject({ feeType: 'loading', taxRate: '0.06' })
    expect(feeDraftInput(value).receiptLineIds).toEqual(['20'])
  })
})
