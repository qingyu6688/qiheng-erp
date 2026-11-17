import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import WmsStockWork from '../src/pages/wms/WmsStockWork.vue'
import { workList } from '../src/api/wms-operations'

vi.mock('../src/api/wms-operations', () => ({
  workList: vi.fn(),
  workDetail: vi.fn(),
  workCommand: vi.fn(),
  scanCode: vi.fn(),
  saveMove: vi.fn(),
  removeMove: vi.fn(),
}))
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ companyId: '7', can: () => false }),
}))

type Area = 'moves' | 'replenishments' | 'counts'
const call = vi.mocked(workList)
beforeEach(() => call.mockReset())

function show(area: Area) {
  return mount(WmsStockWork, {
    props: { warehouse: '1', area },
    global: {
      stubs: {
        'el-table': { props: ['data'], template: '<pre>{{ JSON.stringify(data) }}</pre>' },
        'el-button': {
          props: ['loading', 'disabled'],
          template: '<button :disabled="loading || disabled"><slot/></button>',
        },
        'el-table-column': true,
        'el-input': true,
        'el-form-item': true,
        'el-form': true,
        'el-alert': { props: ['title'], template: '<div>{{ title }}</div>' },
      },
    },
  })
}

it.each([
  ['moves', 'moves', 'move'],
  ['replenishments', 'moves', 'replenish'],
  ['counts', 'count-tasks', undefined],
] as const)('%s 读取下一页并保留已读记录', async (area, endpoint, type) => {
  call.mockResolvedValueOnce({ items: [{ id: '20' }], nextCursor: '20' })
  call.mockResolvedValueOnce({ items: [{ id: '19' }], nextCursor: null })
  const wrapper = show(area)
  await flushPromises()
  const load = wrapper.findAll('button').find((button) => button.text() === '加载更多')!
  await load.trigger('click')
  await flushPromises()
  expect(call).toHaveBeenLastCalledWith(endpoint, {
    warehouseId: '1',
    cursor: '20',
    ...(type ? { type } : {}),
  })
  expect(wrapper.get('pre').text()).toBe('[{"id":"20"},{"id":"19"}]')
  expect(wrapper.text()).not.toContain('加载更多')
  wrapper.unmount()
})

it('切仓时清除旧仓列表并忽略稍后返回的旧仓响应', async () => {
  let oldReply!: (value: Awaited<ReturnType<typeof workList>>) => void
  call.mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        oldReply = resolve
      }),
  )
  call.mockResolvedValueOnce({ items: [{ id: '209' }], nextCursor: null })
  const wrapper = show('counts')
  await wrapper.setProps({ warehouse: '2' })
  await flushPromises()
  expect(call).toHaveBeenLastCalledWith('count-tasks', { warehouseId: '2', cursor: undefined })
  oldReply({ items: [{ id: '101' }], nextCursor: '101' })
  await flushPromises()
  expect(wrapper.get('pre').text()).toBe('[{"id":"209"}]')
  expect(wrapper.text()).not.toContain('加载更多')
  wrapper.unmount()
})
