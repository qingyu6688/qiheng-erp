import type { SalesDocument, SalesLine, SalesOrderChange } from './sales'
export type ProcurementKind = 'purchase-requisitions'|'purchase-orders'|'goods-receipts'|'purchase-returns'|'payments'|'account-transfers'|'stock-adjustments'|'opening-entries'
export interface ProcurementLine extends SalesLine {
  requisitionLineId?:string; receiptLineId?:string; inputTotalCost?:string; needDate?:string; payableNet?:string; receiveLimitBaseQty?:string
}
export interface ProcurementDocument extends SalesDocument {
  supplierId?:string; supplierName?:string; supplier?:{id:string;name:string}; lines?:ProcurementLine[]
  payableAmount?:string; debitAmount?:string; paymentType?:string; expenseItemId?:string
  fromAccountId?:string; toAccountId?:string; fromAccountName?:string; toAccountName?:string; feeAmount?:string
  entryType?:string; direction?:string; adjustType?:string; change?:SalesOrderChange
}
export interface ProcurementSpec {path:ProcurementKind;title:string;singular:string;permission:string;states:string[]}
export const procurementSpecs:ProcurementSpec[]=[
 {path:'purchase-requisitions',title:'请购管理',singular:'请购单',permission:'purchase_requisition',states:['draft','pending','approved','closed','cancelled']},
 {path:'purchase-orders',title:'采购订单',singular:'采购订单',permission:'purchase_order',states:['draft','pending','approved','change_pending','closed','cancelled']},
 {path:'goods-receipts',title:'采购收货',singular:'收货单',permission:'goods_receipt',states:['draft','submitted','posted','voided','reversed']},
 {path:'purchase-returns',title:'采购退货',singular:'采购退货单',permission:'purchase_return',states:['draft','submitted','posted','voided','reversed']},
 {path:'payments',title:'付款登记',singular:'付款单',permission:'payment',states:['draft','pending_confirm','confirmed','reversed']},
 {path:'account-transfers',title:'账户转账',singular:'转账单',permission:'account_transfer',states:['draft','pending_confirm','confirmed','reversed']},
 {path:'stock-adjustments',title:'库存期初',singular:'库存期初',permission:'stock_adjustment',states:['draft','pending','approved','posted','voided']},
 {path:'opening-entries',title:'往来与资金期初',singular:'期初余额',permission:'opening_entry',states:['draft','approved','posted','voided']},
]
export function procurementAction(kind:ProcurementKind,action:string):string {
 if(action==='confirm')return kind==='payments'?'确认付款':'确认转账'
 if(action==='close')return '关闭请购'
 return ''
}
