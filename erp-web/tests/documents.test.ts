import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import DocumentFilesPanel from '../src/components/DocumentFilesPanel.vue'
import PrintPage from '../src/pages/PrintPage.vue'
import * as documents from '../src/api/documents'
import * as downloads from '../src/api/files'
import { ApiError } from '../src/api/http'
import { documentPermission, documentType, printMeta, printTables, printTotals, printValue } from '../src/types/documents'
import type { Attachment, PrintData } from '../src/types/documents'

vi.mock('../src/api/documents')
vi.mock('../src/api/files')
vi.mock('../src/stores/session', () => ({ useSessionStore: () => session }))
vi.mock('vue-router', () => ({ useRoute: () => route }))
const session = reactive({ companyId: '1', can: (() => true) as (permission: string) => boolean })
const route = reactive({ params: { type: 'stock_adjustment', id: '100' } })
const stubs = {
  ElButton: { props: ['nativeType', 'loading', 'disabled'], template: '<button :type="nativeType ?? \'button\'" :disabled="disabled || loading"><slot/></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{title}}</p>' },
  ElPopconfirm: { emits: ['confirm'], template: '<div><slot name="reference"/><button @click="$emit(\'confirm\')">确认删除</button></div>' },
  RouterLink: { props: ['to'], template: '<a :href="to"><slot/></a>' },
}
type Wrapper = ReturnType<typeof mount>
const mounted: Wrapper[] = []
const attachment: Attachment = { id: '10', fileName: '附件.pdf', contentType: 'application/pdf', sizeBytes: 1024, createdAt: '2026-11-15T00:00:00Z' }
function printData(name = '验收公司'): PrintData { return { documentType: 'stock_adjustment', company: { name }, document: { documentNo: 'AD-A-202611-000001', status: 'posted', createdByName: '制单人员', approvedByName: '审核人员', lines: [{ skuName: '商品', unitName: '件', qty: '2.000000', baseQty: '2.000000', inputTotalCost: '100.00', unitCost: '50.00000000', costAmount: '100.00' }] }, printedAt: '2026-11-15T00:00:00Z' } }
function page(component: typeof DocumentFilesPanel | typeof PrintPage = DocumentFilesPanel): Wrapper { const wrapper = mount(component, { props: component === DocumentFilesPanel ? { ownerType: 'stock_adjustment', ownerId: '100' } : {}, global: { stubs } }); mounted.push(wrapper); return wrapper }
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } { let resolve!: (value: T) => void; const promise = new Promise<T>(done => { resolve = done }); return { promise, resolve } }
async function click(wrapper: Wrapper, label: string): Promise<void> { const button = wrapper.findAll('button').find(item => item.text() === label); if (!button) throw new Error('找不到按钮：' + label); await button.trigger('click'); await flushPromises() }
async function selectFile(wrapper: Wrapper, file = new File(['%PDF-1.7\n%%EOF'], '附件.pdf', { type: 'application/pdf' })): Promise<File> {
  const input = wrapper.get('input[type=file]'); Object.defineProperty(input.element, 'files', { value: [file], configurable: true }); await input.trigger('change'); return file
}

beforeEach(() => {
  vi.resetAllMocks(); session.companyId = '1'; session.can = () => true; route.params = { type: 'stock_adjustment', id: '100' }
  vi.mocked(documents.attachments).mockResolvedValue([]); vi.mocked(documents.uploadAttachment).mockResolvedValue(attachment)
  vi.mocked(documents.printData).mockResolvedValue(printData())
})
afterEach(() => { for (const wrapper of mounted.splice(0)) wrapper.unmount() })

