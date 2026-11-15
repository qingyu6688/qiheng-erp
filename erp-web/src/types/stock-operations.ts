export type StockKind = 'stock-transfers' | 'stock-counts' | 'document-reversals'
export interface StockSpec { path: StockKind; title: string; singular: string; permission: string; states: string[] }
export const stockSpecs: StockSpec[] = [
  { path: 'stock-transfers', title: '库存调拨', singular: '调拨单', permission: 'stock_transfer', states: ['draft', 'submitted', 'shipped', 'received_with_diff', 'received', 'voided'] },
  { path: 'stock-counts', title: '库存盘点', singular: '盘点单', permission: 'stock_count', states: ['draft', 'counting', 'submitted', 'approved', 'posted', 'voided'] },
  { path: 'document-reversals', title: '单据冲销', singular: '冲销单', permission: 'reversal', states: ['pending', 'approved', 'posted', 'rejected'] },
]
export interface StockLine {
  id: string; skuId: string; skuName?: string; unitId?: string; unitName?: string; qty?: string; baseQty?: string
  receivedBaseQty?: string; diffBaseQty?: string; diffStatus?: string; remainingQty?: string
  baseUnitName?: string; bookQty?: string; countedQty?: string; diffQty?: string; inputUnitCost?: string; unitCost?: string; costAmount?: string
}
export interface StockDocument {
  id: string; documentNo: string; businessDate: string; status: string; version: number; allowedActions: string[]
  warehouseId?: string; warehouseName?: string; fromWarehouseId?: string; toWarehouseId?: string; fromWarehouseName?: string; toWarehouseName?: string
  remark?: string; reason?: string; snapshotAt?: string; snapshotMovementId?: string; countMode?: string
  sourceType?: string; sourceId?: string; sourceNo?: string; lines?: StockLine[]
}
export interface ReversalSource { id: string; documentNo: string; sourceType?: string; status?: string; businessDate?: string }
export const reversalTypes = [
  { value: 'shipment', label: '发货单', permission: 'shipment:view' },
  { value: 'sales_return', label: '销售退货单', permission: 'sales_return:view' },
  { value: 'goods_receipt', label: '采购收货单', permission: 'goods_receipt:view' },
  { value: 'purchase_return', label: '采购退货单', permission: 'purchase_return:view' },
  { value: 'receipt', label: '收款单', permission: 'receipt:view' },
  { value: 'payment', label: '付款单', permission: 'payment:view' },
  { value: 'account_transfer', label: '账户转账', permission: 'account_transfer:view' },
  { value: 'stock_adjustment', label: '库存调整单', permission: 'stock_adjustment:view' },
]
export const stockActionLabels: Record<string, string> = { update: '编辑', submit: '提交', start: '开始盘点', record_counts: '录入实盘', ship: '确认调出', receive: '登记调入', resolve_diff: '处理调拨差异', 'resolve-diff': '处理调拨差异', approve: '审批通过', reject: '驳回', post: '过账', void: '作废' }
export const stockStatusLabels: Record<string, string> = { draft: '草稿', submitted: '已提交', shipped: '在途', received_with_diff: '调入差异待处理', received: '已调入', voided: '已作废', counting: '盘点中', approved: '已批准', posted: '已过账', pending: '待审核', rejected: '已驳回' }
