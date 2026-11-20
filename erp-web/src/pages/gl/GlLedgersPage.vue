<script setup lang="ts">
import { ref, watch, onScopeDispose, inject } from 'vue'
import { useRoute } from 'vue-router'
import { glLedger, glSubjects, glRead } from '../../api/gl'
import { records } from '../../api/admin'
import { useSessionStore } from '../../stores/session'
import { messageOf, isCancelled } from '../../api/http'
import GlAuxFields from '../../components/GlAuxFields.vue'
import GlOutputActions from '../../components/GlOutputActions.vue'
import { ledgerLabels } from '../../types/gl'
import type { GlLedger, GlSubject, GlQuery, GlAux } from '../../types/gl'
import type { DataRow } from '../../types/admin'
const readOnlyPreview = inject('erp.readOnlyPreview', false)
const availableLedgerLabels = readOnlyPreview ? { balance: '科目余额表', general: '总分类账', detail: '明细分类账' } : ledgerLabels
const session = useSessionStore(),
  route = useRoute(),
  periods = ref<DataRow[]>([]),
  subjects = ref<GlSubject[]>([]),
  result = ref<GlLedger>(),
  type = ref('balance'),
  error = ref(''),
  busy = ref(false)
const query = ref<GlQuery>({ periodFrom: '', periodTo: '', limit: 200 })
const labels: Record<string, string> = {
  subjectCode: '科目编码',
  subjectName: '科目名称',
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
  voucherDate: '日期',
  word: '凭证字',
  number: '凭证号',
  digest: '摘要',
  balance: '余额',
  quantity: '数量',
  amount: '金额',
  aux: '辅助维度',
  auxText: '辅助核算',
  columns: '分栏金额',
}
const aux = ref({
  customerId: '0',
  supplierId: '0',
  deptId: '0',
  employeeId: '0',
  projectId: '0',
  skuId: '0',
  customId: '0',
})
const choices = ref<Record<string, { id: string; name: string; code?: string }[]>>({})
const auxTypes: Record<string, string> = {
  customer: '客户',
  supplier: '供应商',
  dept: '部门',
  employee: '员工',
  project: '项目',
  sku: '存货',
  custom: '自定义',
}
function columns(): string[] {
  if (!result.value?.items.length) return []
  const keys = Object.keys(result.value.items[0]!)
  return keys.filter(
    (k) =>
      k !== 'aux' &&
      (type.value !== 'multi_column' || k !== 'columns') &&
      labels[k] &&
      (type.value === 'quantity' || !k.toLowerCase().includes('quantity')),
  )
}
watch(
  [() => query.value.auxType, aux],
  () => {
    const type = query.value.auxType
    const id = type ? aux.value[(type + 'Id') as keyof GlAux] : undefined
    query.value.auxId = id && id !== '0' ? id : undefined
  },
  { deep: true },
)
function search(): void {
  query.value.cursor = undefined
  void load()
}
function next(): void {
  query.value.cursor = result.value?.nextCursor || undefined
  void load()
}
function multiColumns(): string[] {
  return [
    ...new Set(
      (result.value?.items || []).flatMap((row) =>
        Object.keys((row.columns || {}) as Record<string, string>),
      ),
    ),
  ].sort()
}
function value(v: unknown): string {
  return v == null
    ? '—'
    : typeof v === 'object'
      ? Object.entries(v)
          .filter(([, x]) => x && x !== '0')
          .map(([key, x]) => `${key}: ${x}`)
          .join(' / ')
      : String(v)
}
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  error.value = ''
  busy.value = true
  result.value = undefined
  try {
    const r = await glLedger(type.value, query.value)
    if (current === revision) result.value = r
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function init(): Promise<void> {
  const current = ++revision
  result.value = undefined
  aux.value = {
    customerId: '0',
    supplierId: '0',
    deptId: '0',
    employeeId: '0',
    projectId: '0',
    skuId: '0',
    customId: '0',
  }
  query.value = { periodFrom: '', periodTo: '', limit: 200 }
  try {
    const [p, s, c] = await Promise.all([
      records('accounting-periods', true),
      glSubjects(),
      glRead<Record<string, { id: string; name: string; code?: string }[]>>('choices'),
    ])
    if (current !== revision) return
    periods.value = p.items
    subjects.value = s
    choices.value = c
    query.value.periodFrom = String(
      route.query.periodId || p.items.find((p) => p.status === 'open')?.id || p.items[0]?.id || '',
    )
    query.value.periodTo = query.value.periodFrom
    query.value.subjectId = route.query.subjectId ? String(route.query.subjectId) : undefined
    if (query.value.periodFrom) await load()
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  }
}
watch(
  () => session.companyId,
  () => {
    void init()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>财务账簿</h1>
      <p>{{ readOnlyPreview ? '样例凭证自动汇总，提供科目余额表、总分类账与明细分类账；未演示期末结转。' : '按记账事实查询科目与辅助明细，包含红字和反结账流水。' }}</p>
    </div>
    <GlOutputActions :type="type" :query="query" />
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel table-panel">
    <el-form class="ledger-filters" inline
      ><el-form-item label="账簿"
        ><el-select v-model="type" style="width: 150px"
          ><el-option
            v-for="(label, key) in availableLedgerLabels"
            :key="key"
            :value="key"
            :label="label" /></el-select></el-form-item
      ><el-form-item label="起止期间"
        ><el-select v-model="query.periodFrom" style="width: 125px"
          ><el-option
            v-for="p in periods"
            :key="p.id"
            :value="p.id"
            :label="String(p.period)" /></el-select
        ><span>至</span
        ><el-select v-model="query.periodTo" style="width: 125px"
          ><el-option
            v-for="p in periods"
            :key="p.id"
            :value="p.id"
            :label="String(p.period)" /></el-select></el-form-item
      ><el-form-item label="科目"
        ><el-select v-model="query.subjectId" clearable filterable style="width: 230px"
          ><el-option
            v-for="s in subjects"
            :key="s.id"
            :value="s.id"
            :label="s.code + ' ' + s.name" /></el-select></el-form-item
      ><el-form-item v-if="!readOnlyPreview" label="辅助维度"
        ><el-select
          v-model="query.auxType"
          clearable
          style="width: 130px"
          @change="query.auxId = undefined"
          ><el-option
            v-for="(name, key) in auxTypes"
            :key="key"
            :value="key"
            :label="name" /></el-select></el-form-item
      ><el-form-item v-if="query.auxType" label="辅助项目"
        ><GlAuxFields v-model="aux" :required="[query.auxType]" :options="choices" /></el-form-item
      ><el-form-item
        ><el-button
          type="primary"
          :loading="busy"
          :disabled="!query.periodFrom || !query.periodTo"
          @click="search"
          >查询</el-button
        ></el-form-item
      ></el-form
    ><el-table :data="result?.items || []"
      ><el-table-column
        v-for="column in columns()"
        :key="column"
        :label="labels[column]"
        min-width="140"
        ><template #default="{ row }"
          ><router-link
            v-if="column === 'number'"
            class="text-link"
            :to="'/gl/vouchers/' + row.voucherId"
            >{{ row[column] }}</router-link
          ><span v-else>{{ value(row[column]) }}</span></template
        ></el-table-column
      ><el-table-column
        v-for="code in type === 'multi_column' ? multiColumns() : []"
        :key="code"
        :label="code + ' ' + (subjects.find((s) => s.code === code)?.name || '')"
        min-width="170"
        align="right"
        ><template #default="{ row }">{{
          row.columns?.[code] || '0.00'
        }}</template></el-table-column
      ><template #empty>{{
        busy ? '正在读取账簿…' : '所选期间暂无符合条件的记账资料'
      }}</template></el-table
    >
    <p class="ledger-summary" v-if="result">
      共 {{ result.rowCount }} 行
      <span v-if="result.opening">· 期初 {{ result.opening }} · 期末 {{ result.closing }}</span
      ><el-button v-if="result.nextCursor" link @click="next">下一页</el-button>
    </p>
  </section>
</template>
<style scoped>
.ledger-filters {
  padding: 20px 20px 0;
}
.ledger-filters span {
  margin: 0 8px;
}
.ledger-summary {
  padding: 16px;
  color: var(--el-text-color-secondary);
}
</style>
