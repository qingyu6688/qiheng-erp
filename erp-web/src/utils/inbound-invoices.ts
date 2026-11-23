import type {
  InboundAllocation,
  InboundInvoiceDetail,
  InboundInvoiceRow,
  InboundManualInput,
  InboundPayableSource,
} from '../types/inbound-invoices'

const MONEY = /^(?:[0-9]{1,22})(?:\.[0-9]{1,2})?$/
export function invoiceCents(value: string): bigint {
  if (!MONEY.test(value)) throw new Error('金额须为非负数，最多22位整数和两位小数')
  const [whole = '', fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
function fromCents(value: bigint): string {
  return (value / 100n).toString() + '.' + (value % 100n).toString().padStart(2, '0')
}
export function emptyManualInvoice(day: string): InboundManualInput {
  return {
    supplierId: '',
    invoiceNo: '',
    sellerTaxNo: '',
    sellerName: '',
    invoiceDate: day,
    receivedDate: day,
    amount: '',
    taxAmount: '0.00',
    remark: '',
  }
}
export function manualInvoiceDraft(invoice: InboundInvoiceDetail): InboundManualInput {
  return {
    version: invoice.version,
    supplierId: invoice.supplierId ?? '',
    invoiceNo: invoice.invoiceNo,
    sellerTaxNo: invoice.sellerTaxNo,
    sellerName: invoice.sellerName,
    invoiceDate: invoice.invoiceDate,
    receivedDate: invoice.receivedDate ?? invoice.invoiceDate,
    amount: invoice.amount,
    taxAmount: invoice.taxAmount,
    remark: invoice.remark ?? '',
  }
}
export function validateManualInvoice(draft: InboundManualInput, day: string): InboundManualInput {
  const value = {
    ...draft,
    supplierId: draft.supplierId.trim(),
    invoiceNo: draft.invoiceNo.trim(),
    sellerTaxNo: draft.sellerTaxNo.trim().toUpperCase(),
    sellerName: draft.sellerName?.trim(),
    remark: draft.remark?.trim(),
    amount: draft.amount.trim(),
    taxAmount: draft.taxAmount.trim(),
  }
  if (!/^[1-9][0-9]*$/.test(value.supplierId)) throw new Error('请选择供应商')
  if (!/^[A-Za-z0-9_/-]{1,64}$/.test(value.invoiceNo))
    throw new Error('发票号码最多64位，可填写字母、数字、横线、下划线和斜线')
  if (!/^[A-Z0-9]{8,32}$/.test(value.sellerTaxNo)) throw new Error('请输入8至32位销方纳税人识别号')
  const validDate = (date: string): boolean =>
    /^[1-9]\d{3}-\d{2}-\d{2}$/.test(date) &&
    Number.isFinite(new Date(date + 'T00:00:00Z').getTime()) &&
    new Date(date + 'T00:00:00Z').toISOString().slice(0, 10) === date
  if (
    !validDate(value.invoiceDate) ||
    !validDate(value.receivedDate) ||
    value.invoiceDate > day ||
    value.receivedDate > day ||
    value.receivedDate < value.invoiceDate
  )
    throw new Error('开票和接收日期须有效且不能晚于今天，接收日期不能早于开票日期')
  const amount = invoiceCents(value.amount),
    tax = invoiceCents(value.taxAmount)
  if (amount === 0n || tax > amount) throw new Error('价税合计须大于零，税额不能超过价税合计')
  if ((value.sellerName?.length ?? 0) > 100 || (value.remark?.length ?? 0) > 200)
    throw new Error('销方名称最多100字，备注最多200字')
  return value
}
/** 仅给录入提供精确初值，额度、差异与完成状态以服务器预览及保存为准。 */
export function initialInvoiceAllocation(
  invoice: InboundInvoiceRow,
  lines: InboundAllocation[],
  source: InboundPayableSource,
): InboundAllocation {
  const used = (field: 'invoiceUntaxedAmount' | 'invoiceTaxAmount'): bigint =>
    lines.reduce((total, line) => total + invoiceCents(line[field]), 0n)
  const remainingUntaxed =
    invoiceCents(invoice.amount) - invoiceCents(invoice.taxAmount) - used('invoiceUntaxedAmount')
  const remainingTax = invoiceCents(invoice.taxAmount) - used('invoiceTaxAmount')
  const min = (left: bigint, right: bigint): bigint =>
    left < 0n ? 0n : left < right ? left : right
  const untaxed = fromCents(min(remainingUntaxed, invoiceCents(source.availableUntaxedAmount)))
  const tax = fromCents(min(remainingTax, invoiceCents(source.availableTaxAmount)))
  return {
    payableId: source.id,
    invoiceUntaxedAmount: untaxed,
    invoiceTaxAmount: tax,
    payableUntaxedAmount: untaxed,
    payableTaxAmount: tax,
  }
}
export function allocationInput(lines: InboundAllocation[]): InboundAllocation[] {
  if (lines.length > 200) throw new Error('一张票最多核对200笔应付')
  const ids = new Set<string>()
  return lines.map((line) => {
    if (!/^[1-9][0-9]*$/.test(line.payableId) || ids.has(line.payableId))
      throw new Error('核对来源无效或重复')
    ids.add(line.payableId)
    const copy = { ...line }
    for (const field of [
      'invoiceUntaxedAmount',
      'invoiceTaxAmount',
      'payableUntaxedAmount',
      'payableTaxAmount',
    ] as const)
      copy[field] = fromCents(invoiceCents(copy[field]))
    if (
      invoiceCents(copy.invoiceUntaxedAmount) + invoiceCents(copy.invoiceTaxAmount) === 0n ||
      invoiceCents(copy.payableUntaxedAmount) + invoiceCents(copy.payableTaxAmount) === 0n
    )
      throw new Error('每行票面分配额和应付覆盖额都须大于零')
    return copy
  })
}
