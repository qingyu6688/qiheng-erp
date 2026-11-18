<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { document, salesCommand } from '../api/sales'
import { procurementDocument, procurementCommand } from '../api/procurement'
import { createPrintPdf, customPrintOutput } from '../api/print-design'
import { exportSelectedDocuments } from '../api/document-browser'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import type { SalesDocument } from '../types/sales'
import type { PrintTemplateSummary } from '../types/print-design'

const props = defineProps<{
  kind: 'sales-orders' | 'purchase-orders' | 'receipts'
  selected: SalesDocument[]
  canApprove: boolean
  canPrint: boolean
  canOpenJobs: boolean
  disabled?: boolean
}>()
const emit = defineEmits<{ clear: []; changed: [] }>()
const busy = ref<'approve' | 'templates' | 'print' | 'export'>()
const dialog = ref<'approve' | 'print'>()
const error = ref('')
const message = ref('')
const issues = ref<string[]>([])
const printTask = ref('')
const templates = ref<PrintTemplateSummary[]>([])
const templateId = ref('')
const type = computed(
  () =>
    ({
      'sales-orders': 'sales_order',
      'purchase-orders': 'purchase_order',
      receipts: 'receipt',
    })[props.kind],
)
const action = computed(() => (props.kind === 'receipts' ? 'confirm' : 'approve'))
const label = computed(() => (action.value === 'confirm' ? '批量确认收款' : '批量审核'))
const controller = new AbortController()
let printKey = ''

watch(
  () => props.selected,
  () => {
    if (!busy.value) dialog.value = undefined
  },
)
watch(templateId, () => {
  printKey = ''
})
onBeforeUnmount(() => controller.abort())

async function approve(): Promise<void> {
  if (busy.value || !props.canApprove || !props.selected.length) return
  const rows = [...props.selected]
  const kind = props.kind
  const operation = action.value
  busy.value = 'approve'
  dialog.value = undefined
  error.value = ''
  message.value = ''
  issues.value = []
  let succeeded = 0
  try {
    for (const row of rows) {
      try {
        controller.signal.throwIfAborted()
        const fresh =
          kind === 'purchase-orders'
            ? await procurementDocument(kind, row.id, controller.signal)
            : await document(kind, row.id, controller.signal)
        if (fresh.version !== row.version || !fresh.allowedActions.includes(operation)) {
          issues.value.push(`${row.documentNo ?? row.id}：单据已变更或当前无法执行此操作。`)
          continue
        }
        const data = { version: row.version }
        const key = newIdempotencyKey()
        if (kind === 'purchase-orders')
          await procurementCommand(kind, row.id, operation, data, key, controller.signal)
        else await salesCommand(kind, row.id, operation, data, key, controller.signal)
        succeeded++
      } catch (cause: unknown) {
        if (isCancelled(cause)) return
        issues.value.push(`${row.documentNo ?? row.id}：${messageOf(cause)}`)
      }
    }
    message.value = `已完成 ${succeeded} 张，${issues.value.length} 张需要检查。`
    emit('changed')
  } finally {
    busy.value = undefined
  }
}

async function selectTemplate(): Promise<void> {
  const first = props.selected[0]
  if (!first || props.selected.length > 50 || busy.value || !props.canPrint) return
  const selection = props.selected
  busy.value = 'templates'
  error.value = ''
  printTask.value = ''
  try {
    const output = await customPrintOutput(type.value, first.id, undefined, controller.signal)
    if (props.selected !== selection) return
    templates.value = output.templates
    if (!templates.value.length) {
      error.value = '当前单据类型需要启用打印模板后才能生成批量 PDF。'
      return
    }
    templateId.value = output.templateId ?? templates.value[0]!.id
    printKey = ''
    dialog.value = 'print'
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = undefined
  }
}

async function print(): Promise<void> {
  if (
    busy.value ||
    !templateId.value ||
    !props.canPrint ||
    !props.selected.length ||
    props.selected.length > 50
  )
    return
  busy.value = 'print'
  error.value = ''
  printKey ||= newIdempotencyKey()
  try {
    const result = await createPrintPdf(
      type.value,
      templateId.value,
      props.selected.map((row) => row.id),
      1,
      printKey,
      controller.signal,
    )
    printTask.value = result.taskId
    dialog.value = undefined
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = undefined
  }
}

async function exportSelection(): Promise<void> {
  if (!props.selected.length || busy.value) return
  busy.value = 'export'
  error.value = ''
  try {
    const file = await exportSelectedDocuments(
      props.kind,
      props.selected.map((row) => row.id),
      controller.signal,
    )
    const url = URL.createObjectURL(file)
    const link = window.document.createElement('a')
    link.href = url
    link.download = '所选单据.csv'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = undefined
  }
}
</script>

<template>
  <div v-if="selected.length" class="document-batch-bar" role="group" aria-label="单据批量操作">
    <strong aria-live="polite">已选 {{ selected.length }} 项</strong>
    <el-button
      v-if="canApprove"
      type="primary"
      :disabled="disabled || !!busy"
      @click="dialog = 'approve'"
      >{{ label }}</el-button
    >
    <el-button
      v-if="canPrint"
      :title="selected.length > 50 ? '请分批选择，每次最多打印 50 张' : undefined"
      :disabled="disabled || !!busy || selected.length > 50"
      @click="selectTemplate"
      >批量打印</el-button
    >
    <el-button :disabled="disabled || !!busy" @click="exportSelection">导出所选</el-button>
    <el-button text :disabled="!!busy" @click="emit('clear')">取消选择</el-button>
  </div>
  <p v-if="error && !dialog" class="document-batch-message" role="alert">{{ error }}</p>
  <p v-if="message" class="document-batch-message" role="status">{{ message }}</p>
  <details v-if="issues.length" class="document-batch-message">
    <summary>查看未完成单据</summary>
    <ul>
      <li v-for="issue in issues" :key="issue">{{ issue }}</li>
    </ul>
  </details>
  <p v-if="printTask" class="document-batch-message" role="status">
    已提交 PDF 任务。
    <router-link
      v-if="canOpenJobs"
      :to="{ name: 'file-jobs', query: { tab: 'export', jobId: printTask } }"
      >查看任务</router-link
    >
  </p>
  <el-dialog
    :model-value="!!dialog"
    :title="dialog === 'print' ? '批量打印' : label"
    width="min(420px, 92vw)"
    :close-on-click-modal="!busy"
    @update:model-value="!$event && !busy && (dialog = undefined)"
  >
    <template v-if="dialog === 'print'">
      <label for="batch-print-template">打印模板</label>
      <el-select id="batch-print-template" v-model="templateId" :disabled="!!busy">
        <el-option v-for="item in templates" :key="item.id" :label="item.name" :value="item.id" />
      </el-select>
      <p v-if="error" role="alert">{{ error }}</p>
    </template>
    <p v-else>
      确定处理所选的 {{ selected.length }} 张单据吗？系统将逐张校验，处理结果会在列表上方显示。
    </p>
    <template #footer>
      <el-button :disabled="!!busy" @click="dialog = undefined">取消</el-button>
      <el-button
        v-if="dialog === 'print'"
        type="primary"
        :loading="busy === 'print'"
        :disabled="!templateId"
        @click="print"
        >生成 PDF</el-button
      >
      <el-button v-else type="primary" @click="approve">确认{{ label }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.document-batch-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  background: var(--el-color-primary-light-9);
}
.document-batch-bar :deep(.el-button) {
  margin-left: 0;
}
.document-batch-message {
  margin: 8px 12px;
}
#batch-print-template {
  margin-top: 6px;
}
</style>
