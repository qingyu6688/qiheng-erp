import { describe, expect, it, vi } from 'vitest'
import { keyFor, useTaskFlow } from '../src/composables/useTaskFlow'
import { formatQuantity, quantity, remaining, scaledQuantity, serialLines, stepQuantity } from '../src/utils/quantity'
import { formatSnapshotTime, lineForScan, pendingLine } from '../src/utils/work'

describe('PDA 作业输入', () => {
  it('盘点快照用北京时间展示，并兼容未返回快照的旧接口', () => {
    expect(formatSnapshotTime('2026-11-17T16:15:30Z')).toBe('2026-11-18 00:15:30')
    expect(formatSnapshotTime('2026-11-18T00:15:30+08:00')).toBe('2026-11-18 00:15:30')
    expect(formatSnapshotTime('2026-11-17T16:15:30')).toBe('')
    expect(formatSnapshotTime()).toBe('')
  })

  it('保留大数量的第六位小数', () => {
    expect(remaining('9007199254740993.000002', '9007199254740993.000001')).toBe('0.000001')
    expect(formatQuantity(scaledQuantity('9007199254740993.123456')!)).toBe('9007199254740993.123456')
    expect(() => quantity('0.0000001')).toThrow()
    expect(() => quantity('0')).toThrow()
    expect(quantity('0.0', true)).toBe('0')
  })

  it('规范扫码枪的换行，发现重复序列号时提示', () => {
    expect(serialLines('SN01\r\nSN02\n')).toEqual(['SN01', 'SN02'])
    expect(() => serialLines('SN01\n SN01')).toThrow('序列号不能重复')
  })

  it('数量键保留精度并限制在待确认范围内', () => {
    expect(stepQuantity('9007199254740993.000002', '9007199254740999', -1)).toBe('9007199254740992.000002')
    expect(stepQuantity('1', '1.2', 1)).toBe('1.2')
    expect(stepQuantity('0.2', '1.2', -1)).toBe('0.2')
    expect(stepQuantity('', '0.000001', 1)).toBe('0.000001')
  })

  it('批次扫码选定同商品的唯一行，拒绝歧义', () => {
    const rows = [
      { id: '1', skuId: '100', skuCode: 'A', batchId: '5' },
      { id: '2', skuId: '100', skuCode: 'A', batchId: '6' },
    ]
    expect(lineForScan(rows, { type: 'batch', id: '6', skuId: '100', code: 'B:A|6' }).id).toBe('2')
    expect(() => lineForScan(rows, { type: 'sku', id: '100', code: 'A' })).toThrow('扫码匹配到多行')
    expect(() => lineForScan(rows, { type: 'sku', id: '200', code: 'B' })).toThrow('扫描商品不属于当前单据')
  })

  it('按波次路线行编号直接打开同商品的待拣行，跳过已完成的行', () => {
    const lines = [
      { id: '1', skuId: '100', skuCode: 'A', fromLocationCode: 'A-01', planQty: '1', doneQty: '1' },
      { id: '2', skuId: '100', skuCode: 'A', fromLocationCode: 'A-02', planQty: '2', doneQty: '1' },
    ]
    expect(pendingLine(lines, '2')?.fromLocationCode).toBe('A-02')
    expect(pendingLine(lines, '1')).toBeUndefined()
    expect(pendingLine(lines, '3')).toBeUndefined()
  })

  it('同版本同输入连断线重试时复用请求键，修改数量产生独立请求键', () => {
    const keys = new Map<string, string>()
    const id = vi.fn().mockReturnValueOnce('first').mockReturnValueOnce('second')
    expect(keyFor(JSON.stringify(['/confirm', 2, '1']), keys, id)).toBe('first')
    expect(keyFor(JSON.stringify(['/confirm', 2, '1']), keys, id)).toBe('first')
    expect(keyFor(JSON.stringify(['/confirm', 2, '2']), keys, id)).toBe('second')
    expect(id).toHaveBeenCalledTimes(2)
  })

  it('确认后刷新中断时保留请求键，原数据可安全重试', async () => {
    const { run, failure, success } = useTaskFlow()
    const input = ['/wms/tasks/1/lines/2/confirm', { qty: '2.25', version: 7 }]
    const request = vi.fn().mockRejectedValueOnce(new Error('连接异常')).mockResolvedValueOnce(true)
    await expect(run(input, request)).resolves.toBeUndefined()
    expect(failure.value).toBe('连接异常')
    await expect(run(input, request, '确认成功')).resolves.toBe(true)
    expect(request.mock.calls[0]?.[0]).toBe(request.mock.calls[1]?.[0])
    expect(failure.value).toBe('')
    expect(success.value).toBe('确认成功')
  })
})
