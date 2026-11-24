import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import axios from 'axios'
import { demoAdapter } from '../src/api/demo'
import { demoSession, documents, inventoryRows } from '../src/demo/data'
import { demoCostRevaluation, demoSalesQuotes, demoSupplierPrices } from '../src/demo/completeness-data'
import type { CostRevaluation } from '../src/types/cost-revaluations'
import type { SalesQuote } from '../src/types/sales-quotes'
import type { PriceListView } from '../src/api/crm-pricing'

const client = axios.create({ adapter: demoAdapter, baseURL: '/api/v1' })
async function read<T>(path: string, params = {}): Promise<T> { return (await client.get(path, { params })).data.data as T }
beforeEach(() => { setActivePinia(createPinia()) })

describe('新模块只读展示', () => {
  it('供应商协议阶梯、单位资料与目标来自同一组示例', async () => {
    const price = await read<PriceListView>('/purchase-price-lists/98101')
    expect(price).toEqual(demoSupplierPrices)
    expect(price.items.map((row) => row.unitPrice)).toEqual(['85.00', '82.00', '18.00'])
    const partners = await read<{ items: { id: string; roles: string[] }[] }>('/partners')
    expect(partners.items.find((row) => row.id === '601')?.roles).toEqual(['supplier'])
    expect(await read('/skus/401')).toMatchObject({ units: [{ unitId: '1', unitName: '箱', isBase: true }] })
  })
  it('已转单报价与实际演示订单的客户、数量和金额一致', async () => {
    const quote = await read<SalesQuote>('/sales-quotes/98201')
    const order = documents.find((row) => row.id === quote.convertedOrderId)!
    expect(quote.totalAmount).toBe(order.totalAmount)
    expect(quote.customerId).toBe(order.partnerId)
    expect(quote.pricing.lines[0]?.qty).toBe(String(order.quantity))
    expect(await read('/sales-quotes', { status: 'issued' })).toMatchObject({ items: [{ id: '98202' }] })
  })
  it('未执行重估只展示预览，当前库存与账簿事实不变', async () => {
    const before = inventoryRows()
    const task = await read<CostRevaluation>('/cost-revaluations/98301')
    expect(task.beforeCost).toBe(before.find((row) => row.skuId === task.skuId)?.costAmount)
    expect(Number(task.afterCost) - Number(task.beforeCost)).toBe(Number(task.stockDelta))
    expect(task.lines.reduce((sum, row) => sum + Number(row.costDelta), 0)).toBe(Number(task.stockDelta))
    expect(Number(task.issuedDelta)).toBe(-Number(task.stockDelta))
    expect(task.lines[0]).toMatchObject({ beforeCost: '-5355.00', afterCost: '-5375.00', onHandDelta: '-63' })
    expect(task.status).toBe('approved')
    expect(task.reason).toContain('尚未执行')
    expect(inventoryRows()).toEqual(before)
    expect(await read('/workflow/progress', { businessType: 'cost_revaluation', businessId: task.id })).toMatchObject({ canOpen: false, tasks: [{ comment: '同意按预览差额执行（虚构审批样例）' }] })
  })
  it('访客仅获查看权限，详情不提供可执行动作', () => {
    for (const permission of ['sales_quote:view', 'purchase_price_list:view', 'cost_revaluation:view']) expect(demoSession.permissions).toContain(permission)
    expect(demoSession.permissions.some((value) => /:(create|submit|post|approve|convert|manage|update)$/.test(value))).toBe(false)
    expect(demoCostRevaluation.allowedActions).toEqual([])
    expect(demoSalesQuotes.every((row) => row.allowedActions.length === 0)).toBe(true)
  })
  it.each(['/cost-revaluations/98301/post', '/sales-quotes/98201/convert', '/purchase-price-lists'])('拒绝展示模块写入：%s', async (path) => {
    await expect(client.post(path, {})).rejects.toMatchObject({ response: { status: 403 } })
  })
  it('读取结果独立复制，修改本次响应不会污染后续样例', async () => {
    const value = await read<CostRevaluation>('/cost-revaluations/98301')
    value.lines[0]!.costDelta = '999'
    expect((await read<CostRevaluation>('/cost-revaluations/98301')).lines[0]!.costDelta).toBe('-20.00')
  })
})
