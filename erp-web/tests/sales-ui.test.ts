import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import OrderEditor from '../src/components/sales/OrderEditor.vue'
import SalesDetailPage from '../src/pages/SalesDetailPage.vue'
import SalesListPage from '../src/pages/SalesListPage.vue'
import CommandDialog from '../src/components/sales/CommandDialog.vue'
import * as api from '../src/api/sales'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { SalesDocument } from '../src/types/sales'

vi.mock('../src/api/sales')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ can: () => true }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ params: { id: '10' } }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

const dialog = defineComponent({ template: '<section><slot/><footer><slot name="footer"/></footer></section>' })
const input = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>',
})
const select = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue', 'change'],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>',
})
const option = defineComponent({ props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' })
const button = defineComponent({ template: '<button><slot/></button>' })
const stubs = {
  ElDialog: dialog, ElInput: input, ElSelect: select, ElOption: option, ElButton: button,
  ElForm: { template: '<form><slot/></form>' }, ElFormItem: { template: '<div><slot/></div>' },
  ElSwitch: true, ElCheckbox: input, ElTable: { template: '<div/>' }, ElTableColumn: true,
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' }, ElTag: { template: '<span><slot/></span>' },
  RouterLink: { template: '<a><slot/></a>' }, CommandDialog: true, DocumentEditor: true,
}
const order: SalesDocument = {
  id: '10', documentNo: 'SO-A-202611-000001', version: 5, status: 'draft', allowedActions: [],
  customerId: '20', businessDate: '2026-11-15', priceTaxInclusive: true, orderDiscountAmount: '0',
  lines: [{ id: '30', skuId: '40', warehouseId: '50', unitId: '60', qty: '2', unitPrice: '10', lineDiscountAmount: '0', taxRate: '.13' }],
}
type Wrapper = ReturnType<typeof mount>
async function click(wrapper: Wrapper, label: string): Promise<void> {
  const target = wrapper.findAll('button').find(item => item.text() === label)
  if (!target) throw new Error('找不到按钮：' + label)
  await target.trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.documents).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.candidates).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.document).mockResolvedValue(order)
  vi.mocked(api.pricePreview).mockResolvedValue({ ...order, goodsAmount: '200.00', totalAmount: '200.00', lineDiscountAmount: '0.00', taxAmount: '23.01' })
  vi.mocked(api.saveDocument).mockResolvedValue(order)
  vi.mocked(admin.allRecords).mockImplementation(async path => path === 'partners'
    ? [{ id: '20', name: '客户', roles: ['customer'], status: 'active' }]
    : path === 'warehouses' ? [{ id: '50', name: '主仓', status: 'active' }]
      : [{ id: '40', code: 'S', name: '商品', status: 'active' }])
  vi.mocked(admin.record).mockResolvedValue({ id: '40', salePrice: '10', defaultTaxRate: '.13', units: [
    { unitId: '60', unitName: '件', rateToBase: '1', isBase: true },
    { unitId: '61', unitName: '箱', rateToBase: '10', isBase: false },
  ] })
})

describe('销售订单接口对齐', () => {
  it.each(['direct', 'recover'] as const)('提交库存提示在详情刷新后保留（%s）', async mode => {
    const warning = { message: '商品需要30，当前可用10，审批时可能失败' }
    vi.mocked(api.document).mockResolvedValueOnce({ ...order, allowedActions: ['submit'] })
      .mockResolvedValue({ ...order, status: 'pending', version: 6 })
    if (mode === 'direct') vi.mocked(api.salesCommand).mockResolvedValue({ ...order, status: 'pending', warnings: [warning] })
    else {
      vi.mocked(api.salesCommand).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
      vi.mocked(api.commandResult).mockResolvedValue({ status: 'succeeded', response: { ...order, status: 'pending', warnings: [warning] } })
    }
    const wrapper = mount(SalesDetailPage, { props: { kind: 'sales-orders' }, global: { stubs: { ...stubs, CommandDialog: false } } })
    await flushPromises()
    await click(wrapper, '提交')
    await click(wrapper, '确认提交')
    if (mode === 'recover') {
      await click(wrapper, '查询结果')
      expect(api.commandResult).toHaveBeenCalledWith('sales_order.submit', vi.mocked(api.salesCommand).mock.calls[0][4])
    }
    expect(api.document).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain(warning.message)
    expect(wrapper.findComponent(CommandDialog).exists()).toBe(false)
    wrapper.unmount()
  })

  it('切换箱单位后由服务端换算参考价，并显示真实预览金额', async () => {
    const wrapper = mount(OrderEditor, { props: { original: order }, global: { stubs } })
    await flushPromises()
    expect(wrapper.text()).toContain('商品 200.00')
    await wrapper.get('select[aria-label="单位1"]').setValue('61')
    await click(wrapper, '保存订单')
    expect(api.pricePreview).toHaveBeenLastCalledWith(expect.objectContaining({
      lines: [expect.objectContaining({ unitId: '61', unitPrice: undefined })],
    }))
    expect(api.saveDocument).toHaveBeenCalledWith('sales-orders', expect.objectContaining({
      lines: [expect.objectContaining({ unitId: '61', unitPrice: undefined })], version: 5,
    }), '10', undefined)
    wrapper.unmount()
  })

  it('变更审批使用变更版本与正确路径，超时先查原键并以原键重试', async () => {
    vi.mocked(api.document).mockResolvedValue({ ...order, status: 'change_pending', allowedActions: ['change_approve'],
      change: { id: '99', status: 'pending', version: 2 } })
    vi.mocked(api.financeWrite).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce({})
    vi.mocked(api.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '暂无结果'))
    const wrapper = mount(SalesDetailPage, { props: { kind: 'sales-orders' }, global: { stubs } })
    await flushPromises()
    await click(wrapper, '批准变更')
    await click(wrapper, '确认')
    expect(wrapper.text()).toContain('结果确认中')
    const first = vi.mocked(api.financeWrite).mock.calls[0]
    expect(first.slice(0, 2)).toEqual(['sales-orders/changes/99/approve', { version: 2, comment: undefined }])
    await click(wrapper, '查询结果')
    expect(api.commandResult).toHaveBeenCalledWith('sales_order.change_approve', first[2])
    await click(wrapper, '确认')
    expect(vi.mocked(api.financeWrite).mock.calls[1][2]).toBe(first[2])
    wrapper.unmount()
  })

  it('驳回变更要求填写意见，避免提交无效命令', async () => {
    vi.mocked(api.document).mockResolvedValue({ ...order, status: 'change_pending', allowedActions: ['change_reject'],
      change: { id: '99', status: 'pending', version: 0 } })
    const wrapper = mount(SalesDetailPage, { props: { kind: 'sales-orders' }, global: { stubs } })
    await flushPromises()
    await click(wrapper, '驳回变更')
    await click(wrapper, '确认')
    expect(wrapper.text()).toContain('请填写驳回意见')
    expect(api.financeWrite).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('发货列表只显示发货状态并使用 overdue 参数查询签收超期', async () => {
    const wrapper = mount(SalesListPage, { props: { kind: 'shipments' }, global: { stubs } })
    await flushPromises()
    const labels = wrapper.findAll('option').map(item => item.text())
    expect(labels).toContain('已出库待签收')
    expect(labels).not.toContain('待退款')
    expect(labels).not.toContain('已批准')
    await wrapper.get('select').setValue('posted')
    wrapper.findComponent(input).vm.$emit('update:modelValue', true)
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.documents).toHaveBeenLastCalledWith('shipments', expect.objectContaining({ status: 'posted', overdue: true }))
    wrapper.unmount()
  })
})
