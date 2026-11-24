import { demoCompletenessMetadata, demoCostRevaluation, demoSalesQuotes, demoSupplierPrices } from '../demo/completeness-data'
import { people, products, snapshotDate } from '../demo/data'

type Params = Record<string, unknown>
function page<T extends { id: string; status?: string }>(rows: T[], params: Params): { items: T[]; nextCursor: string | null } {
  const filtered = rows.filter((row) => !params.status || row.status === params.status)
  const offset = Math.max(0, Number(params.cursor) || 0), limit = Math.max(1, Math.min(100, Number(params.limit) || 20))
  return { items: filtered.slice(offset, offset + limit), nextCursor: offset + limit < filtered.length ? String(offset + limit) : null }
}

export function readCompletenessDemo(path: string, params: Params): unknown {
  if (path === '/purchase-price-lists') return page([demoSupplierPrices.header], params)
  if (path === '/purchase-price-lists/' + demoSupplierPrices.header.id) return demoSupplierPrices
  if (path === '/sales-quotes') return page(demoSalesQuotes, params)
  if (path === '/cost-revaluations') return page([demoCostRevaluation], params)
  if (['/sales-quotes/metadata', '/cost-revaluations/metadata'].includes(path)) return demoCompletenessMetadata
  if (/^\/sales-quotes\/\d+$/.test(path)) return demoSalesQuotes.find((row) => path.endsWith('/' + row.id))
  if (path === '/cost-revaluations/' + demoCostRevaluation.id) return demoCostRevaluation
  const sku = /^\/skus\/(\d+)$/.exec(path)
  if (sku) {
    const value = products.find((row) => row.id === sku[1])
    if (value) return { ...value, status: 'active', units: [{ unitId: '1', unitName: value.unit, rateToBase: '1', isBase: true }] }
  }
  if (path === '/workflow/progress' && params.businessType === 'cost_revaluation' && params.businessId === demoCostRevaluation.id) {
    return { canOpen: false, instances: [{ id: demoCostRevaluation.wfInstanceId, status: 'approved', modelVersion: 1,
      startedAt: snapshotDate + 'T08:00:00+08:00', endedAt: snapshotDate + 'T09:00:00+08:00' }],
    tasks: [{ id: '98312', nodeKey: 'review', nodeName: '成本差异复核', nodeKind: 'approver', assigneeId: '302', assigneeName: people[1]!.name,
      status: 'approved', comment: '同意按预览差额执行（虚构审批样例）', createdAt: snapshotDate + 'T08:00:00+08:00', endedAt: snapshotDate + 'T09:00:00+08:00' }] }
  }
  return undefined
}
