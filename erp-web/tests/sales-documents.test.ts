import { beforeEach, expect, it, vi } from 'vitest'
import { computed, defineComponent, inject, provide, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import DocumentEditor from '../src/components/sales/DocumentEditor.vue'
import CommandDialog from '../src/components/sales/CommandDialog.vue'
import SettlementPage from '../src/pages/SettlementPage.vue'
import type { Ref } from 'vue'
import * as api from '../src/api/sales'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { SalesDocument } from '../src/types/sales'

const blocked = vi.hoisted(() => new Set<string>())
vi.mock('../src/api/sales')
vi.mock('../src/api/admin')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ can: (permission: string) => !blocked.has(permission) }) }))
const input = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  template: '<input :value="modelValue" @input="$emit(\'update:modelValue\',$event.target.value)"/>',
})
const select = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue', 'change'],
  template: '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value);$emit(\'change\',$event.target.value)"><slot/></select>',
})
const stubs = {
  ElDialog: { template: '<section><slot/><footer><slot name="footer"/></footer></section>' },
  ElInput: input, ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElButton: { template: '<button><slot/></button>' },
  ElForm: { template: '<div><slot/></div>' }, ElFormItem: { template: '<div><slot/></div>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
}
async function click(wrapper: ReturnType<typeof mount>, label: string): Promise<void> {
  const button = wrapper.findAll('button').find(item => item.text() === label)
  if (!button) throw new Error('找不到按钮：' + label)
  await button.trigger('click')
  await flushPromises()
}
const receipt: SalesDocument = { id: '10', status: 'draft', version: 2, allowedActions: [], receiptType: 'customer', partnerId: '20', amount: '10', cashAccountId: '50' }
beforeEach(() => {
  vi.resetAllMocks()
  blocked.clear()
  vi.mocked(admin.allRecords).mockImplementation(async path => path === 'partners'
    ? [{ id: '20', name: '客户甲', status: 'active' }, { id: '21', name: '客户乙', status: 'active' }]
    : path === 'warehouses' ? [{ id: '30', name: '销售仓', status: 'active' }]
      : path === 'cash-accounts' ? [{ id: '50', name: '银行', status: 'active' }]
        : [])
  vi.mocked(api.candidates).mockResolvedValue({ items: [{ id: '40', orderNo: 'SO-1', skuName: '商品', unitId: '60', unitName: '箱', shippableQty: '20' }], nextCursor: null })
  vi.mocked(api.saveDocument).mockResolvedValue(receipt)
})

it('发货来源保留字符串ID和单位，切换客户清除旧来源', async () => {
  const wrapper = mount(DocumentEditor, { props: { kind: 'shipments' }, global: { stubs } })
  await flushPromises()
  await wrapper.get('#doc-customer').setValue('20')
  await wrapper.get('#doc-warehouse').setValue('30')
  await flushPromises()
  await wrapper.get('select[aria-label="来源行1"]').setValue('40')
  await wrapper.get('input[aria-label="数量1"]').setValue('2')
  await click(wrapper, '保存单据')
  expect(api.saveDocument).toHaveBeenCalledWith('shipments', expect.objectContaining({
    customerId: '20', warehouseId: '30', lines: [{ orderLineId: '40', unitId: '60', qty: '2' }],
  }), undefined, expect.any(String))
  await wrapper.get('#doc-customer').setValue('21')
  await flushPromises()
  await click(wrapper, '保存单据')
  expect(api.saveDocument).toHaveBeenLastCalledWith('shipments', expect.objectContaining({
    customerId: '21', lines: [{ orderLineId: '', unitId: '', qty: '1' }],
  }), undefined, expect.any(String))
  wrapper.unmount()
})

it('收款无订单和收入目录权限时不请求无权目录', async () => {
  blocked.add('sales_order:view')
  blocked.add('income_expense_item:view')
  const wrapper = mount(DocumentEditor, { props: { kind: 'receipts' }, global: { stubs } })
  await flushPromises()
  expect(admin.allRecords).not.toHaveBeenCalledWith('sales-orders', true)
  expect(admin.allRecords).not.toHaveBeenCalledWith('income-expense-items')
  expect(wrapper.find('#doc-order').exists()).toBe(false)
  wrapper.unmount()
})

it('核销切换供应商侧后只查询AP未清项，并重新加载原客户侧', async () => {
  const wrapper = mount(SettlementPage, { global: { stubs: { ...stubs, ElTable: true, ElTableColumn: true, ElRadio: true, ElRadioGroup: true } } })
  await flushPromises()
  await wrapper.get('#settlement-customer').setValue('20')
  await flushPromises()
  await wrapper.get('select[aria-label="往来侧"]').setValue('ap')
  await flushPromises()
  expect(api.candidates).toHaveBeenCalledWith('open-items', { partnerId: '20', side: 'ap', openOnly: true })
  expect(wrapper.text()).toContain('待付应付')
  await wrapper.get('select[aria-label="往来侧"]').setValue('ar')
  await flushPromises()
  expect(api.candidates).toHaveBeenLastCalledWith('settlement-allocations', { partnerId: '20' })
  expect(api.candidates).toHaveBeenCalledWith('open-items', { partnerId: '20', side: 'ar', openOnly: true })
  wrapper.unmount()
})

it('新建收款超时后查原操作结果，未找到时沿用原键重试', async () => {
  vi.mocked(api.saveDocument).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce(receipt)
  vi.mocked(api.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '暂无结果'))
  const wrapper = mount(DocumentEditor, { props: { kind: 'receipts' }, global: { stubs } })
  await flushPromises()
  await click(wrapper, '保存单据')
  const originalKey = vi.mocked(api.saveDocument).mock.calls[0][3]
  await click(wrapper, '查询结果')
  expect(api.commandResult).toHaveBeenCalledWith('receipt.create', originalKey)
  await click(wrapper, '保存单据')
  expect(vi.mocked(api.saveDocument).mock.calls[1][3]).toBe(originalKey)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  wrapper.unmount()
})

