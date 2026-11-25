import axios from 'axios'
import { expect, it } from 'vitest'
import { demoAdapter } from '../src/api/demo'
import { documents, inventoryRows } from '../src/demo/data'
import { demoLedger, demoVouchers } from '../src/demo/finance-data'
import type { SalesDocument } from '../src/types/sales'
import type { ReportResult } from '../src/types/reporting'
import type { WorkflowProgress } from '../src/types/workflow'

const client = axios.create({ adapter: demoAdapter })
async function read<T>(url: string, params: Record<string, unknown> = {}): Promise<T> {
  return (await client.get<{ data: T }>(url, { params })).data.data
}

it('展示已过账退货和部分退款，原选来源不伪造原销售或收款', async () => {
  const returned = await read<SalesDocument>('/sales-returns/70001')
  expect(returned).toMatchObject({
    returnMode: 'sourceless',
    status: 'posted',
    creditAmount: '240.00',
    untaxedAmount: '212.39',
    taxAmount: '27.61',
    costAmount: '170.00',
    allowedActions: [],
  })
  expect(returned.lines).toHaveLength(1)
  expect(returned.lines![0]).toMatchObject({
    qty: '2',
    qualityResult: 'passed',
    costBasis: { source: 'average', unitCost: '85.00' },
  })
  expect(returned.lines![0]).not.toHaveProperty('shipmentLineId')
  expect(returned.relatedOrderId).toBeUndefined()
  const refund = await read<SalesDocument>('/refund-requests/71001')
  expect(refund).toMatchObject({
    refundMode: 'sourceless_credit',
    originalReceiptId: null,
    status: 'refunded',
    amount: '100.00',
    cashAccountName: '演示结算账户',
    warehouseName: '',
    allowedActions: [],
  })
  expect(refund.creditSources).toHaveLength(1)
  expect(refund.creditSources![0]).toMatchObject({
    sourceId: returned.id,
    amount: '240.00',
    openAmount: '140.00',
  })
  expect(JSON.stringify(refund.creditSources)).not.toMatch(/cost|Cost/)
  const page = await read<{ items: SalesDocument[] }>('/document-browser/sales-returns')
  expect(page.items.map((row) => row.id)).toEqual(['70001'])
})

it('审批记录只有虚构已完成结果，所有新模式写入和真实工作流访问均被拒绝', async () => {
  for (const [kind, type, id] of [
    ['sales-returns', 'sales_return', '70001'],
    ['refund-requests', 'refund_request', '71001'],
  ]) {
    const progress = await read<WorkflowProgress>('/workflow/progress', {
      businessType: type,
      businessId: id,
    })
    expect(progress.canOpen).toBe(false)
    expect(progress.instances[0]!.status).toBe('completed')
    expect(progress.tasks[0]).toMatchObject({
      assigneeId: '302',
      status: 'approved',
      comment: '已核对资料（虚构审批样例）',
    })
    for (const method of ['post', 'put', 'delete']) {
      await expect(
        client.request({ method, url: '/' + kind + '/' + id, data: {} }),
      ).rejects.toMatchObject({ response: { status: 403 } })
    }
  }
  await expect(
    client.get('/workflow/progress', {
      params: { businessType: 'refund_request', businessId: '70001' },
    }),
  ).rejects.toMatchObject({ response: { status: 404 } })
  await expect(client.get('/workflow/instances/97001')).rejects.toMatchObject({
    response: { status: 404 },
  })
})

it('同一退货在销售报表反向，在库存中按原冻结成本入库，工作台净额一致', async () => {
  const sales = await read<ReportResult>('/reports/sales-summary', {
    detail: true,
    dateFrom: '2026-11-01',
    limit: 200,
  })
  expect(sales.items.find((row) => row.sourceId === '70001')).toMatchObject({
    sourceType: 'sales_return_line',
    returnMode: 'sourceless',
    returnCostSource: 'average',
    qty: '-2',
    amount: '-240.00',
    untaxedAmount: '-212.39',
    taxAmount: '-27.61',
    costAmount: '-170.00',
  })
  const stock = await read<ReportResult>('/reports/inventory-flow', {
    detail: true,
    dateFrom: '2026-11-01',
    limit: 200,
  })
  expect(stock.items.find((row) => row.sourceId === '70001')).toMatchObject({
    direction: 'in',
    inQty: '2',
    outQty: '0',
    costAmount: '170.00',
  })
  const trade = await read<{ sales: { month: string } }>('/workbench/trade-summary')
  expect(trade.sales.month).toBe(sales.summary.amount)
})

it('账龄按实际日期扣除部分退款，贷方信用额不混为正向应收', async () => {
  const before = await read<ReportResult>('/reports/receivable-aging', {
    agingDate: '2026-11-19',
    partnerId: '501',
  })
  const after = await read<ReportResult>('/reports/receivable-aging', {
    agingDate: '2026-11-20',
    partnerId: '501',
  })
  expect(before.items.find((row) => row.sourceId === '70001')).toMatchObject({
    direction: 'C',
    openAmount: '-240.00',
  })
  expect(after.items.find((row) => row.sourceId === '70001')).toMatchObject({
    direction: 'C',
    openAmount: '-140.00',
  })
  const all = await read<ReportResult>('/reports/receivable-aging', { agingDate: '2026-11-20' })
  expect(all.summary.openAmount).toBe('616.00')
})

it('退货和退款凭证借贷平衡，库存、资金与往来同总账闭合', async () => {
  const cash = await read<ReportResult>('/reports/cash-journal', {
    dateFrom: '2026-11-01',
    limit: 200,
  })
  expect(cash.items.find((row) => row.sourceId === '71001')).toMatchObject({
    sourceType: 'refund_request',
    receiptAmount: '0.00',
    paymentAmount: '100.00',
  })
  const ledger = demoLedger('balance', { periodFrom: '2026-11', periodTo: '2026-11' })!.items
  const closing = (code: string): number => {
    const row = ledger.find((row) => row.subjectCode === code)!
    return Number(row.closingDebit) - Number(row.closingCredit)
  }
  expect(closing('1002')).toBe(Number(cash.summary.closingBalance))
  expect(closing('1122')).toBe(616)
  expect(closing('1405')).toBe(
    inventoryRows().reduce((sum, row) => sum + Number(row.costAmount), 0),
  )
  for (const doc of documents.filter((row) =>
    ['sales-returns', 'refund-requests'].includes(row.kind),
  )) {
    const voucher = demoVouchers.find((voucher) =>
      voucher.lines.some((line) => line.digest.startsWith(doc.documentNo!)),
    )!
    expect(voucher).toBeDefined()
    expect(voucher.debitTotal).toBe(voucher.creditTotal)
    expect(voucher.sourceNo).toBe(doc.documentNo)
    expect(voucher.sourceId).toBe(doc.id)
    expect(voucher.sourceType).toBe(doc.kind === 'sales-returns' ? 'sales_return' : 'refund_request')
  }
})
