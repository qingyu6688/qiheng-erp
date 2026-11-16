import { afterEach, expect, it, vi } from 'vitest'
import { downloadFile } from '../src/api/files'
import { request } from '../src/api/http'

vi.mock('../src/api/http')
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

it.each([
  ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', '资产负债表.xlsx'],
  ['text/csv;charset=utf-8', '资产负债表.csv'],
])('后台导出按实际格式保存文件：%s', async (type, expected) => {
  vi.useFakeTimers()
  const revoke = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:财务测试'), revokeObjectURL: revoke })
  vi.mocked(request).mockResolvedValue(new Blob(['财务资料'], { type }))
  let filename = ''
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
    this: HTMLAnchorElement,
  ) {
    filename = this.download
  })
  await downloadFile('/api/v1/export-jobs/1/download?ticket=测试', '资产负债表.csv')
  expect(filename).toBe(expected)
  vi.runAllTimers()
  expect(revoke).toHaveBeenCalledWith('blob:财务测试')
})
