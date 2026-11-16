<script setup lang="ts">
import { ref, watch, onScopeDispose } from 'vue'
import { useRoute } from 'vue-router'
import { glRead } from '../../api/gl'
import { messageOf, isCancelled } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { formatDateTime } from '../../utils/format'
const route = useRoute(),
  session = useSessionStore(),
  error = ref(''),
  busy = ref(false)
interface PrintData {
  title: string
  company: string
  currency: string
  printedAt: string
  data: {
    items: Record<string, unknown>[]
    voucherDate?: string
    number?: number
    debitTotal?: string
    creditTotal?: string
    makerId?: string
    approverId?: string
    makerName?: string
    approverName?: string
    periodLabel?: string
    yearNotice?: string
  }
}
const result = ref<PrintData>()
const labels: Record<string, string> = {
  lineNo: '行',
  digest: '摘要',
  subjectCode: '科目编码',
  subjectName: '科目名称',
  direction: '方向',
  amount: '金额',
  quantity: '数量',
  unitPrice: '单价',
  period: '期间',
  openingDebit: '期初借方',
  openingCredit: '期初贷方',
  debit: '本期借方',
  credit: '本期贷方',
  closingDebit: '期末借方',
  closingCredit: '期末贷方',
  openingQuantity: '期初数量',
  quantityDebit: '借方数量',
  quantityCredit: '贷方数量',
  closingQuantity: '期末数量',
  code: '项目编码',
  name: '项目',
  yearAmount: '本年累计',
  voucherDate: '日期',
  word: '凭证字',
  number: '凭证号',
  balance: '余额',
  columns: '分栏金额',
  aux: '辅助核算',
  auxText: '辅助核算',
}
function columns(): string[] {
  const rows = result.value?.data.items || []
  const common = Object.keys(rows[0] || {}).filter((key) => labels[key] && key !== 'columns')
  const extra = [
    ...new Set(
      rows.flatMap((row) =>
        row.columns && typeof row.columns === 'object' ? Object.keys(row.columns) : [],
      ),
    ),
  ].sort()
  return [...common, ...extra.map((code) => 'subject:' + code)]
}
function cell(row: Record<string, unknown>, key: string): unknown {
  return key.startsWith('subject:')
    ? (row.columns as Record<string, string> | undefined)?.[key.slice(8)] || '0.00'
    : row[key]
}
function label(key: string): string {
  return key.startsWith('subject:')
    ? key.slice(8)
    : key === 'yearAmount' && route.query.type === 'balance_sheet'
      ? '年初数'
      : labels[key] || key
}

function display(value: unknown): string {
  return value == null
    ? '—'
    : typeof value === 'object'
      ? JSON.stringify(value)
      : value === 'D'
        ? '借'
        : value === 'C'
          ? '贷'
          : String(value)
}
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  result.value = undefined
  error.value = ''
  busy.value = true
  try {
    const data = await glRead<PrintData>('print/' + String(route.query.type || ''), route.query)
    if (current === revision) result.value = data
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
function print(): void {
  window.print()
}
onScopeDispose(() => {
  revision++
})
watch(
  () => session.companyId,
  () => {
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <main class="financial-paper">
    <div class="print-tools">
      <el-button :disabled="!result" @click="print">打印</el-button
      ><span>打印前请核对公司、期间和凭证状态</span>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="busy">正在生成打印资料…</p>
    <template v-if="result"
      ><header>
        <h1>{{ result.title }}</h1>
        <p>
          {{ result.company }} · {{ result.currency }}
          <span v-if="result.data.periodLabel">· {{ result.data.periodLabel }}</span>
          <span v-if="result.data.voucherDate"
            >· {{ result.data.voucherDate }} · 凭证号 {{ result.data.number }}</span
          >
        </p>
      </header>
      <table>
        <thead>
          <tr>
            <th v-for="key in columns()" :key="key">
              {{ label(key) }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in result.data.items" :key="index">
            <td v-for="key in columns()" :key="key">{{ display(cell(row, key)) }}</td>
          </tr>
        </tbody>
      </table>
      <p v-if="result.data.debitTotal">
        借方合计：{{ result.data.debitTotal }} 贷方合计：{{ result.data.creditTotal }}
      </p>
      <p v-if="result.data.yearNotice">{{ result.data.yearNotice }}</p>
      <footer>
        <span>打印时间：{{ formatDateTime(result.printedAt) }}</span
        ><span v-if="result.data.makerId"
          >制单：{{ result.data.makerName || result.data.makerId }} 复核：{{
            result.data.approverName || '待复核'
          }}</span
        >
      </footer></template
    >
  </main>
</template>
<style scoped>
.financial-paper {
  padding: 28px;
  background: white;
  color: #111;
}
.print-tools {
  display: flex;
  gap: 18px;
  align-items: center;
  margin-bottom: 24px;
}
.financial-paper header {
  text-align: center;
  margin-bottom: 24px;
}
.financial-paper table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.financial-paper th,
.financial-paper td {
  border: 1px solid #aaa;
  padding: 8px;
  text-align: left;
  overflow-wrap: anywhere;
}
.financial-paper footer {
  display: flex;
  justify-content: space-between;
  margin-top: 22px;
  font-size: 12px;
}
@media print {
  .print-tools {
    display: none;
  }
  .financial-paper {
    padding: 0;
  }
  .financial-paper thead {
    display: table-header-group;
  }
  .financial-paper tr {
    break-inside: avoid;
  }
}
</style>
