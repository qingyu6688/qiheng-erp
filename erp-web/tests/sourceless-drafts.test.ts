import { expect, it } from 'vitest'
import {
  creditAmount,
  refundError,
  returnDraft,
  returnError,
  returnInput,
} from '../src/utils/sourceless-returns'
import type {
  RefundCreditItem,
  SourcelessCreditRefundInput,
  SourcelessReturnInput,
} from '../src/types/sourceless-returns'

function input(): SourcelessReturnInput {
  return {
    customerId: '9007199254740993',
    warehouseId: '20',
    businessDate: '2026-11-15',
    reason: '  历史退回  ',
    legacyReference: '旧系统01',
    lines: [
      {
        skuId: '30',
        unitId: '40',
        qty: '1.000001',
        unitPrice: '9007199254740991.12345678',
        taxRate: '0.13',
        qualityResult: 'pending',
      },
    ],
  }
}
function source(id: string, amount: string): RefundCreditItem {
  return {
    id,
    partnerId: '10',
    sourceId: '50',
    sourceLineId: '60',
    sourceNo: 'SR-1',
    returnMode: 'sourceless',
    warehouseId: '20',
    warehouseName: '仓库',
    skuName: '商品',
    businessDate: '2026-11-15',
    amount,
    openAmount: amount,
  }
}
it('大额退货单价、六位数量和零人工成本保持精确，成本依据缺失会阻止保存', () => {
  const form = input()
  form.lines[0]!.manualUnitCost = '0'
  form.lines[0]!.manualCostReason = '零成本凭据'
  expect(returnError(form)).toBe('')
  const payload = returnInput(form, true)
  expect(payload.lines[0]).toMatchObject({
    unitPrice: '9007199254740991.12345678',
    qty: '1.000001',
    manualUnitCost: '0',
  })
  expect(payload.reason).toBe('历史退回')
  form.lines[0]!.manualCostReason = ''
  expect(returnError(form)).toContain('提供依据')
})
it.each(['-1', '0', '0.000000', '1.0000001', '1e2'])('退货数量%s无效', (qty) => {
  const form = input()
  form.lines[0]!.qty = qty
  expect(returnError(form)).toContain('数量')
})
it.each(['-0.13', '1.000001', '2', '1e-2'])('退货税率%s无效', (taxRate) => {
  const form = input()
  form.lines[0]!.taxRate = taxRate
  expect(returnError(form)).toContain('税率')
})
it('无成本权限更正省略全部隐藏人工值，原始草稿保持供当前权限重新判断', () => {
  const form = input()
  form.lines[0]!.manualUnitCost = '5'
  form.lines[0]!.manualCostReason = '不应传出'
  expect(returnInput(form, false).lines[0]).not.toHaveProperty('manualUnitCost')
  expect(returnInput(form, false).lines[0]).not.toHaveProperty('manualCostReason')
  expect(form.lines[0]!.manualUnitCost).toBe('5')
  expect(returnDraft('2026-11-15').lines).toHaveLength(1)
})
it('信用额按分累计超过浮点安全范围，超出一分钱也拒绝', () => {
  const sources = [source('11', '9007199254740991.12'), source('12', '0.13')]
  expect(creditAmount(sources)).toBe('9007199254740991.25')
  const refund: SourcelessCreditRefundInput = {
    customerId: '10',
    cashAccountId: '20',
    businessDate: '2026-11-15',
    reason: '历史退款',
    amount: '9007199254740991.25',
    creditItemIds: ['11', '12'],
  }
  expect(refundError(refund, sources)).toBe('')
  expect(refundError({ ...refund, amount: '9007199254740991.26' }, sources)).toContain('不能超过')
  expect(refundError(refund, [sources[0]!, { ...sources[1]!, partnerId: '99' }])).toContain(
    '重新选择',
  )
  expect(
    refundError(refund, [sources[0]!, { ...sources[1]!, sourceStatus: 'reversed' }]),
  ).toContain('重新选择')
})
