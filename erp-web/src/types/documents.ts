import { formatPrintTime } from '../utils/format'
import { procurementSpecs } from './procurement'
import { salesSpecs } from './sales'
import { stockSpecs } from './stock-operations'

export const documentPrintPermissions = [...salesSpecs, ...procurementSpecs, ...stockSpecs].map(
  ({ permission }) => `${permission}:print`,
)
export interface Attachment {
  id: string
  ownerType?: string
  ownerId?: string
  fileName: string
  contentType: string
  sizeBytes: number
  createdAt: string
}
export interface PrintData {
  documentType: string
  company: { name: string; address?: string | null; phone?: string | null }
  document: Record<string, unknown>
  printedAt: string
}
export interface PrintField {
  label: string
  value: string
}
export interface PrintColumn {
  key: string
  label: string
}
export interface PrintTable {
  title: string
  columns: PrintColumn[]
  rows: Record<string, string>[]
}

export function documentPermission(type: string): string {
  return type === 'document_reversal' ? 'reversal' : type
}
export function documentType(permission: string): string {
  return permission === 'reversal' ? 'document_reversal' : permission
}
export function objectField(value: unknown, key: string): unknown {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)[key]
    : undefined
}
export function printValue(value: unknown): string {
  return value === null || value === undefined || typeof value === 'object' ? '—' : String(value)
}
export function printRecords(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter(
        (row): row is Record<string, unknown> =>
          !!row && typeof row === 'object' && !Array.isArray(row),
      )
    : []
}
export function firstPrintValue(...values: unknown[]): string {
  return printValue(values.find((value) => value !== null && value !== undefined && value !== ''))
}

const labels: Record<string, string> = {
  freight: '运费',
  loading: '装卸费',
  other: '其他费用',
  opening: '期初',
  gain: '其他入库',
  loss: '其他出库',
  block: '冻结',
  unblock: '解冻',
  scrap: '报废',
  receivable: '客户往来期初',
  payable: '供应商往来期初',
  cash: '资金期初',
  D: '借方',
  C: '贷方',
  delivery: '送货',
  pickup: '自提',
  submit: '提交',
  approve: '审批通过',
  reject: '驳回',
  withdraw: '撤回',
  revoke: '撤销审批',
  pending: '待处理',
  resolved: '已处理',
  passed: '合格',
  failed: '不合格',
  registered: '已登记',
}
function labeled(value: unknown): string {
  const raw = printValue(value)
  return labels[raw] ?? raw
}
function present(value: unknown): boolean {
  return value !== undefined && value !== null && value !== ''
}
function customPrintFields(row: Record<string, unknown>): Record<string, unknown> {
  const fields = row.printCustomFields
  return fields && typeof fields === 'object' && !Array.isArray(fields)
    ? (fields as Record<string, unknown>)
    : {}
}
function customPrintValue(value: unknown): unknown {
  if (typeof value === 'boolean') return value ? '是' : '否'
  return Array.isArray(value) ? value.join('、') : value
}

export function printMeta(document: Record<string, unknown>): PrintField[] {
  const fields: [string, unknown][] = [
    ['业务日期', document.businessDate ?? document.invoiceDate],
    [
      '往来单位',
      objectField(document.customer, 'name') ??
        objectField(document.supplier, 'name') ??
        document.partnerName ??
        document.customerName ??
        document.supplierName,
    ],
    ['仓库', document.warehouseName],
    ['调出仓库', document.fromWarehouseName],
    ['调入仓库', document.toWarehouseName],
    ['结算账户', document.cashAccountName],
    ['转出账户', document.fromAccountName],
    ['转入账户', document.toAccountName],
    ['经办人', document.requesterName],
    ['交货日期', document.deliveryDate],
    ['签收日期', document.signDate],
    ['调整类型', present(document.adjustType) ? labeled(document.adjustType) : undefined],
    ['期初类型', present(document.entryType) ? labeled(document.entryType) : undefined],
    ['借贷方向', present(document.direction) ? labeled(document.direction) : undefined],
    [
      '计价方式',
      present(document.priceTaxInclusive)
        ? document.priceTaxInclusive === true || document.priceTaxInclusive === 1
          ? '含税单价'
          : '未税单价'
        : undefined,
    ],
    [
      '来源单据',
      printRecords(document.sourceDocuments).length
        ? printRecords(document.sourceDocuments)
            .map((source) => printValue(source.documentNo))
            .join('、')
        : document.sourceNo,
    ],
    ['银行流水号', document.bankSerialNo],
    ['币种', document.currency],
    ['盘点快照时间', document.snapshotAt],
    ['原因', document.reason],
    ['备注', document.remark],
  ]
  for (const [label, value] of Object.entries(customPrintFields(document)))
    fields.push([label, customPrintValue(value)])
  return fields
    .filter(([, value]) => present(value))
    .map(([label, value]) => ({ label, value: printValue(value) }))
}

