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
  targetType: 'all' | 'customer' | 'customer_level'
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

export function priceLists(cursor?: string): Promise<PageResult<PriceListHeader>> {
  return request({ url: '/price-lists', params: { cursor, limit: 100 } }, true)
}

export function priceList(id: Id): Promise<PriceListView> {
  return request({ url: '/price-lists/' + encodeURIComponent(id) }, true)
}

export function savePriceList(input: PriceListInput, id?: Id): Promise<PriceListView> {
  return request(
    {
      url: id ? '/price-lists/' + encodeURIComponent(id) : '/price-lists',
      method: id ? 'PUT' : 'POST',
      data: input,
    },
    true,
  )
}
