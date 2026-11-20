export const demoSalesKinds = [
  { path: 'sales-orders', title: '销售订单', permission: 'sales_order' },
  { path: 'shipments', title: '发货与签收', permission: 'shipment' },
  { path: 'receipts', title: '收款登记', permission: 'receipt' },
] as const
export const demoPurchaseKinds = [
  { path: 'purchase-orders', title: '采购订单', permission: 'purchase_order' },
  { path: 'goods-receipts', title: '采购收货', permission: 'goods_receipt' },
  { path: 'payments', title: '付款登记', permission: 'payment' },
] as const
export const demoReports = [
  { type: 'sales-summary', title: '销售报表' },
  { type: 'purchase-summary', title: '采购报表' },
  { type: 'inventory-summary', title: '库存汇总' },
  { type: 'inventory-flow', title: '库存收发存' },
  { type: 'receivable-aging', title: '应收账龄' },
  { type: 'payable-aging', title: '应付账龄' },
  { type: 'cash-journal', title: '资金流水' },
] as const
