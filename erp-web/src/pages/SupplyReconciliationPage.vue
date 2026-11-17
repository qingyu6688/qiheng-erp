<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { isCancelled, messageOf } from '../api/http'
import {
  reconciliationDifferences,
  reconciliationRuns,
  runReconciliation,
} from '../api/supply-chain'
import type {
  ReconciliationDifference,
  ReconciliationRun,
  SupplyCheckType,
} from '../api/supply-chain'
import type { PageResult } from '../types/api'
import { useSessionStore } from '../stores/session'

const session = useSessionStore()
const checkType = ref<SupplyCheckType>('batch_balance')
const types: Record<SupplyCheckType, string> = {
  batch_balance: '批次与序列号余额',
  stock_blocks: '库存冻结明细',
  location_balance: '库位余额',
}
const statuses: Record<ReconciliationRun['status'], string> = {
  running: '执行中',
  passed: '通过',
  failed: '存在差异',
  error: '执行失败',
}
const runs = ref<PageResult<ReconciliationRun>>({ items: [], nextCursor: null })
const diffs = ref<PageResult<ReconciliationDifference>>({ items: [], nextCursor: null })
const active = ref<ReconciliationRun>()
const busy = ref(false)
const submitting = ref(false)
const diffBusy = ref(false)
const error = ref('')
const diffError = ref('')
let selection = 0

async function load(append = false): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const next = await reconciliationRuns(append ? (runs.value.nextCursor ?? undefined) : undefined)
    if (append) next.items = [...runs.value.items, ...next.items]
    runs.value = next
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function start(): Promise<void> {
  if (submitting.value) return
  submitting.value = true
  error.value = ''
  try {
    await runReconciliation(checkType.value)
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    submitting.value = false
  }
}
async function show(run: ReconciliationRun, append = false): Promise<void> {
  const generation = ++selection
  active.value = run
  if (!append) diffs.value = { items: [], nextCursor: null }
  diffBusy.value = true
  diffError.value = ''
  try {
    const page = await reconciliationDifferences(
      run.id,
      append ? (diffs.value.nextCursor ?? undefined) : undefined,
    )
    if (generation !== selection) return
    if (append) page.items = [...diffs.value.items, ...page.items]
    diffs.value = page
  } catch (cause: unknown) {
    if (!isCancelled(cause) && generation === selection) diffError.value = messageOf(cause)
  } finally {
    if (generation === selection) diffBusy.value = false
  }
}
onMounted(() => load())
</script>

<template>
  <header class="page-heading"><h1>供应链对账</h1></header>
  <el-alert v-if="error" type="error" :title="error" :closable="false" />
  <section class="panel table-panel">
    <div class="filters">
      <template v-if="session.can('reconciliation:run')">
        <el-select v-model="checkType" aria-label="对账类型">
          <el-option v-for="(label, type) in types" :key="type" :label="label" :value="type" />
        </el-select>
        <el-button type="primary" :loading="submitting" :disabled="busy" @click="start"
          >发起对账</el-button
        >
      </template>
      <el-button :loading="busy" @click="load()">刷新</el-button>
    </div>
    <el-table
      :data="runs.items.filter((row) => row.checkType in types)"
      row-key="id"
      v-loading="busy"
      empty-text="暂无供应链对账任务"
    >
      <el-table-column label="对账类型" min-width="160"
        ><template #default="{ row }">{{
          types[row.checkType as SupplyCheckType]
        }}</template></el-table-column
      >
      <el-table-column label="状态" width="110"
        ><template #default="{ row }">{{
          statuses[row.status as ReconciliationRun['status']]
        }}</template></el-table-column
      >
      <el-table-column prop="diffCount" label="差异数量" width="100" />
      <el-table-column prop="startedAt" label="开始时间" min-width="180" />
      <el-table-column prop="errorMessage" label="失败原因" min-width="160" />
      <el-table-column label="操作" width="100"
        ><template #default="{ row }"
          ><el-button v-if="row.diffCount" type="primary" link @click="show(row)"
            >查看差异</el-button
          ></template
        ></el-table-column
      >
    </el-table>
    <el-button v-if="runs.nextCursor" :loading="busy" @click="load(true)">加载更多</el-button>
  </section>
  <el-dialog
    :model-value="!!active"
    title="对账差异"
    width="min(780px, 92vw)"
    @update:model-value="active = undefined"
  >
    <el-alert v-if="diffError" type="error" :title="diffError" :closable="false" />
    <el-table :data="diffs.items" row-key="id" v-loading="diffBusy" empty-text="暂无差异">
      <el-table-column
        prop="objectKey"
        label="仓库 / SKU / 明细"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column prop="expectedValue" label="应有数量" width="125" />
      <el-table-column prop="actualValue" label="实际数量" width="125" />
    </el-table>
    <el-button v-if="diffs.nextCursor && active" :loading="diffBusy" @click="show(active, true)"
      >加载更多</el-button
    >
  </el-dialog>
</template>
