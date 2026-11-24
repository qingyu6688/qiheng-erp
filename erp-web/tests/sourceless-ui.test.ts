import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, inject, onMounted, provide, reactive } from 'vue'
import type { ComputedRef, PropType } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import * as admin from '../src/api/admin'
import * as api from '../src/api/sourceless-returns'
import * as sales from '../src/api/sales'
import * as workflow from '../src/api/workflow'
import { ApiError } from '../src/api/http'
import SourcelessReturnForm from '../src/components/sales/SourcelessReturnForm.vue'
import SourcelessRefundForm from '../src/components/sales/SourcelessRefundForm.vue'
import SourcelessDocumentPanel from '../src/components/sales/SourcelessDocumentPanel.vue'
import SalesListPage from '../src/pages/SalesListPage.vue'
import type { RefundCreditItem } from '../src/types/sourceless-returns'
import type { SalesDocument } from '../src/types/sales'
import type { WorkflowInstance, WorkflowTask } from '../src/types/workflow'

vi.mock('../src/api/admin')
vi.mock('../src/api/sourceless-returns')
vi.mock('../src/api/sales')
vi.mock('../src/api/workflow')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: vi.fn(),
  useRouter: () => ({ push, replace: vi.fn() }),
}))
const denied = reactive(new Set<string>()),
  push = vi.fn()
const session = reactive({
  companyId: '1',
  user: { id: '7', permissions: [] as string[] },
  can: (permission: string): boolean => !denied.has(permission),
})
const wrappers: VueWrapper[] = []
let user = 100

const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)" />',
})
const select = defineComponent({
  props: ['modelValue', 'disabled'],
  emits: ['update:modelValue', 'change'],
  template:
    '<select :disabled="disabled" :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>',
})
const table = defineComponent({
  props: { data: { type: Array as PropType<Record<string, unknown>[]>, default: () => [] } },
  setup(props, { slots }) {
    provide(
      'sourceless-test-rows',
      computed(() => props.data),
    )
    return () => h('div', slots.default?.())
  },
})
const column = defineComponent({
  props: ['label', 'prop'],
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Record<string, unknown>[]>>('sourceless-test-rows')!
    return () =>
      h('div', { 'data-label': props.label }, [
        h('b', String(props.label ?? '')),
        ...rows.value.map((row, $index) =>
          h('div', slots.default?.({ row, $index }) ?? String(row[String(props.prop)] ?? '')),
        ),
      ])
  },
})
const stubs = {
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
  ElForm: {
    props: ['disabled'],
    template: '<form><fieldset :disabled="disabled"><slot/></fieldset></form>',
  },
  ElFormItem: { props: ['label'], template: '<label>{{label}}<slot/></label>' },
  ElInput: input,
  ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElButton: {
    props: ['nativeType', 'disabled', 'loading'],
    template:
      '<button :type="nativeType ?? \'button\'" :disabled="disabled || loading"><slot/></button>',
  },
  ElCheckbox: {
    props: ['modelValue', 'disabled'],
    emits: ['change'],
    template:
      '<input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'change\',$event.target.checked)" />',
  },
  ElTable: table,
  ElTableColumn: column,
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  BusinessUserSelect: true,
  StockSelectionFields: true,
  WorkflowStarterSelector: defineComponent({
    emits: ['ready'],
    setup(_, { emit }) {
      onMounted(() => emit('ready', true))
      return () => h('span', '已选择审批流程')
    },
  }),
  SourcelessSourcePreview: {
    props: ['id'],
    emits: ['close'],
    template: '<aside>退货来源 {{id}}<button @click="$emit(\'close\')">关闭来源</button></aside>',
  },
}
beforeEach(() => {
  vi.clearAllMocks()
  denied.clear()
  session.companyId = '1'
  session.user.id = String(++user)
  session.user.permissions = []
  vi.mocked(admin.allRecords).mockResolvedValue([])
  vi.mocked(admin.record).mockResolvedValue({
    id: '30',
    units: [{ unitId: '40', unitName: '件', isBase: true, rateToBase: '1' }],
  })
  vi.mocked(api.refundCreditItems).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(workflow.workflowProgress).mockResolvedValue({
    canOpen: true,
    tasks: [],
    instances: [],
  })
})
afterEach(() => {
  for (const wrapper of wrappers.splice(0)) wrapper.unmount()
})
function keep<T extends VueWrapper>(wrapper: T): T {
  wrappers.push(wrapper)
  return wrapper
}
async function click(wrapper: VueWrapper, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text() === label)
  expect(button, `缺少按钮：${label}`).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
