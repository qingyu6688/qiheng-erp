<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { record } from '../api/admin'
import { document, documents } from '../api/sales'
import { procurementDocument, procurementList } from '../api/procurement'
import { isCancelled, messageOf } from '../api/http'
import { useSessionStore } from '../stores/session'
import type { DataRow } from '../types/admin'
import type { SalesDocument } from '../types/sales'
import { ORDER_LINE_LIMIT, parseOrderPaste, prepareOrderPaste } from '../utils/order-line-paste'
import type { PastedOrderLine } from '../utils/order-line-paste'

const props = defineProps<{
  kind: 'sales-orders' | 'purchase-orders'
  count: number
  partnerId: string
  disabled: boolean
  skus: DataRow[]
  priceTaxInclusive?: boolean
}>()
const emit = defineEmits<{ import: [PastedOrderLine[]] }>()
const session = useSessionStore()
const mode = ref<'paste' | 'history'>()
const text = ref('')
const error = ref('')
const busy = ref(false)
const histories = ref<SalesDocument[]>([])
const selected = ref('')
const keyword = ref('')
const nextCursor = ref<string | null>(null)
let revision = 0
const canHistory = computed(() =>
  session.can(props.kind === 'sales-orders' ? 'sales_order:view' : 'purchase_order:view'),
)

function openPaste(value = ''): void {
  if (props.disabled) return
  text.value = value
  error.value = ''
  mode.value = 'paste'
}

function close(): void {
  revision++
  mode.value = undefined
  busy.value = false
  error.value = ''
  histories.value = []
  selected.value = ''
}

async function loadHistory(cursor?: string): Promise<void> {
  if (!canHistory.value || props.disabled) return
  const current = ++revision
  mode.value = 'history'
  error.value = ''
  busy.value = true
  try {
    const params = {
      limit: 20,
      cursor,
      keyword: keyword.value || undefined,
      customerId: props.kind === 'sales-orders' ? props.partnerId || undefined : undefined,
      supplierId: props.kind === 'purchase-orders' ? props.partnerId || undefined : undefined,
    }
    const result =
      props.kind === 'sales-orders'
        ? await documents('sales-orders', params)
        : await procurementList('purchase-orders', params)
    if (current !== revision) return
    histories.value = result.items
    selected.value = ''
    nextCursor.value = result.nextCursor
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) busy.value = false
  }
}

function openHistory(): void {
  keyword.value = ''
  void loadHistory()
}

async function confirm(): Promise<void> {
  if (busy.value || props.disabled) return
  const current = ++revision
  busy.value = true
  error.value = ''
  try {
    let source = text.value
    if (mode.value === 'history') {
      if (!selected.value || !canHistory.value) throw new Error('请选择有权读取的历史订单')
      const previous =
        props.kind === 'sales-orders'
          ? await document('sales-orders', selected.value)
          : await procurementDocument('purchase-orders', selected.value)
      if (!previous.lines?.length) throw new Error('该历史订单没有可复制的商品明细')
      const currentSkus = new Map(props.skus.map((sku) => [sku.id, sku]))
      source = previous.lines
        .map((line) => {
          const sku = currentSkus.get(line.skuId ?? line.sku?.id ?? '')
          if (!sku?.code) throw new Error('历史订单包含当前不可用的商品，请先核对商品资料')
          // 历史单位转换由服务端试算完成，复制时保留原数量所用单位。
          return [sku.code, line.qty ?? '', line.unitPrice ?? ''].join('\t')
        })
        .join('\n')
      const rows = parseOrderPaste(source)
      if (rows.length + props.count > ORDER_LINE_LIMIT) throw new Error('导入后商品明细超过500行')
      const prepared = await prepareOrderPaste(rows, props.skus, (id) => record('skus/' + id))
      for (const [index, line] of prepared.entries()) {
        const oldUnit = previous.lines[index]?.unitId ?? previous.lines[index]?.unit?.id
        if (!oldUnit || !line.units.some((unit) => unit.unitId === oldUnit))
          throw new Error('历史订单第' + (index + 1) + '行单位当前不可用')
        line.unitId = oldUnit
        if (
          previous.priceTaxInclusive !== undefined &&
          previous.priceTaxInclusive !== (props.priceTaxInclusive ?? true)
        ) {
          line.unitPrice = ''
        }
      }
      if (current !== revision) return
      emit('import', prepared)
    } else {
      const rows = parseOrderPaste(source)
      if (rows.length + props.count > ORDER_LINE_LIMIT) throw new Error('导入后商品明细超过500行')
      const prepared = await prepareOrderPaste(rows, props.skus, (id) => record('skus/' + id))
      if (current !== revision) return
      emit('import', prepared)
    }
    close()
  } catch (cause: unknown) {
    if (current === revision && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current === revision) busy.value = false
  }
}

watch(() => session.companyId, close, { flush: 'sync' })
onScopeDispose(() => {
  revision++
})
defineExpose({ openPaste })
</script>

<template>
  <el-button :disabled="disabled" @click="openPaste()">批量粘贴</el-button>
  <el-button v-if="canHistory" :disabled="disabled" @click="openHistory">从历史订单复制</el-button>
  <el-dialog
    v-if="mode"
    :model-value="true"
    :title="mode === 'paste' ? '批量粘贴商品' : '从历史订单复制'"
    width="min(640px, 94vw)"
    append-to-body
    :show-close="!busy"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    @close="close"
  >
    <template v-if="mode === 'paste'">
      <p class="import-hint">
        从 Excel 复制商品编码、数量、单价三列，单价可留空。商品使用基础单位，导入后可修改。
      </p>
      <el-input
        v-model="text"
        type="textarea"
        :rows="9"
        :disabled="busy"
        aria-label="商品编码、数量和单价"
        placeholder="SKU001&#9;2.500000&#9;18.500000"
      />
    </template>
    <template v-else>
      <div class="history-query">
        <el-input
          v-model="keyword"
          aria-label="历史订单单号"
          placeholder="输入单号"
          :disabled="busy"
          @keyup.enter="loadHistory()"
        />
        <el-button :disabled="busy" @click="loadHistory()">查询</el-button>
      </div>
      <el-select v-model="selected" aria-label="历史订单" :disabled="busy" class="history-select">
        <el-option
          v-for="item in histories"
          :key="item.id"
          :value="item.id"
          :label="(item.documentNo ?? item.id) + ' · ' + (item.businessDate ?? '')"
        />
      </el-select>
      <p v-if="!busy && !histories.length" role="status">没有可复制的历史订单</p>
      <el-button v-if="nextCursor" text :disabled="busy" @click="loadHistory(nextCursor)"
        >下一页历史订单</el-button
      >
      <p class="import-hint">
        复制商品、原单位和数量。价格口径一致时复制单价，口径不同时使用当前参考价。客户、供应商、仓库、优惠及自定义字段由本单维护。
      </p>
    </template>
    <el-alert v-if="error" type="error" :closable="false" :title="error" />
    <template #footer>
      <el-button :disabled="busy" @click="close">取消导入</el-button>
      <el-button
        type="primary"
        :loading="busy"
        :disabled="mode === 'history' && !selected"
        @click="confirm"
        >追加明细</el-button
      >
    </template>
  </el-dialog>
</template>

<style scoped>
.import-hint {
  color: var(--erp-text-2);
  font-size: 13px;
  margin: 0 0 12px;
}
.history-query {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.history-select {
  width: 100%;
  margin-bottom: 12px;
}
</style>
