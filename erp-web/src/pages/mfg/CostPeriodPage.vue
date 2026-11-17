<script setup lang="ts">
import { ElStep, ElSteps } from 'element-plus/es/components/steps/index.mjs'
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import {
  manufacturingCommand,
  manufacturingDetail,
  manufacturingList,
} from '../../api/manufacturing'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import { manufacturingStates } from '../../types/manufacturing'
import type { CostAllocation, CostExpense, ManufacturingPeriod } from '../../types/manufacturing'
import { today } from '../../types/sales'
import { useWmsCommands } from '../wms/useWmsCommands'
import ManufacturingCostTable from './ManufacturingCostTable.vue'
import { useManufacturingList } from './useManufacturing'

const session = useSessionStore()
const periodId = ref('')
const detail = ref<ManufacturingPeriod>(),
  step = ref('0'),
  opening = ref(false),
  detailError = ref('')
const confirmation = ref<'post' | 'reopen'>()
const steps = ['归集费用', '选择分摊依据', '计算成本', '查看差异', '过账']
const expenses = ref<CostExpense[]>([]),
  orders = ref<CostAllocation[]>([])
const method = ref<'materials' | 'equivalent'>('materials')
const { busy, error: commandError, notice, run } = useWmsCommands()
const {
  items,
  cursor,
  loading,
  error: listError,
  load,
} = useManufacturingList((cursor) =>
  manufacturingList<ManufacturingPeriod>('cost-periods', { cursor }),
)
const posted = computed(() => detail.value?.status === 'posted')
const calculated = computed(() => detail.value?.status === 'calculated')
const endReached = computed(() => Boolean(detail.value?.endDate && today() >= detail.value.endDate))
const manage = computed(
  () =>
    session.can('mfg_cost:manage') &&
    !posted.value &&
    endReached.value &&
    detail.value?.accountingStatus !== 'closed',
)
const canPost = computed(
  () =>
    session.can('mfg_cost:post') && calculated.value && detail.value?.accountingStatus !== 'closed',
)
const canReopen = computed(
  () =>
    session.can('mfg_cost:post') &&
    posted.value &&
    detail.value?.accountingStatus === 'open' &&
    (detail.value.reopenCount ?? 0) > 0,
)
const canAllocate = computed(() => Boolean(detail.value && detail.value.version > 0))
const maxStep = computed(() =>
  posted.value || calculated.value
    ? 4
    : detail.value?.hasAllocation
      ? 2
      : canAllocate.value
        ? 1
        : 0,
)
const requiresHours = computed(() =>
  expenses.value.some((item) => item.allocationBasis === 'hours'),
)
let revision = 0
onScopeDispose(() => {
  revision++
})

function apply(data: ManufacturingPeriod): void {
  detail.value = data
  method.value = data.wipMethod ?? 'materials'
  expenses.value = (data.expenses ?? []).map((item) => ({ ...item }))
  orders.value = (data.orders ?? []).map((item) => ({ ...item }))
}

async function inspect(): Promise<void> {
  if (!periodId.value) return
  const current = ++revision
  detail.value = undefined
  opening.value = true
  detailError.value = ''
  commandError.value = ''
  confirmation.value = undefined
  try {
    const data = await manufacturingDetail<ManufacturingPeriod>('cost-periods', periodId.value)
    if (current === revision) {
      apply(data)
      step.value =
        data.status === 'posted' ? '4' : data.status === 'calculated' ? '3' : String(maxStep.value)
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) detailError.value = messageOf(cause)
  } finally {
    if (current === revision) opening.value = false
  }
}

function addExpense(): void {
  expenses.value.push({
    kind: 'labor',
    source: 'manual',
    amount: '',
    accountCode: '',
    allocationBasis: 'quantity',
  })
}

type CostAction = 'collect' | 'allocate' | 'calculate' | 'recalculate' | 'post' | 'reopen'
const next: Record<CostAction, string> = {
  collect: '1',
  allocate: '2',
  calculate: '3',
  recalculate: '3',
  post: '4',
  reopen: '0',
}
const notices: Record<CostAction, string> = {
  collect: '费用归集已保存。',
  allocate: '工单分摊依据已保存。',
  calculate: '生产成本已计算。',
  recalculate: '生产成本已重算。',
  post: '生产成本差异已过账。',
  reopen: '生产成本差异已冲回，当前期间可重新计算。',
}

