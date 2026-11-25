import { request } from './http'
import type { Id, PageResult } from '../types/api'

export interface PriceListHeader {
  id: Id
  name: string
  currency: string
  priceIncludesTax: boolean
  validFrom: string
  validTo: string
  priority: number
  status: 'active' | 'disabled'
  version: number
}

export interface PriceListItem {
  skuId: Id
  unitId: Id
  minQty: string
  unitPrice: string
}
export interface PriceListTarget {
  targetType: 'all' | 'customer' | 'customer_level' | 'supplier'
  targetValue: string
}
export interface PriceListView {
  header: PriceListHeader
  items: PriceListItem[]
  targets: PriceListTarget[]
}
export type PriceListInput = Omit<PriceListHeader, 'id' | 'currency' | 'version'> & {
  version?: number
  items: PriceListItem[]
  targets: PriceListTarget[]
}

export function priceLists(cursor?: string, purchase = false): Promise<PageResult<PriceListHeader>> {
  return request({ url: purchase ? '/purchase-price-lists' : '/price-lists', params: { cursor, limit: 100 } }, true)
}

export function priceList(id: Id, purchase = false): Promise<PriceListView> {
  return request({ url: (purchase ? '/purchase-price-lists/' : '/price-lists/') + encodeURIComponent(id) }, true)
}

export function savePriceList(input: PriceListInput, id?: Id, purchase = false): Promise<PriceListView> {
  const resource = purchase ? '/purchase-price-lists' : '/price-lists'
  return request(
    {
      url: id ? resource + '/' + encodeURIComponent(id) : resource,
      method: id ? 'PUT' : 'POST',
      data: input,
    },
    true,
  )
}
