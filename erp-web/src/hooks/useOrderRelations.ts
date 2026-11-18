import { computed, ref, watch } from 'vue'
import { isCancelled, messageOf } from '../api/http'
import { orderRelated } from '../api/order-related'
import type { OrderRelatedRow, OrderResource, RelatedResource } from '../api/order-related'
import { useSessionStore } from '../stores/session'

export interface OrderRelationContext {
  kind: OrderResource
  id: string
  version: number
}

interface RelationGroup {
  resource: RelatedResource
  linkResource?: 'receipts' | 'payments'
  label: string
  section: 'fulfillment' | 'settlement'
  permissions: string[]
}

interface RelationPage {
  items: OrderRelatedRow[]
  next: string | null
  busy: boolean
  error: string
}

const definitions: Record<OrderResource, RelationGroup[]> = {
  'sales-orders': [
    {
      resource: 'shipments',
      label: '发货单',
      section: 'fulfillment',
      permissions: ['shipment:view'],
    },
    {
      resource: 'sales-returns',
      label: '销售退货单',
      section: 'fulfillment',
      permissions: ['shipment:view', 'sales_return:view'],
    },
    {
      resource: 'receipts',
      label: '按订单登记的收款单',
      section: 'settlement',
      permissions: ['receipt:view'],
    },
    {
      resource: 'settlement-allocations',
      linkResource: 'receipts',
      label: '收款核销记录',
      section: 'settlement',
      permissions: ['settlement:view', 'receipt:view', 'shipment:view'],
    },
  ],
  'purchase-orders': [
    {
      resource: 'goods-receipts',
      label: '收货单',
      section: 'fulfillment',
      permissions: ['goods_receipt:view'],
    },
    {
      resource: 'purchase-returns',
      label: '采购退货单',
      section: 'fulfillment',
      permissions: ['goods_receipt:view', 'purchase_return:view'],
    },
    {
      resource: 'payments',
      label: '按订单登记的付款单',
      section: 'settlement',
      permissions: ['payment:view'],
    },
    {
      resource: 'settlement-allocations',
      linkResource: 'payments',
      label: '付款核销记录',
      section: 'settlement',
      permissions: ['settlement:view', 'payment:view', 'goods_receipt:view'],
    },
  ],
}

export function useOrderRelations(context: () => OrderRelationContext | undefined) {
  const session = useSessionStore()
  const groups = computed(() => {
    const order = context()
    return order
      ? definitions[order.kind].filter((group) => group.permissions.every(session.can))
      : []
  })
  const pages = ref<Partial<Record<RelatedResource, RelationPage>>>({})
  let requests = new AbortController()

  async function load(resource: RelatedResource, more = false): Promise<void> {
    const order = context()
    const page = pages.value[resource]
    if (!order || !page || page.busy || (more && !page.next)) return
    const { signal } = requests
    page.busy = true
    page.error = ''
    try {
      const result = await orderRelated(
        order.kind,
        order.id,
        resource,
        more ? page.next : null,
        signal,
      )
      if (signal.aborted) return
      page.items = more ? [...page.items, ...result.items] : result.items
      page.next = result.nextCursor
    } catch (cause: unknown) {
      if (!signal.aborted && !isCancelled(cause)) page.error = messageOf(cause)
    } finally {
      page.busy = false
    }
  }

  watch(
    [context, groups, () => session.companyId],
    (_, _previous, onCleanup) => {
      requests = new AbortController()
      const requestsForOrder = requests
      onCleanup(() => requestsForOrder.abort())
      pages.value = {}
      if (!session.companyId) return
      for (const group of groups.value) {
        pages.value[group.resource] = { items: [], next: null, busy: false, error: '' }
        void load(group.resource)
      }
    },
    { immediate: true },
  )

  const sections = computed(() =>
    groups.value.flatMap((group) => {
      const page = pages.value[group.resource]
      return page ? [{ ...group, page }] : []
    }),
  )
  return { sections, load }
}
