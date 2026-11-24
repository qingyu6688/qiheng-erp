import axios from 'axios'
import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../src/api/demo'
import { documents, inventoryRows } from '../src/demo/data'

const client = axios.create({ adapter: demoAdapter })
async function read<T>(url: string, params: Record<string, unknown> = {}): Promise<T> {
  return (await client.get<{ data: T }>(url, { params })).data.data
}

describe('只读演示', () => {
  it('拒绝写请求和未知接口，不回退到真实网络', async () => {
    for (const method of ['post', 'put', 'patch', 'delete']) {
      await expect(client.request({ method, url: '/sales-orders/10000', data: {} })).rejects.toMatchObject({ response: { status: 403 } })
    }
    await expect(client.get('/sys/users')).rejects.toMatchObject({ response: { status: 404 } })
    await expect(client.get('https://qiheng.site/api/v1/auth/me')).rejects.toMatchObject({ response: { status: 403 } })
  })

  it('访客只有展示权限，返回的对象不能修改后续结果', async () => {
    const user = await read<{ permissions: string[] }>('/auth/me')
    expect(user.permissions.some((permission) => /:(create|update|delete|approve|post|configure|manage)$/.test(permission))).toBe(false)
    user.permissions.push('sys:admin')
    expect((await read<{ permissions: string[] }>('/auth/me')).permissions).not.toContain('sys:admin')
    expect(documents.every((row) => row.allowedActions.length === 0)).toBe(true)
  })

  it('列表日期、状态、客户、关键词与游标筛选生效', async () => {
    const page = await read<{ items: { id: string }[]; total: number; nextCursor: string }>('/document-browser/sales-orders', { dateFrom: '2026-11-01', dateTo: '2026-11-30', limit: 2 })
    expect(page.total).toBe(4)
    const next = await read<{ items: { id: string }[] }>('/document-browser/sales-orders', { dateFrom: '2026-11-01', limit: 2, cursor: page.nextCursor })
    expect(next.items.map((row) => row.id)).not.toEqual(page.items.map((row) => row.id))
    const filtered = await read<{ total: number }>('/document-browser/sales-orders', { status: 'pending', partnerId: '504', keyword: '云帆' })
    expect(filtered.total).toBe(1)
    expect((await read<{ total: number }>('/document-browser/sales-orders', { keyword: '不存在的客户' })).total).toBe(0)
  })

  it('库存满足期初加收货及退货减出库，可用量扣除预留', () => {
    for (const row of inventoryRows()) {
      const quantity = (kind: string): number => documents.filter((doc) => doc.kind === kind && doc.productId === row.skuId).reduce((value, doc) => value + doc.quantity, 0)
      expect(Number(row.onHandQty)).toBe(100 + quantity('goods-receipts') + quantity('sales-returns') - quantity('shipments'))
      expect(Number(row.availableQty)).toBe(Number(row.onHandQty) - Number(row.reservedQty))
      expect(Number(row.pendingDemandQty)).toBe(documents.filter((doc) => doc.kind === 'sales-orders' && doc.status === 'pending' && doc.productId === row.skuId).reduce((value, doc) => value + doc.quantity, 0))
    }
  })

  it('单据含税行金额与表头一致，报表仅展示对应汇总项', async () => {
    for (const row of documents.filter((row) => row.lines?.length)) {
      const total = row.lines!.reduce((value, line) => value + Number(line.receivableNet), 0)
      expect(total).toBeCloseTo(Number(row.totalAmount), 2)
    }
    const result = await read<{ summary: Record<string, string> }>('/reports/sales-summary')
    expect(result.summary).not.toHaveProperty('openAmount')
    expect(result.summary).not.toHaveProperty('openingQty')
  })

  it('工作台销售额与销售报表一致，筛选后的汇总会改变', async () => {
    const trade = await read<{ sales: { month: string } }>('/workbench/trade-summary')
    const report = await read<{ summary: { amount: string }; items: { sourceId: string }[] }>('/reports/sales-summary', { dateFrom: '2026-11-01', dateTo: '2026-11-30' })
    expect(report.summary.amount).toBe(trade.sales.month)
    for (const row of report.items) expect(documents.some((doc) => doc.id === row.sourceId)).toBe(true)
    const empty = await read<{ items: unknown[]; summary: { amount: string } }>('/reports/sales-summary', { dateFrom: '2030-01-01' })
    expect(empty.items).toEqual([])
    expect(empty.summary.amount).toBe('0.00')
  })

  it('部分收款在订单、应收账龄和收款关联中保持一致', async () => {
    const summary = await read<{ openAmount: string; allocatedCashAmount: string }>('/sales-orders/10112/settlement-summary')
    const aging = await read<{ summary: { openAmount: string }; items: { aging0: string; aging1: string }[] }>('/reports/receivable-aging', { agingDate: '2026-11-20', partnerId: '503' })
    const related = await read<{ items: { amount: string; documentId: string }[] }>('/sales-orders/10112/related/receipts')
    const reservations = await read<{ items: { remainingQty: string }[] }>('/sales-orders/10112/reservations')
    expect(reservations.items[0]?.remainingQty).toBe('37')
    expect(summary.openAmount).toBe(aging.summary.openAmount)
    expect(aging.items[0]?.aging0).toBe(summary.openAmount)
    expect(aging.items[0]?.aging1).toBe('0.00')
    expect(related.items[0]?.amount).toBe(summary.allocatedCashAmount)
    expect(documents.some((doc) => doc.id === related.items[0]?.documentId && doc.kind === 'receipts')).toBe(true)
  })

  it('库存收发存闭合，资金期末等于期初加收减付', async () => {
    const inventory = await read<{ items: Record<string, string>[] }>('/reports/inventory-flow', { dateFrom: '2026-11-01', dateTo: '2026-11-30' })
    for (const row of inventory.items) expect(Number(row.closingQty)).toBe(Number(row.openingQty) + Number(row.inQty) - Number(row.outQty))
    const cash = await read<{ summary: Record<string, string> }>('/reports/cash-journal', { dateFrom: '2026-11-01', dateTo: '2026-11-30' })
    const { openingBalance, closingBalance, receiptAmount, paymentAmount } = cash.summary
    expect(Number(closingBalance)).toBeCloseTo(Number(openingBalance) + Number(receiptAmount) - Number(paymentAmount), 2)
  })
})
