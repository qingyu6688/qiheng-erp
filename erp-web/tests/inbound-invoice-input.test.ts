import { describe, expect, it } from 'vitest'
import {
  allocationInput,
  emptyManualInvoice,
  initialInvoiceAllocation,
  invoiceCents,
  manualInvoiceDraft,
  validateManualInvoice,
} from '../src/utils/inbound-invoices'
import { invoice, match, source } from './fixtures/inbound-invoices'

describe('进项发票录入', () => {
  it('保留长金额和长标识，税号按票面规范化，不修改原草稿', () => {
    const draft = {
      ...emptyManualInvoice('2026-11-23'),
      supplierId: '9007199254740993',
      invoiceNo: ' PAPER/01 ',
      sellerTaxNo: ' testseller1234 ',
      amount: ' 1234567890123456789012.34 ',
      taxAmount: '0',
    }
    const value = validateManualInvoice(draft, '2026-11-23')
    expect(value).toMatchObject({
      supplierId: '9007199254740993',
      invoiceNo: 'PAPER/01',
      sellerTaxNo: 'TESTSELLER1234',
      amount: '1234567890123456789012.34',
    })
    expect(draft.invoiceNo).toBe(' PAPER/01 ')
    expect(invoiceCents(value.amount)).toBe(123456789012345678901234n)
    expect(manualInvoiceDraft(invoice({ version: 9 }))).toMatchObject({
      version: 9,
      supplierId: '11',
      taxAmount: '13.00',
    })
  })
  it('拒绝无效金额、无效日历日期、接收日提前及未来日期', () => {
    for (const value of ['-1', '1e3', '1.001', 'NaN', '', '10000000000000000000000'])
      expect(() => invoiceCents(value)).toThrow()
    const valid = manualInvoiceDraft(invoice())
    for (const input of [
      { amount: '0' },
      { taxAmount: '113.01' },
      { invoiceDate: '2026-02-31' },
      { invoiceDate: '0001-01-01' },
      { receivedDate: '2026-11-22' },
      { receivedDate: '2026-11-24' },
    ])
      expect(() => validateManualInvoice({ ...valid, ...input }, '2026-11-23')).toThrow()
  })
  it('分批到票的初值分别受未税和税额约束，尾差覆盖由用户另行选择', () => {
    expect(
      initialInvoiceAllocation(invoice({ amount: '56.50', taxAmount: '6.50' }), [], source()),
    ).toEqual({
      payableId: '41',
      invoiceUntaxedAmount: '50.00',
      invoiceTaxAmount: '6.50',
      payableUntaxedAmount: '50.00',
      payableTaxAmount: '6.50',
    })
    expect(initialInvoiceAllocation(invoice({ amount: '112.99' }), [], source())).toMatchObject({
      invoiceUntaxedAmount: '99.99',
      payableUntaxedAmount: '99.99',
    })
    expect(
      initialInvoiceAllocation(
        invoice(),
        [match({ invoiceUntaxedAmount: '30.00', invoiceTaxAmount: '3.90' })],
        source({ id: '42' }),
      ),
    ).toMatchObject({ invoiceUntaxedAmount: '70.00', invoiceTaxAmount: '9.10' })
    expect(
      initialInvoiceAllocation(
        invoice(),
        [],
        source({ availableUntaxedAmount: '5.00', availableTaxAmount: '0.00' }),
      ),
    ).toMatchObject({ invoiceUntaxedAmount: '5.00', invoiceTaxAmount: '0.00' })
  })
  it('分配载荷独立快照，拒绝重复、零额、超精度和超过200个来源', () => {
    const draft = [match()]
    const normalized = allocationInput(draft)
    draft[0]!.invoiceUntaxedAmount = '1.00'
    expect(normalized[0]!.invoiceUntaxedAmount).toBe('100.00')
    expect(() => allocationInput([match(), match()])).toThrow('重复')
    expect(() =>
      allocationInput([match({ invoiceUntaxedAmount: '0', invoiceTaxAmount: '0' })]),
    ).toThrow('大于零')
    expect(() => allocationInput([match({ payableTaxAmount: '0.001' })])).toThrow('两位小数')
    expect(() =>
      allocationInput(
        Array.from({ length: 201 }, (_, index) => match({ payableId: String(index + 1) })),
      ),
    ).toThrow('200')
  })
})
