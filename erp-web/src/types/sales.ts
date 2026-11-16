export type SalesKind =
  | 'sales-orders'
  | 'shipments'
  | 'receipts'
  | 'sales-returns'
  | 'refund-requests'
  | 'invoice-registrations'
export interface SalesRef {
  id: string
  code?: string
  name: string
}
export interface SalesSkuUnit {
  unitId: string
  unitName: string
  rateToBase: string
  isBase: boolean
}
export interface SalesOrderChange {
  id: string
  changeNo?: string
  status: string
  version: number
  fromRevision?: number
  toRevision?: number
  requiresApproval?: boolean
}
export interface SalesLine {
  id: string
  lineNo?: number
  skuId?: string
  skuName?: string
  sku?: SalesRef
  warehouseId?: string
  warehouse?: SalesRef
  unitId?: string
  unitName?: string
  unit?: SalesRef
  orderLineId?: string
  shipmentLineId?: string
  unitRate?: string
  qty?: string
  baseQty?: string
  unitPrice?: string
  lineDiscountAmount?: string
  taxRate?: string
  receivableNet?: string
  receivableAmount?: string
  taxAmount?: string
  costAmount?: string
  signedQty?: string
  diffQty?: string
  diffDisposition?: string
  qualityResult?: string
  reservationState?: string
  reservedQty?: string
  progress?: {
    baseQty: string
    signedQty?: string
    signedBaseQty?: string
    shippedQty: string
    closedQty: string
    returnedQty: string
    shippableQty: string
  }
  [key: string]: unknown
}
export interface SalesDocument {
  salesUserId?: string
  salesUserName?: string
  buyerUserId?: string
  buyerUserName?: string
  deliveryAreaId?: string
  deliveryAddress?: string
  id: string
  documentNo?: string
  status: string
  version: number
  allowedActions: string[]
  customerId?: string
  customer?: SalesRef
  partnerId?: string
  partnerName?: string
  warehouseId?: string
  warehouse?: SalesRef
  cashAccountId?: string
  originalReceiptId?: string
  businessDate?: string
  invoiceDate?: string
  invoiceNo?: string
  deliveryDate?: string
  signDate?: string
  deliveryMethod?: string
  receiptType?: string
  incomeItemId?: string
  relatedOrderId?: string
  bankSerialNo?: string
  reason?: string
  remark?: string
  priceTaxInclusive?: boolean
  orderDiscountAmount?: string
  goodsAmount?: string
  lineDiscountAmount?: string
  feeAmount?: string
  totalAmount?: string
  amount?: string
  taxAmount?: string
  untaxedAmount?: string
  receivableAmount?: string
  change?: SalesOrderChange
  lines?: SalesLine[]
  fees?: { id?: string; feeType: string; bearer: string; amount: string; taxRate?: string }[]
  warnings?: { message: string }[]
  fulfillmentStatus?: string
  settlementStatus?: string
  afterSaleStatus?: string
  [key: string]: unknown
}
export interface SalesSpec {
  path: SalesKind
  title: string
  singular: string
  permission: string
}
export const salesSpecs: SalesSpec[] = [
  { path: 'sales-orders', title: '销售订单', singular: '销售订单', permission: 'sales_order' },
  { path: 'shipments', title: '发货与签收', singular: '发货单', permission: 'shipment' },
  { path: 'sales-returns', title: '销售退货', singular: '退货单', permission: 'sales_return' },
  { path: 'receipts', title: '收款登记', singular: '收款单', permission: 'receipt' },
  {
    path: 'refund-requests',
    title: '退款申请',
    singular: '退款申请',
    permission: 'refund_request',
  },
  {
    path: 'invoice-registrations',
    title: '开票登记',
    singular: '开票登记',
    permission: 'invoice_registration',
  },
]
export const actionLabels: Record<string, string> = {
  submit: '提交',
  approve: '审批通过',
  reject: '驳回',
  withdraw: '撤回',
  return: '退回草稿',
  revoke_approval: '撤销审批',
  'revoke-approval': '撤销审批',
  close_lines: '关闭剩余',
  'close-lines': '关闭剩余',
  cancel: '取消单据',
  reserve: '重新预留',
  change: '提交变更',
  changes: '提交变更',
  post: '过账',
  void: '作废',
  sign: '登记签收',
  resolve_diff: '处理签收差异',
  'resolve-diff': '处理签收差异',
  confirm: '确认到账',
  register_paid: '登记已退款',
  'register-paid': '登记已退款',
  change_approve: '批准变更',
  change_reject: '驳回变更',
  update: '编辑',
  delete: '删除草稿',
}
export const statusLabels: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  approved: '已批准',
  change_pending: '变更待审核',
  closed: '已关闭',
  cancelled: '已取消',
  submitted: '已提交',
  posted: '已出库待签收',
  signed: '已签收',
  signed_with_diff: '签收差异待处理',
  voided: '已作废',
  pending_confirm: '待确认',
  confirmed: '已确认',
  pending_review: '待审核',
  pending_payment: '待退款',
  refunded: '已退款',
  registered: '已登记',
  none: '未开始',
  partial: '部分完成',
  complete: '已完成',
  full: '已完成',
  processing: '处理中',
  partial_returned: '部分退回',
  completed: '已完成',
  reversed: '已冲销',
  reserved: '已预留',
  expired: '已过期',
  released: '已释放',
  active: '有效',
  consumed: '已耗用',
  revoked: '已撤销',
}
export const salesStatuses: Record<SalesKind, string[]> = {
  'sales-orders': ['draft', 'pending', 'approved', 'change_pending', 'closed', 'cancelled'],
  shipments: ['draft', 'submitted', 'posted', 'signed_with_diff', 'signed', 'voided', 'reversed'],
  receipts: ['draft', 'pending_confirm', 'confirmed', 'reversed'],
  'sales-returns': ['draft', 'submitted', 'posted', 'voided', 'reversed'],
  'refund-requests': ['draft', 'pending_review', 'pending_payment', 'refunded', 'cancelled'],
  'invoice-registrations': ['registered', 'voided'],
}
export function salesStatusLabel(kind: SalesKind, status: string): string {
  return status === 'posted' && kind === 'sales-returns'
    ? '已过账'
    : (statusLabels[status] ?? status)
}
export function today(): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
}
