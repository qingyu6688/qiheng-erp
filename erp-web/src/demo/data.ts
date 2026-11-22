import type { UserSession } from '../types/auth'
import type { SalesDocument, SalesLine } from '../types/sales'
import type { InventoryBalance } from '../types/inventory'
import { demoPurchaseKinds, demoReports, demoSalesKinds } from './scope'

export const snapshotDate = '2026-11-20'
export const snapshotTime = snapshotDate + 'T09:00:00+08:00'
export const money = (value: number): string => (Math.round(value * 100) / 100).toFixed(2)
export const warehouses = [{ id: '101', code: 'WH01', name: '武汉中心仓', enabled: true }]
export const accounts = [{ id: '201', code: 'BANK01', name: '演示结算账户', enabled: true }]
export const people = [{ id: '301', name: '陈晓（示例）' }, { id: '302', name: '李宁（示例）' }]
export const products = [
  { id: '401', code: 'BG-001', name: 'A4 复印纸 / 5 包装', unit: '箱', cost: 85, price: 120 },
  { id: '402', code: 'BG-002', name: '中性笔 / 12 支装', unit: '盒', cost: 18, price: 30 },
  { id: '403', code: 'BG-003', name: '桌面收纳盒', unit: '个', cost: 25, price: 42 },
  { id: '404', code: 'BG-004', name: '文件夹 / 10 个装', unit: '套', cost: 32, price: 50 },
]
export const partners = [
  { id: '501', code: 'KH01', name: '江城文具（示例）', isCustomer: true, isSupplier: false },
  { id: '502', code: 'KH02', name: '青禾办公（示例）', isCustomer: true, isSupplier: false },
  { id: '503', code: 'KH03', name: '星桥商贸（示例）', isCustomer: true, isSupplier: false },
  { id: '504', code: 'KH04', name: '云帆零售（示例）', isCustomer: true, isSupplier: false },
  { id: '601', code: 'GY01', name: '华楚办公用品（示例）', isCustomer: false, isSupplier: true },
]

export const demoSession: UserSession = {
  id: '9001', username: 'visitor', displayName: '演示访客', mustChangePassword: false,
  companies: [{ id: '1', code: 'DEMO', name: '柒衡商贸（示例）', allWarehouses: true, warehouseIds: ['101'] }],
  permissions: [
    ...[...demoSalesKinds, ...demoPurchaseKinds].map((item) => item.permission + ':view'),
    ...demoReports.map((item) => 'report:' + item.type.replaceAll('-', '_')),
    'inventory:view', 'cost:view', 'warehouse:view', 'partner:view', 'sku:view', 'cash_account:view', 'settlement:view',
    'crm_customer:view', 'crm_business:view', 'crm_contract:view', 'wms_location:view',
    'mfg_bom:view', 'mfg_work_order:view', 'gl_voucher:view', 'gl_ledger:view',
    'ai:use', 'ai_admin:view',
  ],
}

export interface DemoDocument extends SalesDocument {
  kind: string
  businessDate: string
  partnerId: string
  partnerName: string
  warehouseId: string
  warehouseName: string
  businessUserId: string
  businessUserName: string
  departmentName: string
  totalAmount: string
  quantity: number
  productId: string
  relatedOrderId?: string
  allocatedAmount: string
}

export const documents: DemoDocument[] = []

function createDocument(kind: string, id: number, date: string, productIndex: number, qty: number, price: number, partnerIndex: number, status: string): DemoDocument {
  const product = products[productIndex]!
  const partner = partners[partnerIndex]!
  const amount = qty * price
  const untaxed = Math.round(amount / 1.13 * 100) / 100
  const prefixes: Record<string, string> = { 'sales-orders': 'XS', shipments: 'FH', receipts: 'SK', 'purchase-orders': 'CG', 'goods-receipts': 'SH', payments: 'FK' }
  const line: SalesLine = {
    id: String(id * 10), lineNo: 1, skuId: product.id, skuName: product.name,
    sku: { id: product.id, code: product.code, name: product.name }, warehouseId: '101', warehouse: { id: '101', name: warehouses[0]!.name },
    unitId: '1', unit: { id: '1', name: product.unit }, unitName: product.unit, unitRate: '1', qty: String(qty), baseQty: String(qty),
    unitPrice: money(price), receivableAmount: money(amount), receivableNet: money(amount), payableNet: money(amount), payableAmount: money(amount), untaxedAmount: money(untaxed), receiveLimitBaseQty: String(qty),
    taxRate: '0.13', taxAmount: money(amount - untaxed), costAmount: money(qty * product.cost), lineDiscountAmount: '0.00',
  }
  const sales = partnerIndex < 4
  return {
    id: String(id), kind, businessDate: date, documentNo: prefixes[kind] + '-' + date.replaceAll('-', '') + '-' + product.code.slice(-3),
    status, version: 1, allowedActions: [], partnerId: partner.id, partnerName: partner.name,
    ...(sales ? { customerId: partner.id, customer: partner, salesUserId: '301', salesUserName: people[0]!.name } : { supplierId: partner.id, supplier: partner, supplierName: partner.name, buyerUserId: '302', buyerUserName: people[1]!.name }),
    warehouseId: '101', warehouseName: warehouses[0]!.name, warehouse: warehouses[0], cashAccountId: '201', cashAccountName: accounts[0]!.name,
    businessUserId: sales ? '301' : '302', businessUserName: people[sales ? 0 : 1]!.name, departmentName: '商贸事业部',
    totalAmount: money(amount), amount: money(amount), receivableAmount: money(amount), payableAmount: money(amount),
    goodsAmount: money(amount), untaxedAmount: money(untaxed), taxAmount: money(amount - untaxed), feeAmount: '0.00', lineDiscountAmount: '0.00', orderDiscountAmount: '0.00',
    priceTaxInclusive: true, quantity: qty, productId: product.id, lines: [line], allocatedAmount: '0.00',
    createdAt: date + 'T09:00:00+08:00', submittedAt: date + 'T09:10:00+08:00', approvedAt: status === 'pending' ? undefined : date + 'T10:00:00+08:00', updatedAt: date + 'T15:00:00+08:00',
    remark: '虚构业务样例，仅供演示浏览。', deliveryMethod: 'delivery', deliveryDate: date, fulfillmentStatus: 'none', settlementStatus: 'none',
  }
}

