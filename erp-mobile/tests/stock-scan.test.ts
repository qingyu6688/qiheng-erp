import { describe, expect, it } from 'vitest'
import type { StockLine } from '../src/types/work'
import { scanMismatch } from '../src/utils/work'

const line: StockLine = {
  id: '7', skuId: '2', skuCode: 'SKU-A', batchNo: 'Lot-42',
  fromLocationCode: 'STAGE-A', recommendedLocationCode: 'SHELF-01', toLocationCode: 'SHIP-01',
}

describe('上架和拣货逐步核对扫码', () => {
  it('允许纯库位码和与相应服务端一致的标签前缀', () => {
    expect(scanMismatch('putaway', 'from', ' STAGE-a ', line)).toBe('')
    expect(scanMismatch('putaway', 'from', 'L:stage-a', line)).toBe('')
    expect(scanMismatch('putaway', 'from', 'l:stage-a', line)).toContain('源库位')
    expect(scanMismatch('pick', 'from', 'l:stage-a', line)).toBe('')
    expect(scanMismatch('pick', 'from', 'L:STAGE-B', line)).toContain('源库位')
  })

  it('拣货指定发货库位；上架允许换其他有效格式的目标', () => {
    expect(scanMismatch('pick', 'to', 'l:ship-01', line)).toBe('')
    expect(scanMismatch('pick', 'to', 'SHELF-01', line)).toContain('发货库位')
    expect(scanMismatch('putaway', 'to', 'L:OTHER-SHELF', line)).toBe('')
    expect(scanMismatch('putaway', 'to', 'L:', line)).toContain('库位编码无效')
    expect(scanMismatch('putaway', 'to', 'A'.repeat(49), line)).toContain('库位编码无效')
    expect(scanMismatch('putaway', 'to', 'A\u007fB', line)).toContain('库位编码无效')
  })

  it('商品编码忽略大小写，批次号按原文匹配', () => {
    for (const mode of ['putaway', 'pick'] as const) {
      expect(scanMismatch(mode, 'batch', 'Lot-42', line)).toBe('')
      expect(scanMismatch(mode, 'batch', ' B:sku-a|Lot-42 ', line)).toBe('')
      expect(scanMismatch(mode, 'batch', 'B:SKU-B|Lot-42', line)).not.toBe('')
      expect(scanMismatch(mode, 'batch', 'B:SKU-A|lot-42', line)).not.toBe('')
    }
    expect(scanMismatch('putaway', 'batch', 'b:sku-a|Lot-42', line)).toContain('批次号')
    expect(scanMismatch('pick', 'batch', 'b:sku-a|Lot-42', line)).toBe('')
    expect(scanMismatch('pick', 'batch', 'B:RAW', { ...line, batchNo: 'B:RAW' })).toBe('')
  })

  it('旧行没有预期编码时交给服务端，推荐库位不能代替拣货固定目标', () => {
    const older = { id: '7', skuId: '2', skuCode: 'SKU-A', recommendedLocationCode: 'SHELF-01' }
    expect(scanMismatch('pick', 'from', 'STAGE-A', older)).toBe('')
    expect(scanMismatch('pick', 'to', 'SHIP-02', older)).toBe('')
    expect(scanMismatch('putaway', 'batch', 'Lot-42', older)).toBe('')
  })
})