function credit(id = '91', amount = '10.00'): RefundCreditItem {
  return {
    id,
    partnerId: '10',
    sourceId: '51',
    sourceLineId: '61',
    sourceNo: 'SR-' + id,
    returnMode: 'sourceless',
    warehouseId: '20',
    warehouseName: '退回仓',
    skuName: '退回商品',
    businessDate: '2026-11-15',
    amount,
    openAmount: amount,
  }
}
function returned(overrides: Partial<SalesDocument> = {}): SalesDocument {
  return {
    id: '51',
    documentNo: 'SR-51',
    returnMode: 'sourceless',
    customerId: '10',
    warehouseId: '20',
    customerName: '演示客户',
    warehouseName: '退回仓',
    businessDate: '2026-11-15',
    reason: '历史退回',
    version: 1,
    status: 'draft',
    allowedActions: ['submit', 'update', 'void'],
    creditAmount: '22.60',
    untaxedAmount: '20.00',
    taxAmount: '2.60',
    costAmount: '10.00',
    lines: [
      {
        id: '61',
        skuId: '30',
        unitId: '40',
        skuName: '退回商品',
        unitName: '件',
        qty: '2',
        unitPrice: '11.30',
        taxRate: '0.13',
        qualityResult: 'pending',
        costAmount: '10.00',
        manualUnitCost: '5',
        manualCostReason: '历史台账',
      },
    ],
    ...overrides,
  }
}
function flow(): WorkflowInstance {
  const task: WorkflowTask = {
    id: '81',
    instanceId: '71',
    nodeKey: 'review',
    nodeName: '成本复核',
    nodeKind: 'approver',
    assigneeId: session.user.id,
    status: 'pending',
    version: 4,
    canHandle: true,
    createdAt: '2026-11-15',
  }
  return {
    id: '71',
    businessType: 'sales_return',
    businessId: '51',
    businessNo: 'SR-51',
    title: '退货复核',
    status: 'running',
    starterId: '1',
    modelVersion: 1,
    version: 2,
    startedAt: '2026-11-15',
    summary: {},
    tasks: [task],
    actions: [],
    canCancel: false,
    canTerminate: false,
  }
}

it('无原单表单保留大额与批次资料，成本权限撤销后不发送隐藏成本', async () => {
  const original = returned({
    lines: [
      {
        ...returned().lines![0]!,
        unitPrice: '9007199254740991.12345678',
        qty: '1.000001',
        stockSelection: { batchNo: 'LOT-1', serials: ['SN-1'] },
      },
    ],
  })
  const wrapper = keep(
    mount(SourcelessReturnForm, { props: { original, locked: false }, global: { stubs } }),
  )
  await flushPromises()
  expect(wrapper.find('[aria-label="含税退货单价"]').element).toHaveProperty(
    'value',
    '9007199254740991.12345678',
  )
  denied.add('cost:view')
  await flushPromises()
  expect(wrapper.find('[aria-label="补填基本单位成本"]').exists()).toBe(false)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
  const payload = wrapper.emitted('save')?.[0]?.[0]
  expect(payload).toMatchObject({
    version: 1,
    lines: [
      {
        qty: '1.000001',
        unitPrice: '9007199254740991.12345678',
        stockSelection: { batchNo: 'LOT-1', serials: ['SN-1'] },
      },
    ],
  })
  expect(JSON.stringify(payload)).not.toContain('manualUnitCost')
  expect(JSON.stringify(payload)).not.toContain('manualCostReason')
})

it('更换退回仓库清除旧批次及序列号，未知结果锁定时不再保存', async () => {
  const wrapper = keep(
    mount(SourcelessReturnForm, {
      props: { original: returned(), locked: false },
      global: { stubs },
    }),
  )
  await flushPromises()
  await wrapper.find('[aria-label="退回仓库编号"]').setValue('22')
  await wrapper.find('form').trigger('submit')
  expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
    warehouseId: '22',
    lines: [{ stockSelection: null }],
  })
  await wrapper.setProps({ locked: true })
  await wrapper.find('form').trigger('submit')
  expect(wrapper.emitted('save')).toHaveLength(1)
})

