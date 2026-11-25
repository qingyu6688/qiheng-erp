import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import type { ComputedRef, PropType } from 'vue'
import SalesPriceAdjustmentDrawer from '../src/components/sales/SalesPriceAdjustmentDrawer.vue'
import SalesPriceAdjustmentPage from '../src/pages/SalesPriceAdjustmentPage.vue'
import * as api from '../src/api/sales-price-adjustments'
import type { SalesPriceAdjustment, SalesRedInvoice } from '../src/types/sales-price-adjustments'
import type { SalesDocument } from '../src/types/sales'
import { ApiError } from '../src/api/http'
import { confirmCompanyLeave, hasUnsavedWork } from '../src/utils/unsaved'

vi.mock('../src/api/sales-price-adjustments')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => navigation,
  onBeforeRouteLeave: (guard: () => boolean) => {
    routeLeave = guard
  },
}))
vi.mock('element-plus', () => ({
  ElMessageBox: {
    confirm: vi.fn(async () => 'confirm'),
    prompt: vi.fn(async () => ({ value: '复核原因' })),
  },
}))
const session = reactive<{ companyId: string; can: (permission: string) => boolean }>({
  companyId: '1',
  can: () => true,
})
const route = reactive({
  params: {} as { id?: string },
  fullPath: '/sales-price-adjustments?status=posted',
})
const navigation = { push: vi.fn(), replace: vi.fn() }
let routeLeave: (() => boolean) | undefined
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
})
const select = defineComponent({
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue'],
  template:
    '<select :disabled="disabled" :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot/></select>',
})
const table = defineComponent({
  props: { data: { type: Array as PropType<SalesPriceAdjustment[]>, default: () => [] } },
  setup(props, { slots }) {
    provide(
      'price-test-rows',
      computed(() => props.data),
    )
    return () => h('div', slots.default?.())
  },
})
const column = defineComponent({
  props: ['prop'],
  setup(props, { slots }) {
    const rows = inject<ComputedRef<SalesPriceAdjustment[]>>('price-test-rows')!
    return () =>
      h(
        'div',
        rows.value.map((row) =>
          h(
            'div',
            slots.default?.({ row }) ?? String(row[props.prop as keyof SalesPriceAdjustment] ?? ''),
          ),
        ),
      )
  },
})
const stubs = {
  ElDrawer: {
    props: ['title'],
    template: '<section role="dialog" :aria-label="title"><slot/><slot name="footer"/></section>',
  },
  ElForm: { props: ['disabled'], template: '<fieldset :disabled="disabled"><slot/></fieldset>' },
  ElFormItem: { template: '<div><slot/></div>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot/></button>' },
  ElInput: input,
  ElSelect: select,
  ElOption: { props: ['label', 'value'], template: '<option :value="value">{{label}}</option>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
  ElTable: table,
  ElTableColumn: column,
  WorkflowProgressPanel: true,
  StockDocumentEditor: true,
  SalesInvoiceDrawer: true,
  WorkflowStarterSelector: defineComponent({
    emits: ['ready', 'update:modelValue'],
    mounted() {
      this.$emit('ready', true)
      this.$emit('update:modelValue', { review: ['9007199254740995'] })
    },
    template: '<span>自选复核人</span>',
  }),
}

function document(values: Partial<SalesPriceAdjustment> = {}): SalesPriceAdjustment {
  return {
    id: '9007199254740993',
    documentNo: 'SA-1',
    kind: 'sales',
    partnerId: '10',
    partnerName: '测试客户',
    warehouseId: '20',
    warehouseName: '测试仓',
    businessDate: '2026-11-23',
    reason: '结算调价',
    status: 'draft',
    version: 0,
    amount: '-2.00',
    untaxedAmount: '-2.00',
    taxAmount: '0.00',
    createdBy: '30',
    lines: [
      {
        id: '40',
        shipmentLineId: '50',
        skuId: '60',
        skuName: '测试商品',
        unitId: '70',
        unitName: '件',
        shipmentNo: 'SH-1',
        sourceQty: '2',
        sourceBaseQty: '2',
        sourceReturnedQty: '0',
        inputUnitPrice: null,
        inputAmount: '-2.00',
        oldAmount: '20',
        oldTax: '0',
        needsRed: false,
        oldUnitPrice: '10',
        newUnitPrice: '9',
        amount: '-2.00',
        untaxedAmount: '-2.00',
        taxAmount: '0',
      },
    ],
    allowedActions: ['update'],
    ...values,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  session.companyId = '1'
  session.can = () => true
  route.params = {}
  routeLeave = undefined
  vi.mocked(api.salesPriceAdjustmentMetadata).mockResolvedValue({
    partners: [{ id: '10', name: '测试客户' }],
    warehouses: [{ id: '20', name: '测试仓' }],
  })
  vi.mocked(api.salesPriceAdjustmentSources).mockResolvedValue({
    items: [
      {
        id: '50',
        shipmentId: '100',
        shipmentNo: 'SH-1',
        customerId: '10',
        warehouseId: '20',
        skuId: '60',
        skuName: '测试商品',
        unitId: '70',
        unitName: '件',
        version: 1,
        returnableQty: '2',
        amount: '20',
        taxAmount: '0',
      },
    ],
    nextCursor: null,
  })
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(document())
  vi.mocked(api.salesPriceAdjustments).mockResolvedValue({ items: [document()], nextCursor: null })
  vi.mocked(api.commandSalesPriceAdjustment).mockResolvedValue(
    document({ status: 'pending', allowedActions: [] }),
  )
})

it('在列表右侧查看与关闭详情，不改变路由、筛选或重新加载列表', async () => {
  const wrapper = mount(SalesPriceAdjustmentPage, {
    global: { stubs, directives: { loading: () => undefined } },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('测试客户')
  expect(navigation.push).not.toHaveBeenCalled()
  expect(navigation.replace).not.toHaveBeenCalled()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '关闭')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  expect(api.salesPriceAdjustments).toHaveBeenCalledTimes(1)
  expect(route.fullPath).toBe('/sales-price-adjustments?status=posted')
  wrapper.unmount()
})

it('网络结果不明时冻结原载荷与幂等键，重试成功后读取已经保存的同一单据', async () => {
  vi.mocked(api.saveSalesPriceAdjustment)
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络中断'))
    .mockResolvedValueOnce(document())
  const receipt = {
    id: '100',
    documentNo: 'SH-1',
    customerId: '10',
    warehouseId: '20',
    status: 'signed',
    version: 0,
    allowedActions: [],
    lines: [{ id: '50', skuName: '测试商品', baseQty: '2.000000', returnedBaseQty: '0.000000' }],
  } as SalesDocument
  const wrapper = mount(SalesPriceAdjustmentDrawer, {
    props: { initialShipment: receipt },
    global: { stubs },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '添加明细')!
    .trigger('click')
  await flushPromises()
  await wrapper.get('input[aria-label="调价原因"]').setValue('结算折让')
  await wrapper.get('input[aria-label="第1行含税调整金额"]').setValue('-2.00')
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
  expect(hasUnsavedWork.value).toBe(true)
  expect(routeLeave?.()).toBe(false)
  expect(confirmCompanyLeave()).toBe(false)
  confirm.mockRestore()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '保存草稿')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.get('fieldset').attributes()).toHaveProperty('disabled')
  expect(routeLeave?.()).toBe(false)
  expect(confirmCompanyLeave()).toBe(false)
  const refresh = new Event('beforeunload', { cancelable: true })
  window.dispatchEvent(refresh)
  expect(refresh.defaultPrevented).toBe(true)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试本次请求')!
    .trigger('click')
  await flushPromises()
  const calls = vi.mocked(api.saveSalesPriceAdjustment).mock.calls
  expect(calls).toHaveLength(2)
  expect(calls[1]?.slice(0, 3)).toEqual(calls[0]?.slice(0, 3))
  expect(calls[0]?.[0].lines).toEqual([{ shipmentLineId: '50', adjustmentAmount: '-2.00' }])
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新单据')!
    .trigger('click')
  await flushPromises()
  expect(api.salesPriceAdjustment).toHaveBeenCalledWith('9007199254740993', expect.any(AbortSignal))
  expect(routeLeave?.()).toBe(true)
  expect(confirmCompanyLeave()).toBe(true)
  wrapper.unmount()
  expect(hasUnsavedWork.value).toBe(false)
})

it('提交审批时保留长标识的自选复核人，且不把界面金额传入审批命令', async () => {
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(document({ allowedActions: ['submit'] }))
  const wrapper = mount(SalesPriceAdjustmentDrawer, {
    props: { id: '9007199254740993' },
    global: { stubs },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '提交审批')!
    .trigger('click')
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '确认提交审批')!
    .trigger('click')
  await flushPromises()
  expect(api.commandSalesPriceAdjustment).toHaveBeenCalledWith(
    '9007199254740993',
    'submit',
    { version: 0, workflowAssignees: { review: ['9007199254740995'] } },
    expect.any(String),
    expect.any(AbortSignal),
  )
  wrapper.unmount()
})

it('无成本权限仍可查看销售价格及原票红字，且不显示成本分摊', async () => {
  session.can = (permission) => permission !== 'cost:view'
  const value = document({ status: 'approved', allowedActions: ['post'] })
  value.lines[0]!.needsRed = true
  value.lines[0]!.redInvoices = [
    {
      invoiceId: '800',
      invoiceNo: 'BLUE-800',
      status: 'registered',
      einvoiceRequestId: null,
      electronicStatus: null,
      redInvoiceNo: null,
      redComplete: false,
    },
  ]
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(value)
  const wrapper = mount(SalesPriceAdjustmentDrawer, { props: { id: value.id }, global: { stubs } })
  await flushPromises()
  expect(wrapper.text()).toContain('测试客户')
  expect(wrapper.text()).toContain('请先完成原票整票红字')
  expect(wrapper.text()).toContain('BLUE-800')
  expect(wrapper.text()).not.toContain('成本处理结果')
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看原票及红字')!
    .trigger('click')
  expect(wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).attributes('id')).toBe('800')
  wrapper.unmount()
})

