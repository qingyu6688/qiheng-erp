<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { allRecords } from '../api/admin'
import { checkPeriod } from '../api/period'
import type { PeriodCheck } from '../api/period'
import type { DataRow } from '../types/admin'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import { resourceSpecs } from './resources'
import PeriodActions from '../components/PeriodActions.vue'
import RecordEditor from '../components/RecordEditor.vue'
import AiDocumentButton from '../components/AiDocumentButton.vue'
import { documentLink } from '../types/reporting'
const session = useSessionStore(),
  route = useRoute(),
  router = useRouter(),
  rows = ref<DataRow[]>([]),
  selected = ref<DataRow>(),
  report = ref<PeriodCheck>(),
  loading = ref(false),
  checking = ref(false),
  error = ref(''),
  creating = ref(false)
const labels: Record<string, string> = {
  unposted_documents: '未过账单据',
  pending_money: '待确认资金',
  inventory_reconciliation: '库存对账',
  money_reconciliation: '资金往来对账',
  cost_residue: '成本尾差',
}
let revision = 0,
  checkRevision = 0
function selectPeriod(row: DataRow | undefined): void {
  if (row && row.id !== selected.value?.id)
    void router.replace({ query: { ...route.query, id: row.id } })
}
async function inspect(row: DataRow): Promise<void> {
  const current = ++checkRevision
  selected.value = row
  report.value = undefined
  error.value = ''
  checking.value = false
  if (!session.can('period:close') || row.status !== 'open') return
  checking.value = true
  try {
    const value = await checkPeriod(row.id)
    if (current === checkRevision) report.value = value
  } catch (cause: unknown) {
    if (current === checkRevision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === checkRevision) checking.value = false
  }
}
async function inspectSelected(): Promise<void> {
  const id = route.query.id
  const row =
    id == null
      ? (rows.value.find((item) => item.id === selected.value?.id) ?? rows.value[0])
      : rows.value.find((item) => item.id === id)
  if (row) {
    await inspect(row)
    return
  }
  ++checkRevision
  selected.value = undefined
  report.value = undefined
  checking.value = false
  error.value = id == null ? '' : '当前公司没有可查看的指定期间。'
}
async function load(): Promise<void> {
  const current = ++revision
  ++checkRevision
  loading.value = true
  error.value = ''
  report.value = undefined
  try {
    const periods = await allRecords('accounting-periods', true, session.companyId ?? undefined)
    if (current !== revision) return
    rows.value = periods
    await inspectSelected()
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function saved(): Promise<void> {
  creating.value = false
  await load()
}
watch(
  () => session.companyId,
  () => {
    selected.value = undefined
    rows.value = []
    void load()
  },
  { immediate: true },
)
watch(
  () => route.query.id,
  () => {
    if (rows.value.length) void inspectSelected()
  },
)
onScopeDispose(() => {
  ++revision
  ++checkRevision
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>会计期间与关账</h1>
      <p>关账前检查全部未完成业务；关闭时服务器会复核最新数据。</p>
    </div>
    <el-button v-if="session.can('period:open')" type="primary" @click="creating = true"
      >开启会计期间</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <div class="period-layout">
    <section class="panel table-panel">
      <el-table
        :data="rows"
        row-key="id"
        :current-row-key="selected?.id"
        highlight-current-row
        @current-change="selectPeriod"
        ><el-table-column prop="period" label="期间" /><el-table-column
          prop="startDate"
          label="开始日期"
          min-width="110"
        /><el-table-column prop="endDate" label="结束日期" min-width="110" /><el-table-column
          label="状态"
          ><template #default="{ row }"
            ><el-tag :type="row.status === 'open' ? 'primary' : 'info'">{{
              row.status === 'open' ? '开放' : '已关闭'
            }}</el-tag></template
          ></el-table-column
        ><el-table-column label="操作" min-width="220"
          ><template #default="{ row }"
            ><PeriodActions :period="row" @changed="load" /></template></el-table-column
        ><template #empty>{{ loading ? '正在加载…' : '暂无期间，请先开启' }}</template></el-table
      >
    </section>
    <section class="panel period-check">
      <div class="page-heading">
        <h2>{{ selected?.period ?? '选择期间' }} · 关账检查</h2>
        <div v-if="selected && session.can('period:close')">
          <AiDocumentButton
            v-if="session.company?.allWarehouses"
            :document="{
              documentType: 'accounting_period',
              documentId: selected.id,
              label: String(selected.period),
            }"
          />
          <el-button
            v-if="selected.status === 'open'"
            :loading="checking"
            @click="inspect(selected)"
            >重新检查</el-button
          >
        </div>
      </div>
      <p v-if="checking" role="status">正在核对库存、资金和未完成单据…</p>
      <p v-if="selected?.status === 'closed'">
        期间已关闭。需要更正时，可按顺序重新打开最近关闭的期间并填写原因。
      </p>
      <p v-if="selected?.status === 'open' && !session.can('period:close')">
        当前账户可以查看期间，没有执行关账检查的权限。
      </p>
      <template v-if="report"
        ><el-alert
          :type="report.passed ? 'success' : 'warning'"
          :title="report.passed ? '全部检查通过，可以发起关账' : '有未完成事项，请处理后重新检查'"
          :closable="false"
        />
        <div v-for="item in report.items" :key="item.check" class="check-item">
          <h3>
            <el-tag :type="item.passed ? 'success' : 'danger'">{{
              item.passed ? '通过' : '未通过'
            }}</el-tag>
            {{ labels[item.check] ?? item.check }} <small>{{ item.count }}</small>
          </h3>
          <p>{{ item.message }}</p>
          <p
            v-for="document in item.documents ?? []"
            :key="document.documentType + document.documentId"
          >
            <router-link
              v-if="documentLink(document.documentType, document.documentId)"
              class="text-link"
              :to="documentLink(document.documentType, document.documentId)!"
              >{{ document.documentNo }}</router-link><span v-else>{{ document.documentNo }}</span>
          </p>
        </div></template
      >
    </section>
  </div>
  <RecordEditor
    v-if="creating"
    :spec="resourceSpecs['accounting-periods']!"
    @close="creating = false"
    @saved="saved"
  />
</template>
<style scoped>
.period-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 1fr);
  gap: 20px;
  align-items: start;
}
.period-check {
  padding: 20px;
}
.period-check h2 {
  font-size: 16px;
  margin: 0;
}
.check-item {
  padding: 12px 0;
  border-bottom: 1px solid #e3e6eb;
}
.check-item h3 {
  font-size: 14px;
}
.check-item small {
  float: right;
  color: #6b7380;
}
@media (max-width: 1200px) {
  .period-layout {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