it('信用额分页保留首屏之外原选来源，勾选新页后按分核对大额', async () => {
  const old = credit('91', '9007199254740991.12'),
    next = credit('92', '0.13')
  vi.mocked(api.refundCreditItems)
    .mockResolvedValueOnce({ items: [next], nextCursor: '92' })
    .mockResolvedValueOnce({ items: [credit('93')], nextCursor: null })
  const original: SalesDocument = {
    id: '70',
    status: 'draft',
    version: 3,
    allowedActions: ['update'],
    customerId: '10',
    cashAccountId: '21',
    amount: '9007199254740991.25',
    businessDate: '2026-11-15',
    reason: '历史退款',
    refundMode: 'sourceless_credit',
    creditItemIds: [old.id],
    creditSources: [old],
  }
  const wrapper = keep(
    mount(SourcelessRefundForm, { props: { original, locked: false }, global: { stubs } }),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('SR-91')
  await wrapper.find('[aria-label="选择信用额 SR-92"]').setValue(true)
  expect(wrapper.text()).toContain('9,007,199,254,740,991.25')
  await click(wrapper, '下一页信用额')
  expect(api.refundCreditItems).toHaveBeenLastCalledWith('10', '92', expect.any(AbortSignal))
  expect(wrapper.text()).toContain('SR-92')
  await wrapper.find('form').trigger('submit')
  expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
    amount: '9007199254740991.25',
    creditItemIds: ['91', '92'],
  })
})

it('信用额来源请求返回403后移除已选资料并阻止保存', async () => {
  vi.mocked(api.refundCreditItems).mockRejectedValueOnce(
    new ApiError(403, 'FORBIDDEN', '来源权限已撤销'),
  )
  const original: SalesDocument = {
    id: '70',
    status: 'draft',
    version: 1,
    allowedActions: ['update'],
    customerId: '10',
    cashAccountId: '21',
    amount: '10',
    reason: '退款',
    businessDate: '2026-11-15',
    creditItemIds: ['91'],
    creditSources: [credit()],
  }
  const wrapper = keep(
    mount(SourcelessRefundForm, { props: { original, locked: false }, global: { stubs } }),
  )
  await flushPromises()
  // 403后的原资料也不能从旧props重新出现在列表中。
  expect(wrapper.text()).not.toContain('SR-91')
  await wrapper.find('form').trigger('submit')
  expect(wrapper.emitted('save')).toBeUndefined()
})

it('普通审批人按流程参与资格在详情内审批，不依赖不存在的全局查看权限', async () => {
  denied.add('workflow_instance:view')
  vi.mocked(sales.document).mockResolvedValue(
    returned({ status: 'pending', wfInstanceId: '71', allowedActions: ['approve', 'reject'] }),
  )
  vi.mocked(workflow.workflowInstance).mockResolvedValue(flow())
  vi.mocked(workflow.workflowTaskAction).mockResolvedValue({
    id: '81',
    instanceId: '71',
    status: 'approved',
  })
  const wrapper = keep(
    mount(SourcelessDocumentPanel, {
      props: { kind: 'sales-returns', id: '51' },
      global: { stubs },
    }),
  )
  await flushPromises()
  expect(workflow.workflowInstance).toHaveBeenCalledWith('71')
  await wrapper.find('[aria-label="审批处理意见"]').setValue('已核对历史成本')
  await click(wrapper, '同意 · 成本复核')
  expect(workflow.workflowTaskAction).toHaveBeenCalledWith(
    '81',
    'approve',
    { version: 4, comment: '已核对历史成本' },
    expect.any(String),
  )
  expect(sales.salesCommand).not.toHaveBeenCalled()
  expect(wrapper.emitted('saved')?.[0]).toEqual(['51'])
})

it('成本预览撤权后立即清空，迟到旧响应不回填成本', async () => {
  vi.mocked(sales.document).mockResolvedValue(returned())
  let resolve!: (value: Awaited<ReturnType<typeof api.returnCostPreview>>) => void
  vi.mocked(api.returnCostPreview).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    }),
  )
  const wrapper = keep(
    mount(SourcelessDocumentPanel, {
      props: { kind: 'sales-returns', id: '51' },
      global: { stubs },
    }),
  )
  await flushPromises()
  const button = wrapper.findAll('button').find((item) => item.text() === '预览当前成本')!
  await button.trigger('click')
  denied.add('cost:view')
  await flushPromises()
  resolve({ totalCost: '999999.99', items: [], notice: '旧权限内容' })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧权限内容')
  expect(wrapper.text()).not.toContain('999,999.99')
  expect(wrapper.text()).not.toContain('冻结入库成本')
})

