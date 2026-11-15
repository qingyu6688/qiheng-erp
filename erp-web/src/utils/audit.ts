import { formatDateTimeSeconds } from './format'

export interface AuditChange {
  field: string
  label: string
  before: string
  after: string
  changed: boolean
}
const labels: Record<string, string> = {
  status: '状态',
  version: '版本',
  name: '名称',
  code: '编码',
  remark: '备注',
  reason: '原因',
  qty: '数量',
  baseQty: '基础数量',
  signedQty: '签收数量',
  totalAmount: '单据金额',
  receivableAmount: '确认应收',
  payableAmount: '确认应付',
  amount: '金额',
  taxAmount: '税额',
  untaxedAmount: '未税金额',
  costAmount: '成本金额',
  businessDate: '业务日期',
  createdAt: '创建时间',
  approvedAt: '审核时间',
  signedAt: '签收时间',
  fulfillmentStatus: '履约状态',
  settlementStatus: '结算状态',
  lines: '明细',
  permissions: '权限',
  allowedActions: '可用操作',
  id: '编号',
  customerId: '客户编号',
  supplierId: '供应商编号',
  warehouseId: '仓库编号',
  priceTaxInclusive: '含税口径',
  taxRate: '税率',
  separateOrderApprover: '订单制单审核分离',
  separateReceiptConfirmer: '收付款制单确认分离',
  reservationValidDays: '预留有效天数',
  reservationGraceDays: '交货宽限天数',
}
const states: Record<string, string> = {
  draft: '草稿',
  pending: '待审核',
  approved: '已审核',
  posted: '已过账',
  signed: '已签收',
  signed_with_diff: '签收有差异',
  pending_confirm: '待确认',
  submitted: '已提交',
  pending_review: '待审核',
  pending_payment: '待退款',
  registered: '已登记',
  paid: '已退款',
  voided: '已作废',
  confirmed: '已确认',
  cancelled: '已取消',
  void: '已作废',
  active: '启用',
  disabled: '停用',
  open: '未关账',
  closed: '已关账',
  none: '未开始',
  partial: '部分完成',
  full: '全部完成',
}
const eventStates: Record<string, string> = {
  pending: '待执行',
  processing: '执行中',
  done: '已完成',
  dead: '失败待处理',
}
const jobStates: Record<string, string> = {
  pending: '待处理',
  running: '处理中',
  succeeded: '已完成',
  failed: '处理失败',
  partial: '部分成功',
  expired: '已过期',
}
function fieldKey(value: string): string {
  return value.replace(/_([a-z])/g, (_, letter: string) => letter.toUpperCase())
}
function fields(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? Object.fromEntries(Object.entries(value).map(([key, item]) => [fieldKey(key), item]))
    : value == null
      ? {}
      : { value }
}
function display(value: unknown, field: string, entityType: string): string {
  if (value === undefined || value === null) return '—'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (typeof value === 'object')
    return Array.isArray(value)
      ? value.map((item) => display(item, '', entityType)).join('；')
      : Object.entries(value)
          .map(
            ([key, item]) =>
              (labels[fieldKey(key)] ?? key) + '：' + display(item, fieldKey(key), entityType),
          )
          .join('，')
  const text = String(value)
  if (/At$/.test(field) && /^\d{4}-\d{2}-\d{2}T/.test(text)) return formatDateTimeSeconds(text)
  const vocabulary =
    entityType === 'outbox_event'
      ? eventStates
      : ['import_job', 'export_job'].includes(entityType)
        ? jobStates
        : states
  return /[Ss]tatus$/.test(field) ? (vocabulary[text] ?? states[text] ?? text) : text
}
export function auditChanges(before: unknown, after: unknown, entityType = ''): AuditChange[] {
  const previous = fields(before),
    current = fields(after)
  return [...new Set([...Object.keys(previous), ...Object.keys(current)])].map((field) => ({
    field,
    label: labels[field] ?? field,
    before: display(previous[field], field, entityType),
    after: display(current[field], field, entityType),
    changed: JSON.stringify(previous[field]) !== JSON.stringify(current[field]),
  }))
}
