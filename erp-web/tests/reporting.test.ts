import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ReportPage from '../src/pages/ReportPage.vue'
import * as reports from '../src/api/reporting'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import {
  documentLink,
  reportCell,
  reportPermission,
  reportSpecs,
  reportToday,
} from '../src/types/reporting'
import type { ReportResult } from '../src/types/reporting'
import type { DataRow } from '../src/types/admin'

vi.mock('../src/api/reporting')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push }) }))
const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const route = reactive({ query: {} as Record<string, string> })
const push = vi.fn()
const select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot/></select>',
})
const stubs = {
  BusinessUserSelect: true,
  AreaCascader: true,
  ElButton: {
    props: ['nativeType', 'loading', 'disabled'],
    template: '<button :type="nativeType ?? \'button\'" :disabled="disabled"><slot/></button>',
  },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElTable: { name: 'ElTable', props: ['data'], template: '<div><slot/></div>' },
  ElTableColumn: { name: 'ElTableColumn', props: ['label'], template: '<div>{{label}}</div>' },
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
}
type Wrapper = ReturnType<typeof mount>
const mounted: Wrapper[] = []
function result(id = '1'): ReportResult {
  return {
    reportType: 'inventory_summary',
    asOf: '2026-11-15T08:00:00Z',
    items: [{ id, warehouseName: '业务仓', onHandQty: '123456789012345678.123456' }],
    nextCursor: null,
    summary: {},
  }
}
function page(type = 'inventory-summary'): Wrapper {
  const wrapper = mount(ReportPage, { props: { type }, global: { stubs } })
  mounted.push(wrapper)
  return wrapper
}
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
async function click(wrapper: Wrapper, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === label)
  if (!button) throw new Error('找不到按钮：' + label)
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  session.can = () => true
  route.query = {}
  vi.mocked(admin.allRecords).mockResolvedValue([])
  vi.mocked(reports.report).mockResolvedValue(result())
  vi.mocked(reports.createExport).mockResolvedValue({ taskId: '50' })
})
afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.useRealTimers()
})

describe('报表类型与精度', () => {
  it('十类报表独立权限、事实别名穿透和上海日期保持一致', () => {
    expect(reportSpecs).toHaveLength(10)
    expect(new Set(reportSpecs.map((spec) => reportPermission(spec.type))).size).toBe(10)
    expect(reportPermission('sales-summary', 'export')).toBe('export:sales_summary')
    expect(documentLink('shipment_sign', '123')).toBe('/shipments/123')
    expect(documentLink('sales_return_line', '124')).toBe('/sales-returns/124')
    expect(documentLink('purchase_order_fee', '125')).toBe('/goods-receipts/125')
    expect(documentLink('document_reversal', '126')).toBe('/document-reversals/126')
    expect(documentLink('shipment', '../1')).toBeUndefined()
    expect(documentLink('shipment', 123)).toBeUndefined()
    expect(reportCell('amount', '1234567890123456789012.34')).toBe('1234567890123456789012.34')
    expect(reportCell('direction', 'D')).toBe('借方')
    expect(reportCell('postingAction', 'reverse')).toBe('冲销')
    expect(reportToday(new Date('2026-11-14T16:00:00Z'))).toBe('2026-11-15')
  })
})