describe('打印数据字段', () => {
  it('订单保留原始金额、数量、优惠与含税费用，不经过浮点数', () => {
    const large = '9999999999999999999999.99'
    const tables = printTables('sales_order', { lines: [{ sku: { code: '0001', name: '商品' }, unit: { name: '箱' }, warehouse: { name: '仓库甲' }, qty: '0.000001', unitPrice: '123.12345678', lineDiscountAmount: '1.00', orderDiscountAlloc: '2.00', receivableNet: large, taxRate: '0.130000' }], fees: [{ feeType: 'freight', bearer: 'buyer', amount: '10.00', totalAmount: '11.30', taxRate: '0.130000', untaxedAmount: '10.00', taxAmount: '1.30' }] }, false)
    expect(tables[0].rows[0]).toMatchObject({ product: '0001\n商品\n单位：箱\n仓库：仓库甲', qty: '0.000001', unitPrice: '123.12345678', lineAmount: large })
    expect(tables[1].rows[0]).toMatchObject({ feeType: '运费', bearer: '买方承担', amount: '10.00', totalAmount: '11.30' })
    expect(printTotals('sales_order', { totalAmount: large, orderDiscountAmount: '2.00' }, false)).toContainEqual({ label: '合计金额', value: large })
  })
  it('发货、收货和退货打印实际确认金额，不猜测不存在的单价', () => {
    for (const [type, field] of [['shipment', 'receivableAmount'], ['goods_receipt', 'payableAmount'], ['sales_return', 'creditAmount'], ['purchase_return', 'debitAmount']]) {
      const table = printTables(type, { lines: [{ skuName: '商品', qty: '1.000000', [field]: '12.30' }] }, false)[0]
      expect(table.rows[0].lineAmount).toBe('12.30'); expect(table.columns.some(column => column.key === 'unitPrice')).toBe(false)
    }
  })
  it('成本权限同时控制明细与合计，库存成本汇总按十进制字符串计算', () => {
    const document = { lines: [{ inputTotalCost: '9999999999999999999999.99', unitCost: '3.12345678' }, { inputTotalCost: '0.01' }] }
    expect(printTotals('stock_adjustment', document, true)).toEqual([{ label: '成本合计', value: '10000000000000000000000.00' }])
    expect(printTotals('stock_adjustment', document, false)).toEqual([])
    const table = printTables('stock_adjustment', document, false)[0]
    expect(table.columns.some(column => /cost/i.test(column.key))).toBe(false); expect(JSON.stringify(table)).not.toContain('9999999999999999999999.99')
  })
  it('盘点、调拨、开票、审批记录和元数据使用各自业务字段', () => {
    expect(printTables('stock_count', { lines: [{ bookQty: '5.000000', countedQty: '4.000000', diffQty: '-1.000000' }] }, false)[0].rows[0].diffQty).toBe('-1.000000')
    expect(printTables('stock_transfer', { lines: [{ receivedBaseQty: '3.000000', diffStatus: 'resolved' }] }, false)[0].rows[0].diffStatus).toBe('已处理')
    const invoice = printTables('invoice_registration', { lines: [{ receivableId: '9007199254740993123', amount: '10.00' }], approvalRecords: [{ action: 'approve', operatorName: '审核人' }] }, false)
    expect(invoice[0].rows[0].sourceNo).toBe('—'); expect(JSON.stringify(invoice)).not.toContain('9007199254740993123')
    expect(printTables('invoice_registration', { lines: [{ amount: '10.00' }], sourceDocuments: [{ documentNo: 'SH-A-202611-000001' }] }, false)[0].rows[0].sourceNo).toBe('SH-A-202611-000001')
    expect(printMeta({ adjustType: 'gain', priceTaxInclusive: false, reason: '验收', cashAccountName: '资金账户' })).toContainEqual({ label: '计价方式', value: '未税单价' })
    expect(printMeta({ relatedOrderId: '9007199254740993123', sourceDocuments: [{ documentType: 'purchase_order', documentNo: 'PO-A-202611-000001' }] })).toContainEqual({ label: '来源单据', value: 'PO-A-202611-000001' })
    expect(documentPermission('document_reversal')).toBe('reversal'); expect(documentType('reversal')).toBe('document_reversal'); expect(printValue({ secret: 1 })).toBe('—')
  })
})