it('提交使用现有工作流选择且不把批准当作过账，原键失败可在同详情确认', async () => {
  vi.mocked(sales.document).mockResolvedValue(returned())
  vi.mocked(sales.salesCommand).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络中断'))
  const wrapper = keep(
    mount(SourcelessDocumentPanel, {
      props: { kind: 'sales-returns', id: '51' },
      global: { stubs },
    }),
  )
  await flushPromises()
  await click(wrapper, '提交')
  await click(wrapper, '确认提交')
  expect(sales.salesCommand).toHaveBeenCalledWith(
    'sales-returns',
    '51',
    'submit',
    { version: 1, reason: undefined, workflowAssignees: {} },
    expect.any(String),
  )
  expect(wrapper.text()).toContain('原请求结果尚未确认')
  vi.mocked(sales.commandResult).mockResolvedValueOnce({
    status: 'succeeded',
    response: returned({ status: 'pending' }),
  })
  await click(wrapper, '确认原请求结果')
  expect(wrapper.emitted('saved')?.[0]).toEqual(['51'])
})

it('退款按真实连字符动作匹配下划线权限，实际付款信息齐全后才登记', async () => {
  denied.add('refund_request:register-paid')
  const refund: SalesDocument = {
    id: '92',
    documentNo: 'RF-92',
    refundMode: 'sourceless_credit',
    customerId: '10',
    amount: '10.00',
    version: 2,
    status: 'pending_payment',
    allowedActions: ['register-paid', 'cancel'],
    creditSources: [credit()],
  }
  vi.mocked(sales.document).mockResolvedValue(refund)
  vi.mocked(sales.salesCommand).mockResolvedValueOnce({ ...refund, status: 'refunded' })
  const wrapper = keep(
    mount(SourcelessDocumentPanel, {
      props: { kind: 'refund-requests', id: '92' },
      global: { stubs },
    }),
  )
  await flushPromises()
  await click(wrapper, '登记已退款')
  await click(wrapper, '确认登记已退款')
  expect(wrapper.text()).toContain('请填写实际付款日期和银行流水号')
  expect(sales.salesCommand).not.toHaveBeenCalled()
  await wrapper.get('[aria-label="实际退款日期"]').setValue('2026-11-15')
  await wrapper.get('[aria-label="银行流水号"]').setValue('BANK-REAL-92')
  await click(wrapper, '确认登记已退款')
  expect(sales.salesCommand).toHaveBeenCalledWith(
    'refund-requests',
    '92',
    'register_paid',
    {
      version: 2,
      paidDate: '2026-11-15',
      bankSerialNo: 'BANK-REAL-92',
    },
    expect.any(String),
  )
  denied.add('refund_request:register_paid')
  await flushPromises()
  expect(wrapper.findAll('button').map((button) => button.text())).not.toContain('登记已退款')
})

it('只读详情即使接口返回操作也不显示保存、审批或过账按钮', async () => {
  vi.mocked(sales.document).mockResolvedValue(
    returned({ status: 'approved', allowedActions: ['update', 'post', 'submit'] }),
  )
  const wrapper = keep(
    mount(SourcelessDocumentPanel, {
      props: { kind: 'sales-returns', id: '51' },
      global: { stubs, provide: { 'erp.readOnlyPreview': true } },
    }),
  )
  await flushPromises()
  expect(wrapper.text()).toContain('SR-51')
  expect(wrapper.findAll('button').map((item) => item.text())).not.toContain('过账')
  expect(wrapper.findComponent(SourcelessReturnForm).exists()).toBe(false)
})

it('新模式列表查看与新建在右侧打开，保存刷新原列表并保留路由', async () => {
  const refresh = vi.fn(),
    row = returned()
  const browser = defineComponent({
    setup(_, { slots, expose }) {
      expose({ refresh })
      return () =>
        h('div', [
          slots.open?.({ row, label: '查看' }),
          slots.open?.({ row, label: row.documentNo }),
        ])
    },
  })
  const drawer = defineComponent({
    props: ['kind', 'id'],
    emits: ['close', 'saved'],
    template:
      '<aside>右侧单据 {{id}}<button @click="$emit(\'saved\',id)">保存测试单据</button></aside>',
  })
  const wrapper = keep(
    mount(SalesListPage, {
      props: { kind: 'sales-returns' },
      global: {
        stubs: { ...stubs, DocumentBrowserPanel: browser, SourcelessDocumentDrawer: drawer },
      },
    }),
  )
  await flushPromises()
  await click(wrapper, '查看')
  expect(wrapper.find('aside').text()).toContain('51')
  await click(wrapper, '保存测试单据')
  expect(refresh).toHaveBeenCalledTimes(1)
  expect(push).not.toHaveBeenCalled()
})
