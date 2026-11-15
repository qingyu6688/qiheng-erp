import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkbenchPage from '../src/pages/WorkbenchPage.vue'
import { workbenchSummary } from '../src/api/workbench'
import type { WorkbenchSummary } from '../src/api/workbench'
const current = reactive({ companyId: '1' })
vi.mock('../src/api/workbench')
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({
    get companyId() {
      return current.companyId
    },
    get company() {
      return current.companyId ? { id: current.companyId } : undefined
    },
    can: (permission: string) => permission === 'sales_order:create',
  }),
}))
const stubs = {
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  ElButton: { template: '<button><slot /></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElTable: { props: ['data'], template: '<div>{{JSON.stringify(data)}}</div>' },
  ElTableColumn: true,
  ElTag: { template: '<span><slot /></span>' },
}
const data: WorkbenchSummary = {
  asOf: '2026-11-15T06:00:00Z',
  blocks: [
    { key: 'sales-orders', label: '待审核销售订单', count: 2, url: '/sales-orders?status=pending' },
  ],
  todos: [],
}
beforeEach(() => {
  vi.resetAllMocks()
  current.companyId = '1'
  vi.mocked(workbenchSummary).mockResolvedValue(data)
})
it('显示服务器待办和筛选链接，不渲染未返回的财务块', async () => {
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  await flushPromises()
  expect(wrapper.get('a[href="/sales-orders?status=pending"]').text()).toContain('2')
  expect(wrapper.text()).not.toContain('本月期间')
  expect(wrapper.text()).not.toContain('T06:00')
  wrapper.unmount()
})
it('切换公司清除旧结果并中止请求', async () => {
  let resolve: (value: WorkbenchSummary) => void = () => undefined
  vi.mocked(workbenchSummary).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  const signal = vi.mocked(workbenchSummary).mock.calls[0]?.[0]
  current.companyId = '2'
  await flushPromises()
  resolve({ ...data, blocks: [{ ...data.blocks[0]!, label: '旧公司待办' }] })
  await flushPromises()
  expect(signal?.aborted).toBe(true)
  expect(wrapper.text()).not.toContain('旧公司待办')
  wrapper.unmount()
})
it('无公司范围显示配置提示，不查询业务数据', async () => {
  current.companyId = ''
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  await flushPromises()
  expect(workbenchSummary).not.toHaveBeenCalled()
  expect(wrapper.text()).toContain('没有可访问的公司')
  wrapper.unmount()
})
