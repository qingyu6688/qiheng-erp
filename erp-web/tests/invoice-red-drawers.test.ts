import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import type { ComputedRef, PropType } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import SalesInvoiceDrawer from '../src/components/sales/SalesInvoiceDrawer.vue'
import ElectronicInvoiceDrawer from '../src/components/einvoice/ElectronicInvoiceDrawer.vue'
import ManualInvoiceRedPage from '../src/pages/ManualInvoiceRedPage.vue'
import ReportPage from '../src/pages/ReportPage.vue'
import * as manual from '../src/api/manual-invoice-reds'
import * as electronic from '../src/api/e-invoice'
import * as reporting from '../src/api/reporting'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { InvoiceRegistration, ManualInvoiceRed } from '../src/types/manual-invoice-reds'
import type { InvoiceDetail } from '../src/api/e-invoice'
import { confirmCompanyLeave, hasUnsavedWork } from '../src/utils/unsaved'

vi.mock('../src/api/manual-invoice-reds')
vi.mock('../src/api/e-invoice')
vi.mock('../src/api/reporting')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
  onBeforeRouteLeave: (guard: () => boolean) => {
    routeLeave = guard
  },
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn(async () => 'confirm') } }))
const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const route = reactive({ query: {} as Record<string, string> })
const push = vi.fn()
let routeLeave: (() => boolean) | undefined
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const table = defineComponent({
  props: { data: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
  setup(props, { slots }) {
    provide(
      'invoice.rows',
      computed(() => props.data),
    )
    return () => h('div', slots.default?.())
  },
})
const column = defineComponent({
  props: ['prop'],
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Record<string, unknown>[]>>('invoice.rows')!
    return () =>
      h(
        'div',
        rows.value.map((row) =>
          h('div', slots.default?.({ row }) ?? String(row[props.prop as string] ?? '')),
        ),
      )
  },
})
const stubs = {
  BusinessUserSelect: true,
  AreaCascader: true,
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
  ElDrawer: {
    name: 'ElDrawer',
    props: ['title', 'beforeClose', 'showClose'],
    template: '<section role="dialog" :aria-label="title"><slot/><slot name="footer"/></section>',
  },
  ElForm: { props: ['disabled'], template: '<fieldset :disabled="disabled"><slot/></fieldset>' },
  ElFormItem: { template: '<div><slot/></div>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElInput: input,
  ElDatePicker: input,
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot/></select>',
  },
  ElOption: { props: ['label', 'value'], template: '<option :value="value">{{label}}</option>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
  ElTable: table,
  ElTableColumn: column,
}
const wrappers: ReturnType<typeof mount>[] = []
function blue(values: Partial<InvoiceRegistration> = {}): InvoiceRegistration {
  return {
    id: '700',
    invoiceType: 'sales',
    invoiceNo: 'BLUE-700',
    invoiceDate: '2026-11-15',
    partnerName: '双来源客户',
    amount: '113.00',
    taxAmount: '13.00',
    status: 'registered',
    version: 3,
    einvoiceRequestId: null,
    lines: [
      { id: '701', receivableId: '90071992547409931', amount: '70.00' },
      { id: '702', receivableId: '90071992547409932', amount: '43.00' },
    ],
    ...values,
  }
}
function red(values: Partial<ManualInvoiceRed> = {}): ManualInvoiceRed {
  return {
    id: '800',
    invoiceId: '700',
    invoiceNo: 'BLUE-700',
    redInvoiceNo: 'RED-800',
    redInvoiceDate: '2026-11-23',
    amount: '113.00',
    taxAmount: '13.00',
    reason: '整票红字待另一人核对',
    status: 'pending',
    version: 0,
    createdBy: '10',
    lines: blue().lines,
    allowedActions: ['cancel'],
    ...values,
  }
}
function issue(values: Partial<InvoiceDetail> = {}): InvoiceDetail {
  return {
    id: '900',
    requestedBy: '10',
    buyerName: '数电客户',
    buyerTaxNo: 'TEST',
    invoiceKind: 'normal',
    status: 'issued',
    amount: '113.00',
    taxAmount: '13.00',
    invoiceNo: 'E-BLUE-900',
    redInvoiceNo: null,
    error: null,
    version: 4,
    lines: [
      {
        id: '901',
        goodsName: '补价商品',
        taxCategoryCode: 'TEST',
        unitName: '笔',
        quantity: '1',
        untaxedAmount: '100.00',
        taxAmount: '13.00',
        shipmentId: '10',
      },
    ],
    files: [],
    ...values,
  }
}
function manualView(readOnly = false): ReturnType<typeof mount> {
  const wrapper = mount(SalesInvoiceDrawer, {
    props: { id: '700' },
    global: {
      stubs: { ...stubs, ElectronicInvoiceDrawer: true },
      provide: { 'erp.readOnlyPreview': readOnly },
    },
  })
  wrappers.push(wrapper)
  return wrapper
}
function electronicView(readOnly = false): ReturnType<typeof mount> {
  const wrapper = mount(ElectronicInvoiceDrawer, {
    props: { id: '900' },
    global: { stubs, provide: { 'erp.readOnlyPreview': readOnly } },
  })
  wrappers.push(wrapper)
  return wrapper
}
async function click(wrapper: ReturnType<typeof mount>, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === label)
  if (!button) throw new Error('找不到按钮：' + label)
  await button.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  session.companyId = '1'
  routeLeave = undefined
  route.query = {}
  session.can = () => true
  vi.mocked(manual.invoiceRegistration).mockResolvedValue(blue())
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(electronic.invoiceDetail).mockResolvedValue(issue())
  vi.mocked(manual.searchManualInvoiceReds).mockResolvedValue({ items: [red()], nextCursor: null })
  vi.mocked(admin.allRecords).mockResolvedValue([])
  vi.mocked(reporting.report).mockResolvedValue({
    reportType: 'sales_summary',
    asOf: '2026-11-23T00:00:00Z',
    items: [
      {
        id: '1',
        sourceType: 'sales_price_adjustment',
        sourceId: '123',
        sourceNo: 'SA-123',
        businessDate: '2026-11-23',
        qty: '0',
        amount: '11.30',
      },
    ],
    nextCursor: null,
    summary: { amount: '11.30' },
  })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
  vi.restoreAllMocks()
})

