import { beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import StockDocumentEditor from '../src/components/stock-operations/StockDocumentEditor.vue'
import StockCommandDialog from '../src/components/stock-operations/StockCommandDialog.vue'
import StockOperationPage from '../src/pages/StockOperationPage.vue'
import * as api from '../src/api/stock-operations'
import * as admin from '../src/api/admin'
import { ApiError } from '../src/api/http'
import type { StockDocument } from '../src/types/stock-operations'

const navigation = vi.hoisted(() => ({ push: vi.fn() }))
const security = vi.hoisted(() => ({ permissions: null as string[] | null }))
const currentRoute = reactive({ params: { id: '10' } })
vi.mock('../src/api/stock-operations')
vi.mock('../src/api/admin')
vi.mock('../src/api/workflow', () => ({ workflowStartOptions: vi.fn(async () => ({ nodes: [] })), workflowReversalStartOptions: vi.fn(async () => ({ nodes: [] })) }))
vi.mock('vue-router', () => ({ useRoute: () => currentRoute, useRouter: () => navigation }))
vi.mock('../src/stores/session', () => ({ useSessionStore: () => ({ companyId: '1', can: (code: string) => security.permissions === null || security.permissions.includes(code) }) }))
const input = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)"/>' })
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue', 'change'], template: '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value);$emit(\'change\', $event.target.value)"><slot/></select>' })
const stubs = {
  DocumentFilesPanel: true,
  ElDialog: { template: '<section><slot/><footer><slot name="footer"/></footer></section>' }, ElInput: input, ElSelect: select,
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{label}}</option>' },
  ElButton: { template: '<button><slot/></button>' }, ElForm: { template: '<form><slot/></form>' }, ElFormItem: { template: '<div><slot/></div>' },
  ElCheckbox: { props: ['modelValue'], emits: ['update:modelValue'], template: '<label><input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)"/><slot/></label>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' }, ElTable: { template: '<div/>' }, ElTableColumn: true,
  ElTag: { template: '<span><slot/></span>' }, RouterLink: { template: '<a><slot/></a>' },
}
const document: StockDocument = { id: '10', documentNo: 'SC-A-202611-000001', businessDate: '2026-11-15', status: 'counting', version: 7, allowedActions: ['record_counts'], warehouseId: '50', lines: [{ id: '30', skuId: '40', skuName: '商品', baseUnitName: '件', bookQty: '2.000000' }] }
type Wrapper = ReturnType<typeof mount>
async function click(wrapper: Wrapper, label: string): Promise<void> { const button = wrapper.findAll('button').find(item => item.text() === label); if (!button) throw new Error('未找到按钮：' + label); await button.trigger('click'); await flushPromises() }
beforeEach(() => {
  vi.resetAllMocks(); security.permissions = null; currentRoute.params.id = '10'
  vi.mocked(api.stockDocument).mockResolvedValue(document)
  vi.mocked(api.stockList).mockResolvedValue({ items: [], nextCursor: null })
  vi.mocked(api.saveStock).mockResolvedValue(document)
  vi.mocked(api.stockCommand).mockResolvedValue(document)
  vi.mocked(api.recordStockCounts).mockResolvedValue(document)
  vi.mocked(api.reversalSources).mockResolvedValue({ items: [{ id: '80', documentNo: 'SH-A-202611-000003', businessDate: '2026-11-14' }], nextCursor: '80' })
  vi.mocked(admin.allRecords).mockImplementation(async path => path === 'warehouses' ? [{ id: '50', name: '调出仓', status: 'active' }, { id: '51', name: '调入仓', status: 'active' }] : [{ id: '40', code: 'SKU', name: '商品', status: 'active' }])
  vi.mocked(admin.record).mockResolvedValue({ id: '40', units: [{ unitId: '60', unitName: '件', rateToBase: '1', isBase: true }] })
})

