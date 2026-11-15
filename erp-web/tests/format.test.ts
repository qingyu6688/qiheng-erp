import { describe, expect, it, vi } from 'vitest'
import {
  formatMoney,
  formatPrice,
  formatQuantity,
  formatDateTime,
  formatDateTimeSeconds,
  formatPrintTime,
  formatRelativeTime,
} from '../src/utils/format'

describe('服务端数值的显示', () => {
  it('显示精确金额和负数', () => {
    expect(formatMoney('1130.00')).toBe('1,130.00')
    expect(formatMoney('-45.83')).toBe('−45.83')
    expect(formatMoney('9007199254740993.05')).toBe('9,007,199,254,740,993.05')
  })
  it('按数值类型保留小数位', () => {
    expect(formatPrice('33.33333333')).toBe('33.33333333')
    expect(formatPrice('0')).toBe('0.00')
    expect(formatQuantity('4.000000', '件')).toBe('4 件')
    expect(formatQuantity('0.001000')).toBe('0.001')
  })
  it('未返回的数据使用占位符', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatQuantity(undefined, '件')).toBe('—')
  })
})

describe('上海业务时间显示', () => {
  const now = new Date('2026-11-15T08:00:00Z')
  it('同年、跨年及跨零点按上海时区显示', () => {
    expect(formatDateTime('2026-11-15T06:49:29.673Z', now)).toBe('11-15 14:49')
    expect(formatDateTime('2025-12-31T15:59:00Z', now)).toBe('2025-12-31 23:59')
    expect(formatDateTime('2026-11-14T16:01:00Z', now)).toBe('11-15 00:01')
    expect(formatDateTimeSeconds('2026-11-15T06:49:29Z', now)).toBe('11-15 14:49:29')
    expect(formatPrintTime('2026-11-15T06:49:29Z')).toBe('2026-11-15 14:49')
  })
  it('今天昨天与更早时间边界正确', () => {
    expect(formatRelativeTime('2026-11-15T00:30:00Z', now)).toBe('08:30')
    expect(formatRelativeTime('2026-11-14T15:59:00Z', now)).toBe('昨天 23:59')
    expect(formatRelativeTime('2026-11-13T12:00:00Z', now)).toBe('11-13 20:00')
    expect(formatRelativeTime('2025-11-13T12:00:00Z', now)).toBe('2025-11-13 20:00')
  })
  it('日期字段、空值及非法值不被误转为时间', () => {
    for (const input of [undefined, null, '', '错误', '2026-11-15']) {
      expect(formatDateTime(input, now)).toBe('—')
      expect(formatDateTimeSeconds(input, now)).toBe('—')
      expect(formatPrintTime(input)).toBe('—')
      expect(formatRelativeTime(input, now)).toBe('—')
    }
  })
  it('导入上传时间按北京时间显示，无时区时间仅提示', () => {
    expect(formatDateTime('2026-11-15T13:47:00.123Z', now)).toBe('11-15 21:47')
    expect(formatDateTime('2026-11-15T21:47:00.123+08:00', now)).toBe('11-15 21:47')
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    try {
      expect(formatDateTime('2026-11-15T13:47:00.123', now)).toBe('—')
      expect(warning).toHaveBeenCalledTimes(1)
    } finally {
      warning.mockRestore()
    }
  })
})
