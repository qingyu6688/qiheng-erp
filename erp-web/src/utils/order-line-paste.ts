import type { DataRow } from '../types/admin'
import type { SalesSkuUnit } from '../types/sales'

export const ORDER_LINE_LIMIT = 500

export interface PastedOrderLine {
  skuId: string
  unitId: string
  qty: string
  unitPrice: string
  taxRate: string
  units: SalesSkuUnit[]
}

export interface OrderPasteRow {
  code: string
  qty: string
  unitPrice: string
}

export function parseOrderPaste(text: string): OrderPasteRow[] {
  const rows = text
    .replace(/\r\n?/g, '\n')
    .replace(/^\uFEFF/, '')
    .split('\n')
  while (rows.at(-1)?.trim() === '') rows.pop()
  if (!rows.length) throw new Error('请粘贴商品编码、数量和单价')
  if (rows.length > ORDER_LINE_LIMIT) throw new Error('商品明细最多500行')
  return rows.map((row, index) => {
    const cells = row.split('\t').map((cell) => cell.trim())
    if (cells.length < 2 || cells.length > 3)
      throw new Error('第' + (index + 1) + '行应为商品编码、数量、单价三列，单价可留空')
    const [code = '', qty = '', unitPrice = ''] = cells
    if (!code) throw new Error('第' + (index + 1) + '行商品编码不能为空')
    if (!/^\d{1,18}(?:\.\d{1,6})?$/.test(qty) || !/[1-9]/.test(qty))
      throw new Error('第' + (index + 1) + '行数量须大于0且最多6位小数')
    if (unitPrice && !/^\d{1,16}(?:\.\d{1,8})?$/.test(unitPrice))
      throw new Error('第' + (index + 1) + '行单价须为非负数且最多16位整数、8位小数')
    return { code, qty, unitPrice }
  })
}

export async function prepareOrderPaste(
  rows: OrderPasteRow[],
  skus: DataRow[],
  loadSku: (id: string) => Promise<DataRow>,
): Promise<PastedOrderLine[]> {
  const selected = rows.map((row, index) => {
    const matches = skus.filter((sku) => sku.status === 'active' && sku.code === row.code)
    if (matches.length !== 1)
      throw new Error('第' + (index + 1) + '行商品编码不存在、已停用或不能唯一确定')
    return matches[0]!
  })
  const details = new Map<string, DataRow>()
  // 同一商品只查询一次，整批校验通过后才修改明细。
  for (const sku of selected) if (!details.has(sku.id)) details.set(sku.id, await loadSku(sku.id))
  return rows.map((row, index) => {
    const sku = selected[index]!
    const detail = details.get(sku.id)!
    const units = (detail.units as SalesSkuUnit[] | undefined) ?? []
    const base = units.find((unit) => unit.isBase)
    if (!base) throw new Error('第' + (index + 1) + '行商品缺少有效基础单位')
    return {
      skuId: sku.id,
      unitId: base.unitId,
      qty: row.qty,
      unitPrice: row.unitPrice,
      taxRate: String(detail.defaultTaxRate ?? '0.13'),
      units,
    }
  })
}
