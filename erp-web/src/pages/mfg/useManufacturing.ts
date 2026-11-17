import { onScopeDispose, ref, shallowRef } from 'vue'
import { allRecords, record } from '../../api/admin'
import { isCancelled, messageOf } from '../../api/http'
import type { PageResult } from '../../types/api'
import type { DataRow } from '../../types/admin'
import type { SalesSkuUnit } from '../../types/sales'
import { useSessionStore } from '../../stores/session'

export function useManufacturingList<T>(read: (cursor?: string) => Promise<PageResult<T>>) {
  const items = shallowRef<T[]>([])
  const cursor = ref<string | null>(null)
  const loading = ref(false)
  const error = ref('')
  let revision = 0

  async function load(append = false): Promise<void> {
    if (append && (!cursor.value || loading.value)) return
    const current = ++revision
    if (!append) {
      items.value = []
      cursor.value = null
    }
    loading.value = true
    error.value = ''
    try {
      const page = await read(append ? (cursor.value ?? undefined) : undefined)
      if (current !== revision) return
      items.value = append ? [...items.value, ...page.items] : page.items
      cursor.value = page.nextCursor
    } catch (cause: unknown) {
      if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
    } finally {
      if (current === revision) loading.value = false
    }
  }

  onScopeDispose(() => {
    revision++
  })
  return { items, cursor, loading, error, load }
}

export function useManufacturingCatalog() {
  const session = useSessionStore()
  const warehouses = ref<DataRow[]>([])
  const skus = ref<DataRow[]>([])
  const units = ref<Record<string, SalesSkuUnit[]>>({})
  const catalogError = ref('')
  const requests = new Map<string, Promise<SalesSkuUnit[]>>()
  let disposed = false
  onScopeDispose(() => {
    disposed = true
  })

  async function loadCatalog(fields: Array<'warehouses' | 'skus'>): Promise<void> {
    const results = await Promise.allSettled(
      fields.map(async (field) => {
        if (!session.can(field === 'warehouses' ? 'warehouse:view' : 'sku:view')) return
        const rows = (await allRecords(field, field === 'warehouses')).filter(
          ({ status }) => status === 'active',
        )
        if (!disposed) (field === 'warehouses' ? warehouses : skus).value = rows
      }),
    )
    const error = results.find(({ status }) => status === 'rejected')
    if (error?.status === 'rejected' && !disposed && !isCancelled(error.reason))
      catalogError.value = messageOf(error.reason)
  }

  async function unitsFor(skuId: string): Promise<SalesSkuUnit[]> {
    if (!skuId || !session.can('sku:view')) return []
    if (units.value[skuId]) return units.value[skuId]
    let request = requests.get(skuId)
    if (!request) {
      request = record('skus/' + encodeURIComponent(skuId)).then(
        (row) => (row.units ?? []) as SalesSkuUnit[],
      )
      requests.set(skuId, request)
    }
    try {
      const list = await request
      if (!disposed) units.value[skuId] = list
      return list
    } catch (cause: unknown) {
      if (!disposed && !isCancelled(cause)) catalogError.value = messageOf(cause)
      return []
    } finally {
      requests.delete(skuId)
    }
  }

  return { warehouses, skus, units, catalogError, loadCatalog, unitsFor }
}

export function catalogLabel(row: DataRow): string {
  return [row.code, row.name].filter(Boolean).join(' · ') || row.id
}

const QUANTITY_SCALE = 1_000_000n

function scaled(input: string): bigint {
  const [, negative, integer, fraction = ''] = /^(-?)([0-9]+)(?:\.([0-9]{1,6}))?$/.exec(input) ?? []
  if (integer === undefined) throw new Error('数量须为最多六位小数的十进制数')
  const magnitude = BigInt(integer) * QUANTITY_SCALE + BigInt(fraction.padEnd(6, '0'))
  return negative ? -magnitude : magnitude
}

export function remainingMaterial(required: string, issued: string, returned: string): string {
  return formatQuantityUnits(scaled(required) - scaled(issued) + scaled(returned))
}

function formatQuantityUnits(value: bigint): string {
  const magnitude = value < 0n ? -value : value
  const fraction = (magnitude % QUANTITY_SCALE).toString().padStart(6, '0').replace(/0+$/, '')
  return (value < 0n ? '-' : '') + magnitude / QUANTITY_SCALE + (fraction ? '.' + fraction : '')
}
