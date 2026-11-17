<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { printData } from '../api/documents'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { createPrintPdf, customPrintOutput, logTemplatePrint } from '../api/print-design'
import PrintCanvas from './print/PrintCanvas.vue'
import {
  documentPermission,
  firstPrintValue,
  printMeta,
  printTables,
  printTotals,
} from '../types/documents'
import type { PrintData } from '../types/documents'
import type { PrintOutput } from '../types/print-design'
import { documentLink } from '../types/reporting'
import { salesSpecs, salesStatusLabel } from '../types/sales'
import { procurementSpecs } from '../types/procurement'
import { stockSpecs, stockStatusLabels } from '../types/stock-operations'
import { useSessionStore } from '../stores/session'
import { formatPrintTime } from '../utils/format'

const route = useRoute(),
  session = useSessionStore(),
  data = ref<PrintData>(),
  custom = ref<PrintOutput>(),
  chosen = ref(''),
  changing = ref(false),
  printError = ref(''),
  queued = ref(''),
  loading = ref(false),
  error = ref('')
const type = computed(() => String(route.params.type)),
  id = computed(() => String(route.params.id)),
  doc = computed(() => data.value?.document ?? {})
const spec = computed(() =>
  [...salesSpecs, ...procurementSpecs, ...stockSpecs].find(
    (item) => item.permission === documentPermission(type.value),
  ),
)
const canCost = computed(
  () => session.can('cost:view') && !['shipment', 'sales_order'].includes(type.value),
)
const tables = computed(() => printTables(type.value, doc.value, canCost.value)),
  totals = computed(() => printTotals(type.value, doc.value, canCost.value))
const status = computed(() =>
  spec.value && salesSpecs.some((item) => item.path === spec.value?.path)
    ? salesStatusLabel(
        spec.value.path as Parameters<typeof salesStatusLabel>[0],
        String(doc.value.status),
      )
    : (stockStatusLabels[String(doc.value.status)] ??
      salesStatusLabel('sales-orders', String(doc.value.status ?? '—'))),
)
let revision = 0,
  controller = new AbortController()
let printSignature = '',
  printKey = ''
const printStyle = document.createElement('style')
document.head.append(printStyle)

