import { demoBoms, demoBusinesses, demoContracts, demoCustomers, demoSlotBalances, demoWarehouseLayout, demoWorkOrders } from '../demo/extended-data'
import { demoLedger, demoPeriods, demoSubjects, demoVouchers } from '../demo/finance-data'
import { inventoryRows, people, snapshotTime } from '../demo/data'

type Params = Record<string, unknown>
function page<T extends object>(rows: T[], params: Params): { items: T[]; nextCursor: string | null; total: number } {
  const filtered = rows.filter((item) => {
    const row = item as Record<string, unknown>
    if (params.keyword && !JSON.stringify(row).toLowerCase().includes(String(params.keyword).trim().toLowerCase())) return false
    return ['status', 'partnerId', 'result', 'skuId', 'periodId', 'wordId', 'source'].every((key) => !params[key] || row[key] === params[key])
  })
  const offset = Math.max(0, Number(params.cursor) || 0), limit = Math.max(1, Math.min(200, Number(params.limit) || 30))
  return { items: filtered.slice(offset, offset + limit), nextCursor: offset + limit < filtered.length ? String(offset + limit) : null, total: filtered.length }
}

export function readExtendedDemo(path: string, params: Params): unknown {
  if (path === '/dicts') return { version: 'demo.2', unchanged: false, items: Object.fromEntries(Object.entries({
    customer_source: [['referral', '老客户推荐']], crm_industry: [['office', '办公用品']],
    crm_level: [['A', '重点客户'], ['B', '普通客户']], crm_follow_method: [['visit', '上门拜访']],
  }).map(([key, rows]) => [key, rows.map(([value, label]) => ({ id: value, value, label, color: '', enabled: true }))])) }
  if (path === '/areas/tree') return { version: 'demo.2', items: [] }
  if (path === '/crm/employee-options') return people.map((row) => ({ id: row.id, displayName: row.name, departmentId: '1' }))
  if (path === '/crm/follow-ups' && demoCustomers.some((row) => row.id === params.bizId) && params.bizType === 'customer') return page([{
    id: '79001', bizType: 'customer', bizId: params.bizId, method: 'visit', content: '核对本月办公用品补货需求，已确认配送安排。下周回访使用情况。',
    mentionedUserIds: [], authorName: people[0]!.name, createdAt: snapshotTime,
  }], params)
  const customerExtra = /^\/crm\/customers\/(\d+)\/(contacts|team)$/.exec(path)
  if (customerExtra && demoCustomers.some((row) => row.id === customerExtra[1])) return { items: customerExtra[2] === 'contacts'
    ? [{ id: '79501', name: '王经理（示例）', position: '采购负责人', isDecisionMaker: true, isDefault: true, version: 1 }]
    : [{ userId: '301', displayName: people[0]!.name, permission: 'read' }] }
  const collections: Record<string, object[]> = {
    '/crm/customers': demoCustomers, '/crm/businesses': demoBusinesses, '/crm/contracts': demoContracts,
    '/mfg/boms': demoBoms, '/mfg/work-orders': demoWorkOrders,
  }
  if (collections[path]) return page(collections[path], params)
  const parent = path.slice(0, path.lastIndexOf('/')), id = path.slice(path.lastIndexOf('/') + 1)
  if (collections[parent]) return collections[parent].find((row) => 'id' in row && row.id === id)
  const explode = /^\/mfg\/boms\/(\d+)\/explode$/.exec(path)
  if (explode) {
    const bom = demoBoms.find((row) => row.id === explode[1]), qty = Number(params.qty)
    if (!bom || !Number.isFinite(qty) || qty <= 0 || qty > 1000000 || (params.date && String(params.date) < bom.validFrom) || (params.warehouseId && params.warehouseId !== '101')) return undefined
    return { lines: bom.lines.map((row) => ({ ...row, skuCode: row.componentSkuCode, skuName: row.componentSkuName,
      requiredQty: String(qty * Number(row.qty) / Number(bom.baseQty)), availableQty: inventoryRows().find((item) => item.skuId === row.componentSkuId)?.availableQty,
      warehouseName: demoWarehouseLayout.warehouse.name, consumable: true, path: [bom.skuId, row.componentSkuId],
    })) }
  }
  if (path === '/warehouses/101/locations') return demoWarehouseLayout
  if (path === '/warehouses/101/locations/balances') return demoSlotBalances().filter((row) => (!params.locationId || row.locationId === params.locationId) && (!params.cursor || row.id > String(params.cursor))).slice(0, Number(params.limit) || 200)
  if (path === '/accounting-periods') return page(demoPeriods, params)
  if (path === '/gl/config/subjects') return demoSubjects
  if (path === '/gl/config/words') return [{ id: '1', name: '记', code: '记', version: 1, isDefault: true, status: 'active' }]
  if (['/gl/config/cash-flow-items', '/gl/config/voucher-templates'].includes(path)) return []
  if (path === '/gl/choices') return {}
  if (path === '/gl/vouchers') return page([...demoVouchers].reverse().filter((row) => !params.cursor || Number(row.id) < Number(params.cursor)), { ...params, cursor: undefined }).items
  if (/^\/gl\/vouchers\/\d+$/.test(path)) return demoVouchers.find((row) => row.id === id)
  if (path.startsWith('/gl/ledgers/')) return demoLedger(id, params)
  return undefined
}
