<script setup lang="ts">
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs'
import { ElTooltip } from 'element-plus/es/components/tooltip/index.mjs'
import { ref, watch, onScopeDispose } from 'vue'
import GlOutputActions from '../../components/GlOutputActions.vue'
import { glReport, glSubjects, glRead } from '../../api/gl'
import { records } from '../../api/admin'
import { messageOf, isCancelled } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { GlReport, GlSubject, GlReportRow } from '../../types/gl'
import type { DataRow } from '../../types/admin'
const session = useSessionStore(),
  periods = ref<DataRow[]>([]),
  subjects = ref<GlSubject[]>([]),
  periodId = ref(''),
  type = ref('balance_sheet'),
  result = ref<GlReport>(),
  error = ref(''),
  busy = ref(false)
const detail = ref<GlReportRow>(),
  metrics = ref<{
    items: {
      code: string
      name: string
      value: string | null
      unit: string
      formula: string
      notice: string
    }[]
    basis: string
  }>()
const types: Record<string, string> = {
  balance_sheet: '资产负债表',
  income_statement: '利润表',
  cash_flow: '现金流量表',
  indicators: '财务指标',
}
let revision = 0
async function load(): Promise<void> {
  const current = ++revision
  result.value = undefined
  metrics.value = undefined
  detail.value = undefined
  error.value = ''
  busy.value = true
  try {
    if (type.value === 'indicators') {
      const data = await glRead<{
        items: {
          code: string
          name: string
          value: string | null
          unit: string
          formula: string
          notice: string
        }[]
        basis: string
      }>('indicators', { periodId: periodId.value })
      if (current === revision) metrics.value = data
      return
    }
    const data = await glReport(type.value, periodId.value)
    if (current === revision) result.value = data
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function init(): Promise<void> {
  const current = ++revision
  result.value = undefined
  metrics.value = undefined
  detail.value = undefined
  periodId.value = ''
  try {
    const [p, s] = await Promise.all([records('accounting-periods', true), glSubjects()])
    if (current !== revision) return
    periods.value = p.items
    subjects.value = s
    periodId.value = p.items[0]?.id || ''
    if (periodId.value) await load()
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
      <h1>财务报表</h1>
      <p>人民币 · 通用样例口径。损益发生排除结转凭证，结转后仍可核对本期利润。</p>
    </div>
    <GlOutputActions
      v-if="type !== 'indicators'"
      :type="type"
      :query="{ periodFrom: periodId, periodTo: periodId }"
      :print-id="periodId"
    />
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section class="panel report-panel">
    <div class="report-filters">
      <el-select v-model="type" style="width: 180px"
        ><el-option
          v-for="(label, key) in types"
          :key="key"
          :label="label"
          :value="key" /></el-select
      ><el-select v-model="periodId" style="width: 150px"
        ><el-option
          v-for="p in periods"
          :key="p.id"
          :label="String(p.period)"
          :value="p.id" /></el-select
      ><el-button type="primary" :disabled="!periodId" :loading="busy" @click="load"
        >查看报表</el-button
      >
    </div>
    <template v-if="result"
      ><h2>{{ result.name }}</h2>
      <el-table
        :data="result.rows"
        :row-class-name="({ row }: { row: { bold: boolean } }) => (row.bold ? 'report-total' : '')"
        ><el-table-column label="项目" min-width="260"
          ><template #default="{ row }"
            ><span :style="{ paddingLeft: row.indent * 16 + 'px' }">{{ row.name }}</span></template
          ></el-table-column
        ><el-table-column label="本期 / 期末数" align="right" width="180"
          ><template #default="{ row }"
            ><el-button link @click="detail = row">{{ row.amount }}</el-button></template
          ></el-table-column
        ><el-table-column
          :label="type === 'balance_sheet' ? '年初数' : '本年累计'"
          align="right"
          width="190"
          ><template #default="{ row }">{{ row.yearAmount ?? '—' }}</template></el-table-column
        ><el-table-column label="明细" min-width="200"
          ><template #default="{ row }"
            ><router-link
              v-for="code in row.subjectCodes"
              :key="code"
              class="text-link report-drill"
              :to="{
                path: '/gl/ledgers',
                query: { periodId, subjectId: subjects.find((s) => s.code === code)?.id },
              }"
              >{{ code }}</router-link
            ><el-tooltip :content="row.formula"
              ><span class="formula-hint">公式</span></el-tooltip
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="report-checks">
        <el-alert
          v-for="check in result.checks"
          :key="check.name"
          :title="`${check.name}：${check.left} / ${check.right}，差额 ${check.difference}`"
          :type="check.passed ? 'success' : 'warning'"
          :closable="false"
        />
      </div>
      <el-alert v-if="result.yearNotice" :title="result.yearNotice" type="info" :closable="false" />
      <p class="hash">口径确认：{{ result.configHash }}</p></template
    ><template v-else-if="metrics"
      ><el-table :data="metrics.items"
        ><el-table-column prop="name" label="指标" /><el-table-column label="本期值"
          ><template #default="{ row }">{{
            row.value === null ? '—' : row.value + ' ' + row.unit
          }}</template></el-table-column
        ><el-table-column prop="formula" label="计算口径" min-width="280" /><el-table-column
          prop="notice"
          label="说明"
      /></el-table>
      <p>{{ metrics.basis }}</p></template
    ><el-empty v-else-if="!busy" description="请选择期间查看报表" />
  </section>
  <el-dialog :model-value="!!detail" title="报表取数构成" @close="detail = undefined"
    ><template v-if="detail"
      ><h3>{{ detail.name }} · {{ detail.amount }}</h3>
      <p>{{ detail.formula }}</p>
      <div v-for="code in detail.references || []" :key="code">
        <el-button link @click="detail = result?.rows.find((row) => row.code === code)"
          >{{ result?.rows.find((row) => row.code === code)?.name }}：{{
            result?.rows.find((row) => row.code === code)?.amount
          }}</el-button
        >
      </div>
      <p v-for="code in detail.subjectCodes" :key="code">
        <router-link
          class="text-link"
          :to="{
            path: '/gl/ledgers',
            query: { periodId, subjectId: subjects.find((s) => s.code === code)?.id },
          }"
          >{{ code }} {{ subjects.find((s) => s.code === code)?.name }} · 查看科目账簿</router-link
        >
      </p>
      <router-link
        v-if="!detail.subjectCodes.length && !detail.references?.length"
        class="text-link"
        :to="{ path: '/gl/ledgers', query: { periodId } }"
        >查看本期科目余额及辅助明细</router-link
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.report-panel {
  padding: 24px;
}
.report-filters {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
}
.report-panel h2 {
  text-align: center;
  margin: 24px;
}
.report-checks {
  display: grid;
  gap: 8px;
  margin-top: 20px;
}
.hash {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.report-drill {
  display: inline-block;
  white-space: nowrap;
  margin-right: 8px;
}
.formula-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
:deep(.report-total) {
  font-weight: 600;
  background: var(--el-fill-color-light);
}
</style>
