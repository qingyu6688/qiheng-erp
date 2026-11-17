import { describe, expect, it } from 'vitest'
import type { ProductionMaterial } from '../src/types/manufacturing'
import { identifiesMaterial, materialQuantity, scanIssueDetail } from '../src/utils/manufacturing'

const material: ProductionMaterial = {
  id: '40', componentSkuId: '10', requiredQty: '9007199254740993.123456',
  issuedQty: '9007199254740992.123455', returnedQty: '0.000001', substitutes: [{ substituteSkuId: '11' }],
}

describe('生产领料扫码', () => {
  it('用剩余数量计算保留六位精度，确认冻结替代料', () => {
    expect(materialQuantity(material)).toBe('1.000002')
    expect(identifiesMaterial(material, '10')).toBe(true)
    expect(identifiesMaterial(material, '11')).toBe(true)
    expect(identifiesMaterial(material, '12')).toBe(false)
  })

  it('根据首个序列号确定批次、库位，拒绝串批、串库位、重复和其他商品', () => {
    const scan = { type: 'serial' as const, id: '20', code: 'SN-1', skuId: '10', batchId: '30', locationId: '31' }
    const selection = scanIssueDetail({}, scan, '10')
    expect(selection).toEqual({ batchId: '30', locationId: '31', serials: ['SN-1'] })
    expect(() => scanIssueDetail(selection, { ...scan, code: 'SN-2', batchId: '32' }, '10')).toThrow('其他批次')
    expect(() => scanIssueDetail(selection, { ...scan, code: 'SN-2', locationId: '32' }, '10')).toThrow('跨库位')
    expect(() => scanIssueDetail(selection, scan, '10')).toThrow('已经扫描')
    expect(() => scanIssueDetail(selection, { ...scan, skuId: '11' }, '10')).toThrow('当前领料商品')
  })
})
