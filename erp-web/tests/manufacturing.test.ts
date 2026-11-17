import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import {
  manufacturingCommand,
  manufacturingDetail,
  manufacturingList,
} from '../src/api/manufacturing'
import CostPeriodPage from '../src/pages/mfg/CostPeriodPage.vue'
import ManufacturingReports from '../src/pages/mfg/ManufacturingReports.vue'
import { remainingMaterial } from '../src/pages/mfg/useManufacturing'
import type { ManufacturingPeriod } from '../src/types/manufacturing'

vi.mock('../src/api/manufacturing')
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ companyId: '1', can: (permission: string) => grants.has(permission) }),
}))

const period: ManufacturingPeriod = {
  periodId: '9007199254740993',
  period: '200001',
  startDate: '2000-01-01',
  endDate: '2000-01-31',
  status: 'open',
  accountingStatus: 'open',
  version: 0,
  wipMethod: 'materials',
  expenses: [],
  orders: [],
  items: [],
  hasAllocation: false,
}
const grants = new Set<string>()
const stubButton = {
  props: ['disabled', 'nativeType'],
  template: '<button :disabled="disabled" :type="nativeType || \'button\'"><slot /></button>',
}
const stubs = {
  ElStep: true,
  ElSteps: true,
  ElInput: true,
  ElSelect: true,
  ElOption: true,
  ElCheckbox: true,
  ElEmpty: true,
  ElAlert: true,
  ElTable: true,
  ElTableColumn: true,
  ElFormItem: true,
  ElForm: { template: '<form><slot /></form>' },
  ElTabs: { template: '<div><slot /></div>' },
  ElTabPane: {
    props: ['label', 'disabled'],
    template: '<section :data-label="label" :data-disabled="disabled"><slot /></section>',
  },
  ElButton: stubButton,
  ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
  ManufacturingCostTable: true,
}

beforeEach(() => {
  vi.resetAllMocks()
  grants.clear()
})

it('用料差异计算保留六位精度，可显示超领后负的待领数量', () => {
  expect(remainingMaterial('9007199254740993.123456', '9007199254740993.123451', '0.000002')).toBe(
    '0.000007',
  )
  expect(remainingMaterial('1', '1.000002', '0.000001')).toBe('-0.000001')
})

it('无金额权限时只读取生产进度，并隐藏成本报表入口', async () => {
  grants.add('mfg_report:view')
  vi.mocked(manufacturingList).mockResolvedValue({ items: [], nextCursor: null })
  const page = mount(ManufacturingReports, {
    global: { stubs: { ...stubs, RouterLink: true }, directives: { loading: () => undefined } },
  })
  await flushPromises()
  expect(page.find('[data-label="生产成本明细"]').exists()).toBe(false)
  expect(manufacturingList).toHaveBeenCalledExactlyOnceWith('reports/progress', {
    warehouseId: undefined,
    workOrderId: undefined,
    periodId: undefined,
    cursor: undefined,
  })
  page.unmount()
})

it('归集、分摊、计算、确认过账逐步带上最新版本和幂等键', async () => {
  for (const permission of ['mfg_cost:view', 'cost:view', 'mfg_cost:manage', 'mfg_cost:post'])
    grants.add(permission)
  vi.mocked(manufacturingList).mockResolvedValue({ items: [period], nextCursor: null })
  vi.mocked(manufacturingDetail).mockResolvedValue(period)
  vi.mocked(manufacturingCommand)
    .mockResolvedValueOnce({ ...period, version: 1 })
    .mockResolvedValueOnce({ ...period, version: 2, hasAllocation: true })
    .mockResolvedValueOnce({ ...period, version: 3, status: 'calculated', hasAllocation: true })
    .mockResolvedValueOnce({ ...period, version: 4, status: 'posted', hasAllocation: true })
  const page = mount(CostPeriodPage, {
    global: { stubs, directives: { loading: () => undefined } },
  })
  await flushPromises()
  const button = (label: string) => page.findAll('button').find((node) => node.text() === label)
  expect(button('过账生产成本差异')).toBeUndefined()
  expect(page.get('[data-label="计算成本"]').attributes('data-disabled')).toBe('true')
  await page.get('[data-label="归集费用"] form').trigger('submit')
  await flushPromises()
  await button('保存分摊依据')!.trigger('click')
  await flushPromises()
  await button('计算生产成本')!.trigger('click')
  await flushPromises()
  await button('过账生产成本差异')!.trigger('click')
  await flushPromises()
  expect(vi.mocked(manufacturingCommand)).toHaveBeenCalledTimes(3)
  await button('确认过账')!.trigger('click')
  await flushPromises()
  const calls = vi.mocked(manufacturingCommand).mock.calls
  expect(calls.map(([scope, id, action, input]) => [scope, id, action, input])).toEqual([
    [
      'cost-periods',
      period.periodId,
      'collect',
      { version: 0, wipMethod: 'materials', expenses: [] },
    ],
    ['cost-periods', period.periodId, 'allocate', { version: 1, orders: [] }],
    ['cost-periods', period.periodId, 'calculate', { version: 2 }],
    ['cost-periods', period.periodId, 'post', { version: 3 }],
  ])
  expect(new Set(calls.map((call) => call[4])).size).toBe(4)
  expect(button('过账生产成本差异')).toBeUndefined()
  page.unmount()
})