function redInvoice(id: string): SalesRedInvoice {
  return {
    invoiceId: id,
    invoiceNo: 'BLUE-' + id,
    status: 'voided',
    einvoiceRequestId: null,
    electronicStatus: null,
    redInvoiceNo: 'RED-' + id,
    redComplete: true,
  }
}

it('原票分页去重并保留右侧查看，刷新后重置旧游标', async () => {
  const value = document({ redInvoices: [redInvoice('800')], redInvoiceCursor: '800' })
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(value)
  vi.mocked(api.salesPriceRedInvoices).mockResolvedValue({
    items: [redInvoice('800'), redInvoice('700')],
    nextCursor: null,
  })
  const wrapper = mount(SalesPriceAdjustmentDrawer, { props: { id: value.id }, global: { stubs } })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '更多原票')!
    .trigger('click')
  await flushPromises()
  expect(api.salesPriceRedInvoices).toHaveBeenCalledWith(
    value.id,
    { cursor: '800', limit: 20 },
    expect.any(AbortSignal),
  )
  expect(wrapper.findAll('.price-invoice-list li')).toHaveLength(2)
  expect(wrapper.text()).toContain('BLUE-700')
  await wrapper.findAll('.price-invoice-list li')[1]!.get('button').trigger('click')
  expect(wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).attributes('id')).toBe('700')
  expect(navigation.push).not.toHaveBeenCalled()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新单据')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('BLUE-700')
  expect(wrapper.findAll('button').some((button) => button.text() === '更多原票')).toBe(true)
  wrapper.unmount()
})

