import { expect, it } from 'vitest'
import {
  salesPriceDocumentDraft,
  salesPriceDraftError,
  salesPriceDraftInput,
} from '../src/utils/sales-price-adjustments'
import type { SalesPriceDraft } from '../src/utils/sales-price-adjustments'
import type { SalesPriceAdjustment } from '../src/types/sales-price-adjustments'

function draft(): SalesPriceDraft {
  return {
    partnerId: '9007199254740993001',
    warehouseId: '9007199254740993002',
    businessDate: '2026-11-23',
    reason: ' 客户结算折让 ',
    lines: [{ shipmentLineId: '9007199254740993003', mode: 'amount', value: '-0.01' }],
  }
}

it('保留大编号与分币折让字符串，只提交销售来源', () => {
  const result = salesPriceDraftInput(draft(), 7)
  expect(result).toMatchObject({
    version: 7,
    reason: '客户结算折让',
    lines: [{ shipmentLineId: '9007199254740993003', adjustmentAmount: '-0.01' }],
  })
  expect(result.lines[0]).not.toHaveProperty('receiptLineId')
  expect(result.lines[0]).not.toHaveProperty('newUnitPrice')
})

it('允许零售价与六位小数，禁止负售价及超位金额', () => {
  const value = draft()
  value.lines[0] = { shipmentLineId: '1', mode: 'unit', value: '0.000000' }
  expect(salesPriceDraftError(value)).toBe('')
  value.lines[0]!.value = '-1'
  expect(salesPriceDraftError(value)).toContain('含税单价')
  value.lines[0] = { shipmentLineId: '1', mode: 'amount', value: '-0.001' }
  expect(salesPriceDraftError(value)).toContain('最多两位小数')
})

it('禁止零调整、重复发货行和超过百行', () => {
  const value = draft()
  value.lines[0]!.value = '-0.00'
  expect(salesPriceDraftError(value)).toContain('不能为零')
  value.lines.push({ ...value.lines[0]! })
  expect(salesPriceDraftError(value)).toContain('不能重复')
  value.lines = Array.from({ length: 101 }, (_, index) => ({
    shipmentLineId: String(index + 1),
    mode: 'amount',
    value: '1',
  }))
  expect(salesPriceDraftError(value)).toContain('1到100行')
})

it('缺少客户、原因或来源时使用销售文案阻止保存', () => {
  const value = draft()
  value.partnerId = ''
  expect(salesPriceDraftError(value)).toBe('请选择客户和仓库')
  value.partnerId = '1'
  value.reason = ' '
  expect(() => salesPriceDraftInput(value)).toThrow('调价原因')
  value.reason = '客户结算'
  value.lines[0]!.shipmentLineId = '0'
  expect(salesPriceDraftError(value)).toContain('原发货行')
})

it('更正草稿恢复原输入模式，不把成本或红字摘要写回请求', () => {
  const original: SalesPriceAdjustment = {
    id: '1',
    kind: 'sales',
    documentNo: 'SA-1',
    partnerId: '2',
    partnerName: '客户',
    warehouseId: '3',
    warehouseName: '发货仓',
    businessDate: '2026-11-23',
    reason: '更正',
    status: 'draft',
    version: 2,
    amount: '-1',
    taxAmount: '0',
    untaxedAmount: '-1',
    createdBy: '4',
    allowedActions: ['update'],
    lines: [
      {
        id: '5',
        shipmentLineId: '6',
        shipmentNo: 'SH-1',
        skuId: '7',
        skuName: '商品',
        unitId: '8',
        unitName: '件',
        sourceQty: '1',
        sourceBaseQty: '1',
        sourceReturnedQty: '0',
        inputUnitPrice: null,
        inputAmount: '-1.00',
        oldUnitPrice: '10',
        newUnitPrice: '9',
        oldAmount: '10',
        oldTax: '0',
        amount: '-1',
        taxAmount: '0',
        untaxedAmount: '-1',
        needsRed: true,
      },
    ],
  }
  const result = salesPriceDraftInput(salesPriceDocumentDraft(original), original.version)
  expect(result.lines).toEqual([{ shipmentLineId: '6', adjustmentAmount: '-1.00' }])
})
