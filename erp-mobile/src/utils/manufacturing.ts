import { formatQuantity, scaledQuantity } from './quantity'
import type { Id, ScanMatch } from '../types/api'
import type { IssueSelection, ProductionMaterial } from '../types/manufacturing'

export function materialQuantity(material: ProductionMaterial): string {
  const pending = (scaledQuantity(material.requiredQty) ?? 0n) - (scaledQuantity(material.issuedQty) ?? 0n) + (scaledQuantity(material.returnedQty) ?? 0n)
  return formatQuantity(pending > 0n ? pending : 0n)
}

export function identifiesMaterial(material: ProductionMaterial, sku: Id): boolean {
  return material.componentSkuId === sku || Boolean(material.substitutes?.some(({ substituteSkuId }) => substituteSkuId === sku))
}

export function scanIssueDetail(current: IssueSelection, scan: ScanMatch, sku: Id): IssueSelection {
  if (scan.type === 'location') return { ...current, locationId: scan.id }
  if (scan.skuId !== sku || (scan.type !== 'batch' && scan.type !== 'serial'))
    throw new Error('请扫描当前领料商品的批次、序列号或仓库库位')
  if (scan.type === 'batch') {
    if (current.serials?.length && current.batchId && current.batchId !== scan.id) throw new Error('请先清空已扫描的序列号，再更换批次')
    return { ...current, batchId: scan.id }
  }
  if (scan.batchId && current.batchId && current.batchId !== scan.batchId) throw new Error('扫描的序列号属于其他批次')
  if (scan.locationId && current.locationId && current.locationId !== scan.locationId) throw new Error('跨库位的序列号请分行领料')
  if (current.serials?.includes(scan.code)) throw new Error('序列号已经扫描')
  return { ...current, batchId: current.batchId ?? scan.batchId, locationId: current.locationId ?? scan.locationId, serials: [...(current.serials ?? []), scan.code] }
}
