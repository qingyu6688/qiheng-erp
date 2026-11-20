import type { CrmCustomer } from '../types/crm-core'
import type { CrmCycleRow } from '../types/crm-cycle'
import type { Bom, WorkOrder } from '../types/manufacturing'
import type { WarehouseLayout, SlotBalance } from '../api/supply-chain'
import { documents, inventoryRows, partners, products, warehouses } from './data'

export const demoCustomers: CrmCustomer[] = partners.filter((row) => row.isCustomer).map((row, index) => ({
  id: row.id, partnerId: row.id, partnerCode: row.code, partnerName: row.name,
  ownerUserId: '301', dealStatus: index === 3 ? 'unsold' : 'sold', status: 'active',
  canWrite: false, locked: false, version: 1, source: 'referral', industry: 'office',
  level: index < 2 ? 'A' : 'B', lastFollowAt: '2026-11-20T09:00:00+08:00',
  nextContactAt: '2026-11-25T10:00:00+08:00',
}))

const currentOrders = documents.filter((row) => row.kind === 'sales-orders' && row.businessDate.startsWith('2026-11'))
export const demoBusinesses: CrmCycleRow[] = currentOrders.map((row, index) => ({
  id: String(71001 + index), name: row.partnerName + ' · 冬季办公补货', partnerId: row.partnerId,
  partnerName: row.partnerName, ownerUserId: '301', expectedAmount: row.totalAmount,
  expectedDate: '2026-11-25', stageName: index === 3 ? '方案报价' : '合同签订',
  stageId: index === 3 ? '2' : '3', winRate: index === 3 ? '60' : '100', result: index === 3 ? 'open' : 'won',
  editable: false, allowedActions: [], lines: row.lines?.map((line) => ({ ...line, amount: row.totalAmount })),
}))
export const demoContracts: CrmCycleRow[] = currentOrders.slice(0, 3).map((row, index) => ({
  id: String(72001 + index), documentNo: 'HT-202611-' + String(index + 1).padStart(3, '0'),
  name: row.partnerName + '办公用品采购合同', partnerId: row.partnerId, partnerName: row.partnerName,
  businessId: demoBusinesses[index]!.id, signDate: row.businessDate, totalAmount: row.totalAmount,
  status: 'approved', version: 1, allowedActions: [], salesOrderIds: [row.id],
  lines: row.lines?.map((line) => ({ ...line, amount: row.totalAmount, totalAmount: row.totalAmount, remainingQty: '0' })),
}))

export const manufacturingProducts = [
  { id: '405', code: 'BG-005', name: '员工入职办公套装', unit: '套', status: 'active' },
  { id: '406', code: 'BG-006', name: '会议文具套装', unit: '套', status: 'active' },
]
export const demoBoms: Bom[] = manufacturingProducts.map((product, index) => ({
  id: String(73001 + index), skuId: product.id, skuCode: product.code, skuName: product.name,
  versionNo: 'V1.0', unitId: '1', baseQty: '1', validFrom: '2026-11-01', isDefault: true,
  status: 'approved', version: 1, allowedActions: [], remark: '组装计划示例，尚未领料或入库。',
  lines: products.slice(index + 1, index + 3).map((part) => ({
    componentSkuId: part.id, componentSkuCode: part.code, componentSkuName: part.name,
    unitId: '1', qty: '1', scrapRate: '0', issueWarehouseId: '101', phantom: false, substitutes: [],
  })),
}))
export const demoWorkOrders: WorkOrder[] = demoBoms.map((bom, index) => ({
  id: String(74001 + index), documentNo: 'SC-202611-' + String(index + 1).padStart(3, '0'),
  skuId: bom.skuId, skuCode: bom.skuCode, skuName: bom.skuName, bomId: bom.id, unitId: '1',
  planQty: String(20 + index * 10), completedQty: '0', issueWarehouseId: '101', receiveWarehouseId: '101',
  issueWarehouseName: warehouses[0]!.name, receiveWarehouseName: warehouses[0]!.name,
  planStartDate: '2026-11-23', planFinishDate: '2026-11-26', status: index === 0 ? 'released' : 'draft',
  version: 1, allowedActions: [], remark: '未领料的组装计划，不改变商贸库存与成本。',
  materials: bom.lines.map((line, lineIndex) => ({
    id: String(75000 + index * 10 + lineIndex), componentSkuId: line.componentSkuId,
    skuCode: line.componentSkuCode, skuName: line.componentSkuName, unitId: '1',
    unitName: products.find((product) => product.id === line.componentSkuId)!.unit,
    issueWarehouseId: '101', requiredQty: String(20 + index * 10), issuedQty: '0', returnedQty: '0',
  })), issues: [], receipts: [],
}))

export const demoWarehouseLayout: WarehouseLayout = {
  warehouse: { ...warehouses[0]!, status: 'active', version: 1, locationManaged: true, defaultReceivingLocationId: '76005', defaultShippingLocationId: '76006' },
  zones: [
    { id: '77001', code: 'A', name: '办公用品存储区', type: 'storage', status: 'active', version: 1 },
    { id: '77002', code: 'R', name: '收货暂存区', type: 'receiving', status: 'active', version: 1 },
    { id: '77003', code: 'S', name: '发货复核区', type: 'shipping', status: 'active', version: 1 },
  ],
  locations: Array.from({ length: 6 }, (_, index) => ({
    id: String(76001 + index), zoneId: index < 4 ? '77001' : index === 4 ? '77002' : '77003',
    code: index < 4 ? 'A-01-0' + (index + 1) : index === 4 ? 'R-01' : 'S-01',
    name: index < 4 ? products[index]!.name + '货位' : index === 4 ? '收货暂存位' : '发货复核位',
    status: 'active', version: 1, aisle: '01', shelf: '01', layer: String(index + 1), position: '01',
    capacityQty: '1000', mixedSkuAllowed: false, mixedBatchAllowed: true, pickSequence: index + 1,
  })),
}

export function demoSlotBalances(): (SlotBalance & { locationId: string })[] {
  return inventoryRows().map((row, index) => ({
    id: String(78001 + index), locationId: demoWarehouseLayout.locations[index]!.id,
    skuCode: row.skuCode, skuName: row.skuName, locationCode: demoWarehouseLayout.locations[index]!.code,
    batchNo: null, expiryDate: null, onHandQty: row.onHandQty, reservedQty: row.reservedQty,
    blockedQty: row.blockedQty, availableQty: row.availableQty,
  }))
}
