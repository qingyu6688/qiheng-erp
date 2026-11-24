export type GlStandard = 'enterprise' | 'small_enterprise'
export type GlDirection = 'D' | 'C'
export type GlVoucherStatus = 'draft' | 'pending' | 'approved' | 'posted' | 'reversed'
export interface GlBook {
  exists?: boolean
  id?: string
  standard?: GlStandard
  status?: 'setup' | 'active'
  currency?: string
  startPeriodId?: string
  version?: number
  confirmedHash?: string | null
  confirmedBy?: string | null
  confirmedAt?: string | null
}
export interface GlAux {
  customerId?: string
  supplierId?: string
  deptId?: string
  employeeId?: string
  projectId?: string
  skuId?: string
  customId?: string
}
export interface GlSubject {
  id: string
  code: string
  name: string
  parentId?: string
  category: string
  direction: GlDirection
  isCash: number | boolean
  isBank: number | boolean
  isLeaf: number | boolean
  auxTypes: string
  customAuxTypeId?: string
  quantityUnit: string
  profitRole: string
  status: string
  version: number
}
export interface GlLine {
  id?: string
  digest: string
  subjectId: string
  direction: GlDirection
  amount: string
  quantity: string
  unitPrice?: string
  aux: GlAux
  cashFlowItemId?: string
  auxCustomerId?: string
  auxSupplierId?: string
  auxDeptId?: string
  auxEmployeeId?: string
  auxProjectId?: string
  auxSkuId?: string
  auxCustomId?: string
}
export interface GlVoucherInput {
  wordId: string
  voucherDate: string
  attachmentCount: number
  remark: string
  lines: GlLine[]
  version?: number
}
export interface GlVoucher extends GlVoucherInput {
  id: string
  periodId: string
  number: number
  source: 'manual' | 'business' | 'closing' | 'opening'
  sourceType?: string
  sourceId?: string
  sourceNo?: string
  returnMode?: 'original' | 'sourceless'
  returnSourceId?: string
  returnSourceNo?: string
  refundMode?: 'receipt' | 'sourceless_credit'
  status: GlVoucherStatus
  makerId: string
  approverId?: string
  reversalOfId?: string
  debitTotal: string
  creditTotal: string
  version: number
}
export interface GlConfigRow {
  id: string
  version: number
  code?: string
  name?: string
  status?: string
  [key: string]: unknown
}
export interface GlQuery {
  periodFrom: string
  periodTo: string
  subjectId?: string
  auxType?: string
  auxId?: string
  dateFrom?: string
  dateTo?: string
  level?: number
  cursor?: string
  limit?: number
}
export interface GlLedger {
  type: string
  items: Record<string, unknown>[]
  rowCount: number
  opening?: string
  closing?: string
  nextCursor?: string
}
export interface GlReportRow {
  code: string
  name: string
  amount: string
  yearAmount: string | null
  bold: boolean
  indent: number
  formula: string
  subjectCodes: string[]
  references?: string[]
}
export interface GlReport {
  type: string
  name: string
  periodId: string
  rows: GlReportRow[]
  checks: { name: string; passed: boolean; left: string; right: string; difference: string }[]
  passed: boolean
  yearNotice?: string
  configHash: string
}
export const glStatus: Record<GlVoucherStatus, string> = {
  draft: '草稿',
  pending: '待复核',
  approved: '已复核',
  posted: '已记账',
  reversed: '已冲销',
}
export const auxLabels: Record<string, string> = {
  customer: '客户',
  supplier: '供应商',
  dept: '部门',
  employee: '员工',
  project: '项目',
  sku: '存货',
  custom: '自定义辅助',
}
export const ledgerLabels: Record<string, string> = {
  balance: '科目余额表',
  general: '总分类账',
  detail: '明细分类账',
  multi_column: '多栏账',
  quantity: '数量金额账',
  auxiliary: '辅助核算账',
}
export function subjectAux(subject: GlSubject | undefined): string[] {
  if (!subject) return []
  const value: unknown = JSON.parse(subject.auxTypes)
  return Array.isArray(value) && value.every((item) => typeof item === 'string') ? value : []
}