describe('报表页面请求与权限', () => {
  it('较晚返回的旧查询不能覆盖新结果，卸载中止请求', async () => {
    const old = deferred<ReportResult>(),
      latest = deferred<ReportResult>()
    vi.mocked(reports.report).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page()
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    latest.resolve(result('2'))
    await flushPromises()
    old.resolve(result('1'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual(result('2').items)
    expect(vi.mocked(reports.report).mock.calls[0][2]?.aborted).toBe(true)
    wrapper.unmount()
    expect(vi.mocked(reports.report).mock.calls[1][2]?.aborted).toBe(true)
  })
  it('同一公司快速切换报表时忽略旧初始化资料和旧错误', async () => {
    const old = deferred<DataRow[]>()
    vi.mocked(admin.allRecords).mockImplementation((path) =>
      path === 'warehouses' ? old.promise : Promise.resolve([]),
    )
    const wrapper = page()
    await flushPromises()
    await wrapper.setProps({ type: 'cash-journal' })
    await flushPromises()
    old.resolve([{ id: '10', name: '旧页面仓库' }])
    await flushPromises()
    expect(reports.report).toHaveBeenCalledTimes(1)
    expect(vi.mocked(reports.report).mock.calls[0][0]).toBe('cash-journal')
    expect(wrapper.find('#report-warehouse').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('旧页面仓库')
  })
  it('切换公司时旧结果不能回填，当前公司重新查询', async () => {
    const old = deferred<ReportResult>()
    vi.mocked(reports.report).mockReturnValueOnce(old.promise).mockResolvedValueOnce(result('2'))
    const wrapper = page()
    await flushPromises()
    session.companyId = '2'
    await flushPromises()
    old.resolve(result('1'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'ElTable' }).props('data')).toEqual(result('2').items)
  })
  it('仅加载受支持筛选资料，隐藏无成本权限列及导出按钮', async () => {
    session.can = (permission) => permission !== 'cost:view' && !permission.startsWith('export:')
    const warehousePage = page()
    await flushPromises()
    expect(vi.mocked(admin.allRecords).mock.calls.map((call) => call[0])).toEqual([
      'warehouses',
      'skus',
    ])
    expect(warehousePage.find('#report-partner').exists()).toBe(false)
    expect(warehousePage.text()).not.toContain('导出报表')
    expect(
      warehousePage
        .findAllComponents({ name: 'ElTableColumn' })
        .map((column) => column.props('label')),
    ).not.toContain('成本金额')
    vi.mocked(admin.allRecords).mockClear()
    await warehousePage.setProps({ type: 'receivable-aging' })
    await flushPromises()
    expect(vi.mocked(admin.allRecords).mock.calls.map((call) => call[0])).toEqual(['partners'])
  })
  it('网络超时重试同一导出沿用原幂等键，筛选变化后使用新键', async () => {
    vi.mocked(reports.createExport)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '请求超时'))
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '请求超时'))
      .mockResolvedValueOnce({ taskId: '70' })
    const wrapper = page('sales-summary')
    await flushPromises()
    await click(wrapper, '导出报表')
    expect(wrapper.text()).toContain('请求超时')
    await click(wrapper, '导出报表')
    const [first, retry] = vi.mocked(reports.createExport).mock.calls
    expect(first[2]).toBe(retry[2])
    await wrapper.get('#report-date-from').setValue('2026-11-02')
    await click(wrapper, '导出报表')
    expect(vi.mocked(reports.createExport).mock.calls[2][2]).not.toBe(first[2])
    expect(push).toHaveBeenCalledWith({ path: '/file-jobs', query: { tab: 'export', jobId: '70' } })
  })
  it('切换公司后完成的旧导出不能跳转覆盖当前页面', async () => {
    const old = deferred<{ taskId: string }>()
    vi.mocked(reports.createExport).mockReturnValueOnce(old.promise)
    const wrapper = page()
    await flushPromises()
    await click(wrapper, '导出报表')
    session.companyId = '2'
    await flushPromises()
    old.resolve({ taskId: 'OLD' })
    await flushPromises()
    expect(push).not.toHaveBeenCalled()
  })
  it('商业汇总展示合计行，明细深链接保留单位日期和费用筛选并按当前层级导出', async () => {
    const wrapper = page('sales-summary')
    await flushPromises()
    expect(
      wrapper.findAllComponents({ name: 'ElTableColumn' }).map((column) => column.props('label')),
    ).toContain('明细行数')
    expect(
      wrapper.findAllComponents({ name: 'ElTableColumn' }).map((column) => column.props('label')),
    ).not.toContain('来源单号')
    route.query = {
      detail: 'true',
      partnerId: '4',
      dateFrom: '2026-11-04',
      dateTo: '2026-11-04',
      feesOnly: 'true',
    }
    await flushPromises()
    expect(wrapper.get('h1').text()).toBe('销售报表明细')
    expect(vi.mocked(reports.report).mock.calls.at(-1)?.[1]).toMatchObject({
      detail: true,
      partnerId: '4',
      dateFrom: '2026-11-04',
      dateTo: '2026-11-04',
      feesOnly: true,
    })
    expect(
      wrapper.findAllComponents({ name: 'ElTableColumn' }).map((column) => column.props('label')),
    ).toContain('来源单号')
    await click(wrapper, '导出报表')
    expect(vi.mocked(reports.createExport).mock.calls[0][1]).toMatchObject({
      detail: true,
      partnerId: '4',
      feesOnly: true,
    })
  })
  it('资金流水仅提供账户与日期筛选，避免无法归属单位的资金分录被误筛掉', async () => {
    vi.mocked(admin.allRecords).mockResolvedValue([{ id: '10', name: '现金账户' }])
    const wrapper = page('cash-journal')
    await flushPromises()
    expect(vi.mocked(admin.allRecords).mock.calls.map((call) => call[0])).toEqual(['cash-accounts'])
    expect(wrapper.find('#report-partner').exists()).toBe(false)
    expect(wrapper.find('#report-warehouse').exists()).toBe(false)
    expect(wrapper.find('#report-sku').exists()).toBe(false)
    expect(wrapper.get('#report-account').text()).toContain('现金账户')
    expect(wrapper.find('#report-date-from').exists()).toBe(true)
    expect(wrapper.find('#report-date-to').exists()).toBe(true)
  })
})