it('手工整票登记在网络未知后冻结原票版本、金额及键，确认前显示占用未释放', async () => {
  vi.mocked(manual.createManualInvoiceRed)
    .mockRejectedValueOnce(new Error('网络中断'))
    .mockResolvedValueOnce(red())
  const wrapper = manualView()
  await flushPromises()
  expect(wrapper.text()).toContain('90071992547409931')
  expect(wrapper.text()).toContain('90071992547409932')
  await click(wrapper, '登记实际红字')
  await wrapper.get('input[aria-label="手工红字发票号"]').setValue('RED-800')
  await wrapper.get('input[aria-label="手工红字原因"]').setValue('客户折让整票红字')
  await click(wrapper, '登记并等待财务确认')
  expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
  expect(routeLeave?.()).toBe(false)
  expect(confirmCompanyLeave()).toBe(false)
  const unload = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(unload)
  expect(unload.defaultPrevented).toBe(true)
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({ items: [red()], nextCursor: null })
  vi.mocked(manual.invoiceRegistration).mockResolvedValue(
    blue({ manualRedId: '800', manualRedStatus: 'pending' }),
  )
  await click(wrapper, '重试本次请求')
  const calls = vi.mocked(manual.createManualInvoiceRed).mock.calls
  expect(calls).toHaveLength(2)
  expect(calls[1]?.slice(0, 2)).toEqual(calls[0]?.slice(0, 2))
  expect(calls[0]?.[0]).toMatchObject({
    invoiceId: '700',
    sourceVersion: 3,
    amount: '113.00',
    taxAmount: '13.00',
  })
  expect(wrapper.text()).toContain('原票占用尚未释放')
  expect(wrapper.findAll('button').some((button) => button.text() === '财务确认红字')).toBe(false)
  expect(hasUnsavedWork.value).toBe(false)
})

it('另一位财务人员只按当前红字版本确认整票，并刷新原票及来源', async () => {
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({
    items: [red({ allowedActions: ['confirm'] })],
    nextCursor: null,
  })
  vi.mocked(manual.commandManualInvoiceRed).mockResolvedValue(
    red({ status: 'confirmed', version: 1, allowedActions: [] }),
  )
  const wrapper = manualView()
  await flushPromises()
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({
    items: [red({ status: 'confirmed', version: 1, allowedActions: [] })],
    nextCursor: null,
  })
  await click(wrapper, '财务确认红字')
  expect(manual.commandManualInvoiceRed).toHaveBeenCalledWith(
    '800',
    'confirm',
    0,
    expect.any(String),
    expect.any(AbortSignal),
  )
  expect(wrapper.text()).toContain('红字已确认')
  expect(wrapper.emitted('changed')).toHaveLength(1)
})

