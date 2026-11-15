export interface InventoryBalance {
  warehouseId: string
  warehouseName: string
  skuId: string
  skuCode: string
  skuName: string
  onHandQty: string
  reservedQty: string
  blockedQty: string
  availableQty: string
  pendingDemandQty: string
  unitCost?: string | null
  costAmount?: string | null
}
export interface InventorySnapshot {
  items: InventoryBalance[]
  nextCursor: string | null
  asOf: string
  summary: {
    costAmount?: string
    blockedQty: string
    shortageCount: number
    unreceivedCostAmount?: string
    unreceivedCount?: number
  }
}
export function demandExceedsAvailable(demand: string, available: string): boolean {
  const scaled = (value: string): bigint | undefined => {
    const parts = /^(-?)(\d{1,24})(?:\.(\d{1,6}))?$/.exec(value)
    if (!parts) return undefined
    const quantity = BigInt(parts[2]!) * 1_000_000n + BigInt((parts[3] ?? '').padEnd(6, '0'))
    return parts[1] ? -quantity : quantity
  }
  const required = scaled(demand),
    usable = scaled(available)
  return required !== undefined && usable !== undefined && required > usable
}