async function change(action: CostAction): Promise<void> {
  const current = detail.value
  if (
    !current ||
    (action === 'post' ? !canPost.value : action === 'reopen' ? !canReopen.value : !manage.value)
  )
    return
  const payload: {
    version: number
    wipMethod?: typeof method.value
    expenses?: CostExpense[]
    orders?: CostAllocation[]
  } = { version: current.version }
  if (action === 'collect') {
    if (
      expenses.value.some((item) =>
        item.source === 'manual' ? !item.amount?.trim() : !item.accountCode?.trim(),
      )
    ) {
      commandError.value = '请填写每行实际费用或总账科目编码。'
      return
    }
    payload.wipMethod = method.value
    payload.expenses = expenses.value.map((item) => ({
      kind: item.kind,
      source: item.source,
      amount: item.source === 'manual' ? item.amount?.trim() : undefined,
      accountCode: item.source === 'gl' ? item.accountCode?.trim() : undefined,
      allocationBasis: item.allocationBasis,
    }))
  }
  if (action === 'allocate') {
    payload.orders = orders.value.map(({ workOrderId, hours, progressPct }) => ({
      workOrderId,
      hours: hours?.trim() || undefined,
      progressPct: progressPct?.trim() || undefined,
    }))
  }
  const previous = revision
  const result = await run(
    'cost:' + action + ':' + current.periodId,
    payload,
    (key) =>
      manufacturingCommand<ManufacturingPeriod>(
        'cost-periods',
        current.periodId,
        action,
        payload,
        key,
      ),
    notices[action],
  )
  if (!result || previous !== revision) return
  apply(result)
  step.value = next[action]
  confirmation.value = undefined
  void load()
}

onMounted(async () => {
  await load()
  periodId.value =
    items.value.find(({ status }) => status === 'open')?.periodId ?? items.value[0]?.periodId ?? ''
  if (periodId.value) void inspect()
})
</script>

