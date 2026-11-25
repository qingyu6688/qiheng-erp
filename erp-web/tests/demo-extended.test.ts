import axios from 'axios'
import { describe, expect, it } from 'vitest'
import { demoAdapter } from '../src/api/demo'
import { documents, inventoryRows } from '../src/demo/data'
import { demoLedger, demoVouchers } from '../src/demo/finance-data'
import type { CrmCycleRow } from '../src/types/crm-cycle'
import type { Bom, BomPreview, WorkOrder } from '../src/types/manufacturing'
import type { SlotBalance } from '../src/api/supply-chain'
import type { GlVoucher } from '../src/types/gl'

const client = axios.create({ adapter: demoAdapter })
async function read<T>(url: string, params: Record<string, unknown> = {}): Promise<T> {
  return (await client.get<{ data: T }>(url, { params })).data.data
}
type Page<T> = { items: T[]; nextCursor: string | null; total: number }

describe('扩展只读演示', () => {
  it('每个新增模块均拒绝写请求，未知记录不返回占位数据', async () => {
    for (const path of ['/crm/customers', '/crm/businesses', '/crm/contracts', '/mfg/boms', '/mfg/work-orders', '/gl/vouchers', '/warehouses/101/locations']) {
      for (const method of ['post', 'put', 'patch', 'delete']) await expect(client.request({ method, url: path })).rejects.toMatchObject({ response: { status: 403 } })
      await expect(client.get(path + '/99999999')).rejects.toMatchObject({ response: { status: 404 } })
    }
    await expect(client.get('/gl/ledgers/quantity')).rejects.toMatchObject({ response: { status: 404 } })
  })

  it('客户分页和商机状态筛选生效，合同金额与销售订单相同', async () => {
    const first = await read<Page<CrmCycleRow>>('/crm/customers', { limit: 2 })
    const next = await read<Page<CrmCycleRow>>('/crm/customers', { limit: 2, cursor: first.nextCursor })
    expect([...first.items, ...next.items].map((row) => row.id)).toEqual(['501', '502', '503', '504'])
    const business = await read<Page<CrmCycleRow>>('/crm/businesses', { result: 'open', partnerId: '504' })
    expect(business.total).toBe(1)
    for (const contract of (await read<Page<CrmCycleRow>>('/crm/contracts')).items) {
      expect(contract.allowedActions).toEqual([])
      const order = documents.find((row) => row.id === contract.salesOrderIds?.[0])!
      expect(contract.partnerId).toBe(order.partnerId)
      expect(contract.totalAmount).toBe(order.totalAmount)
      expect(contract.lines?.[0]?.totalAmount).toBe(order.totalAmount)
    }
    expect((await read<Page<CrmCycleRow>>('/crm/contracts', { status: 'draft' })).total).toBe(0)
  })

  it('客户联系人、团队和跟进按客户编号读取', async () => {
    for (const path of ['/crm/customers/501/contacts', '/crm/customers/501/team', '/crm/follow-ups']) {
      const response = await read<Page<object>>(path, { bizType: 'customer', bizId: '501' })
      expect(response.items).toHaveLength(1)
    }
    await expect(client.get('/crm/follow-ups', { params: { bizId: '999', bizType: 'customer' } })).rejects.toMatchObject({ response: { status: 404 } })
  })

  it('BOM 用料展开按数量计算，工单尚未领料且不可操作', async () => {
    const boms = await read<Page<Bom>>('/mfg/boms', { skuId: '405' })
    expect(boms.total).toBe(1)
    const preview = await read<{ lines: BomPreview[] }>('/mfg/boms/' + boms.items[0]!.id + '/explode', { qty: '20', warehouseId: '101' })
    expect(preview.lines.map((row) => row.requiredQty)).toEqual(['20', '20'])
    for (const row of preview.lines) expect(row.availableQty).toBe(inventoryRows().find((stock) => stock.skuId === row.componentSkuId)?.availableQty)
    const orders = await read<Page<WorkOrder>>('/mfg/work-orders', { status: 'released' })
    expect(orders.total).toBe(1)
    expect(orders.items[0]!.materials?.every((row) => row.issuedQty === '0')).toBe(true)
    expect(orders.items[0]!.allowedActions).toEqual([])
    await expect(client.get('/mfg/boms/73001/explode', { params: { qty: '-1' } })).rejects.toMatchObject({ response: { status: 404 } })
  })

  it('库位库存等于商品库存并可筛选具体库位', async () => {
    const balances = await read<SlotBalance[]>('/warehouses/101/locations/balances')
    expect(balances.map((row) => row.onHandQty)).toEqual(inventoryRows().map((row) => row.onHandQty))
    expect(balances.map((row) => row.availableQty)).toEqual(inventoryRows().map((row) => row.availableQty))
    expect(await read<SlotBalance[]>('/warehouses/101/locations/balances', { locationId: '76001' })).toHaveLength(1)
  })

  it('每张凭证借贷相等，期间和游标筛选不重不漏', async () => {
    for (const voucher of demoVouchers) {
      expect(voucher.debitTotal).toBe(voucher.creditTotal)
      expect(voucher.lines.every((row) => Number.isFinite(Number(row.amount)))).toBe(true)
    }
    const first = await read<GlVoucher[]>('/gl/vouchers', { periodId: '2026-11', limit: 3 })
    const next = await read<GlVoucher[]>('/gl/vouchers', { periodId: '2026-11', cursor: first.at(-1)!.id, limit: 200 })
    expect([...first, ...next].map((row) => row.id).sort()).toEqual(demoVouchers.filter((row) => row.periodId === '2026-11').map((row) => row.id).sort())
  })

  it('跨期余额衔接，期末库存、资金及往来与原报表一致', () => {
    const current = demoLedger('balance', { periodFrom: '2026-11', periodTo: '2026-11' })!.items
    const previous = demoLedger('balance', { periodFrom: '2025-12', periodTo: '2026-10' })!.items
    for (const row of current) {
      const before = previous.find((item) => item.subjectCode === row.subjectCode)!
      expect(row.openingDebit).toBe(before.closingDebit)
      expect(row.openingCredit).toBe(before.closingCredit)
    }
    const closing = (code: string): number => { const row = current.find((item) => item.subjectCode === code)!; return Number(row.closingDebit) - Number(row.closingCredit) }
    const total = (kind: string): number => documents.filter((row) => row.kind === kind).reduce((sum, row) => sum + Number(row.totalAmount), 0)
    expect(closing('1405')).toBeCloseTo(inventoryRows().reduce((sum, row) => sum + Number(row.costAmount), 0), 2)
    expect(closing('1002')).toBeCloseTo(100000 + total('receipts') - total('payments') - total('refund-requests'), 2)
    expect(closing('1122')).toBeCloseTo(total('shipments') - total('receipts') - total('sales-returns') + total('refund-requests'), 2)
    expect(closing('2202')).toBeCloseTo(total('payments') - total('goods-receipts'), 2)
    expect(current.reduce((sum, row) => sum + Number(row.closingDebit) - Number(row.closingCredit), 0)).toBeCloseTo(0, 2)
  })

  it('明细账按科目筛选并回溯到原凭证', () => {
    const detail = demoLedger('detail', { periodFrom: '2026-11', periodTo: '2026-11', subjectId: '1002', limit: 2 })!
    expect(detail.items).toHaveLength(2)
    expect(detail.nextCursor).toBe('2')
    for (const row of detail.items) {
      expect(row.subjectCode).toBe('1002')
      expect(demoVouchers.some((voucher) => voucher.id === row.voucherId)).toBe(true)
    }
  })
})
