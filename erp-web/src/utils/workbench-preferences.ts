import type { InjectionKey, Ref } from 'vue'

export const SHORTCUT_LIMIT = 8
const RECENT_LIMIT = 5
export const shortcuts = [
  { path: '/sales-orders/new', title: '新建销售订单', permission: 'sales_order:create' },
  { path: '/purchase-orders/new', title: '新建采购订单', permission: 'purchase_order:create' },
  { path: '/receipts', title: '登记收款', permission: 'receipt:view' },
  { path: '/inventory-balances', title: '库存查询', permission: 'inventory:view' },
  { path: '/shipments', title: '发货与签收', permission: 'shipment:view' },
  { path: '/reports/sales-summary', title: '销售报表', permission: 'report:sales_summary' },
  { path: '/settlements', title: '核销工作台', permission: 'settlement:view' },
  { path: '/accounting-periods', title: '会计期间', permission: 'period:view' },
  { path: '/workflow/todo', title: '待我审批' },
  { path: '/alerts', title: '预警中心', permission: 'alert:view' },
  { path: '/quality', title: '来料质检', permission: 'qc_inspection:view' },
]

export interface RecentWorkPage {
  path: string
  title: string
}
export const recentWorkPages: InjectionKey<Readonly<Ref<RecentWorkPage[]>>> =
  Symbol('recentWorkPages')

export function workbenchScope(user?: string, company?: string | null): string | undefined {
  return user && company ? `erp.workbench.${user}.${company}` : undefined
}

function readPaths(scope: string, key: 'recent' | 'shortcuts'): string[] | undefined {
  try {
    const data: unknown = JSON.parse(localStorage.getItem(`${scope}.${key}`) ?? 'null')
    if (!Array.isArray(data)) return undefined
    return [...new Set(data.filter((item): item is string => typeof item === 'string'))]
  } catch {
    return undefined
  }
}

function savePaths(scope: string, key: 'recent' | 'shortcuts', paths: string[]): boolean {
  try {
    localStorage.setItem(`${scope}.${key}`, JSON.stringify(paths))
    return true
  } catch {
    return false
  }
}

export function shortcutPaths(scope?: string): string[] | undefined {
  return scope ? readPaths(scope, 'shortcuts')?.slice(0, SHORTCUT_LIMIT) : undefined
}

export function saveShortcuts(scope: string, paths: string[]): boolean {
  return savePaths(scope, 'shortcuts', paths.slice(0, SHORTCUT_LIMIT))
}

export function recentPaths(scope?: string): string[] {
  return scope ? (readPaths(scope, 'recent')?.slice(0, RECENT_LIMIT) ?? []) : []
}

export function visitWorkPage(scope: string, path: string, previous: string[]): string[] {
  const updated = [path, ...previous.filter((item) => item !== path)].slice(0, RECENT_LIMIT)
  savePaths(scope, 'recent', updated)
  return updated
}
