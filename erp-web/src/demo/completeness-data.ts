import type { PriceListView } from '../api/crm-pricing'
import type { CostRevaluation } from '../types/cost-revaluations'
import type { SalesQuote } from '../types/sales-quotes'
import { documents, inventoryRows, money, partners, products, snapshotDate, warehouses } from './data'

export const demoSupplierPrices: PriceListView = {
  header: { id: '98101', name: '华楚办公 · 复印纸采购协议（示例）', currency: 'CNY', priceIncludesTax: true,
    validFrom: '2026-11-01', validTo: '2026-12-31', priority: 10, status: 'active', version: 1 },
  targets: [{ targetType: 'supplier', targetValue: '601' }],
  items: [
    { skuId: '401', unitId: '1', minQty: '1', unitPrice: '85.00' },
    { skuId: '401', unitId: '1', minQty: '100', unitPrice: '82.00' },
    { skuId: '402', unitId: '1', minQty: '1', unitPrice: '18.00' },
  ],
}

function quote(index: number, converted: boolean): SalesQuote {
  const source = documents.find((row) => row.kind === 'sales-orders' && row.productId === products[index]!.id && row.businessDate.startsWith('2026-11'))!
  const product = products[index]!, line = source.lines![0]!
  return {
    id: String(98201 + index), documentNo: `SQ-DEMO-202611-${index + 1}`, customerId: source.partnerId, customerName: source.partnerName,
    businessDate: source.businessDate, validTo: '2026-12-20', status: converted ? 'converted' : 'issued', version: 1,
    totalAmount: source.totalAmount, remark: '虚构报价样例，仅供浏览；确认及转单操作不对访客开放。',
    input: { customerId: source.partnerId, businessDate: source.businessDate, priceTaxInclusive: true, orderDiscountAmount: '0.00', fees: [],
      lines: [{ skuId: product.id, unitId: '1', warehouseId: '101', qty: String(source.quantity), unitPrice: money(product.price), taxRate: '0.13' }] },
    pricing: { priceTaxInclusive: true, goodsAmount: source.totalAmount, orderDiscountAmount: '0.00', lineDiscountAmount: '0.00', feeAmount: '0.00',
      totalAmount: source.totalAmount, untaxedAmount: source.untaxedAmount!, taxAmount: source.taxAmount!, warnings: [],
      lines: [{ lineNo: 1, skuId: product.id, unitId: '1', warehouseId: '101', qty: String(source.quantity), baseQty: String(source.quantity),
        unitRate: '1', unitPrice: money(product.price), lineDiscountAmount: '0.00', totalAmount: source.totalAmount,
        taxRate: '0.13', untaxedAmount: source.untaxedAmount!, taxAmount: source.taxAmount! }] },
    wfInstanceId: '', belowMinPrice: false, belowMinPricePolicy: 'warn', allowedActions: [],
    convertedOrderId: converted ? source.id : undefined, convertedOrderNo: converted ? source.documentNo : undefined,
    lines: [{ id: line.id!, lineNo: 1, skuId: product.id, skuName: product.name, unitId: '1', unitName: product.unit, warehouseName: warehouses[0]!.name }],
  }
}

export const demoSalesQuotes: SalesQuote[] = [quote(0, true), quote(1, false)]

const stock = inventoryRows().find((row) => row.skuId === '401')!
const beforeCost = stock.costAmount
if (typeof beforeCost !== 'string') throw new Error('重估展示样例缺少库存成本，请先核对示例库存。')
const shipment = documents.find((row) => row.kind === 'shipments' && row.productId === '401' && row.businessDate.startsWith('2026-11'))!
const shipmentCost = shipment.lines![0]!.costAmount
if (typeof shipmentCost !== 'string') throw new Error('重估展示样例缺少原发货成本，请先核对示例明细。')
export const demoCostRevaluation: CostRevaluation = {
  id: '98301', documentNo: 'CR-DEMO-202611-001', status: 'approved', warehouseId: '101', warehouseName: warehouses[0]!.name,
  skuId: '401', skuName: products[0]!.name, startDate: '2026-11-01', businessDate: snapshotDate,
  reason: '历史成本核对差异预览（虚构样例）。尚未执行，不计入当前库存和账簿。',
  quantity: stock.onHandQty, beforeCost, afterCost: money(Number(beforeCost) - 20), stockDelta: '-20.00', issuedDelta: '20.00',
  eventCount: 6, version: 1, previewHash: 'readonly-demo-cost-preview', wfInstanceId: '98311', allowedActions: [],
  lines: [{ id: '98321', movementId: '98331', movementType: 'shipment', businessDate: shipment.businessDate,
    postedAt: shipment.businessDate + 'T09:00:00+08:00', onHandDelta: String(-shipment.quantity), sourceType: 'shipment', sourceId: shipment.id,
    beforeCost: money(-Number(shipmentCost)), afterCost: money(-Number(shipmentCost) - 20), costDelta: '-20.00' }],
}

export const demoCompletenessMetadata = {
  customers: partners.filter((row) => row.isCustomer).map(({ id, name }) => ({ id, name })),
  warehouses: warehouses.map(({ id, name }) => ({ id, name })), skus: products.map(({ id, name }) => ({ id, name })), opportunities: [], skuLimit: 200,
}