describe('库存作业业务表单', () => {
  it('调拨保存保留大数量字符串，单位从SKU选择，提交不含客户端成本', async () => {
    const wrapper = mount(StockDocumentEditor, { props: { kind: 'stock-transfers' }, global: { stubs } })
    await flushPromises(); await wrapper.get('#stock-from-warehouse').setValue('50'); await wrapper.get('#stock-to-warehouse').setValue('51')
    await wrapper.get('select[aria-label="库存商品1"]').setValue('40'); await flushPromises()
    await wrapper.get('input[aria-label="调拨数量1"]').setValue('9007199254740993.000001'); await click(wrapper, '保存调拨单')
    expect(api.saveStock).toHaveBeenCalledWith('stock-transfers', expect.objectContaining({ fromWarehouseId: '50', toWarehouseId: '51', lines: [{ skuId: '40', unitId: '60', qty: '9007199254740993.000001' }] }), undefined, expect.any(String))
    expect(JSON.stringify(vi.mocked(api.saveStock).mock.calls[0][1])).not.toContain('cost')
    wrapper.unmount()
  })
  it('盘点创建固定使用水位方式，不把实盘数当作创建时余额', async () => {
    const wrapper = mount(StockDocumentEditor, { props: { kind: 'stock-counts' }, global: { stubs } })
    await flushPromises(); await wrapper.get('#stock-warehouse').setValue('50'); await wrapper.get('select[aria-label="库存商品1"]').setValue('40'); await flushPromises(); await click(wrapper, '保存盘点单')
    expect(api.saveStock).toHaveBeenCalledWith('stock-counts', expect.objectContaining({ warehouseId: '50', countMode: 'watermark', lines: [{ skuId: '40' }] }), undefined, expect.any(String))
    expect(wrapper.text()).toContain('过账只调整盘点差额'); wrapper.unmount()
  })
  it('冲销按有权类型和单号分页选源，不要求填写内部ID', async () => {
    security.permissions = ['shipment:view', 'reversal:create']
    const wrapper = mount(StockDocumentEditor, { props: { kind: 'document-reversals' }, global: { stubs } })
    await flushPromises()
    expect(wrapper.findAll('#stock-source-type option').map(option => option.attributes('value'))).toEqual(['shipment'])
    await wrapper.get('#stock-source').setValue('80'); await wrapper.get('#stock-reason').setValue('仓库录错'); await click(wrapper, '下一页来源')
    expect(api.reversalSources).toHaveBeenLastCalledWith(expect.objectContaining({ sourceType: 'shipment', cursor: '80', limit: 20 }))
    await click(wrapper, '保存冲销单')
    expect(api.saveStock).toHaveBeenCalledWith('document-reversals', expect.objectContaining({ sourceType: 'shipment', sourceId: '80', reason: '仓库录错' }), undefined, expect.any(String))
    expect(wrapper.find('#stock-source').element.tagName).toBe('SELECT'); wrapper.unmount()
  })
  it('实盘允许0并带本单版本，PUT超时要求刷新且不冒充幂等命令', async () => {
    vi.mocked(api.recordStockCounts).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    const wrapper = mount(StockCommandDialog, { props: { kind: 'stock-counts', document, action: 'record_counts' }, global: { stubs } })
    await wrapper.get('input[aria-label="实盘数量1"]').setValue('0'); await click(wrapper, '保存实盘')
    expect(api.recordStockCounts).toHaveBeenCalledWith('10', { version: 7, lines: [{ lineId: '30', countedQty: '0', inputUnitCost: undefined }] })
    expect(wrapper.text()).toContain('关闭并刷新单据'); expect(api.stockResult).not.toHaveBeenCalled()
    await click(wrapper, '保存实盘'); expect(api.recordStockCounts).toHaveBeenCalledTimes(1); wrapper.unmount()
  })
  it('全部调入使用剩余基础数量原字符串，末批标记明确发送', async () => {
    const transfer: StockDocument = { ...document, status: 'shipped', fromWarehouseId: '50', toWarehouseId: '51', lines: [{ id: '30', skuId: '40', skuName: '商品', remainingQty: '9007199254740993.000001' }] }
    const wrapper = mount(StockCommandDialog, { props: { kind: 'stock-transfers', document: transfer, action: 'receive' }, global: { stubs } })
    await click(wrapper, '全部调入'); await click(wrapper, '确认登记调入')
    expect(api.stockCommand).toHaveBeenCalledWith('stock-transfers', '10', 'receive', expect.objectContaining({ version: 7, finalReceipt: true, lines: [{ lineId: '30', qty: '9007199254740993.000001' }] }), expect.any(String))
    wrapper.unmount()
  })
  it('调入超时后按原operation和键查询，未查到仍以原内容重试', async () => {
    vi.mocked(api.stockCommand).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce(document)
    vi.mocked(api.stockResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '尚无结果'))
    const wrapper = mount(StockCommandDialog, { props: { kind: 'stock-transfers', document: { ...document, status: 'shipped' }, action: 'receive' }, global: { stubs } })
    await wrapper.get('input[aria-label="调入实收1"]').setValue('1.000001'); await click(wrapper, '确认登记调入')
    const first = vi.mocked(api.stockCommand).mock.calls[0]
    await click(wrapper, '查询结果'); expect(api.stockResult).toHaveBeenCalledWith('stock_transfer.receive', first[4])
    await click(wrapper, '确认登记调入'); expect(vi.mocked(api.stockCommand).mock.calls[1]).toEqual(first); wrapper.unmount()
  })
  it('切换资源或详情ID后清空旧单据并重新加载，按服务器动作显示按钮', async () => {
    vi.mocked(api.stockDocument).mockImplementation(async (_kind, id) => ({ ...document, id, documentNo: '单据' + id, allowedActions: [] }))
    const wrapper = mount(StockOperationPage, { props: { kind: 'stock-counts' }, global: { stubs: { ...stubs, StockDocumentEditor: true, StockCommandDialog: true } } })
    await flushPromises(); expect(wrapper.get('h1').text()).toBe('单据10'); expect(wrapper.findAll('button').map(button => button.text())).not.toContain('过账')
    await wrapper.setProps({ kind: 'stock-transfers' }); await flushPromises(); expect(api.stockDocument).toHaveBeenLastCalledWith('stock-transfers', '10')
    currentRoute.params.id = '20'; await flushPromises(); expect(api.stockDocument).toHaveBeenLastCalledWith('stock-transfers', '20'); expect(wrapper.get('h1').text()).toBe('单据20'); wrapper.unmount()
  })
  it('创建超时恢复后仍按原键和原请求重试', async () => {
    vi.mocked(api.saveStock).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时')).mockResolvedValueOnce(document)
    vi.mocked(api.stockResult).mockRejectedValueOnce(new ApiError(404, 'NOT_FOUND', '尚无结果'))
    const wrapper = mount(StockDocumentEditor, { props: { kind: 'stock-counts' }, global: { stubs } })
    await flushPromises(); await wrapper.get('#stock-warehouse').setValue('50'); await wrapper.get('select[aria-label="库存商品1"]').setValue('40'); await flushPromises(); await click(wrapper, '保存盘点单')
    const first = vi.mocked(api.saveStock).mock.calls[0]
    await click(wrapper, '查询结果'); expect(api.stockResult).toHaveBeenCalledWith('stock_count.create', first[3])
    await click(wrapper, '保存盘点单'); expect(vi.mocked(api.saveStock).mock.calls[1]).toEqual(first); wrapper.unmount()
  })
  it('冲销创建按文本展示依赖类型、单号与关系，恶意标记不作为HTML执行', async () => {
    const number = '<img src=x onerror="alert(1)">', relation = '<b>先冲销原退货单</b>'
    vi.mocked(api.saveStock).mockRejectedValueOnce(new ApiError(422, 'DEPENDENCY_EXISTS', '存在下游单据，请先处理', [{ documentType: 'sales_return', documentId: '91', documentNo: number, relation }]))
    const wrapper = mount(StockDocumentEditor, { props: { kind: 'document-reversals' }, global: { stubs } })
    await flushPromises(); await wrapper.get('#stock-source').setValue('80'); await wrapper.get('#stock-reason').setValue('修正仓库'); await click(wrapper, '保存冲销单')
    const details = wrapper.get('[aria-label="冲销依赖单据"]')
    expect(details.text()).toContain('销售退货单'); expect(details.text()).toContain(number); expect(details.text()).toContain(relation)
    expect(details.find('img').exists()).toBe(false); expect(details.find('b').exists()).toBe(false)
    expect(details.find('a').attributes('to')).toBe('/sales-returns/91'); wrapper.unmount()
  })
  it('撤销受阻冲销要求填写原因，超时后按原键查询撤销结果', async () => {
    vi.mocked(api.stockCommand).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    const wrapper = mount(StockCommandDialog, {
      props: { kind: 'document-reversals', document: { ...document, status: 'approved' }, action: 'cancel' },
      global: { stubs },
    })
    const confirm = wrapper.findAll('button').find((button) => button.text() === '确认撤销冲销')!
    expect(confirm.attributes('disabled')).toBeDefined()
    await wrapper.get('#stock-command-reason').setValue('旧版本已上架')
    expect(confirm.attributes('disabled')).toBeUndefined()
    await click(wrapper, '确认撤销冲销')
    const request = vi.mocked(api.stockCommand).mock.calls[0]
    expect(request).toEqual([
      'document-reversals', '10', 'cancel', expect.objectContaining({ version: 7, reason: '旧版本已上架' }),
      expect.any(String),
    ])
    await click(wrapper, '查询结果')
    expect(api.stockResult).toHaveBeenCalledWith('reversal.cancel', request[4])
    wrapper.unmount()
  })
  it('冲销过账展示无单号核销记录并链接工作台，其他错误清空旧依赖', async () => {
    vi.mocked(api.stockCommand).mockRejectedValueOnce(new ApiError(422, 'DEPENDENCY_EXISTS', '存在下游单据，请先处理', [{ documentType: 'settlement_allocation', documentId: '999999', documentNo: null, relation: '本单未清项已核销' }])).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '网络超时'))
    const wrapper = mount(StockCommandDialog, { props: { kind: 'document-reversals', document: { ...document, status: 'approved' }, action: 'post' }, global: { stubs } })
    await click(wrapper, '确认过账'); const details = wrapper.get('[aria-label="冲销依赖单据"]')
    expect(details.text()).toContain('核销记录'); expect(details.text()).toContain('本单未清项已核销'); expect(details.text()).not.toContain('999999')
    expect(details.find('a').attributes('to')).toBe('/settlements')
    await click(wrapper, '确认过账'); expect(wrapper.find('[aria-label="冲销依赖单据"]').exists()).toBe(false); expect(wrapper.text()).toContain('结果确认中'); wrapper.unmount()
  })
})
