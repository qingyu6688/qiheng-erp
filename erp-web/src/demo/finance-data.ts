import type { GlLedger, GlLine, GlSubject, GlVoucher } from '../types/gl'
import { documents, money, products } from './data'

export const demoPeriods = Array.from({ length: 12 }, (_, index) => {
  const period = index === 0 ? '2025-12' : '2026-' + String(index).padStart(2, '0')
  return { id: period, period, status: index === 11 ? 'open' : 'closed' }
}).reverse()

export const demoSubjects: GlSubject[] = [
  ['1002', '银行存款', 'asset', 'D'], ['1122', '应收账款', 'asset', 'D'],
  ['1405', '库存商品', 'asset', 'D'], ['2202', '应付账款', 'liability', 'C'],
  ['2221', '应交税费', 'liability', 'C'], ['4001', '实收资本', 'equity', 'C'],
  ['6001', '主营业务收入', 'income', 'C'], ['6401', '主营业务成本', 'expense', 'D'],
].map(([code, name, category, direction]) => ({
  id: code!, code: code!, name: name!, category: category!, direction: direction as 'D' | 'C',
  isCash: false, isBank: code === '1002', isLeaf: true, auxTypes: '[]', quantityUnit: '',
  profitRole: '', status: 'active', version: 1,
}))

function line(subjectId: string, direction: 'D' | 'C', amount: string, digest: string): GlLine {
  return { subjectId, direction, amount, digest, quantity: '0', aux: {} }
}
function voucher(index: number, date: string, lines: GlLine[], source: GlVoucher['source']): GlVoucher {
  const total = (direction: string): string => money(lines.filter((row) => row.direction === direction).reduce((sum, row) => sum + Number(row.amount), 0))
  return { id: String(80001 + index), wordId: '1', number: index + 1, periodId: date.slice(0, 7),
    voucherDate: date, attachmentCount: 0, source, status: 'posted', makerId: '301', approverId: '302',
    debitTotal: total('D'), creditTotal: total('C'), version: 1, lines,
    remark: '虚构记账示例；采购含不可抵扣税额计入库存成本，销售按样例税额拆分。无期末结转。',
  }
}

const openingInventory = products.reduce((sum, product) => sum + 100 * product.cost, 0)
export const demoVouchers: GlVoucher[] = [voucher(0, '2025-12-01', [
  line('1002', 'D', '100000.00', '演示期初银行存款'),
  line('1405', 'D', money(openingInventory), '演示期初商品库存'),
  line('4001', 'C', money(100000 + openingInventory), '演示期初投入'),
], 'opening')]

for (const doc of documents.filter((row) => ['receipts', 'payments', 'shipments', 'goods-receipts'].includes(row.kind)).sort((a, b) => a.businessDate.localeCompare(b.businessDate) || a.id.localeCompare(b.id))) {
  const digest = doc.documentNo + ' · ' + doc.partnerName
  let lines: GlLine[]
  if (doc.kind === 'receipts') lines = [line('1002', 'D', doc.totalAmount, digest), line('1122', 'C', doc.totalAmount, digest)]
  else if (doc.kind === 'payments') lines = [line('2202', 'D', doc.totalAmount, digest), line('1002', 'C', doc.totalAmount, digest)]
  else if (doc.kind === 'goods-receipts') lines = [line('1405', 'D', doc.totalAmount, digest), line('2202', 'C', doc.totalAmount, digest)]
  else {
    const cost = money(doc.quantity * products.find((product) => product.id === doc.productId)!.cost)
    lines = [line('1122', 'D', doc.totalAmount, digest), line('6001', 'C', String(doc.untaxedAmount), digest),
      line('2221', 'C', String(doc.taxAmount), digest), line('6401', 'D', cost, digest), line('1405', 'C', cost, digest)]
  }
  demoVouchers.push(voucher(demoVouchers.length, doc.businessDate, lines, 'business'))
}

/** 账簿直接汇总同一组凭证；整数分运算避免跨期累计误差。 */
export function demoLedger(type: string, params: Record<string, unknown>): GlLedger | undefined {
  if (!['balance', 'general', 'detail'].includes(type)) return undefined
  const from = String(params.periodFrom || '2026-11'), to = String(params.periodTo || from)
  const selectedSubjects = demoSubjects.filter((subject) => !params.subjectId || subject.id === params.subjectId)
  const items: Record<string, unknown>[] = []
  for (const subject of selectedSubjects) {
    const lines = demoVouchers.flatMap((entry) => entry.lines.filter((row) => row.subjectId === subject.id).map((row) => ({ ...row, entry })))
    const cents = (amount: string): number => Math.round(Number(amount) * 100)
    let balance = lines.filter((row) => row.entry.periodId < from).reduce((sum, row) => sum + (row.direction === 'D' ? 1 : -1) * cents(row.amount), 0)
    const current = lines.filter((row) => row.entry.periodId >= from && row.entry.periodId <= to)
    const base = { subjectCode: subject.code, subjectName: subject.name }
    if (type === 'detail') {
      for (const row of current) {
        balance += (row.direction === 'D' ? 1 : -1) * cents(row.amount)
        items.push({ ...base, voucherId: row.entry.id, voucherDate: row.entry.voucherDate, word: '记', number: row.entry.number,
          digest: row.digest, debit: row.direction === 'D' ? row.amount : '0.00', credit: row.direction === 'C' ? row.amount : '0.00', balance: money(balance / 100) })
      }
    } else {
      const periods = type === 'balance' ? [from + ' ~ ' + to] : [...new Set(current.map((row) => row.entry.periodId))]
      for (const period of periods) {
        const rows = type === 'balance' ? current : current.filter((row) => row.entry.periodId === period)
        const debit = rows.filter((row) => row.direction === 'D').reduce((sum, row) => sum + cents(row.amount), 0)
        const credit = rows.filter((row) => row.direction === 'C').reduce((sum, row) => sum + cents(row.amount), 0)
        const closing = balance + debit - credit
        items.push({ ...base, period, openingDebit: money(Math.max(0, balance) / 100), openingCredit: money(Math.max(0, -balance) / 100),
          debit: money(debit / 100), credit: money(credit / 100), closingDebit: money(Math.max(0, closing) / 100), closingCredit: money(Math.max(0, -closing) / 100) })
        balance = closing
      }
    }
  }
  const offset = Math.max(0, Number(params.cursor) || 0), limit = Math.max(1, Math.min(200, Number(params.limit) || 200))
  return { type, items: items.slice(offset, offset + limit), rowCount: items.length, nextCursor: offset + limit < items.length ? String(offset + limit) : undefined }
}
