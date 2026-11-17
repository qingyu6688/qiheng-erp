import { beforeEach, expect, it, vi } from 'vitest'
import { request } from '../src/api/http'
import { claimWmsTask, confirmWmsTaskLine, finishWmsTask, wmsTasks } from '../src/api/wms'

vi.mock('../src/api/http', () => ({ request: vi.fn() }))

beforeEach(() => vi.clearAllMocks())

it('上架请求携带当前公司范围、精确标识、操作版本和原幂等键', async () => {
  const id = '9007199254740993'
  const signal = new AbortController().signal
  const input = {
    fromLocationCode: 'L:A01',
    toLocationCode: 'L:B02',
    batchNo: null,
    qty: '1.100001',
    serialNos: [],
    version: 4,
  }
  await wmsTasks({ warehouseId: id, limit: 20 }, signal)
  expect(request).toHaveBeenLastCalledWith(
    { url: '/wms/tasks', params: { warehouseId: id, limit: 20 }, signal },
    true,
  )
  await claimWmsTask(id, 3, 'claim-key')
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'POST',
      url: `/wms/tasks/${id}/claim`,
      data: { version: 3 },
      headers: { 'Idempotency-Key': 'claim-key' },
    },
    true,
  )
  await confirmWmsTaskLine(id, '9007199254740994', input, 'confirm-key')
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'POST',
      url: `/wms/tasks/${id}/lines/9007199254740994/confirm`,
      data: input,
      headers: { 'Idempotency-Key': 'confirm-key' },
    },
    true,
  )
  await finishWmsTask(id, 5, 'finish-key')
  expect(request).toHaveBeenLastCalledWith(
    {
      method: 'POST',
      url: `/wms/tasks/${id}/finish`,
      data: { version: 5 },
      headers: { 'Idempotency-Key': 'finish-key' },
    },
    true,
  )
})