function decimalSum(values: unknown[]): string | undefined {
  if (
    !values.length ||
    values.some((value) => typeof value !== 'string' || !/^-?\d+(?:\.\d+)?$/.test(value))
  )
    return undefined
  const strings = values as string[],
    scale = Math.max(...strings.map((value) => value.split('.')[1]?.length ?? 0))
  const sum = strings.reduce((total, value) => {
    const negative = value.startsWith('-'),
      [whole, fraction = ''] = value.replace(/^-/, '').split('.')
    const digits = BigInt(whole + fraction.padEnd(scale, '0'))
    return total + (negative ? -digits : digits)
  }, 0n)
  const digits = (sum < 0n ? -sum : sum).toString().padStart(scale + 1, '0')
  return (
    (sum < 0n ? '-' : '') + (scale ? digits.slice(0, -scale) + '.' + digits.slice(-scale) : digits)
  )
}

export function printTotals(
  type: string,
  document: Record<string, unknown>,
  canCost: boolean,
): PrintField[] {
  const fields: [string, unknown][] = [
    ['货款金额', document.goodsAmount],
    ['行优惠', document.lineDiscountAmount],
    ['整单优惠', document.orderDiscountAmount],
    [type === 'account_transfer' ? '手续费' : '费用合计', document.feeAmount],
    ['未税金额', document.untaxedAmount],
    ['税额', document.taxAmount],
    ['合计金额', document.totalAmount],
    ['应收金额', document.receivableAmount],
    ['应付金额', document.payableAmount],
    ['退货信用额', document.creditAmount],
    ['冲减应付', document.debitAmount],
    ['金额', document.amount],
  ]
  if (canCost) {
    const lines = printRecords(document.lines)
    const cost =
      document.costAmount ??
      document.totalCost ??
      (type === 'stock_adjustment'
        ? decimalSum(lines.map((line) => line.costAmount ?? line.inputTotalCost))
        : undefined)
    fields.push(['成本合计', cost])
  }
  return fields
    .filter(([, value]) => present(value))
    .map(([label, value]) => ({ label, value: printValue(value) }))
}

function product(row: Record<string, unknown>): string {
  const code = objectField(row.sku, 'code') ?? row.skuCode
  const name = objectField(row.sku, 'name') ?? row.skuName
  const unit = objectField(row.unit, 'name') ?? row.unitName ?? row.baseUnitName
  const warehouse = objectField(row.warehouse, 'name') ?? row.warehouseName
  return (
    [
      code,
      name,
      present(unit) ? '单位：' + printValue(unit) : undefined,
      present(warehouse) ? '仓库：' + printValue(warehouse) : undefined,
    ]
      .filter(present)
      .map(printValue)
      .join('\n') || '—'
  )
}

function lineColumns(type: string, canCost: boolean): PrintColumn[] {
  const common = [
    { key: 'number', label: '序号' },
    { key: 'product', label: '商品 / 单位' },
  ]
  if (type === 'invoice_registration')
    return [
      { key: 'number', label: '序号' },
      { key: 'sourceNo', label: '来源单据' },
      { key: 'amount', label: '本次开票金额' },
    ]
  if (type === 'stock_count')
    return [
      ...common,
      { key: 'bookQty', label: '账面数量' },
      { key: 'countedQty', label: '实盘数量' },
      { key: 'diffQty', label: '差异数量' },
      ...(canCost
        ? [
            { key: 'inputUnitCost', label: '盘盈录入成本' },
            { key: 'costAmount', label: '差异成本' },
          ]
        : []),
    ]
  if (type === 'stock_transfer')
    return [
      ...common,
      { key: 'qty', label: '调出数量' },
      { key: 'baseQty', label: '调出基本数量' },
      { key: 'receivedBaseQty', label: '实收基本数量' },
      { key: 'diffBaseQty', label: '差异基本数量' },
      { key: 'diffStatus', label: '差异状态' },
      ...(canCost ? [{ key: 'costAmount', label: '调出成本' }] : []),
    ]
  if (type === 'stock_adjustment')
    return [
      ...common,
      { key: 'qty', label: '单据数量' },
      { key: 'baseQty', label: '基本数量' },
      ...(canCost
        ? [
            { key: 'inputTotalCost', label: '录入总成本' },
            { key: 'unitCost', label: '单位成本' },
            { key: 'costAmount', label: '过账成本' },
          ]
        : []),
    ]
  if (type === 'purchase_requisition')
    return [
      ...common,
      { key: 'qty', label: '请购数量' },
      { key: 'baseQty', label: '基本数量' },
      { key: 'needDate', label: '需求日期' },
      { key: 'remark', label: '备注' },
    ]
  if (type === 'sales_order' || type === 'purchase_order')
    return [
      ...common,
      { key: 'qty', label: '数量' },
      { key: 'unitPrice', label: '单价' },
      { key: 'lineDiscountAmount', label: '行优惠' },
      { key: 'orderDiscountAlloc', label: '整单优惠分摊' },
      { key: 'taxRate', label: '税率' },
      { key: 'untaxedAmount', label: '未税金额' },
      { key: 'taxAmount', label: '税额' },
      { key: 'lineAmount', label: '含税净额' },
    ]
  return [
    ...common,
    { key: 'qty', label: '单据数量' },
    { key: 'baseQty', label: '基本数量' },
    { key: 'untaxedAmount', label: '未税金额' },
    { key: 'taxAmount', label: '税额' },
    { key: 'lineAmount', label: '确认金额' },
    ...(canCost ? [{ key: 'costAmount', label: '成本金额' }] : []),
  ]
}