it('登记红字触发父单刷新时保留原票抽屉，刷新撤权才关闭', async () => {
  const value = document({ redInvoices: [redInvoice('800')] })
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(value)
  const wrapper = mount(SalesPriceAdjustmentDrawer, { props: { id: value.id }, global: { stubs } })
  await flushPromises()
  await wrapper.findAll('.price-invoice-list li')[0]!.get('button').trigger('click')
  let finish: ((value: SalesPriceAdjustment) => void) | undefined
  vi.mocked(api.salesPriceAdjustment).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).vm.$emit('changed')
  await flushPromises()
  expect(wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).attributes('id')).toBe('800')
  finish?.(value)
  await flushPromises()
  expect(wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).attributes('id')).toBe('800')
  vi.mocked(api.salesPriceAdjustment).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '查看权限已变更'),
  )
  wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).vm.$emit('changed')
  await flushPromises()
  expect(wrapper.findComponent({ name: 'SalesInvoiceDrawer' }).exists()).toBe(false)
  expect(wrapper.text()).not.toContain('测试客户')
  wrapper.unmount()
})

it('原票翻页撤权清空详情，切公司后的迟到页不能恢复旧资料', async () => {
  const value = document({ redInvoices: [redInvoice('800')], redInvoiceCursor: '800' })
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(value)
  vi.mocked(api.salesPriceRedInvoices).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '原票权限已变更'),
  )
  const denied = mount(SalesPriceAdjustmentDrawer, { props: { id: value.id }, global: { stubs } })
  await flushPromises()
  await denied
    .findAll('button')
    .find((button) => button.text() === '更多原票')!
    .trigger('click')
  await flushPromises()
  expect(denied.text()).not.toContain('BLUE-800')
  expect(denied.text()).not.toContain('测试客户')
  expect(denied.text()).toContain('原票权限已变更')
  denied.unmount()
  let finish: ((value: Awaited<ReturnType<typeof api.salesPriceRedInvoices>>) => void) | undefined
  vi.mocked(api.salesPriceRedInvoices).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  const changed = mount(SalesPriceAdjustmentDrawer, { props: { id: value.id }, global: { stubs } })
  await flushPromises()
  await changed
    .findAll('button')
    .find((button) => button.text() === '更多原票')!
    .trigger('click')
  session.companyId = '2'
  await flushPromises()
  finish?.({ items: [redInvoice('700')], nextCursor: '700' })
  await flushPromises()
  expect(changed.emitted('close')).toHaveLength(1)
  expect(changed.text()).not.toContain('测试客户')
  expect(changed.text()).not.toContain('BLUE-700')
  expect(changed.text()).not.toContain('BLUE-800')
  changed.unmount()
})

