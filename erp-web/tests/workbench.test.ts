import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkbenchPage from '../src/pages/WorkbenchPage.vue'
import { workbenchAlertCount, workbenchSummary } from '../src/api/workbench'
import type { WorkbenchSummary } from '../src/api/workbench'
const current = reactive({ companyId: '1', canViewAlerts: false })
vi.mock('../src/api/workbench')
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({
    get companyId() {
      return current.companyId
    },
    get company() {
      return current.companyId ? { id: current.companyId } : undefined
    },
    can: (permission: string) =>
      permission === 'sales_order:create' || (permission === 'alert:view' && current.canViewAlerts),
  }),
}))
const stubs = {
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  ElButton: { template: '<button><slot /></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElTable: { props: ['data'], template: '<div>{{JSON.stringify(data)}}</div>' },
  ElTableColumn: true,
  ElTag: { template: '<span><slot /></span>' },
  ElCheckbox: true,
  ElCheckboxGroup: true,
  ElDialog: { template: '<div v-if="$attrs.modelValue"><slot /><slot name="footer" /></div>' },
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
  current.canViewAlerts = false
  vi.mocked(workbenchSummary).mockResolvedValue(data)
})
it('显示服务器待办和筛选链接，不渲染未返回的财务块', async () => {
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  await flushPromises()
  const button = wrapper
    .findAll('.todo-types button')
    .find((item) => item.text().includes('待审核销售订单'))!
  expect(button.text()).toContain('2')
  await button.trigger('click')
  expect(wrapper.get('a[href="/sales-orders?status=pending"]').text()).toContain('待审核销售订单')
  expect(wrapper.text()).not.toContain('本月期间')
  expect(wrapper.text()).not.toContain('T06:00')
  expect(workbenchAlertCount).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('分类待办在全局预览之外仍可查看，零项有灰显状态', async () => {
  vi.mocked(workbenchSummary).mockResolvedValue({
    ...data,
    blocks: [
      ...data.blocks,
      { key: 'payments', label: '待确认付款', count: 0, url: '/payments?status=pending_confirm' },
    ],
    todosByType: {
      'sales-orders': [
        {
          id: '1',
          kind: 'sales-orders',
          documentNo: 'SO-ONLY-TAB',
          label: '待审',
          url: '/sales-orders/1',
        },
      ],
    },
  })
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  await flushPromises()
  expect(wrapper.text()).not.toContain('SO-ONLY-TAB')
  expect(wrapper.get('.todo-types .is-empty').text()).toContain('待确认付款')
  await wrapper.findAll('.todo-types button')[1]!.trigger('click')
  expect(wrapper.text()).toContain('SO-ONLY-TAB')
  wrapper.unmount()
})

it('只展示当前公司的预警计数，权限收回后不再展示', async () => {
  current.canViewAlerts = true
  let oldReply: (value: { total: number }) => void = () => undefined
  vi.mocked(workbenchAlertCount)
    .mockReturnValueOnce(
      new Promise((resolve) => {
        oldReply = resolve
      }),
    )
    .mockResolvedValueOnce({ total: 3 })
  const wrapper = mount(WorkbenchPage, { global: { stubs } })
  const oldSignal = vi.mocked(workbenchAlertCount).mock.calls[0]?.[0]
  current.companyId = '2'
  await flushPromises()
  oldReply({ total: 19 })
  await flushPromises()
  expect(oldSignal?.aborted).toBe(true)
  expect(wrapper.get('a[href="/alerts?status=open"]').text()).toContain('3条预警')
  current.canViewAlerts = false
  await flushPromises()
  expect(wrapper.find('a[href="/alerts?status=open"]').exists()).toBe(false)
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
