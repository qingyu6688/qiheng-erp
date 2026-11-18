import * as browser from '../src/api/document-browser'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import ProcurementEditor from '../src/components/procurement/ProcurementEditor.vue'
import DocumentMoreActions from '../src/components/DocumentMoreActions.vue'
import ProcurementDetailPage from '../src/pages/ProcurementDetailPage.vue'
import ProcurementListPage from '../src/pages/ProcurementListPage.vue'
import * as procurement from '../src/api/procurement'
import * as sales from '../src/api/sales'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { ProcurementDocument } from '../src/types/procurement'

const route = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn() }))
vi.mock('../src/api/procurement')
vi.mock('../src/api/sales')
vi.mock('../src/api/admin')
vi.mock('../src/api/document-browser')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ can: () => true }) }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '10' }, query: {}, path: '/purchase-requisitions' }),
  useRouter: () => route,
}))
const input = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template:
    '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>',
})
const select = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue', 'change'],
  template:
    '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>',
})
const stubs = {
  BusinessUserSelect: true,
  AreaCascader: true,
  DocumentFilesPanel: true,
  ElDialog: { template: '<section><slot/><footer><slot name="footer"/></footer></section>' },
  ElInput: input,
  ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElButton: { template: '<button><slot/></button>' },
  ElForm: { template: '<form><slot/></form>' },
  ElFormItem: { template: '<div><slot/></div>' },
  ElSwitch: true,
  ElCheckbox: true,
  ElTable: { template: '<div/>' },
  ElTableColumn: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElTag: { template: '<span><slot/></span>' },
  RouterLink: { template: '<a><slot/></a>' },
}
const order: ProcurementDocument = {
  id: '10',
  documentNo: 'PO-A-202611-000001',
  status: 'draft',
  version: 5,
  allowedActions: [],
  supplier: { id: '20', name: '供应商' },
  businessDate: '2026-11-15',
  priceTaxInclusive: true,
  orderDiscountAmount: '0',
  lines: [
    {
      id: '30',
      skuId: '40',
      warehouseId: '50',
      unitId: '60',
      qty: '2',
      unitPrice: '10',
      lineDiscountAmount: '0',
      taxRate: '0.13',
    },
  ],
}
type Wrapper = ReturnType<typeof mount>
async function click(wrapper: Wrapper, label: string): Promise<void> {
  const target = wrapper.findAll('button').find((b) => b.text() === label)
  if (!target) throw new Error('找不到按钮：' + label)
  await target.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(browser.browseDocuments).mockResolvedValue({
    items: [],
    nextCursor: null,
    total: 0,
    statusCounts: {},
  })
  vi.mocked(procurement.procurementDocument).mockResolvedValue(order)
  vi.mocked(procurement.procurementList).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(procurement.saveProcurement).mockResolvedValue(order)
  vi.mocked(procurement.procurementCommand).mockResolvedValue(order)
  vi.mocked(sales.pricePreview).mockResolvedValue({
    ...order,
    goodsAmount: '200.00',
    totalAmount: '200.00',
    taxAmount: '23.01',
  })
  vi.mocked(sales.candidates).mockResolvedValue({
    items: [
      {
        id: '70',
        requisitionNo: 'PR-1',
        skuId: '40',
        skuName: '商品',
        unitId: '61',
        unitName: '箱',
        orderableQty: '20',
      },
    ],
    nextCursor: null,
  })
  vi.mocked(admin.allRecords).mockImplementation(async (path) =>
    path === 'partners'
      ? [{ id: '20', name: '供应商', roles: ['supplier'], status: 'active' }]
      : path === 'warehouses'
        ? [{ id: '50', name: '收货仓', status: 'active' }]
        : [{ id: '40', code: 'S', name: '商品', status: 'active' }],
  )
  vi.mocked(admin.record).mockResolvedValue({
    id: '40',
    purchasePrice: '10',
    defaultTaxRate: '0.13',
    units: [
      { unitId: '60', unitName: '件', rateToBase: '1', isBase: true },
      { unitId: '61', unitName: '箱', rateToBase: '10', isBase: false },
    ],
  })
})
describe('采购和请购界面契约', () => {
  it('新建付款带出默认付款账户，编辑草稿保留原账户', async () => {
    vi.mocked(admin.allRecords).mockImplementation(async (path) =>
      path === 'cash-accounts'
        ? [
            { id: '50', name: '银行甲', status: 'active' },
            { id: '51', name: '银行乙', status: 'active', defaultForPayment: true },
          ]
        : [],
    )
    const fresh = mount(ProcurementEditor, { props: { kind: 'payments' }, global: { stubs } })
    await flushPromises()
    expect((fresh.get('#purchase-account').element as HTMLSelectElement).value).toBe('51')
    fresh.unmount()
    const original: ProcurementDocument = {
      id: '10',
      status: 'draft',
      version: 1,
      allowedActions: [],
      cashAccountId: '50',
    }
    const draft = mount(ProcurementEditor, {
      props: { kind: 'payments', original },
      global: { stubs },
    })
    await flushPromises()
    expect((draft.get('#purchase-account').element as HTMLSelectElement).value).toBe('50')
    draft.unmount()
  })
  it('新建付款带出默认付款账户，编辑时保留原选择', async () => {
    vi.mocked(admin.allRecords).mockImplementation(async (path) =>
      path === 'cash-accounts'
        ? [
            { id: '50', name: '现金', status: 'active' },
            { id: '51', name: '付款银行', status: 'active', defaultForPayment: true },
          ]
        : [],
    )
    const wrapper = mount(ProcurementEditor, { props: { kind: 'payments' }, global: { stubs } })
    await flushPromises()
    expect((wrapper.get('#purchase-account').element as HTMLSelectElement).value).toBe('51')
    wrapper.unmount()
    const draft = mount(ProcurementEditor, {
      props: {
        kind: 'payments',
        original: { id: '9', status: 'draft', version: 1, allowedActions: [], cashAccountId: '50' },
      },
      global: { stubs },
    })
    await flushPromises()
    expect((draft.get('#purchase-account').element as HTMLSelectElement).value).toBe('50')
    draft.unmount()
  })
  it('从请购箱单位选行后保留来源单位，采购金额由服务端试算', async () => {
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'purchase-orders', original: order },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('select[aria-label="采购来源1"]').setValue('70')
    await flushPromises()
    expect((wrapper.get('select[aria-label="采购单位1"]').element as HTMLSelectElement).value).toBe(
      '61',
    )
    await click(wrapper, '保存采购订单')
    expect(sales.pricePreview).toHaveBeenLastCalledWith(
      expect.objectContaining({
        documentType: 'purchase_order',
        supplierId: '20',
        lines: [
          expect.objectContaining({ requisitionLineId: '70', unitId: '61', unitPrice: undefined }),
        ],
      }),
    )
    expect(procurement.saveProcurement).toHaveBeenCalledWith(
      'purchase-orders',
      expect.objectContaining({
        lines: [
          expect.objectContaining({
            id: '30',
            requisitionLineId: '70',
            unitId: '61',
            unitPrice: undefined,
          }),
        ],
        version: 5,
      }),
      '10',
      undefined,
    )
    expect(wrapper.text()).toContain('合计 ¥200.00')
    wrapper.unmount()
  })
  it('手动切换单位清空旧单价，取消可选来源保留商品和单位', async () => {
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'purchase-orders', original: order },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('select[aria-label="采购来源1"]').setValue('70')
    await flushPromises()
    await wrapper.get('select[aria-label="采购来源1"]').setValue('')
    await wrapper.get('select[aria-label="采购单位1"]').setValue('60')
    await click(wrapper, '保存采购订单')
    expect(procurement.saveProcurement).toHaveBeenCalledWith(
      'purchase-orders',
      expect.objectContaining({
        lines: [
          expect.objectContaining({
            skuId: '40',
            unitId: '60',
            unitPrice: undefined,
            requisitionLineId: null,
          }),
        ],
      }),
      '10',
      undefined,
    )
    wrapper.unmount()
  })
  it('请购保存需用日期和数量，不调用采购价格试算', async () => {
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'purchase-requisitions' },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('select[aria-label="采购商品1"]').setValue('40')
    await flushPromises()
    await wrapper.get('input[aria-label="采购数量1"]').setValue('3.25')
    await wrapper.get('input[aria-label="需求日期1"]').setValue('2026-12-01')
    await click(wrapper, '保存请购单')
    expect(procurement.saveProcurement).toHaveBeenCalledWith(
      'purchase-requisitions',
      expect.objectContaining({
        lines: [
          expect.objectContaining({
            skuId: '40',
            unitId: '60',
            qty: '3.25',
            needDate: '2026-12-01',
          }),
        ],
      }),
      undefined,
      expect.any(String),
    )
    expect(sales.pricePreview).not.toHaveBeenCalled()
    expect(admin.allRecords).not.toHaveBeenCalledWith('warehouses', true)
    wrapper.unmount()
  })
  it('变更审批使用变更版本与原幂等键恢复，不使用订单版本', async () => {
    vi.mocked(procurement.procurementDocument).mockResolvedValue({
      ...order,
      status: 'change_pending',
      allowedActions: ['change_approve'],
      change: { id: '90', version: 2, status: 'pending' },
    })
    vi.mocked(sales.financeWrite)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
      .mockResolvedValueOnce({})
    vi.mocked(sales.commandResult).mockRejectedValueOnce(
      new ApiError(404, 'NOT_FOUND', '未找到结果'),
    )
    const wrapper = mount(ProcurementDetailPage, {
      props: { kind: 'purchase-orders' },
      global: { stubs: { ...stubs, ProcurementEditor: true } },
    })
    await flushPromises()
    await click(wrapper, '批准变更')
    await click(wrapper, '确认批准变更')
    const originalKey = vi.mocked(sales.financeWrite).mock.calls[0][2]
    expect(sales.financeWrite).toHaveBeenCalledWith(
      'purchase-orders/changes/90/approve',
      expect.objectContaining({ version: 2 }),
      originalKey,
    )
    await click(wrapper, '查询结果')
    expect(sales.commandResult).toHaveBeenCalledWith('purchase_order.change_approve', originalKey)
    await click(wrapper, '确认批准变更')
    expect(vi.mocked(sales.financeWrite).mock.calls[1][2]).toBe(originalKey)
    wrapper.unmount()
  })
  it('全部关闭采用后端基础数量原字符串，避免大数量精度丢失', async () => {
    const closeLine = {
      id: '30',
      skuName: '商品',
      progress: {
        baseQty: '9007199254740993.000001',
        shippedQty: '0',
        closedQty: '0',
        returnedQty: '0',
        shippableQty: '9007199254740993.000001',
        closableQty: '9007199254740993.000001',
      },
    }
    vi.mocked(procurement.procurementDocument).mockResolvedValue({
      ...order,
      status: 'approved',
      allowedActions: ['close_lines'],
      lines: [closeLine],
    })
    const wrapper = mount(ProcurementDetailPage, {
      props: { kind: 'purchase-orders' },
      global: { stubs: { ...stubs, ProcurementEditor: true } },
    })
    await flushPromises()
    await click(wrapper, '关闭剩余')
    await click(wrapper, '全部关闭')
    await wrapper.get('#purchase-command-reason').setValue('停止供货')
    await click(wrapper, '确认关闭剩余')
    expect(procurement.procurementCommand).toHaveBeenCalledWith(
      'purchase-orders',
      '10',
      'close_lines',
      expect.objectContaining({
        version: 5,
        reason: '停止供货',
        lines: [{ lineId: '30', closeQty: '9007199254740993.000001' }],
      }),
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('删除草稿超时后详情404直接确认并返回列表', async () => {
    vi.mocked(procurement.procurementDocument)
      .mockResolvedValueOnce({ ...order, allowedActions: ['delete'] })
      .mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '草稿不存在'))
    vi.mocked(procurement.procurementCommand).mockRejectedValueOnce(
      new ApiError(0, 'NETWORK_ERROR', '网络超时'),
    )
    const wrapper = mount(ProcurementDetailPage, {
      props: { kind: 'purchase-orders' },
      global: { stubs: { ...stubs, ProcurementEditor: true } },
    })
    await flushPromises()
    const more = wrapper.getComponent(DocumentMoreActions)
    expect(more.props('actions')).toEqual(['delete'])
    more.vm.$emit('choose', 'delete')
    await flushPromises()
    await click(wrapper, '确认删除草稿')
    await click(wrapper, '查询结果')
    expect(route.replace).toHaveBeenCalledWith('/purchase-orders')
    expect(sales.commandResult).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('请购列表仅显示自身合法状态', async () => {
    const wrapper = mount(ProcurementListPage, {
      props: { kind: 'purchase-requisitions' },
      global: { stubs: { ...stubs, ProcurementEditor: true } },
    })
    await flushPromises()
    expect(wrapper.findAll('[role=tab]').map((tab) => tab.text().replace(/\s+0$/, ''))).toEqual([
      '全部',
      '草稿',
      '待审核',
      '已批准',
      '已关闭',
      '已取消',
    ])
    wrapper.unmount()
  })
  it.each(['loss', 'block', 'unblock', 'scrap'])('库存调整%s不把总成本发给服务端', async (type) => {
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'stock-adjustments' },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('select[aria-label="调整类型"]').setValue(type)
    await wrapper.get('#purchase-warehouse').setValue('50')
    await wrapper.get('#purchase-reason').setValue('库存核对')
    await wrapper.get('select[aria-label="采购商品1"]').setValue('40')
    await flushPromises()
    await wrapper.get('input[aria-label="采购数量1"]').setValue('9007199254740993.000001')
    await click(wrapper, '保存库存调整单')
    const body = vi.mocked(procurement.saveProcurement).mock.calls[0][1]
    expect(body).toEqual(
      expect.objectContaining({
        adjustType: type,
        lines: [
          { skuId: '40', unitId: '60', qty: '9007199254740993.000001', inputTotalCost: undefined },
        ],
      }),
    )
    expect(JSON.stringify(body)).not.toContain('inputTotalCost')
    expect(wrapper.find('input[aria-label="期初总成本1"]').exists()).toBe(false)
    wrapper.unmount()
  })
  it.each(['opening', 'gain'])('库存调整%s提交整行成本并保留数量字符串', async (type) => {
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'stock-adjustments' },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.get('select[aria-label="调整类型"]').setValue(type)
    await wrapper.get('#purchase-warehouse').setValue('50')
    await wrapper.get('#purchase-reason').setValue('库存核对')
    await wrapper.get('select[aria-label="采购商品1"]').setValue('40')
    await flushPromises()
    await wrapper.get('input[aria-label="采购数量1"]').setValue('9007199254740993.000001')
    await wrapper
      .get('input[aria-label="' + (type === 'opening' ? '期初总成本' : '调整总成本') + '1"]')
      .setValue('123.45')
    await click(wrapper, '保存库存调整单')
    expect(procurement.saveProcurement).toHaveBeenCalledWith(
      'stock-adjustments',
      expect.objectContaining({
        adjustType: type,
        lines: [
          { skuId: '40', unitId: '60', qty: '9007199254740993.000001', inputTotalCost: '123.45' },
        ],
      }),
      undefined,
      expect.any(String),
    )
    wrapper.unmount()
  })
  it('编辑库存调整保留原类型，页面禁止更换类型', async () => {
    const original: ProcurementDocument = {
      id: '10',
      documentNo: 'SA-A-202611-000001',
      status: 'draft',
      version: 3,
      allowedActions: ['update'],
      warehouseId: '50',
      adjustType: 'gain',
      businessDate: '2026-11-15',
      reason: '其他入库',
      lines: [{ id: '30', skuId: '40', unitId: '60', qty: '2.000001', inputTotalCost: '12.35' }],
    }
    const wrapper = mount(ProcurementEditor, {
      props: { kind: 'stock-adjustments', original },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.get('select[aria-label="调整类型"]').attributes()).toHaveProperty('disabled')
    await click(wrapper, '保存库存调整单')
    expect(procurement.saveProcurement).toHaveBeenCalledWith(
      'stock-adjustments',
      expect.objectContaining({
        version: 3,
        adjustType: 'gain',
        lines: [{ skuId: '40', unitId: '60', qty: '2.000001', inputTotalCost: '12.35' }],
      }),
      '10',
      undefined,
    )
    wrapper.unmount()
  })
})