describe('单据附件与打印生命周期', () => {
  it('上传超时保留文件并复用幂等键，下载使用鉴权接口', async () => {
    vi.mocked(documents.uploadAttachment).mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', '请求超时')).mockResolvedValueOnce(attachment)
    const wrapper = page(); await flushPromises(); const file = await selectFile(wrapper)
    await wrapper.get('form').trigger('submit'); await flushPromises(); expect(wrapper.text()).toContain('请求超时')
    vi.mocked(documents.attachments).mockResolvedValue([attachment]); await wrapper.get('form').trigger('submit'); await flushPromises()
    const [first, second] = vi.mocked(documents.uploadAttachment).mock.calls
    expect(first[1]).toBe(second[1]); expect(first[0].get('file')).toBe(file); expect(first[0].get('ownerId')).toBe('100')
    await click(wrapper, '下载'); expect(downloads.downloadFile).toHaveBeenCalledWith('/attachments/10/download', '附件.pdf', true, expect.any(AbortSignal))
  })
  it('切换公司取消旧列表，旧结果不能重新出现，卸载也取消请求', async () => {
    const old = deferred<Attachment[]>(), latest = deferred<Attachment[]>()
    vi.mocked(documents.attachments).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page(); const firstSignal = vi.mocked(documents.attachments).mock.calls[0][2]
    session.companyId = '2'; await flushPromises(); latest.resolve([{ ...attachment, fileName: '新公司.pdf' }]); await flushPromises()
    old.resolve([{ ...attachment, fileName: '旧公司.pdf' }]); await flushPromises()
    expect(wrapper.text()).toContain('新公司.pdf'); expect(wrapper.text()).not.toContain('旧公司.pdf'); expect(firstSignal?.aborted).toBe(true)
    const signal = vi.mocked(documents.attachments).mock.calls[1][2]; wrapper.unmount(); expect(signal?.aborted).toBe(true)
  })
  it('旧上传完成不能清除新单据的忙碌状态或文件选择', async () => {
    const old = deferred<Attachment>(), latest = deferred<Attachment>()
    vi.mocked(documents.uploadAttachment).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page(); await flushPromises(); await selectFile(wrapper); await wrapper.get('form').trigger('submit')
    await wrapper.setProps({ ownerId: '200' }); await flushPromises(); await selectFile(wrapper, new File(['新文件'], '新文件.csv'))
    await wrapper.get('form').trigger('submit'); old.resolve(attachment); await flushPromises()
    expect(wrapper.findAll('button').find(button => button.text() === '上传附件')?.attributes('disabled')).toBeDefined()
    latest.resolve({ ...attachment, id: '20' }); await flushPromises()
    expect(vi.mocked(documents.uploadAttachment).mock.calls[0][2]?.aborted).toBe(true)
  })
  it('缺少修改权限时隐藏上传删除，缺少打印权限时不读取打印数据', async () => {
    session.can = permission => permission.endsWith(':view'); vi.mocked(documents.attachments).mockResolvedValue([attachment])
    const wrapper = page(); await flushPromises(); expect(wrapper.find('form').exists()).toBe(false); expect(wrapper.text()).not.toContain('确认删除'); expect(wrapper.text()).not.toContain('打印预览')
    const printing = page(PrintPage); await flushPromises(); expect(printing.text()).toContain('没有查看或打印'); expect(documents.printData).not.toHaveBeenCalled()
  })
  it('打印页面忽略旧单据结果，使用当前成本权限并在卸载时中止', async () => {
    const old = deferred<PrintData>(), latest = deferred<PrintData>()
    vi.mocked(documents.printData).mockReturnValueOnce(old.promise).mockReturnValueOnce(latest.promise)
    const wrapper = page(PrintPage); route.params.id = '200'; await flushPromises()
    latest.resolve(printData('当前公司')); await flushPromises(); old.resolve(printData('旧公司')); await flushPromises()
    expect(wrapper.get('h1').text()).toBe('当前公司'); expect(wrapper.text()).toContain('100.00'); expect(wrapper.text()).toContain('制单人员'); expect(wrapper.text()).toContain('审核人员')
    session.can = permission => permission !== 'cost:view'; await flushPromises(); expect(wrapper.text()).not.toContain('100.00'); expect(wrapper.text()).not.toContain('成本合计')
    expect(vi.mocked(documents.printData).mock.calls[0][2]?.aborted).toBe(true)
    const signal = vi.mocked(documents.printData).mock.calls[1][2]; wrapper.unmount(); expect(signal?.aborted).toBe(true)
  })
})
