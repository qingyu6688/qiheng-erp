export const reportSpecs = [
  {
    type: 'inventory-summary',
    title: '库存汇总',
    fields: [
      'warehouseName',
      'skuCode',
      'skuName',
      'onHandQty',
      'reservedQty',
      'blockedQty',
      'availableQty',
      'costAmount',
    ],
    snapshot: true,
  },
  {
    type: 'inventory-flow',
    title: '库存收发存',
    fields: [
      'warehouseName',
      'skuCode',
      'skuName',
      'openingQty',
      'inQty',
      'outQty',
      'closingQty',
      'openingCost',
      'inCost',
      'outCost',
      'closingCost',
    ],
  },
  {
    type: 'unsigned-shipments',
    title: '未签收出库',
    fields: [
      'sourceNo',
      'businessDate',
      'warehouseName',
      'partnerName',
      'skuName',
      'unsignedQty',
      'costAmount',
      'diffDisposition',
    ],
  },
  {
    type: 'sales-summary',
    title: '销售报表',
    fields: [
      'businessUserName',
      'businessDate',
      'partnerName',
      'warehouseName',
      'skuCode',
      'skuName',
      'qty',
      'amount',
      'untaxedAmount',
      'taxAmount',
      'costAmount',
      'sourceCount',
    ],
  },
  {
    type: 'purchase-summary',
    title: '采购报表',
    fields: [
      'businessUserName',
      'businessDate',
      'partnerName',
      'warehouseName',
      'skuCode',
      'skuName',
      'qty',
      'amount',
      'untaxedAmount',
      'taxAmount',
      'costAmount',
      'sourceCount',
    ],
  },
  {
    type: 'receivable-aging',
    title: '应收账龄',
    fields: [
      'partnerName',
      'sourceNo',
      'agingDate',
      'dueDate',
      'overdueDays',
      'direction',
      'openAmount',
      'ageDays',
      'age030',
      'age3160',
      'age6190',
      'age91180',
      'ageOver180',
    ],
    snapshot: true,
  },
  {
    type: 'payable-aging',
    title: '应付账龄',
    fields: [
      'partnerName',
      'sourceNo',
      'agingDate',
      'dueDate',
      'overdueDays',
      'direction',
      'openAmount',
      'ageDays',
      'age030',
      'age3160',
      'age6190',
      'age91180',
      'ageOver180',
    ],
    snapshot: true,
  },
  {
    type: 'partner-statement',
    title: '往来对账',
    fields: [
      'partnerName',
      'businessDate',
      'sourceNo',
      'accountCode',
      'direction',
      'debitAmount',
      'creditAmount',
      'openingBalance',
      'runningBalance',
    ],
  },
  {
    type: 'cash-journal',
    title: '资金流水',
    fields: [
      'cashAccountName',
      'businessDate',
      'sourceNo',
      'receiptAmount',
      'paymentAmount',
      'openingBalance',
      'runningBalance',
    ],
  },
  {
    type: 'gross-margin',
    title: '销售毛利',
    fields: [
      'businessUserName',
      'sourceNo',
      'businessDate',
      'partnerName',
      'warehouseName',
      'skuName',
      'qty',
      'untaxedAmount',
      'costAmount',
      'grossMargin',
    ],
  },
]
export const reportLabels: Record<string, string> = {
  businessUserName: '业务员',
  brandName: '品牌',
  warehouseName: '仓库',
  skuCode: '商品编码',
  skuName: '商品',
  partnerName: '往来单位',
  cashAccountName: '资金账户',
  onHandQty: '在库数量',
  reservedQty: '预留数量',
  blockedQty: '冻结数量',
  availableQty: '可用数量',
  costAmount: '成本金额',
  openingQty: '期初数量',
  inQty: '入库数量',
  outQty: '出库数量',
  closingQty: '期末数量',
  openingCost: '期初成本',
  inCost: '入库成本',
  outCost: '出库成本',
  closingCost: '期末成本',
  sourceNo: '来源单号',
  businessDate: '业务日期',
  unsignedQty: '未签收数量',
  diffDisposition: '差异处理',
  qty: '数量',
  amount: '含税金额',
  untaxedAmount: '不含税金额',
  taxAmount: '税额',
  agingDate: '起算日期',
  dueDate: '到期日',
  overdueDays: '逾期天数',
  direction: '方向',
  openAmount: '未核销金额',
  ageDays: '账龄天数',
  age030: '0–30天',
  age3160: '31–60天',
  age6190: '61–90天',
  age91180: '91–180天',
  ageOver180: '180天以上',
  accountCode: '科目',
  debitAmount: '借方发生额',
  creditAmount: '贷方发生额',
  openingBalance: '期初余额',
  runningBalance: '本行余额',
  closingBalance: '期末余额',
  receiptAmount: '收入',
  paymentAmount: '支出',
  grossMargin: '毛利',
  rowCount: '记录数',
  totalRows: '记录数',
  sourceCount: '明细行数',
  blockedQtyDelta: '冻结变动',
  postingAction: '过账动作',
}
export interface ReportResult {
  reportType: string
  asOf: string
  dataSource?: 'primary' | 'replica'
  visibilityBasis?: string
  statisticDate?: string
  rowCount?: number
  items: Record<string, unknown>[]
  nextCursor: string | null
  summary: Record<string, unknown>
}
export function reportPermission(type: string, operation = 'report'): string {
  return operation + ':' + type.replaceAll('-', '_')
}
export function reportCell(field: string, value: unknown): string {
  if (value === null || value === undefined || typeof value === 'object') return '—'
  const text = String(value)
  if (field === 'direction')
    return (
      (
        {
          D: '借方',
          C: '贷方',
          debit: '借方',
          credit: '贷方',
          receivable: '应收',
          payable: '应付',
        } as Record<string, string>
      )[text] ?? text
    )
  if (field === 'postingAction')
    return (
      ({ post: '过账', reverse: '冲销', adjustment: '成本调整' } as Record<string, string>)[text] ??
      text
    )
  if (field === 'accountCode')
    return ({ AR: '应收', AP: '应付', FUNDS: '资金' } as Record<string, string>)[text] ?? text
  if (field === 'diffDisposition')
    return (
      (
        { pending: '待处理', returned: '退回', lost: '损耗', none: '无差异' } as Record<
          string,
          string
        >
      )[text] ?? text
    )
  return text
}
export function documentLink(type: unknown, id: unknown): string | undefined {
  if (typeof type !== 'string' || typeof id !== 'string' || !/^[1-9][0-9]*$/.test(id))
    return undefined
  const paths: Record<string, string> = {
    sales_order: 'sales-orders',
    shipment: 'shipments',
    sales_return: 'sales-returns',
    receipt: 'receipts',
    refund_request: 'refund-requests',
    invoice_registration: 'invoice-registrations',
    purchase_requisition: 'purchase-requisitions',
    purchase_order: 'purchase-orders',
    goods_receipt: 'goods-receipts',
    purchase_return: 'purchase-returns',
    price_adjustment: 'purchase-price-adjustments',
    purchase_fee_doc: 'purchase-fee-documents',
    payment: 'payments',
    account_transfer: 'account-transfers',
    stock_adjustment: 'stock-adjustments',
    opening_entry: 'opening-entries',
    stock_transfer: 'stock-transfers',
    stock_count: 'stock-counts',
    stock_misc_in: 'stock-misc-in',
    stock_misc_out: 'stock-misc-out',
    document_reversal: 'document-reversals',
  }
  const aliases: Record<string, string> = {
    shipment_post: 'shipment',
    shipment_sign: 'shipment',
    shipment_diff: 'shipment',
    shipment_line: 'shipment',
    sales_order_fee: 'shipment',
    sales_return_line: 'sales_return',
    goods_receipt_line: 'goods_receipt',
    purchase_order_fee: 'goods_receipt',
    purchase_return_line: 'purchase_return',
    price_adjustment_line: 'price_adjustment',
    price_adjustment_cost: 'price_adjustment',
    purchase_fee_doc_line: 'purchase_fee_doc',
    purchase_fee_cost: 'purchase_fee_doc',
    goods_receipt_fee: 'goods_receipt',
    stock_transfer_out: 'stock_transfer',
    stock_transfer_in: 'stock_transfer',
    stock_transfer_diff: 'stock_transfer',
  }
  const path = paths[aliases[type] ?? type]
  return path ? '/' + path + '/' + id : undefined
}
export function reportToday(now = new Date()): string {
  return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(now)
}