export function printTables(
  type: string,
  document: Record<string, unknown>,
  canCost: boolean,
): PrintTable[] {
  const tables: PrintTable[] = [],
    lines = printRecords(document.lines)
  if (lines.length) {
    const columns = lineColumns(type, canCost)
    const customColumns =
      type === 'sales_order'
        ? [...new Set(lines.flatMap((line) => Object.keys(customPrintFields(line))))].map(
            (label, index) => ({ key: `custom-field-${index}`, label }),
          )
        : []
    const rows = lines.map((line, index) => {
      const result: Record<string, string> = {}
      for (const column of columns) result[column.key] = printValue(line[column.key])
      result.number = String(index + 1)
      result.product = product(line)
      if (type === 'invoice_registration') {
        const sources = printRecords(document.sourceDocuments)
        result.sourceNo = firstPrintValue(
          line.sourceNo,
          sources.length === 1 ? sources[0].documentNo : undefined,
        )
      }
      result.lineAmount = firstPrintValue(
        line.receivableNet,
        line.payableNet,
        line.receivableAmount,
        line.payableAmount,
        line.creditAmount,
        line.debitAmount,
        line.amount,
      )
      if (present(line.diffStatus)) result.diffStatus = labeled(line.diffStatus)
      const custom = customPrintFields(line)
      for (const column of customColumns) {
        const value = Object.hasOwn(custom, column.label) ? custom[column.label] : undefined
        result[column.key] = printValue(customPrintValue(value))
      }
      return result
    })
    tables.push({ title: '单据明细', columns: [...columns, ...customColumns], rows })
  }
  const fees = [...printRecords(document.fees), ...printRecords(document.recognizedFees)]
  if (fees.length)
    tables.push({
      title: '费用明细',
      columns: [
        { key: 'feeType', label: '费用项目' },
        { key: 'bearer', label: '承担方' },
        { key: 'amount', label: '原金额' },
        { key: 'taxRate', label: '税率' },
        { key: 'untaxedAmount', label: '未税金额' },
        { key: 'taxAmount', label: '税额' },
        { key: 'totalAmount', label: '含税金额' },
      ],
      rows: fees.map((fee) => ({
        feeType: present(fee.feeType) ? labeled(fee.feeType) : '已确认费用',
        bearer:
          fee.bearer === 'buyer'
            ? type === 'purchase_order' || type === 'goods_receipt'
              ? '我方承担'
              : '买方承担'
            : fee.bearer === 'seller'
              ? type === 'purchase_order' || type === 'goods_receipt'
                ? '供应商承担'
                : '我方承担'
              : '—',
        amount: printValue(fee.amount),
        taxRate: printValue(fee.taxRate),
        untaxedAmount: printValue(fee.untaxedAmount),
        taxAmount: printValue(fee.taxAmount),
        totalAmount: firstPrintValue(fee.totalAmount, fee.amount),
      })),
    })
  const approvals = printRecords(document.approvalRecords)
  if (approvals.length)
    tables.push({
      title: '审批记录',
      columns: [
        { key: 'action', label: '审批动作' },
        { key: 'operatorName', label: '操作人' },
        { key: 'createdAt', label: '时间' },
        { key: 'comment', label: '意见' },
      ],
      rows: approvals.map((row) => ({
        action: labeled(row.action),
        operatorName: printValue(row.operatorName),
        createdAt: formatPrintTime(typeof row.createdAt === 'string' ? row.createdAt : undefined),
        comment: printValue(row.comment),
      })),
    })
  return tables
}
