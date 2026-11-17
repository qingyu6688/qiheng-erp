import { expect, it, vi } from 'vitest'
import { ApiError } from '../src/api/http'
import { useWmsCommands } from '../src/pages/wms/useWmsCommands'

it('网络异常时按操作与输入保留重试凭据，确认成功后释放', async () => {
  const commands = useWmsCommands()
  const work = vi
    .fn<(key: string) => Promise<boolean>>()
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '连接超时'))
    .mockResolvedValue(true)
  const input = { version: 3, qty: '1.250000' }

  await expect(commands.run('pick:42:confirm', input, work)).resolves.toBeUndefined()
  expect(commands.error.value).toBe('连接超时')
  await expect(commands.run('pick:42:confirm', input, work, '拣货已确认')).resolves.toBe(true)
  expect(work.mock.calls[1]?.[0]).toBe(work.mock.calls[0]?.[0])
  expect(commands.notice.value).toBe('拣货已确认')

  await commands.run('pick:42:confirm', { ...input, version: 4 }, work)
  expect(work.mock.calls[2]?.[0]).not.toBe(work.mock.calls[1]?.[0])
})