<template>
  <el-alert
    v-if="listError || commandError || detailError"
    :title="listError || commandError || detailError"
    type="error"
    :closable="false"
  />
  <p v-if="notice" role="status">{{ notice }}</p>
  <section class="panel mfg-panel" aria-label="月末生产成本">
    <div class="mfg-toolbar">
      <el-select
        v-model="periodId"
        aria-label="生产成本期间"
        placeholder="请选择会计期间"
        :disabled="busy"
        :loading="loading"
        @change="inspect"
      >
        <el-option
          v-for="item in items"
          :key="item.periodId"
          :value="item.periodId"
          :label="item.period + ' · ' + manufacturingStates[item.status]"
        />
      </el-select>
      <el-button v-if="cursor" :loading="loading" @click="load(true)">加载更多期间</el-button>
      <el-button v-if="periodId" :disabled="busy" :loading="opening" @click="inspect"
        >刷新期间</el-button
      >
    </div>
    <p v-if="opening" role="status">正在读取生产成本期间。</p>
    <el-empty v-else-if="!detail" description="选择期间开展生产成本计算" />
    <template v-else>
      <p>
        {{ detail.period }} · {{ detail.startDate }} 至 {{ detail.endDate }} ·
        {{ manufacturingStates[detail.status] }}
      </p>
      <p v-if="!endReached && !posted && session.can('mfg_cost:manage')">
        本期自 {{ detail.endDate }} 起可以归集和计算生产成本。
      </p>
      <p v-if="posted && detail.accountingStatus === 'closed'">
        要重算本期成本，请先在会计期间办理重开，再冲回生产成本差异。
      </p>
      <el-steps :active="Number(step)" finish-status="success" align-center>
        <el-step v-for="label in steps" :key="label" :title="label" />
      </el-steps>
      <el-tabs v-model="step" class="mfg-cost-tabs">
        <el-tab-pane name="0" label="归集费用" :disabled="busy">
          <el-form
            label-position="top"
            :disabled="!manage || busy"
            @submit.prevent="change('collect')"
          >
            <div class="mfg-form">
              <el-form-item label="期末在制计算方法">
                <el-select v-model="method" aria-label="期末在制计算方法">
                  <el-option label="只留材料成本" value="materials" /><el-option
                    label="约当产量"
                    value="equivalent"
                  />
                </el-select>
              </el-form-item>
            </div>
            <div v-for="(expense, index) in expenses" :key="index" class="mfg-lines mfg-form">
              <el-form-item label="费用类型"
                ><el-select v-model="expense.kind" aria-label="费用类型"
                  ><el-option value="labor" label="直接人工" /><el-option
                    value="overhead"
                    label="制造费用" /></el-select
              ></el-form-item>
              <el-form-item label="来源"
                ><el-select v-model="expense.source" aria-label="费用来源"
                  ><el-option value="manual" label="手工录入" /><el-option
                    value="gl"
                    label="总账归集" /></el-select
              ></el-form-item>
              <el-form-item v-if="expense.source === 'manual'" label="费用金额"
                ><el-input v-model="expense.amount" aria-label="归集费用金额" inputmode="decimal"
              /></el-form-item>
              <el-form-item v-else label="总账科目编码"
                ><el-input v-model="expense.accountCode" aria-label="总账科目编码" /><span
                  v-if="expense.amount"
                  >已归集 {{ expense.amount }}</span
                ></el-form-item
              >
              <el-form-item label="分摊方式"
                ><el-select v-model="expense.allocationBasis" aria-label="费用分摊方式"
                  ><el-option value="hours" label="按工时" /><el-option
                    value="quantity"
                    label="按产量" /><el-option value="material" label="按材料成本" /></el-select
              ></el-form-item>
              <el-button v-if="manage" link type="danger" @click="expenses.splice(index, 1)"
                >移除费用</el-button
              >
            </div>
            <el-empty v-if="!expenses.length" description="本期无人工或制造费用记录" />
            <div v-if="manage" class="mfg-toolbar">
              <el-button @click="addExpense">添加费用</el-button>
              <el-button native-type="submit" type="primary" :loading="busy"
                >保存费用归集</el-button
              >
            </div>
          </el-form>
        </el-tab-pane>
        <el-tab-pane name="1" label="分摊依据" :disabled="maxStep < 1 || busy">
          <el-table :data="orders" empty-text="本期无生产工单">
            <el-table-column label="工单号" min-width="140"
              ><template #default="{ row }">{{
                row.documentNo ?? row.workOrderId
              }}</template></el-table-column
            >
            <el-table-column prop="skuCode" label="产成品" min-width="115" />
            <el-table-column prop="planQty" label="计划产量" min-width="100" />
            <el-table-column prop="periodCompletedQty" label="本期完工" min-width="100" />
            <el-table-column v-if="requiresHours" label="本期工时" min-width="125"
              ><template #default="{ row }"
                ><el-input
                  v-model="row.hours"
                  :disabled="!manage || busy"
                  inputmode="decimal"
                  aria-label="工单本期工时" /></template
            ></el-table-column>
            <el-table-column
              v-if="method === 'equivalent'"
              label="期末完工程度（%）"
              min-width="150"
              ><template #default="{ row }"
                ><el-input
                  v-model="row.progressPct"
                  :disabled="!manage || busy"
                  inputmode="decimal"
                  aria-label="期末完工程度" /></template
            ></el-table-column>
          </el-table>
          <el-button
            v-if="manage"
            class="mfg-list-pager"
            type="primary"
            :loading="busy"
            @click="change('allocate')"
            >保存分摊依据</el-button
          >
        </el-tab-pane>
        <el-tab-pane name="2" label="计算成本" :disabled="maxStep < 2 || busy">
          <p>根据费用池、分摊依据和在制方法，计算本期完工实际成本。</p>
          <el-button
            v-if="manage"
            type="primary"
            :loading="busy"
            @click="change(calculated ? 'recalculate' : 'calculate')"
            >{{ calculated ? '重算生产成本' : '计算生产成本' }}</el-button
          >
        </el-tab-pane>
        <el-tab-pane name="3" label="查看差异" :disabled="maxStep < 3 || busy">
          <ManufacturingCostTable :rows="detail.items ?? []" />
          <el-button
            v-if="manage && calculated"
            class="mfg-list-pager"
            :loading="busy"
            @click="change('recalculate')"
            >重算</el-button
          >
        </el-tab-pane>
        <el-tab-pane name="4" label="过账" :disabled="maxStep < 4 || busy">
          <ManufacturingCostTable :rows="detail.items ?? []" variance-only />
          <el-button
            v-if="canPost"
            class="mfg-list-pager"
            type="primary"
            :disabled="busy"
            @click="confirmation = 'post'"
            >过账生产成本差异</el-button
          >
          <el-button
            v-if="canReopen"
            class="mfg-list-pager"
            type="warning"
            :disabled="busy"
            @click="confirmation = 'reopen'"
            >冲回生产成本差异</el-button
          >
        </el-tab-pane>
      </el-tabs>
    </template>
  </section>
  <el-dialog
    v-if="confirmation"
    :model-value="true"
    :title="confirmation === 'post' ? '确认生产成本差异过账' : '确认冲回生产成本差异'"
    width="min(500px, 94vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="confirmation = undefined"
  >
    <el-alert v-if="commandError" :title="commandError" type="error" :closable="false" />
    <p>
      {{
        confirmation === 'post'
          ? '本次过账将登记在库和已出库部分的差异，并锁定本期成本结果。'
          : '冲回后可以修改费用、重算并重新过账。'
      }}
    </p>
    <template #footer
      ><el-button :disabled="busy" @click="confirmation = undefined">取消</el-button
      ><el-button type="primary" :loading="busy" @click="confirmation && change(confirmation)">{{
        confirmation === 'post' ? '确认过账' : '确认冲回'
      }}</el-button></template
    >
  </el-dialog>
</template>

<style scoped>
.mfg-cost-tabs {
  margin-top: 14px;
}
</style>