it('刷新时权限被收窄后清空旧单据和原票摘要', async () => {
  const wrapper = mount(SalesPriceAdjustmentDrawer, {
    props: { id: '9007199254740993' },
    global: { stubs },
  })
  await flushPromises()
  expect(wrapper.text()).toContain('测试客户')
  vi.mocked(api.salesPriceAdjustment).mockRejectedValueOnce(
    new ApiError(404, 'NOT_FOUND', '单据不在当前范围'),
  )
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新单据')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('单据不在当前范围')
  expect(wrapper.text()).not.toContain('测试客户')
  expect(wrapper.text()).not.toContain('测试商品')
  wrapper.unmount()
})

it('翻页后权限失效不继续保留上一页的销售调价数据', async () => {
  vi.mocked(api.salesPriceAdjustments).mockResolvedValueOnce({
    items: [document()],
    nextCursor: '80',
  })
  const wrapper = mount(SalesPriceAdjustmentPage, {
    global: { stubs, directives: { loading: () => undefined } },
  })
  await flushPromises()
  expect(wrapper.text()).toContain('测试客户')
  vi.mocked(api.salesPriceAdjustments).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '当前没有销售调价查看权限'),
  )
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '下一页')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('当前没有销售调价查看权限')
  expect(wrapper.text()).not.toContain('测试客户')
  wrapper.unmount()
})

it('只读上下文隐藏编辑、过账和冲销，即使旧响应含有操作', async () => {
  vi.mocked(api.salesPriceAdjustment).mockResolvedValue(
    document({ status: 'posted', allowedActions: ['update', 'post'] }),
  )
  const wrapper = mount(SalesPriceAdjustmentDrawer, {
    props: { id: '9007199254740993' },
    global: { stubs, provide: { 'erp.readOnlyPreview': true } },
  })
  await flushPromises()
  expect(wrapper.text()).not.toContain('编辑草稿')
  expect(wrapper.text()).not.toContain('申请冲销')
  expect(wrapper.findAll('button').some((button) => button.text() === '过账')).toBe(false)
  wrapper.unmount()
})
