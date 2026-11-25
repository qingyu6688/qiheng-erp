import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createInvoice,
  downloadInvoice,
  invoiceAction,
  invoiceProvider,
  invoiceRequests,
  linkPayable,
  saveInvoiceProvider,
  saveSkuTaxCategory,
} from '../src/api/e-invoice'
import type { InboundInvoice, InvoiceDetail } from '../src/api/e-invoice'
import { downloadFile } from '../src/api/files'
import { request } from '../src/api/http'
import {
  allocateInboundInvoice,
  inboundHistory,
  inboundInvoice,
  inboundInvoices,
  inboundSources,
  inboundSuppliers,
  previewInboundInvoice,
  saveInboundInvoice,
  voidInboundInvoice,
} from '../src/api/e-invoice'

vi.mock('../src/api/http', () => ({ request: vi.fn() }))
vi.mock('../src/api/files', () => ({ downloadFile: vi.fn() }))

describe('数电发票接口', () => {
  it('多对多进项入口保留字符串金额、版本、幂等键和取消信号', async () => {
    const signal = new AbortController().signal
    const input = {
      version: 7,
      lines: [
        {
          payableId: '9007199254740995',
          invoiceUntaxedAmount: '12345678901234567890.01',
          invoiceTaxAmount: '0.00',
          payableUntaxedAmount: '12345678901234567890.02',
          payableTaxAmount: '0.00',
        },
      ],
    }
    await allocateInboundInvoice('9007199254740993', input, 'original-key', signal)
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'POST',
        url: '/e-invoices/inbound/9007199254740993/allocations',
        data: input,
        headers: { 'Idempotency-Key': 'original-key' },
        signal,
      },
      true,
    )
    await previewInboundInvoice('21', input, signal)
    expect(request).toHaveBeenLastCalledWith(
      { method: 'POST', url: '/e-invoices/inbound/21/preview', data: input, headers: {}, signal },
      true,
    )
    await voidInboundInvoice('21', { version: 7, reason: '原票录入错误' }, 'void-key', signal)
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        url: '/e-invoices/inbound/21/void',
        headers: { 'Idempotency-Key': 'void-key' },
        signal,
      }),
      true,
    )
    const manual = {
      supplierId: '11',
      invoiceNo: 'P-1',
      sellerTaxNo: 'TEST12345678',
      invoiceDate: '2026-11-23',
      receivedDate: '2026-11-23',
      amount: '1.00',
      taxAmount: '0.00',
    }
    await saveInboundInvoice(manual, 'new-key', undefined, signal)
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({ method: 'POST', url: '/e-invoices/inbound', data: manual, signal }),
      true,
    )
    await saveInboundInvoice({ ...manual, version: 2 }, 'edit-key', '21', signal)
    expect(request).toHaveBeenLastCalledWith(
      expect.objectContaining({
        method: 'PUT',
        url: '/e-invoices/inbound/21',
        data: { ...manual, version: 2 },
      }),
      true,
    )
  })
  it('列表、供应商、来源和历史分页使用当前公司作用域', async () => {
    await inboundInvoices('21', { source: 'manual', matchStatus: 'difference', keyword: '供应商' })
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'GET',
        url: '/e-invoices/inbound',
        params: { cursor: '21', source: 'manual', matchStatus: 'difference', keyword: '供应商' },
      },
      true,
    )
    await inboundSuppliers('TEST', '11')
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'GET',
        url: '/e-invoices/inbound/suppliers',
        params: { keyword: 'TEST', cursor: '11' },
      },
      true,
    )
    await inboundSources('21', '41')
    expect(request).toHaveBeenLastCalledWith(
      { method: 'GET', url: '/e-invoices/inbound/21/sources', params: { cursor: '41' } },
      true,
    )
    await inboundHistory('21', '71')
    expect(request).toHaveBeenLastCalledWith(
      { method: 'GET', url: '/e-invoices/inbound/21/history', params: { cursor: '71' } },
      true,
    )
    await inboundInvoice('21')
    expect(request).toHaveBeenLastCalledWith(
      { method: 'GET', url: '/e-invoices/inbound/21', params: undefined },
      true,
    )
  })
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('按当前公司查询，并保留大整数标识及幂等键', async () => {
    const id = '9007199254740999'
    await invoiceRequests(id)
    expect(request).toHaveBeenLastCalledWith(
      { method: 'GET', url: '/e-invoices/requests', params: { cursor: id } },
      true,
    )
    await createInvoice({ partnerId: id, invoiceKind: 'special', receivableIds: [id] }, 'draft-key')
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'POST',
        url: '/e-invoices/requests',
        data: { partnerId: id, invoiceKind: 'special', receivableIds: [id] },
        headers: { 'Idempotency-Key': 'draft-key' },
      },
      true,
    )
    await invoiceAction(id, 'red-issue', 3, 'stable-red-key', '验收红字原因')
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'POST',
        url: `/e-invoices/requests/${id}/red-issue`,
        data: { version: 3, reason: '验收红字原因' },
        headers: { 'Idempotency-Key': 'stable-red-key' },
      },
      true,
    )
  })

  it('按原票和红字选择对应文件，使用已校验当前公司的下载入口', async () => {
    const invoice: InvoiceDetail = {
      id: '1',
      invoiceNo: 'A01',
      redInvoiceNo: 'R01',
      requestedBy: '2',
      buyerName: '验收客户',
      buyerTaxNo: '',
      invoiceKind: 'normal',
      status: 'red_issued',
      amount: '113.00',
      taxAmount: '13.00',
      error: null,
      version: 4,
      files: [],
      lines: [],
    }
    await downloadInvoice(invoice, { variant: 'red', fileFormat: 'ofd' })
    expect(downloadFile).toHaveBeenLastCalledWith('/e-invoices/requests/1/files/red/ofd', 'R01.ofd')
  })

  it('解除进项关联只提交空应付标识，税码和服务商修改带原版本', async () => {
    const invoice: InboundInvoice = {
      id: '5',
      invoiceNo: 'I05',
      sellerName: '测试供应商',
      sellerTaxNo: 'X012345678',
      invoiceDate: '2026-11-01',
      amount: '100.00',
      taxAmount: '0.00',
      status: 'normal',
      checked: false,
      payableId: '10',
      version: 7,
    }
    await linkPayable(invoice, null, 'unlink-key')
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'POST',
        url: '/e-invoices/inbound/5/match',
        data: { version: 7, payableId: null },
        headers: { 'Idempotency-Key': 'unlink-key' },
      },
      true,
    )
    await saveSkuTaxCategory(
      { id: '4', code: 'TEST', name: '测试商品', taxCategoryCode: '123456', version: 3 },
      null,
    )
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'PUT',
        url: '/e-invoices/skus/4/tax-category',
        data: { version: 3, taxCategoryCode: null },
        headers: {},
      },
      true,
    )
    await invoiceProvider()
    expect(request).toHaveBeenLastCalledWith(
      { method: 'GET', url: '/e-invoices/provider', params: undefined },
      true,
    )
    await saveInvoiceProvider({ taxNo: 'X012345678', enabled: true, version: 2 })
    expect(request).toHaveBeenLastCalledWith(
      {
        method: 'PUT',
        url: '/e-invoices/provider',
        data: { taxNo: 'X012345678', enabled: true, version: 2 },
        headers: {},
      },
      true,
    )
  })
})
