import { beforeEach, expect, it, vi } from 'vitest'
import { request } from '../src/api/http'
import {
  refundCreditItems,
  returnCostPreview,
  saveSourcelessCreditRefund,
  saveSourcelessReturn,
} from '../src/api/sourceless-returns'
import type { SourcelessReturnInput } from '../src/types/sourceless-returns'

vi.mock('../src/api/http', () => ({ request: vi.fn() }))
beforeEach(() => vi.clearAllMocks())

const input: SourcelessReturnInput = {
  customerId: '9007199254740993',
  warehouseId: '21',
  businessDate: '2026-11-15',
  reason: '旧系统退回',
  lines: [
    {
      skuId: '31',
      unitId: '41',
      qty: '1.000001',
      qualityResult: 'passed',
      unitPrice: '1234567890123456.12',
      taxRate: '0.13',
    },
  ],
}

it('无原单创建及更正都保留精确字符串、固定模式和原幂等键', async () => {
  await saveSourcelessReturn(input, 'create-key')
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'POST',
      url: '/sales-returns',
      data: {
        ...input,
        returnMode: 'sourceless',
        lines: [{ ...input.lines[0], shipmentLineId: null }],
      },
      headers: { 'Idempotency-Key': 'create-key' },
    },
    true,
  )
  await saveSourcelessReturn({ ...input, version: 2 }, 'update-key', '91')
  expect(request).toHaveBeenLastCalledWith(
    expect.objectContaining({
      method: 'PUT',
      url: '/sales-returns/91',
      data: expect.objectContaining({ version: 2 }),
      headers: { 'Idempotency-Key': 'update-key' },
    }),
    true,
  )
})

it('成本预览支持取消旧请求，不带写入动作或幂等键', async () => {
  const signal = new AbortController().signal
  await returnCostPreview('91', signal)
  expect(request).toHaveBeenLastCalledWith(
    { method: 'GET', url: '/sales-returns/91/cost-preview', signal },
    true,
  )
})

it('信用额候选按20项分页，客户和游标保持精确标识', async () => {
  const signal = new AbortController().signal
  await refundCreditItems('9007199254740993', '9007199254740995', signal)
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'GET',
      url: '/refund-credit-items',
      params: { customerId: '9007199254740993', cursor: '9007199254740995', limit: 20 },
      signal,
    },
    true,
  )
})

it('信用额退款显式选择来源、原收款为空，更正继续使用原键', async () => {
  const refund = {
    customerId: '11',
    cashAccountId: '21',
    amount: '1234567890123456.12',
    businessDate: '2026-11-15',
    reason: '历史退回退款',
    creditItemIds: ['9007199254740993'],
    version: 3,
  }
  await saveSourcelessCreditRefund(refund, 'refund-update-key', '92')
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'PUT',
      url: '/refund-requests/92',
      data: { ...refund, refundMode: 'sourceless_credit', originalReceiptId: null },
      headers: { 'Idempotency-Key': 'refund-update-key' },
    },
    true,
  )
})
