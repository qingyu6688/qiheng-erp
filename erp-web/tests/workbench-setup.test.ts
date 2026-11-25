import { beforeEach, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import WorkbenchSetupGuide from '../src/components/WorkbenchSetupGuide.vue'
import { setupStatus } from '../src/api/workbench'
import type { SetupStatus } from '../src/api/workbench'
import { ApiError } from '../src/api/http'

vi.mock('../src/api/workbench')
const granted = reactive(new Set<string>())
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ can: (permission: string) => granted.has(permission) }),
}))
const stubs = {
  RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
}
const pending: SetupStatus = {
  completed: false,
  steps: [
    { key: 'warehouse', required: true, done: true },
    { key: 'customer', required: true, done: false },
    { key: 'sku', required: true, done: false },
    { key: 'stock-opening', required: false, done: false },
    { key: 'first-document', required: true, done: false },
  ],
}
beforeEach(() => {
  vi.resetAllMocks()
  granted.clear()
  localStorage.clear()
})

it('按顺序列出步骤，高亮第一项未完成的必做步骤，已完成项不再给入口', async () => {
  granted.add('sales_order:create')
  vi.mocked(setupStatus).mockResolvedValue(pending)
  const wrapper = mount(WorkbenchSetupGuide, { props: { scope: 'erp.workbench.1.1' }, global: { stubs } })
  await flushPromises()
  expect(wrapper.text()).toContain('上线准备')
  expect(wrapper.text()).toContain('必做 1 / 4')
  const items = wrapper.findAll('li')
  expect(items.map((item) => item.find('strong').text())).toEqual([
    '建立仓库，已完成',
    '添加客户',
    '建立商品与 SKU',
    '录入库存期初（可选）',
    '开出第一张单据',
  ])
  expect(items[0].classes()).toContain('done')
  expect(items[0].find('a').exists()).toBe(false)
  expect(items[1].classes()).toContain('next')
  expect(items[1].get('a').attributes('href')).toBe('/partners')
  // 没有 SKU 查看权限时落到商品目录；没有采购建单权限时不给采购入口
  expect(items[2].get('a').attributes('href')).toBe('/products')
  expect(items[4].findAll('a').map((link) => link.attributes('href'))).toEqual(['/sales-orders/new'])
  wrapper.unmount()
})

it('必做项全部完成后不显示清单', async () => {
  vi.mocked(setupStatus).mockResolvedValue({ completed: true, steps: pending.steps })
  const wrapper = mount(WorkbenchSetupGuide, { props: { scope: 'erp.workbench.1.1' }, global: { stubs } })
  await flushPromises()
  expect(wrapper.find('section').exists()).toBe(false)
  wrapper.unmount()
})

it('收起状态按用户和公司记住，加载失败时给出提示', async () => {
  vi.mocked(setupStatus).mockResolvedValue(pending)
  const first = mount(WorkbenchSetupGuide, { props: { scope: 'erp.workbench.1.1' }, global: { stubs } })
  await flushPromises()
  await first.get('button[aria-controls="setup-guide-steps"]').trigger('click')
  expect(first.get('ol').isVisible()).toBe(false)
  expect(localStorage.getItem('erp.workbench.1.1.setup-collapsed')).toBe('1')
  first.unmount()

  const second = mount(WorkbenchSetupGuide, { props: { scope: 'erp.workbench.1.1' }, global: { stubs } })
  await flushPromises()
  expect(second.get('button[aria-controls="setup-guide-steps"]').text()).toContain('展开')
  second.unmount()

  vi.mocked(setupStatus).mockRejectedValue(new ApiError(500, 'INTERNAL', '服务暂不可用'))
  const failed = mount(WorkbenchSetupGuide, { props: { scope: 'erp.workbench.1.2' }, global: { stubs } })
  await flushPromises()
  expect(failed.get('[role="alert"]').text()).toBe('上线准备加载失败：服务暂不可用')
  failed.unmount()
})
