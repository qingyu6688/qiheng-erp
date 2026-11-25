export const demoSalesKinds = [
  { path: 'sales-orders', title: '销售订单', permission: 'sales_order' },
  { path: 'shipments', title: '发货与签收', permission: 'shipment' },
  { path: 'sales-returns', title: '销售退货', permission: 'sales_return' },
  { path: 'receipts', title: '收款登记', permission: 'receipt' },
  { path: 'refund-requests', title: '退款申请', permission: 'refund_request' },
] as const
export const demoPurchaseKinds = [
  { path: 'purchase-orders', title: '采购订单', permission: 'purchase_order' },
  { path: 'goods-receipts', title: '采购收货', permission: 'goods_receipt' },
  { path: 'payments', title: '付款登记', permission: 'payment' },
] as const
export const demoSalesExtraPages = [{ path: 'sales-quotes', title: '销售报价' }] as const
export const demoPurchaseExtraPages = [{ path: 'purchase-price-lists', title: '采购价格表' }] as const
export const demoReports = [
  { type: 'sales-summary', title: '销售报表' },
  { type: 'purchase-summary', title: '采购报表' },
  { type: 'inventory-summary', title: '库存汇总' },
  { type: 'inventory-flow', title: '库存收发存' },
  { type: 'receivable-aging', title: '应收账龄' },
  { type: 'payable-aging', title: '应付账龄' },
  { type: 'cash-journal', title: '资金流水' },
] as const

export const demoCrmPages = [
  { path: 'crm/customers', title: '客户档案' },
  { path: 'crm/businesses', title: '销售商机' },
  { path: 'crm/contracts', title: '客户合同' },
] as const
export const demoManufacturingPages = [
  { path: 'mfg/boms', title: '物料清单 BOM' },
  { path: 'mfg/work-orders', title: '生产工单' },
] as const
export const demoFinancePages = [
  { path: 'gl/vouchers', title: '总账凭证' },
  { path: 'gl/ledgers', title: '财务账簿' },
] as const

export const demoAiPages = [
  { path: 'ai/wiki', title: '知识百科' },
  { path: 'ai/knowledge', title: '知识库' },
  { path: 'ai/answers', title: 'AI 回答样例' },
] as const
