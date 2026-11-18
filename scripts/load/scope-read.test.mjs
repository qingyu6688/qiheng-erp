import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseScopeFixture, scopeRequests, verifyScopeProbe } from './scope-read.mjs'

const fixture = {
  dataset: 'scope-smoke',
  companyId: '991001',
  username: 'load_scope',
  warehouseId: '992001',
  skuId: '993001',
  dateFrom: '2026-11-01',
  dateTo: '2026-11-17',
  orders: {
    visible: { id: '994001', documentNo: 'TEST-SO-A', shipmentLineId: '996001' },
    departmentDenied: { id: '994002', documentNo: 'TEST-SO-B', shipmentLineId: '996002' },
    warehouseDenied: { id: '994003', documentNo: 'TEST-SO-C', shipmentLineId: '996003' },
  },
}
const probe = {
  fixture,
  control: { companyId: fixture.companyId },
  restricted: { companyId: fixture.companyId, scoped: true },
  sameTenantCompany: '991002',
  foreignTenantCompany: '991003',
}
const orders = Object.values(fixture.orders)
const details = orders.map((order, position) => ({
  ...order,
  ownerDepartmentId: position === 1 ? '995002' : '995001',
  lines: [{ warehouseId: position === 2 ? '992002' : fixture.warehouseId }],
}))
const reportRows = details.map((order, position) => ({
  sourceType: 'shipment',
  sourceId: String(997001 + position),
  sourceLineId: order.shipmentLineId,
  skuId: fixture.skuId,
  departmentId: order.ownerDepartmentId,
  warehouseId: order.lines[0].warehouseId,
}))

function read(actor, path) {
  if (actor.companyId !== fixture.companyId) return { status: 403 }
  const selected = actor.scoped ? orders.slice(0, 1) : orders
  if (path.startsWith('/document-browser/')) {
    const keyword = new URL('http://localhost' + path).searchParams.get('keyword')
    const items = selected.filter((order) => !keyword || order.documentNo === keyword)
    return { status: 200, data: { items, total: items.length } }
  }
  if (path.startsWith('/sales-orders/')) {
    const id = path.split('/').at(-1)
    const data = selected.some((order) => id === order.id) && details.find((order) => id === order.id)
    return data ? { status: 200, data } : { status: 404 }
  }
  if (path.startsWith('/reports/')) {
    const items = actor.scoped ? reportRows.slice(0, 1) : reportRows
    return { status: 200, data: { items } }
  }
  const position = reportRows.findIndex((item) => path === '/shipments/' + item.sourceId)
  return {
    status: 200,
    data: { lines: [{ id: orders[position].shipmentLineId, orderNo: orders[position].documentNo }] },
  }
}

test('用保留字符串的真实 ID、日期和商品生成三条确定性读取路径', () => {
  const valid = parseScopeFixture(JSON.stringify(fixture))
  assert.deepEqual(scopeRequests(valid), [
    { operation: '/document-browser/sales-orders', path: '/document-browser/sales-orders?dateFrom=2026-11-01&dateTo=2026-11-17&limit=20&withTotal=true' },
    { operation: '/sales-orders/:id', path: '/sales-orders/994001' },
    { operation: '/reports/sales-summary', path: '/reports/sales-summary?dateFrom=2026-11-01&dateTo=2026-11-17&detail=true&skuId=993001&limit=20' },
  ])
  assert.throws(() => parseScopeFixture(JSON.stringify({ ...fixture, companyId: 991001 })), /公司、仓库和商品 ID/)
  assert.throws(() => parseScopeFixture(JSON.stringify({
    ...fixture,
    orders: { ...fixture.orders, departmentDenied: fixture.orders.visible },
  })), /正反例必须分别对应不同事实/)
})

test('有限角色能读自有单据与报表并对部门、仓库、公司和租户四级越权失败', () => {
  verifyScopeProbe(probe, read)
})

test('空数据、越权统计、外租户读成功或无关签收事实均拒绝开始计时', () => {
  const variants = [
    [(a, p) => a.scoped && p.startsWith('/reports/') ? { status: 200, data: { items: [] } } : read(a, p), /受限账号首页报表/],
    [(a, p) => a.scoped && p.includes('keyword=TEST-SO-B') ? { status: 200, data: { items: [], total: 1 } } : read(a, p), /范围外订单/],
    [(a, p) => a.companyId === probe.foreignTenantCompany ? { status: 200 } : read(a, p), /越权公司或租户/],
    [(a, p) => !a.scoped && p === '/shipments/997001' ? { status: 200, data: { lines: [] } } : read(a, p), /实际关联样本订单/],
  ]
  for (const [request, error] of variants) assert.throws(() => verifyScopeProbe(probe, request), error)
})
