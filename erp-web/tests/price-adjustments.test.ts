import { describe, expect, it } from 'vitest'
import {
  priceDocumentDraft,
  priceDraftError,
  priceDraftInput,
} from '../src/utils/price-adjustments'
import type { PriceDraft } from '../src/utils/price-adjustments'
import type { PriceAdjustment } from '../src/types/price-adjustments'

function draft(): PriceDraft {
  return {
    partnerId: '10',
    warehouseId: '20',
    businessDate: '2026-11-23',
    reason: '  结算折让  ',
    lines: [{ receiptLineId: '9007199254740993', mode: 'amount', value: '-1.25' }],
  }
}

describe('采购调价输入', () => {
  it('金额模式只提交调整金额并保留长标识与十进制文本', () => {
    expect(priceDraftInput(draft(), 3)).toEqual({
      partnerId: '10',
      warehouseId: '20',
      businessDate: '2026-11-23',
      reason: '结算折让',
      version: 3,
      lines: [{ receiptLineId: '9007199254740993', adjustmentAmount: '-1.25' }],
    })
  })
  it('允许零单价并省略金额字段，拒绝零调整金额', () => {
    const value = draft()
    value.lines[0]!.mode = 'unit'
    value.lines[0]!.value = '0.000000'
    expect(priceDraftInput(value).lines[0]).toEqual({
      receiptLineId: '9007199254740993',
      newUnitPrice: '0.000000',
    })
    value.lines[0]!.mode = 'amount'
    expect(priceDraftError(value)).not.toBe('')
  })
  it('拒绝重复来源、指数格式、超精度及缺少原因', () => {
    const value = draft()
    value.lines.push({ ...value.lines[0]! })
    expect(priceDraftError(value)).toContain('重复')
    value.lines.pop()
    for (const invalid of ['1e3', '1.001', '', 'NaN']) {
      value.lines[0]!.value = invalid
      expect(priceDraftError(value)).not.toBe('')
    }
    value.reason = ''
    expect(() => priceDraftInput(value)).toThrow('原因')
  })
  it('重新编辑保留原输入方式，避免把折让金额误当作单价', () => {
    const document = {
      partnerId: '10',
      warehouseId: '20',
      businessDate: '2026-11-23',
      reason: '折让',
      lines: [
        {
          receiptLineId: '30',
          receiptNo: 'GR-1',
          skuName: '商品',
          inputUnitPrice: null,
          inputAmount: '-12.00',
        },
      ],
    } as PriceAdjustment
    expect(priceDocumentDraft(document).lines).toEqual([
      { receiptLineId: '30', mode: 'amount', value: '-12.00', label: 'GR-1 · 商品' },
    ])
  })
})
