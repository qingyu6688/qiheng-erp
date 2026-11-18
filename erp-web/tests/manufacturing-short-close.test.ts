import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, inject, provide, type PropType } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'
import { manufacturingCommand, manufacturingDetail } from '../src/api/manufacturing'
import WorkOrderPage from '../src/pages/mfg/WorkOrderPage.vue'
import type { WorkOrder } from '../src/types/manufacturing'

vi.mock('../src/api/manufacturing')
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: order.id } }),
  useRouter: () => ({ push: vi.fn() }),
}))
vi.mock('../src/stores/session', () => ({
  useSessionStore: () => ({ companyId: '8', can: (permission: string) => grants.has(permission) }),
}))

const grants = new Set<string>()
const order: WorkOrder = {
  id: '9007199254741001',
  documentNo: 'MO-60',
  status: 'in_progress',
  version: 13,
  allowedActions: ['short_close'],
  skuId: '2',
  skuName: '示例产成品',
  bomId: '3',
  unitId: '4',
  planQty: '100',
  completedQty: '60',
  issueWarehouseId: '5',
  receiveWarehouseId: '6',
  planStartDate: '2026-11-01',
  planFinishDate: '2026-11-30',
  materials: [
    {
      id: '9007199254741002',
      componentSkuId: '7',
      skuName: '生产原料',
      unitId: '4',
      requiredQty: '9007199254740993.123458',
      issuedQty: '9007199254740993.123458',
      returnedQty: '0.000002',
    },
  ],
}
const pending: WorkOrder = {
  ...order,
  version: 14,
  allowedActions: ['approve_short_close'],
  shortClose: {
    status: 'pending',
    orderVersion: 14,
    periodId: '17',
    businessDate: '2026-11-18',
    reason: '客户取消剩余计划',
    costDisposition: 'expense',
    requestedBy: '19',
    materials: [
      {
        materialId: '9007199254741002',
        consumedQty: '9007199254740993.123453',
        scrappedQty: '0.000003',
        scrapReason: '余料损坏',
      },
    ],
  },
}
const tableData = Symbol('tableData')
const slot = { template: '<div><slot /></div>' }
const stubs = {
  RouterLink: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' },
  ElButton: {
    props: ['disabled', 'loading'],
    template: '<button type="button" :disabled="disabled || loading"><slot /></button>',
  },
  ElTag: slot,
  ElDescriptions: slot,
  ElDescriptionsItem: {
    props: ['label'],
    template: '<p>{{ label }} <slot /></p>',
  },
  ElTabs: slot,
  ElTabPane: slot,
  ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: slot,
  ElInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  ElSelect: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template:
      '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><option value=""></option><slot /></select>',
  },
  ElOption: {
    props: ['value', 'label'],
    template: '<option :value="value">{{ label }}</option>',
  },
  ElTable: defineComponent({
    props: { data: { type: Array as PropType<Array<Record<string, unknown>>>, required: true } },
    setup(props, { slots }) {
      provide(tableData, () => props.data)
      return () => h('div', slots.default?.() ?? [])
    },
  }),
  ElTableColumn: defineComponent({
    props: { prop: String },
    setup(props, { slots }) {
      const rows = inject<() => Array<Record<string, unknown>>>(tableData, () => [])
      return () =>
        h(
          'div',
          rows().map((row) =>
            h('div', props.prop ? String(row[props.prop] ?? '') : (slots.default?.({ row }) ?? [])),
          ),
        )
    },
  }),
  ElEmpty: true,
  ManufacturingCostTable: true,
}

async function mountOrder(detail: WorkOrder) {
  vi.mocked(manufacturingDetail).mockResolvedValue(detail)
  const page = mount(WorkOrderPage, {
    global: { stubs, directives: { loading: () => undefined } },
  })
  await flushPromises()
  return {
    page,
    button: (label: string) => page.findAll('button').find((node) => node.text() === label),
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  grants.clear()
  grants.add('mfg_work_order:view')
})

it('短交申请校验净领料及报废原因，并以原始精度、工单版本和幂等键提交', async () => {
  grants.add('mfg_work_order:short_close')
  const { page, button } = await mountOrder(order)
  await button('申请短交关闭')!.trigger('click')
  await page.get('[aria-label="短交日期"]').setValue('2026-11-18')
  await page.get('[aria-label="短交原因"]').setValue('客户取消剩余计划')
  await page.get('[aria-label="剩余在制成本"]').setValue('expense')
  await page.get('[aria-label="生产原料已耗用"]').setValue('9007199254740993.123453')
  await button('提交短交申请')!.trigger('click')
  expect(page.text()).toContain('合计须等于净领料')
  await page.get('[aria-label="生产原料已报废"]').setValue('0.000003')
  await button('提交短交申请')!.trigger('click')
  expect(page.text()).toContain('请填写生产原料的报废原因')
  expect(manufacturingCommand).not.toHaveBeenCalled()
  await page.get('[aria-label="生产原料报废原因"]').setValue('余料损坏')
  let finish!: (result: WorkOrder) => void
  vi.mocked(manufacturingCommand).mockImplementationOnce(
    () =>
      new Promise((resolve) => {
        finish = resolve
      }),
  )
  await button('提交短交申请')!.trigger('click')
  await button('提交短交申请')!.trigger('click')
  expect(manufacturingCommand).toHaveBeenCalledExactlyOnceWith(
    'work-orders',
    order.id,
    'short-close',
    {
      version: order.version,
      businessDate: '2026-11-18',
      reason: '客户取消剩余计划',
      costDisposition: 'expense',
      materials: pending.shortClose?.materials,
    },
    expect.stringMatching(/^[a-z0-9-]+$/),
  )
  finish(pending)
  await flushPromises()
  expect(page.text()).toContain('短交待复核')
  expect(page.text()).toContain('发起人编号 19')
  expect(page.text()).toContain('余料损坏')
  expect(button('复核短交申请')).toBeUndefined()
  page.unmount()
})

it('复核入口受权限、服务端动作和申请版本约束，确认后使用当前版本关闭工单', async () => {
  grants.add('mfg_work_order:approve_short_close')
  const missingAction = await mountOrder({ ...pending, allowedActions: [] })
  expect(missingAction.button('复核短交申请')).toBeUndefined()
  missingAction.page.unmount()
  const expired = await mountOrder({ ...pending, version: 15 })
  expect(expired.page.text()).toContain('短交申请已失效')
  expect(expired.button('复核短交申请')).toBeUndefined()
  expired.page.unmount()
  const { page, button } = await mountOrder(pending)
  expect(page.text()).toContain('客户取消剩余计划')
  expect(page.text()).toContain('9007199254740993.123453')
  await button('复核短交申请')!.trigger('click')
  expect(manufacturingCommand).not.toHaveBeenCalled()
  vi.mocked(manufacturingCommand).mockResolvedValueOnce({
    ...pending,
    version: 15,
    status: 'closed',
    allowedActions: [],
    shortClose: { ...pending.shortClose!, status: 'approved', reviewedBy: '21' },
  })
  await button('确认短交关闭')!.trigger('click')
  await flushPromises()
  expect(manufacturingCommand).toHaveBeenCalledExactlyOnceWith(
    'work-orders',
    order.id,
    'approve-short-close',
    { version: 14 },
    expect.any(String),
  )
  expect(page.text()).toContain('已短交关闭')
  expect(page.text()).toContain('复核人编号 21')
  page.unmount()
})