it('原票更新后不能把正在编辑的红字登记静默换到新版本', async () => {
  const wrapper = manualView()
  await flushPromises()
  await click(wrapper, '登记实际红字')
  await wrapper.get('input[aria-label="手工红字发票号"]').setValue('RED-800')
  vi.mocked(manual.invoiceRegistration).mockResolvedValue(blue({ version: 4 }))
  await click(wrapper, '刷新原票及红字')
  expect(wrapper.text()).toContain('原票已更新')
  const save = wrapper.findAll('button').find((button) => button.text() === '登记并等待财务确认')!
  expect(save.attributes()).toHaveProperty('disabled')
  expect(manual.createManualInvoiceRed).not.toHaveBeenCalled()
})

it('仅修改红字日期也有离开保护，普通作废仍显示需要实际红字', async () => {
  vi.mocked(manual.invoiceRegistration).mockResolvedValue(blue({ status: 'voided' }))
  const wrapper = manualView()
  await flushPromises()
  expect(wrapper.text()).toContain('普通作废登记不代表实际红字已完成')
  await click(wrapper, '登记实际红字')
  await wrapper.get('input[aria-label="手工红字日期"]').setValue('2026-11-22')
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  expect(confirmCompanyLeave()).toBe(false)
  await click(wrapper, '返回详情')
  expect(wrapper.find('input[aria-label="手工红字日期"]').exists()).toBe(true)
})

it('当前权限失效后清空原票和红字历史，避免继续展示旧摘要', async () => {
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({ items: [red()], nextCursor: null })
  const wrapper = manualView()
  await flushPromises()
  vi.mocked(manual.invoiceRegistration).mockRejectedValueOnce(
    new ApiError(404, 'NOT_FOUND', '原票不在当前范围'),
  )
  await click(wrapper, '刷新原票及红字')
  expect(wrapper.text()).toContain('原票不在当前范围')
  expect(wrapper.text()).not.toContain('BLUE-700')
  expect(wrapper.text()).not.toContain('RED-800')
})

it('加载更多红字历史时撤权，也会清空已经显示的整票资料', async () => {
  vi.mocked(manual.manualInvoiceReds).mockResolvedValueOnce({ items: [red()], nextCursor: '600' })
  const wrapper = manualView()
  await flushPromises()
  vi.mocked(manual.manualInvoiceReds).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '红字查看权限已失效'),
  )
  await click(wrapper, '更多历史记录')
  expect(wrapper.text()).toContain('红字查看权限已失效')
  expect(wrapper.text()).not.toContain('BLUE-700')
  expect(wrapper.text()).not.toContain('RED-800')
})

it('只读上下文保留红字事实但隐藏登记、确认与撤回', async () => {
  vi.mocked(manual.manualInvoiceReds).mockResolvedValue({
    items: [red({ allowedActions: ['confirm', 'cancel'] })],
    nextCursor: null,
  })
  const wrapper = manualView(true)
  await flushPromises()
  expect(wrapper.text()).toContain('RED-800')
  expect(wrapper.findAll('button').some((button) => button.text() === '财务确认红字')).toBe(false)
  expect(wrapper.text()).not.toContain('撤回登记')
  expect(wrapper.text()).not.toContain('登记实际红字')
})

it('数电红字未知结果保持原版本与键，普通读取已开具状态不能冒充处理完成', async () => {
  vi.mocked(electronic.invoiceAction)
    .mockRejectedValueOnce(new Error('响应中断'))
    .mockResolvedValueOnce({ id: '900' })
  const wrapper = electronicView()
  await flushPromises()
  await click(wrapper, '申请整票红字')
  await wrapper.get('input[aria-label="数电红字原因"]').setValue('客户折让')
  await click(wrapper, '确认申请整票红字')
  expect(routeLeave?.()).toBe(false)
  await click(wrapper, '刷新进度')
  expect(wrapper.text()).toContain('重试本次请求')
  expect(wrapper.text()).not.toContain('红字已完成')
  vi.mocked(electronic.invoiceDetail).mockResolvedValue(
    issue({ status: 'red_submitted', version: 5 }),
  )
  await click(wrapper, '重试本次请求')
  const calls = vi.mocked(electronic.invoiceAction).mock.calls
  expect(calls).toHaveLength(2)
  expect(calls[1]?.slice(0, 5)).toEqual(calls[0]?.slice(0, 5))
  expect(calls[0]?.slice(0, 3)).toEqual(['900', 'red-issue', 4])
  expect(wrapper.text()).toContain('红字处理中')
  expect(wrapper.text()).not.toContain('红字已完成')
  expect(hasUnsavedWork.value).toBe(false)
})