async function load(): Promise<void> {
  controller.abort()
  controller = new AbortController()
  const current = ++revision
  data.value = undefined
  custom.value = undefined
  chosen.value = ''
  queued.value = ''
  printError.value = ''
  printSignature = ''
  printKey = ''
  error.value = ''
  loading.value = true
  if (
    !session.can(documentPermission(type.value) + ':view') ||
    !session.can(documentPermission(type.value) + ':print')
  ) {
    error.value = '没有查看或打印该单据的权限'
    loading.value = false
    return
  }
  try {
    const result = await printData(type.value, id.value, controller.signal)
    if (current === revision) data.value = result
    if (current === revision) await selectTemplate(undefined, current)
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) loading.value = false
  }
}
async function selectTemplate(templateId?: string, current = revision): Promise<void> {
  printError.value = ''
  queued.value = ''
  if (templateId === '') {
    if (custom.value)
      custom.value = { ...custom.value, templateId: null, version: null, model: null }
    chosen.value = ''
    return
  }
  changing.value = true
  try {
    const result = await customPrintOutput(type.value, id.value, templateId, controller.signal)
    if (current === revision && result && Array.isArray(result.templates)) {
      custom.value = result
      chosen.value = result.templateId ?? ''
    }
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause) && templateId)
      printError.value = messageOf(cause)
    if (current === revision) chosen.value = custom.value?.templateId ?? ''
  } finally {
    if (current === revision) changing.value = false
  }
}
async function print(): Promise<void> {
  if (!data.value || changing.value) return
  const current = revision
  const choice = custom.value
  printError.value = ''
  changing.value = true
  try {
    if (choice?.templateId && choice.version !== null) {
      const fresh = await customPrintOutput(
        type.value,
        id.value,
        choice.templateId,
        controller.signal,
      )
      if (
        current !== revision ||
        fresh.templateId !== choice.templateId ||
        !fresh.model ||
        fresh.version === null
      )
        return
      custom.value = fresh
      if (fresh.version !== choice.version) {
        printError.value = '打印模板已更新，请核对当前预览再打印。'
        return
      }
      const signature = JSON.stringify([type.value, id.value, choice.templateId, fresh.version])
      if (signature !== printSignature) {
        printSignature = signature
        printKey = newIdempotencyKey()
      }
      await logTemplatePrint(
        type.value,
        id.value,
        choice.templateId,
        fresh.version,
        printKey,
        controller.signal,
      )
      printSignature = ''
      printKey = ''
    }
    if (current !== revision) return
    await nextTick()
    window.print()
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) printError.value = messageOf(cause)
  } finally {
    if (current === revision) changing.value = false
  }
}
async function queuePdf(): Promise<void> {
  const templateId = custom.value?.templateId
  if (!templateId || changing.value || queued.value) return
  const current = revision
  changing.value = true
  printError.value = ''
  try {
    const result = await createPrintPdf(
      type.value,
      templateId,
      [id.value],
      1,
      newIdempotencyKey(),
      controller.signal,
    )
    if (current === revision) queued.value = result.taskId
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) printError.value = messageOf(cause)
  } finally {
    if (current === revision) changing.value = false
  }
}
watch(
  () => custom.value?.model,
  (model) => {
    printStyle.textContent =
      model && Number.isFinite(model.width) && Number.isFinite(model.height)
        ? `@media print { @page { size: ${model.width}mm ${model.height}mm; margin: 0; } }`
        : ''
  },
)
watch(
  () => [type.value, id.value, session.companyId],
  () => {
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
  controller.abort()
  printStyle.remove()
})
</script>
<template>
  <div class="print-controls page-heading">
    <router-link v-if="documentLink(type, id)" class="text-link" :to="documentLink(type, id)!"
      >返回单据</router-link
    >
    <el-select
      v-if="custom?.templates.length"
      v-model="chosen"
      aria-label="打印模板"
      class="template-select"
      :disabled="changing"
      @change="selectTemplate(chosen)"
    >
      <el-option label="系统默认模板" value="" />
      <el-option
        v-for="option in custom.templates"
        :key="option.id"
        :label="option.name"
        :value="option.id"
      />
    </el-select>
    <el-button type="primary" :disabled="!data || loading || changing" @click="print"
      >打印 / 保存 PDF</el-button
    >
    <el-button v-if="custom?.templateId" :disabled="changing || !!queued" @click="queuePdf"
      >后台生成 PDF</el-button
    >
    <router-link v-if="queued" :to="{ name: 'file-jobs', query: { tab: 'export', jobId: queued } }"
      >前往文件任务查看 PDF</router-link
    >
  </div>
  <el-alert
    v-if="printError"
    class="print-controls"
    :title="printError"
    type="error"
    :closable="false"
  />
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="loading">正在准备打印数据…</p>
  <el-button v-if="error && !loading" class="print-controls" text @click="load"
    >重新加载打印数据</el-button
  >
  <PrintCanvas v-if="data && custom?.model" :model="custom.model" />
  <article v-else-if="data" class="print-sheet">
    <header>
      <h1>{{ data.company.name }}</h1>
      <p v-if="data.company.address || data.company.phone">
        {{ data.company.address }} {{ data.company.phone }}
      </p>
      <h2>{{ spec?.singular ?? '业务单据' }}</h2>
    </header>
    <dl class="print-meta">
      <dt>单据编号</dt>
      <dd>{{ firstPrintValue(doc.documentNo, doc.invoiceNo) }}</dd>
      <dt>状态</dt>
      <dd>{{ status }}</dd>
      <template v-for="field in printMeta(doc)" :key="field.label"
        ><dt>{{ field.label }}</dt>
        <dd>{{ field.value }}</dd></template
      >
    </dl>
    <section v-for="table in tables" :key="table.title" class="print-section">
      <h3>{{ table.title }}</h3>
      <table class="print-table">
        <thead>
          <tr>
            <th v-for="column in table.columns" :key="column.key" :class="column.key">
              {{ column.label }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(row, index) in table.rows" :key="index">
            <td v-for="column in table.columns" :key="column.key" :class="column.key">
              {{ row[column.key] }}
            </td>
          </tr>
        </tbody>
      </table>
    </section>
    <dl v-if="totals.length" class="print-totals">
      <template v-for="field in totals" :key="field.label"
        ><dt>{{ field.label }}</dt>
        <dd>{{ field.value }}</dd></template
      >
    </dl>
    <footer>
      <div class="print-signatures">
        <span>制单：{{ firstPrintValue(doc.createdByName, doc.requesterName) }}</span
        ><span>审核：{{ firstPrintValue(doc.approvedByName, doc.confirmedByName) }}</span
        ><span>复核 / 经办：________________</span>
      </div>
      <p class="print-version">固定格式 v1 · 数据生成于 {{ formatPrintTime(data.printedAt) }}</p>
    </footer>
  </article>
</template>
<style scoped>
.template-select {
  width: 190px;
}
.print-sheet {
  box-sizing: border-box;
  background: white;
  color: #111;
  padding: 28px;
  width: min(100%, 210mm);
  margin: 20px auto;
  border: 1px solid #ddd;
  font-family: 'Microsoft YaHei', sans-serif;
  font-variant-numeric: tabular-nums;
}
.print-sheet header {
  text-align: center;
}
.print-sheet h1 {
  font-size: 23px;
  margin: 0 0 6px;
  overflow-wrap: anywhere;
}
.print-sheet header p {
  font-size: 12px;
  overflow-wrap: anywhere;
}
.print-sheet h2 {
  font-size: 21px;
  margin: 18px 0;
}
.print-sheet h3 {
  font-size: 12px;
  margin: 18px 0 6px;
}
.print-meta {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 76px minmax(0, 1fr);
  gap: 8px;
  font-size: 12px;
}
.print-meta dd {
  margin: 0;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}
.print-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 10px;
}
.print-table th,
.print-table td {
  border: 1px solid #555;
  padding: 7px 4px;
  overflow-wrap: anywhere;
  text-align: left;
  white-space: pre-wrap;
  vertical-align: top;
}
.print-table .number {
  width: 28px;
}
.print-table .product {
  width: 22%;
}
.print-totals {
  display: grid;
  grid-template-columns: repeat(2, minmax(60px, 1fr) minmax(90px, 1.4fr));
  gap: 8px;
  margin: 18px 0;
  font-size: 12px;
  border-top: 1px solid #555;
  padding-top: 10px;
}
.print-totals dd {
  font-weight: bold;
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
.print-sheet footer {
  margin-top: 28px;
  font-size: 11px;
}
.print-signatures {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 18px;
}
.print-version {
  color: #555;
  font-size: 9px;
}
.print-section h3 {
  break-after: avoid;
}
@media (max-width: 600px) {
  .print-sheet {
    padding: 14px;
  }
  .print-meta {
    grid-template-columns: 76px minmax(0, 1fr);
  }
  .print-table {
    font-size: 9px;
  }
  .print-totals {
    grid-template-columns: minmax(60px, 1fr) minmax(90px, 1.4fr);
  }
}
@media print {
  @page {
    size: A4 portrait;
    margin: 14mm;
  }
  :global(.sidebar),
  :global(.topbar),
  .print-controls {
    display: none !important;
  }
  :global(html),
  :global(body) {
    min-width: 0 !important;
    background: white !important;
  }
  :global(.shell),
  :global(.workspace) {
    display: block !important;
    margin: 0 !important;
    width: 100% !important;
    min-height: 0 !important;
  }
  :global(.content) {
    padding: 0 !important;
    overflow: visible !important;
  }
  .print-sheet {
    border: 0;
    margin: 0;
    padding: 0;
    width: 100%;
    max-width: none;
  }
  .print-meta {
    grid-template-columns: 76px minmax(0, 1fr) 76px minmax(0, 1fr);
  }
  .print-totals {
    grid-template-columns: repeat(2, minmax(60px, 1fr) minmax(90px, 1.4fr));
    break-inside: avoid;
  }
  .print-table thead {
    display: table-header-group;
  }
  .print-table tr {
    break-inside: avoid;
  }
  .print-sheet footer {
    break-inside: avoid;
  }
  .print-table {
    font-size: 9px;
  }
}
</style>