it('新建收款带出默认账户，已有草稿保留原账户', async () => {
  vi.mocked(admin.allRecords).mockImplementation(async path => path === 'cash-accounts'
    ? [{ id: '50', name: '原银行', status: 'active' }, { id: '51', name: '默认银行', status: 'active', defaultForReceipt: true }]
    : [])
  const fresh = mount(DocumentEditor, { props: { kind: 'receipts' }, global: { stubs } })
  await flushPromises()
  await click(fresh, '保存单据')
  expect(api.saveDocument).toHaveBeenLastCalledWith('receipts', expect.objectContaining({ cashAccountId: '51' }), undefined, expect.any(String))
  fresh.unmount()
  const draft = mount(DocumentEditor, { props: { kind: 'receipts', original: receipt }, global: { stubs } })
  await flushPromises()
  await click(draft, '保存单据')
  expect(api.saveDocument).toHaveBeenLastCalledWith('receipts', expect.objectContaining({ cashAccountId: '50' }), '10', undefined)
  draft.unmount()
})

it('编辑超时保留版本并提示刷新核对，避免误查新建结果', async () => {
  vi.mocked(api.saveDocument).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
  const wrapper = mount(DocumentEditor, { props: { kind: 'receipts', original: receipt }, global: { stubs } })
  await flushPromises()
  await click(wrapper, '保存单据')
  expect(wrapper.text()).toContain('保存结果尚未确认，请关闭并刷新单据后核对')
  expect(wrapper.text()).not.toContain('查询结果')
  expect(api.commandResult).not.toHaveBeenCalled()
  expect(api.saveDocument).toHaveBeenCalledWith('receipts', expect.objectContaining({ version: 2 }), '10', undefined)
  wrapper.unmount()
})

it('签收提交全部行的基础数量，超时查询保留原请求键', async () => {
  const shipment: SalesDocument = { id: '11', documentNo: 'SH-1', status: 'posted', version: 3, allowedActions: ['sign'], lines: [
    { id: '41', skuName: '甲', qty: '2', baseQty: '20' }, { id: '42', skuName: '乙', qty: '1', baseQty: '1' },
  ] }
  vi.mocked(api.salesCommand).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
  vi.mocked(api.commandResult).mockResolvedValue({ status: 'succeeded', response: shipment })
  const wrapper = mount(CommandDialog, { props: { kind: 'shipments', document: shipment, action: 'sign' }, global: { stubs } })
  await click(wrapper, '确认登记签收')
  expect(api.salesCommand).toHaveBeenCalledWith('shipments', '11', 'sign', expect.objectContaining({
    version: 3, lines: [{ lineId: '41', signedQty: '20' }, { lineId: '42', signedQty: '1' }],
  }), expect.any(String))
  const originalKey = vi.mocked(api.salesCommand).mock.calls[0][4]
  await click(wrapper, '查询结果')
  expect(api.commandResult).toHaveBeenCalledWith('shipment.sign', originalKey)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  wrapper.unmount()
})

it('删除草稿超时后通过详情404确认结果', async () => {
  vi.mocked(api.salesCommand).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
  vi.mocked(api.document).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '草稿不存在'))
  const wrapper = mount(CommandDialog, { props: { kind: 'sales-orders', document: { ...receipt, allowedActions: ['delete'] }, action: 'delete' }, global: { stubs } })
  await click(wrapper, '确认删除草稿')
  await click(wrapper, '查询结果')
  expect(api.document).toHaveBeenCalledWith('sales-orders', '10')
  expect(api.commandResult).not.toHaveBeenCalled()
  expect(wrapper.emitted('saved')).toHaveLength(1)
  wrapper.unmount()
})

it('撤销核销超时后先查原结果，未找到时保留原键重试', async () => {
  const table = defineComponent({
    props: { data: Array },
    setup(props) { provide('tableRows', computed(() => props.data)); return {} },
    template: '<section><slot/></section>',
  })
  const column = defineComponent({
    setup() { return { rows: inject<Ref<Record<string, unknown>[]>>('tableRows', ref([])) } },
    template: '<div><slot v-for="row in rows" :row="row"/></div>',
  })
  vi.mocked(api.candidates).mockImplementation(async path => ({
    items: path === 'settlement-allocations' ? [{ id: '70', version: 1, status: 'active', allowedActions: ['revoke'] }] : [], nextCursor: null,
  }))
  vi.mocked(api.financeWrite).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce({})
  vi.mocked(api.commandResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '暂无结果'))
  const wrapper = mount(SettlementPage, { global: { stubs: { ...stubs, ElTable: table, ElTableColumn: column, ElRadio: true, ElRadioGroup: true } } })
  await flushPromises()
  await wrapper.get('#settlement-customer').setValue('20')
  await flushPromises()
  await click(wrapper, '撤销核销')
  await wrapper.get('input[maxlength="200"]').setValue('录入更正')
  await click(wrapper, '确认撤销')
  const originalKey = vi.mocked(api.financeWrite).mock.calls[0][2]
  await click(wrapper, '查询原撤销结果')
  expect(api.commandResult).toHaveBeenCalledWith('settlement.revoke', originalKey)
  await click(wrapper, '确认撤销')
  expect(api.financeWrite).toHaveBeenLastCalledWith('settlement-allocations/70/revoke', { version: 1, reason: '录入更正' }, originalKey)
  wrapper.unmount()
})
