import { ApiError } from '../../api/http'
import { workDetail, workList } from '../../api/wms-operations'
import type { PackBox } from '../../api/wms-operations'
import type { Id, Quantity } from '../../types/api'

export interface ShipmentBox extends PackBox {
  shipmentNo: string
}

export interface ShipmentBoxDetail extends ShipmentBox {
  lines: Array<{
    id: Id
    skuCode: string
    skuName: string
    batchNo: string | null
    qty: Quantity
    serialNos: string[]
  }>
}

type PackingScope = Pick<ShipmentBox, 'warehouseId' | 'shipmentId'>
export const BOX_LABEL_LIMIT = 100
const DETAIL_BATCH_SIZE = 6

function belongsToShipment(box: ShipmentBox, scope: PackingScope): boolean {
  return (
    box.status === 'done' &&
    box.warehouseId === scope.warehouseId &&
    box.shipmentId === scope.shipmentId
  )
}

export async function shipmentBoxes(
  scope: PackingScope,
  isCurrent: () => boolean,
): Promise<ShipmentBox[]> {
  const boxes = new Map<Id, ShipmentBox>()
  const cursors = new Set<Id>()
  let cursor: Id | undefined
  do {
    if (!isCurrent()) return []
    const page = await workList<ShipmentBox>('packs', {
      warehouseId: scope.warehouseId,
      shipmentId: scope.shipmentId,
      cursor,
    })
    if (!isCurrent()) return []
    for (const box of page.items) {
      if (belongsToShipment(box, scope)) boxes.set(box.id, box)
    }
    cursor = page.nextCursor ?? undefined
    if (cursor) {
      if (cursors.has(cursor))
        throw new ApiError(409, 'PACK_PAGE_CHANGED', '装箱记录分页异常，请重新查询。')
      cursors.add(cursor)
    }
  } while (cursor)
  if (!boxes.size) throw new ApiError(409, 'PACK_EMPTY', '该发货单没有已完成的装箱记录。')
  return [...boxes.values()].sort((left, right) =>
    left.boxNo.localeCompare(right.boxNo, 'zh-CN', { numeric: true }),
  )
}

export async function shipmentPackingList(
  scope: PackingScope,
  isCurrent: () => boolean,
): Promise<ShipmentBoxDetail[]> {
  const boxes = await shipmentBoxes(scope, isCurrent)
  const details: ShipmentBoxDetail[] = []
  for (let offset = 0; offset < boxes.length && isCurrent(); offset += DETAIL_BATCH_SIZE) {
    const batch = boxes.slice(offset, offset + DETAIL_BATCH_SIZE)
    const records = await Promise.all(
      batch.map((box) => workDetail<ShipmentBoxDetail>('packs', box.id)),
    )
    if (!isCurrent()) return []
    for (const [index, record] of records.entries()) {
      if (record.id !== batch[index]?.id || !belongsToShipment(record, scope)) {
        throw new ApiError(409, 'PACK_CHANGED', '装箱记录已变更，请重新查询。')
      }
    }
    details.push(...records)
  }
  return details
}
