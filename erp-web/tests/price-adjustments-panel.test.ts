import { beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import type { ComputedRef, PropType } from 'vue'
import PurchasePriceAdjustmentDrawer from '../src/components/procurement/PurchasePriceAdjustmentDrawer.vue'
import PurchasePriceAdjustmentPage from '../src/pages/PurchasePriceAdjustmentPage.vue'
import * as api from '../src/api/price-adjustments'
import type { PriceAdjustment } from '../src/types/price-adjustments'
import type { ProcurementDocument } from '../src/types/procurement'
import { ApiError } from '../src/api/http'
import { confirmCompanyLeave, hasUnsavedWork } from '../src/utils/unsaved'

vi.mock('../src/api/price-adjustments')
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
const session = reactive({ companyId: '1', can: () => true })
const route = reactive({
  params: {} as { id?: string },
  fullPath: '/purchase-price-adjustments?status=posted',
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
  props: { data: { type: Array as PropType<PriceAdjustment[]>, default: () => [] } },
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
    const rows = inject<ComputedRef<PriceAdjustment[]>>('price-test-rows')!
    return () =>
      h(
        'div',
        rows.value.map((row) =>
          h(
            'div',
            slots.default?.({ row }) ?? String(row[props.prop as keyof PriceAdjustment] ?? ''),
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
  WorkflowStarterSelector: defineComponent({
    emits: ['ready', 'update:modelValue'],
    mounted() {
      this.$emit('ready', true)
      this.$emit('update:modelValue', { review: ['9007199254740995'] })
    },
    template: '<span>自选复核人</span>',
  }),
}

function document(values: Partial<PriceAdjustment> = {}): PriceAdjustment {
  return {
    id: '9007199254740993',
    documentNo: 'PA-1',
    kind: 'purchase',
    partnerId: '10',
    partnerName: '测试供应商',
    warehouseId: '20',
    warehouseName: '测试仓',
    businessDate: '2026-11-23',
    reason: '结算调价',
    status: 'draft',
    version: 0,
    amount: '-2.00',
    untaxedAmount: '-2.00',
    taxAmount: '0.00',
    stockCost: '0.00',
    issuedCost: '0.00',
    createdBy: '30',
    lines: [
      {
        id: '40',
        receiptLineId: '50',
        skuId: '60',
        skuName: '测试商品',
        unitId: '70',
        unitName: '件',
        receiptNo: 'GR-1',
        sourceQty: '2',
        sourceBaseQty: '2',
        sourceReturnedQty: '0',
        inputUnitPrice: null,
        inputAmount: '-2.00',
        oldUnitPrice: '10',
        newUnitPrice: '9',
        amount: '-2.00',
        untaxedAmount: '-2.00',
        taxAmount: '0',
        stockCost: '0',
        issuedCost: '0',
        costTaxInclusive: false,
      },
    ],
    costAllocations: [],
    allowedActions: ['update'],
    ...values,
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  session.companyId = '1'
  route.params = {}
  routeLeave = undefined
  vi.mocked(api.priceAdjustmentMetadata).mockResolvedValue({
    partners: [{ id: '10', name: '测试供应商' }],
    warehouses: [{ id: '20', name: '测试仓' }],
  })
  vi.mocked(api.priceAdjustmentSources).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.priceAdjustment).mockResolvedValue(document())
  vi.mocked(api.priceAdjustments).mockResolvedValue({ items: [document()], nextCursor: null })
  vi.mocked(api.commandPriceAdjustment).mockResolvedValue(
    document({ status: 'pending', allowedActions: [] }),
  )
})

it('在列表右侧查看与关闭详情，不改变路由、筛选或重新加载列表', async () => {
  const wrapper = mount(PurchasePriceAdjustmentPage, {
    global: { stubs, directives: { loading: () => undefined } },
  })
  await flushPromises()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '查看')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.get('[role="dialog"]').text()).toContain('测试供应商')
  expect(navigation.push).not.toHaveBeenCalled()
  expect(navigation.replace).not.toHaveBeenCalled()
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '关闭')!
    .trigger('click')
  await flushPromises()
  expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  expect(api.priceAdjustments).toHaveBeenCalledTimes(1)
  expect(route.fullPath).toBe('/purchase-price-adjustments?status=posted')
  wrapper.unmount()
})

it('网络结果不明时冻结原载荷与幂等键，重试成功后读取已经保存的同一单据', async () => {
  vi.mocked(api.savePriceAdjustment)
    .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络中断'))
    .mockResolvedValueOnce(document())
  const receipt = {
    id: '100',
    documentNo: 'GR-1',
    supplierId: '10',
    warehouseId: '20',
    lines: [{ id: '50', skuName: '测试商品', baseQty: '2.000000', returnedBaseQty: '0.000000' }],
  } as ProcurementDocument
  const wrapper = mount(PurchasePriceAdjustmentDrawer, {
    props: { initialReceipt: receipt },
    global: { stubs },
  })
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
  const calls = vi.mocked(api.savePriceAdjustment).mock.calls
  expect(calls).toHaveLength(2)
  expect(calls[1]?.slice(0, 3)).toEqual(calls[0]?.slice(0, 3))
  expect(calls[0]?.[0].lines).toEqual([{ receiptLineId: '50', adjustmentAmount: '-2.00' }])
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '刷新单据')!
    .trigger('click')
  await flushPromises()
  expect(api.priceAdjustment).toHaveBeenCalledWith('9007199254740993', expect.any(AbortSignal))
  expect(routeLeave?.()).toBe(true)
  expect(confirmCompanyLeave()).toBe(true)
  wrapper.unmount()
  expect(hasUnsavedWork.value).toBe(false)
})

it('提交审批时保留长标识的自选复核人，且不把界面金额传入审批命令', async () => {
  vi.mocked(api.priceAdjustment).mockResolvedValue(document({ allowedActions: ['submit'] }))
  const wrapper = mount(PurchasePriceAdjustmentDrawer, {
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
  expect(api.commandPriceAdjustment).toHaveBeenCalledWith(
    '9007199254740993',
    'submit',
    { version: 0, workflowAssignees: { review: ['9007199254740995'] } },
    expect.any(String),
    expect.any(AbortSignal),
  )
  wrapper.unmount()
})
