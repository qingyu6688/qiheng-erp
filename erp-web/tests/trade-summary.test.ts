import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkbenchTradeSummary from '../src/components/WorkbenchTradeSummary.vue'
import { tradeSummary } from '../src/api/workbench'
import type { TradeSummary } from '../src/api/workbench'

const scope = reactive({ companyId: '1', sales: true, purchase: true })
vi.mock('../src/api/workbench')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({
  get companyId() { return scope.companyId },
  get company() { return scope.companyId ? { id: scope.companyId } : undefined },
  can: (permission: string) => permission === 'report:sales_summary' ? scope.sales : scope.purchase,
}) }))
const response: TradeSummary = {
  asOf: '2026-11-16T04:00:00Z', dataSource: 'replica',
  sales: { today: '20.00', yesterday: '10.00', month: '120.00', year: '240.00' },
  purchase: { today: '5.00', yesterday: '2.00', month: '30.00', year: '60.00' },
  trend: [{ month: '2026-11', sales: '120.00', purchase: '30.00' }],
}
const stubs = { EChart: { props: ['option'], template: '<div role="img">{{JSON.stringify(option)}}</div>' }, ElButton: true, ElAlert: true }
beforeEach(() => {
  vi.resetAllMocks()
  Object.assign(scope, { companyId: '1', sales: true, purchase: true })
  vi.mocked(tradeSummary).mockResolvedValue(response)
})
it('展示金额、月份和备库截止时间，图表使用返回的权限块', async () => {
  const wrapper = mount(WorkbenchTradeSummary, { global: { stubs } })
  await flushPromises()
  expect(wrapper.text()).toContain('销售确认金额')
  expect(wrapper.text()).toContain('采购收货金额')
  expect(wrapper.text()).toContain('120.00')
  expect(wrapper.text()).toContain('备库')
  expect(wrapper.get('[role="img"]').text()).toContain('2026-11')
  wrapper.unmount()
})
it('仅销售权限时只显示销售，切换公司中止请求并清除旧金额', async () => {
  scope.purchase = false
  vi.mocked(tradeSummary).mockResolvedValueOnce({ ...response, purchase: undefined })
  const wrapper = mount(WorkbenchTradeSummary, { global: { stubs } })
  await flushPromises()
  expect(wrapper.find('[data-trade="purchase"]').exists()).toBe(false)
  expect(wrapper.get('[role="img"]').text()).not.toContain('"name":"采购"')
  let resolve: (value: TradeSummary) => void = () => undefined
  vi.mocked(tradeSummary).mockReturnValueOnce(new Promise(done => { resolve = done }))
  scope.companyId = '2'
  await flushPromises()
  expect(wrapper.text()).not.toContain('120.00')
  const signal = vi.mocked(tradeSummary).mock.calls[1]?.[0]
  scope.companyId = '3'
  await flushPromises()
  resolve({ ...response, sales: { ...response.sales!, month: '9999.00' } })
  await flushPromises()
  expect(signal?.aborted).toBe(true)
  expect(wrapper.text()).not.toContain('9999.00')
  wrapper.unmount()
})
