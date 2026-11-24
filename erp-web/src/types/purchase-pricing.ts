export interface PurchasePriceSource {
  lineNo: number
  source: 'manual' | 'supplier_price_list' | 'recent_purchase' | 'reference'
  referenceSource: 'supplier_price_list' | 'recent_purchase' | 'reference'
  suggestedUnitPrice: string | null
  priceListId: string | null
  priceListVersion: number | null
  historyLineId: string | null
  abovePriceList: boolean
}
export const purchasePriceSourceLabels: Record<string, string> = {
  manual: '手工录入', supplier_price_list: '供应商协议价', recent_purchase: '最近采购价', reference: '商品参考价',
}
