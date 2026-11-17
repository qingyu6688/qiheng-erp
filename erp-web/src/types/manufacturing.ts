import type { Id, Money, Quantity } from './api'
import type { StockSelection } from './inventory-tracking'

export const manufacturingNavigation = [
  { area: 'boms', title: '物料清单', permission: 'mfg_bom:view' },
  { area: 'work-orders', title: '生产工单', permission: 'mfg_work_order:view' },
  { area: 'issues', title: '生产领退料', permission: 'mfg_issue:view' },
  { area: 'receipts', title: '完工入库', permission: 'mfg_receipt:view' },
  { area: 'cost-periods', title: '生产成本', permission: 'mfg_cost:view' },
  { area: 'reports', title: '生产报表', permission: 'mfg_report:view' },
] as const

export type ManufacturingArea = (typeof manufacturingNavigation)[number]['area']
export type StockArea = 'issues' | 'receipts'

export interface ManufacturingVersion {
  id: Id
  status: string
  version: number
  allowedActions?: string[]
}

export interface SubstituteDraft {
  substituteSkuId: Id
  unitId: Id
  ratio: Quantity
  priority: number
  skuName?: string
  skuCode?: string
}

export interface BomLineDraft {
  componentSkuId: Id
  componentSkuCode?: string
  componentSkuName?: string
  unitId: Id
  qty: Quantity
  scrapRate: Quantity
  issueWarehouseId?: Id | null
  phantom: boolean
  remark?: string
  substitutes: SubstituteDraft[]
}

export interface BomDraft {
  skuId: Id
  versionNo: string
  unitId: Id
  baseQty: Quantity
  validFrom: string
  validTo?: string | null
  isDefault: boolean
  remark?: string
  lines: BomLineDraft[]
  version?: number
}

export type Bom = ManufacturingVersion & BomDraft & { skuCode?: string; skuName?: string }

export interface BomPreview {
  componentSkuId: Id
  skuCode?: string
  skuName?: string
  unitId: Id
  unitName?: string
  requiredQty: Quantity
  availableQty?: Quantity | null
  issueWarehouseId?: Id | null
  warehouseName?: string
  path?: Id[] | string
  phantom?: boolean
  consumable?: boolean
  substitutes?: SubstituteDraft[]
}

export interface WorkOrderDraft {
  skuId: Id
  bomId?: Id
  unitId: Id
  planQty: Quantity
  issueWarehouseId: Id
  receiveWarehouseId: Id
  planStartDate: string
  planFinishDate: string
  overReceivePct?: Quantity
  remark?: string
  version?: number
}

export interface WorkMaterial {
  id: Id
  componentSkuId: Id
  skuCode?: string
  skuName?: string
  unitId: Id
  unitName?: string
  issueWarehouseId?: Id | null
  requiredQty: Quantity
  issuedQty: Quantity
  returnedQty: Quantity
  varianceQty?: Quantity
  substitutes?: SubstituteDraft[]
}

export type WorkOrder = WorkOrderDraft &
  ManufacturingVersion & {
    bomId: Id
    documentNo: string
    skuCode?: string
    skuName?: string
    completedQty: Quantity
    materials?: WorkMaterial[]
    issues?: ProductionDocument[]
    receipts?: ProductionDocument[]
    issueWarehouseName?: string
    receiveWarehouseName?: string
    issueWarehouseCode?: string
    receiveWarehouseCode?: string
    sourceType?: string
    sourceId?: Id
  }

export interface SalesWorkDraft {
  salesOrderId: Id
  salesOrderLineId: Id
  bomId?: Id
  issueWarehouseId: Id
  receiveWarehouseId: Id
  planStartDate: string
  planFinishDate: string
  overReceivePct?: Quantity
  remark?: string
}

export interface ProductionLineDraft {
  materialId?: Id
  skuId?: Id
  qty: Quantity
  stockSelection?: StockSelection | null
  originalIssueLineId?: Id
}

export interface ProductionDraft {
  workOrderId: Id
  warehouseId: Id
  businessDate: string
  direction?: 'issue' | 'return'
  remark?: string
  lines: ProductionLineDraft[]
  version?: number
}

export interface ProductionLine extends ProductionLineDraft {
  id: Id
  skuId: Id
  skuCode?: string
  skuName?: string
  unitName?: string
  materialQty?: Quantity
  unitCost?: Money
  estimatedUnitCost?: Money
  amount?: Money
}

export type ProductionDocument = Omit<ProductionDraft, 'lines'> &
  ManufacturingVersion & {
    documentNo: string
    workOrderNo?: string
    warehouseName?: string
    overIssue?: boolean
    workflowInstanceId?: Id
    workflowStatus?: string
    lines?: ProductionLine[]
  }

export interface CostExpense {
  kind: 'labor' | 'overhead'
  source: 'manual' | 'gl'
  amount?: Money
  accountCode?: string
  allocationBasis: 'hours' | 'quantity' | 'material'
}

export interface CostAllocation {
  workOrderId: Id
  documentNo?: string
  skuId?: Id
  skuCode?: string
  planQty?: Quantity
  periodCompletedQty?: Quantity
  hours?: Quantity
  progressPct?: Quantity
}

export interface CostRow {
  workOrderId: Id
  documentNo?: string
  period?: string
  materialCost: Money
  laborCost: Money
  overheadCost: Money
  wipBegin: Money
  wipEnd: Money
  finishedCost: Money
  estimatedCost: Money
  variance: Money
  inventoryVariance: Money
  issuedVariance: Money
}

export interface ManufacturingPeriod {
  periodId: Id
  period: string
  startDate: string
  endDate: string
  status: 'open' | 'calculated' | 'posted'
  accountingStatus?: 'open' | 'closed'
  reopenCount?: number
  version: number
  wipMethod?: 'materials' | 'equivalent'
  expenses?: CostExpense[]
  orders?: CostAllocation[]
  items?: CostRow[]
  hasAllocation?: boolean
}

export const manufacturingStates: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  approved: '已审核',
  disabled: '已停用',
  released: '已下达',
  in_progress: '生产中',
  completed: '已完工',
  closed: '已关闭',
  cancelled: '已取消',
  posted: '已过账',
  reversed: '已冲销',
  rejected: '已驳回',
  open: '开放',
  calculated: '已计算',
}

export const manufacturingActions: Record<string, string> = {
  submit: '提交',
  approve: '审核',
  reject: '驳回',
  disable: '停用',
  release: '下达',
  close: '关闭',
  cancel: '取消',
  post: '过账',
}