// 用同一组单据生成库存、结算与图表，避免各页展示互不相符的演示金额。
for (let monthIndex = 0; monthIndex < 12; monthIndex++) {
  const month = new Date(Date.UTC(2025, 11 + monthIndex, 1)).toISOString().slice(0, 7)
  for (let index = 0; index < products.length; index++) {
    const current = monthIndex === 11
    const pending = current && index === 3
    const partial = current && index === 2
    const date = month + '-' + String(3 + index * 5).padStart(2, '0')
    const product = products[index]!
    const orderQty = 30 + monthIndex * 3 + index * 5
    const sales = createDocument('sales-orders', 10000 + monthIndex * 10 + index, date, index, orderQty, product.price, index, pending ? 'pending' : 'approved')
    const purchase = createDocument('purchase-orders', 20000 + monthIndex * 10 + index, date, index, orderQty + 10, product.cost, 4, pending ? 'pending' : 'approved')
    const delivered = pending ? 0 : partial ? Math.floor(orderQty / 2) : orderQty
    const received = pending ? 0 : purchase.quantity
    const receivedCash = delivered * product.price * (partial ? 0.5 : 1)
    const paidCash = received * product.cost * (partial ? 0.5 : 1)
    sales.fulfillmentStatus = pending ? 'none' : partial ? 'partial' : 'full'
    purchase.fulfillmentStatus = pending ? 'none' : 'full'
    sales.settlementStatus = pending ? 'none' : partial ? 'partial' : 'full'
    purchase.settlementStatus = pending ? 'none' : partial ? 'partial' : 'full'
    sales.allocatedAmount = money(receivedCash)
    sales.lines![0]!.reservedQty = String(pending ? 0 : orderQty - delivered)
    sales.lines![0]!.reservationState = pending ? 'none' : partial ? 'reserved' : 'consumed'
    purchase.allocatedAmount = money(paidCash)
    sales.lines![0]!.progress = { baseQty: String(orderQty), closedQty: '0', shippedQty: String(delivered), signedQty: String(delivered), returnedQty: '0', shippableQty: String(orderQty - delivered) }
    purchase.lines![0]!.progress = { baseQty: String(purchase.quantity), closedQty: '0', shippedQty: '0', returnedQty: '0', shippableQty: '0', receivedQty: String(received) } as SalesLine['progress']
    documents.push(sales, purchase)
    if (!pending) {
      const shipment = createDocument('shipments', 30000 + monthIndex * 10 + index, date, index, delivered, product.price, index, 'signed')
      shipment.relatedOrderId = sales.id
      shipment.signDate = date
      shipment.lines![0]!.signedQty = String(delivered)
      const receipt = createDocument('receipts', 40000 + monthIndex * 10 + index, date, index, delivered, product.price, index, 'confirmed')
      receipt.relatedOrderId = sales.id
      receipt.totalAmount = receipt.amount = money(receivedCash)
      receipt.taxAmount = '0.00'
      receipt.lines = []
      receipt.receiptType = 'customer'
      const goods = createDocument('goods-receipts', 50000 + monthIndex * 10 + index, date, index, received, product.cost, 4, 'posted')
      goods.relatedOrderId = purchase.id
      const payment = createDocument('payments', 60000 + monthIndex * 10 + index, date, index, received, product.cost, 4, 'confirmed')
      payment.relatedOrderId = purchase.id
      payment.totalAmount = payment.amount = money(paidCash)
      payment.taxAmount = '0.00'
      payment.lines = []
      for (const child of [shipment, receipt, goods, payment]) {
        const parent = child.relatedOrderId === sales.id ? sales : purchase
        child.sourceDocuments = [{ id: parent.id, documentNo: parent.documentNo }]
        child.sourceNo = parent.documentNo
      }
      documents.push(shipment, receipt, goods, payment)
    }
  }
}

export function inventoryRows(): InventoryBalance[] {
  return products.map((product) => {
    const rows = documents.filter((document) => document.productId === product.id)
    const received = rows.filter((document) => document.kind === 'goods-receipts').reduce((sum, row) => sum + row.quantity, 0)
    const shipped = rows.filter((document) => document.kind === 'shipments').reduce((sum, row) => sum + row.quantity, 0)
    const reserved = rows.filter((row) => row.kind === 'sales-orders' && row.status === 'approved').reduce((sum, row) => sum + row.quantity - Number(row.lines?.[0]?.progress?.shippedQty ?? 0), 0)
    const onHand = 100 + received - shipped
    const pending = rows.filter((row) => row.kind === 'sales-orders' && row.status === 'pending').reduce((sum, row) => sum + row.quantity, 0)
    return { warehouseId: '101', warehouseName: warehouses[0]!.name, skuId: product.id, skuCode: product.code, skuName: product.name, onHandQty: String(onHand), reservedQty: String(reserved), blockedQty: '0', availableQty: String(onHand - reserved), pendingDemandQty: String(pending), unitCost: money(product.cost), costAmount: money(onHand * product.cost) }
  })
}
