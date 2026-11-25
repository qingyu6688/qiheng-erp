import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import type { ComputedRef, PropType } from 'vue'
import CostRevaluationPage from '../src/pages/CostRevaluationPage.vue'
import SalesQuotePage from '../src/pages/SalesQuotePage.vue'
import PriceListsPage from '../src/pages/crm/PriceListsPage.vue'
import * as costs from '../src/api/cost-revaluations'
import * as quotes from '../src/api/sales-quotes'
import * as prices from '../src/api/crm-pricing'
import * as admin from '../src/api/admin'
import type { CostRevaluation } from '../src/types/cost-revaluations'
import type { SalesQuote } from '../src/types/sales-quotes'

vi.mock('../src/api/cost-revaluations')
vi.mock('../src/api/sales-quotes')
vi.mock('../src/api/crm-pricing')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => navigation, onBeforeRouteLeave: vi.fn() }))
const session = reactive({ companyId: '1', user: { id: '1' }, can: (): boolean => true })
const route = reactive({ params: {} as { id?: string }, fullPath: '/sales-quotes?status=issued' })
const navigation = { push: vi.fn(), replace: vi.fn() }
const wrappers: ReturnType<typeof mount>[] = []
type Row = Record<string, unknown>
const table = defineComponent({
  props: { data: { type: Array as PropType<Row[]>, default: () => [] } },
  setup(props, { slots }) { provide('completeness-rows', computed(() => props.data)); return () => h('div', slots.default?.()) },
})
const column = defineComponent({
  props: ['prop'],
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Row[]>>('completeness-rows')!
    return () => h('div', rows.value.map((row) => h('div', slots.default?.({ row }) ?? String(row[String(props.prop)] ?? ''))))
  },
})
const input = { props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' }
const stubs = {
  ElDrawer: { props: ['modelValue', 'title', 'direction'], template: '<section v-if="modelValue" role="dialog" :aria-label="title" :data-direction="direction"><slot/><slot name="footer"/></section>' },
  ElTable: table, ElTableColumn: column,
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElSelect: { props: ['modelValue'], emits: ['update:modelValue'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot/></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElInput: input, ElInputNumber: input,
  ElForm: { template: '<form><slot/></form>' }, ElFormItem: { template: '<div><slot/></div>' }, ElSwitch: true,
  ElDescriptions: { template: '<dl><slot/></dl>' }, ElDescriptionsItem: { props: ['label'], template: '<div><dt>{{label}}</dt><dd><slot/></dd></div>' },
  ElEmpty: true, ElSkeleton: true, ElPagination: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  WorkflowProgressPanel: true, WorkflowStarterSelector: true,
}
function costDoc(): CostRevaluation {
  return { id: '9007199254740993', documentNo: 'CR-验收', status: 'posted', warehouseId: '21', warehouseName: '主仓', skuId: '31', skuName: '商品',
    startDate: '2026-11-01', businessDate: '2026-11-24', reason: '旧系统成本差异', quantity: '9', beforeCost: '140.00', afterCost: '120.00', stockDelta: '-20.00',
    issuedDelta: '20.00', eventCount: 3, version: 4, previewHash: '', wfInstanceId: '', lines: [], allowedActions: [] }
}
function quoteDoc(): SalesQuote {
  return { id: '9007199254740995', documentNo: 'SQ-验收', status: 'issued', customerId: '51', customerName: '设备客户', businessDate: '2026-11-15', validTo: '2026-11-30',
    version: 2, totalAmount: '43.20', input: { customerId: '51', businessDate: '2026-11-15', priceTaxInclusive: true, lines: [] },
    pricing: { priceTaxInclusive: true, goodsAmount: '43.20', orderDiscountAmount: '0', lineDiscountAmount: '0', feeAmount: '0', totalAmount: '43.20', untaxedAmount: '38.23', taxAmount: '4.97', lines: [], warnings: [] },
    wfInstanceId: '', belowMinPrice: false, belowMinPricePolicy: 'warn', allowedActions: [], lines: [] }
}
const header = (): prices.PriceListHeader => ({ id: '9007199254740997', name: '供应商协议', currency: 'CNY', priceIncludesTax: true, validFrom: '2026-11-01', validTo: '2026-11-30', priority: 1, status: 'active', version: 0 })
const listView = (): prices.PriceListView => ({ header: header(), items: [], targets: [{ targetType: 'supplier', targetValue: '51' }] })
beforeEach(() => {
  vi.resetAllMocks(); session.companyId = '1'; session.user.id = String(Number(session.user.id) + 1); route.params = {}
  vi.mocked(costs.costRevaluations).mockResolvedValue({ items: [costDoc()], nextCursor: null })
  vi.mocked(costs.costRevaluation).mockResolvedValue(costDoc())
  vi.mocked(quotes.salesQuotes).mockResolvedValue({ items: [quoteDoc()], nextCursor: null })
  vi.mocked(quotes.salesQuote).mockResolvedValue(quoteDoc())
  vi.mocked(prices.priceLists).mockResolvedValue({ items: [header()], nextCursor: null })
  vi.mocked(prices.priceList).mockResolvedValue(listView())
  vi.mocked(admin.allRecords).mockResolvedValue([])
})
afterEach(() => { for (const wrapper of wrappers.splice(0)) wrapper.unmount() })

it('成本列表从右侧查看详情、突出差异摘要，关闭后保留筛选与路由', async () => {
  const wrapper = mount(CostRevaluationPage, { global: { stubs, directives: { loading: () => undefined } } }); wrappers.push(wrapper)
  await flushPromises(); await wrapper.get('select').setValue('posted'); await flushPromises()
  await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click'); await flushPromises()
  expect(wrapper.get('[role="dialog"]').attributes('data-direction')).toBe('rtl')
  expect(wrapper.get('[aria-label="成本重估差异摘要"]').text()).toContain('库存成本调整')
  expect(wrapper.get('[aria-label="成本重估差异摘要"]').text()).toContain('120.00')
  await wrapper.findAll('button').find((button) => button.text() === '关闭')!.trigger('click'); await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false); expect(wrapper.get('select').element.value).toBe('posted')
  expect(costs.costRevaluations).toHaveBeenCalledTimes(2); expect(navigation.push).not.toHaveBeenCalled(); expect(navigation.replace).not.toHaveBeenCalled()
})

it('报价列表原位打开右侧金额摘要，关闭不重新加载或跳转', async () => {
  const wrapper = mount(SalesQuotePage, { global: { stubs, directives: { loading: () => undefined } } }); wrappers.push(wrapper)
  await flushPromises(); await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click'); await flushPromises()
  expect(wrapper.get('[role="dialog"]').attributes('data-direction')).toBe('rtl')
  expect(wrapper.get('[aria-label="报价金额摘要"]').text()).toContain('43.20')
  expect(wrapper.get('[aria-label="报价金额摘要"]').text()).toContain('2026-11-30')
  await wrapper.findAll('button').find((button) => button.text() === '关闭')!.trigger('click'); await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false); expect(quotes.salesQuotes).toHaveBeenCalledOnce()
  expect(navigation.push).not.toHaveBeenCalled(); expect(navigation.replace).not.toHaveBeenCalled()
})

it('采购价格表使用供应商目标及右侧详情', async () => {
  const wrapper = mount(PriceListsPage, { props: { purchase: true }, global: { stubs } }); wrappers.push(wrapper)
  await flushPromises(); await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click'); await flushPromises()
  expect(prices.priceList).toHaveBeenCalledWith(header().id, true)
  expect(wrapper.get('[role="dialog"]').attributes('data-direction')).toBe('rtl')
  expect(wrapper.get('[aria-label="适用类型"]').text()).toContain('指定供应商')
  expect(wrapper.get('[role="dialog"]').text()).not.toContain('通用')
})

it('公司切换到禁止低价后，报价明确提示禁止并隐藏旧响应中的转单按钮', async () => {
  vi.mocked(quotes.salesQuote).mockResolvedValue({ ...quoteDoc(), belowMinPrice: true, belowMinPricePolicy: 'block', allowedActions: ['update', 'convert'] })
  const wrapper = mount(SalesQuotePage, { global: { stubs, directives: { loading: () => undefined } } }); wrappers.push(wrapper)
  await flushPromises(); await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click'); await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('禁止低于最低售价')
  expect(wrapper.findAll('button').some((button) => button.text() === '转销售订单')).toBe(false)
})

it('价格表切换公司后迟到的详情不应覆盖新公司的新建表单', async () => {
  let resolve!: (value: prices.PriceListView) => void
  vi.mocked(prices.priceList).mockReturnValue(new Promise((done) => { resolve = done }))
  const wrapper = mount(PriceListsPage, { props: { purchase: true }, global: { stubs } }); wrappers.push(wrapper)
  await flushPromises(); await wrapper.findAll('button').find((button) => button.text() === '查看')!.trigger('click'); await flushPromises()
  session.companyId = '2'; await flushPromises()
  await wrapper.findAll('button').find((button) => button.text() === '新增价格表')!.trigger('click'); await flushPromises()
  resolve(listView()); await flushPromises()
  expect(wrapper.get<HTMLInputElement>('[role="dialog"] input').element.value).toBe('')
})