it('数电已完成红字显示真实红票号，下载沿原请求及文件类型', async () => {
  vi.mocked(electronic.invoiceDetail).mockResolvedValue(
    issue({
      status: 'red_issued',
      redInvoiceNo: 'E-RED-900',
      files: [{ variant: 'red', fileFormat: 'pdf' }],
    }),
  )
  vi.mocked(electronic.downloadInvoice).mockResolvedValue()
  const wrapper = electronicView()
  await flushPromises()
  expect(wrapper.text()).toContain('E-RED-900')
  expect(wrapper.text()).toContain('红字已完成')
  expect(wrapper.text()).not.toContain('申请整票红字')
  await click(wrapper, '红字 PDF')
  expect(electronic.downloadInvoice).toHaveBeenCalledWith(expect.objectContaining({ id: '900' }), {
    variant: 'red',
    fileFormat: 'pdf',
  })
})

it('数电只读详情不提供红字操作，撤权刷新清空旧票号', async () => {
  const wrapper = electronicView(true)
  await flushPromises()
  expect(wrapper.text()).not.toContain('申请整票红字')
  vi.mocked(electronic.invoiceDetail).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '没有数电查看权限'),
  )
  await click(wrapper, '刷新进度')
  expect(wrapper.text()).toContain('没有数电查看权限')
  expect(wrapper.text()).not.toContain('E-BLUE-900')
})

it('财务待确认列表在右侧打开原票，关闭保持当前筛选与分页', async () => {
  vi.mocked(manual.searchManualInvoiceReds).mockImplementation(async (params) => ({
    items: [red({ status: params.status ?? 'pending' })],
    nextCursor: params.cursor ? null : '600',
  }))
  const detail = defineComponent({
    name: 'SalesInvoiceDrawer',
    props: ['id'],
    emits: ['close'],
    template:
      '<aside role="dialog">原票{{id}}<button @click="$emit(\'close\')">关闭原票</button></aside>',
  })
  const wrapper = mount(ManualInvoiceRedPage, {
    global: {
      stubs: { ...stubs, SalesInvoiceDrawer: detail },
      directives: { loading: () => undefined },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  expect(manual.searchManualInvoiceReds).toHaveBeenLastCalledWith(
    { status: 'pending', cursor: undefined, limit: 20 },
    expect.any(AbortSignal),
  )
  await wrapper.get('select[aria-label="手工红字状态筛选"]').setValue('confirmed')
  await flushPromises()
  await click(wrapper, '下一页')
  expect(manual.searchManualInvoiceReds).toHaveBeenLastCalledWith(
    { status: 'confirmed', cursor: '600', limit: 20 },
    expect.any(AbortSignal),
  )
  const count = vi.mocked(manual.searchManualInvoiceReds).mock.calls.length
  await click(wrapper, '查看原票及红字')
  expect(wrapper.get('[role="dialog"]').text()).toContain('原票700')
  await click(wrapper, '关闭原票')
  expect(manual.searchManualInvoiceReds).toHaveBeenCalledTimes(count)
  expect(push).not.toHaveBeenCalled()
  expect((wrapper.get('select').element as HTMLSelectElement).value).toBe('confirmed')
})

it('销售报表来源按销售权限打开右侧抽屉，无需成本权限和路由跳转', async () => {
  session.can = (permission) => permission !== 'cost:view'
  route.query = { detail: 'true', dateFrom: '2026-11-01', dateTo: '2026-11-23' }
  const detail = defineComponent({
    name: 'SalesPriceAdjustmentDrawer',
    props: ['id'],
    emits: ['close'],
    template:
      '<aside role="dialog">销售调价{{id}}<button @click="$emit(\'close\')">关闭调价</button></aside>',
  })
  const wrapper = mount(ReportPage, {
    props: { type: 'sales-summary' },
    global: {
      stubs: { ...stubs, SalesPriceAdjustmentDrawer: detail },
      directives: { loading: () => undefined },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  await click(wrapper, 'SA-123')
  expect(wrapper.get('[role="dialog"]').text()).toContain('销售调价123')
  await click(wrapper, '关闭调价')
  expect(reporting.report).toHaveBeenCalledTimes(1)
  expect(push).not.toHaveBeenCalled()
  expect(route.query.detail).toBe('true')
  await click(wrapper, 'SA-123')
  session.companyId = '2'
  await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
})

it('缺少销售调价查看权限时，报表不会回退成可跳转的来源链接', async () => {
  session.can = (permission) => permission !== 'sales_price_adjustment:view'
  route.query = { detail: 'true' }
  const wrapper = mount(ReportPage, {
    props: { type: 'sales-summary' },
    global: { stubs, directives: { loading: () => undefined } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  expect(
    wrapper
      .findAll('button')
      .some((button) => button.text() === 'SA-123' || button.text() === '查看原单'),
  ).toBe(false)
  expect(wrapper.find('a[href="/sales-price-adjustments/123"]').exists()).toBe(false)
})
