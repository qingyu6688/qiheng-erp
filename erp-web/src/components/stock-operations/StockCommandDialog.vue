<script setup lang="ts">
import { computed, ref } from 'vue'
import { recordStockCounts, stockCommand, stockResult } from '../../api/stock-operations'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { stockActionLabels, stockSpecs } from '../../types/stock-operations'
import type { StockDocument, StockKind } from '../../types/stock-operations'
import { today } from '../../types/sales'
import { useSessionStore } from '../../stores/session'
import DependencyList from './DependencyList.vue'
import StockSelectionFields from './StockSelectionFields.vue'
import type { StockSelection } from '../../types/inventory-tracking'

const props = defineProps<{ kind: StockKind; document: StockDocument; action: string }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const session = useSessionStore(), action = props.action.replaceAll('-', '_')
const title = computed(() => stockActionLabels[action] ?? action)
const reason = ref(''), businessDate = ref(today()), finalReceipt = ref(true)
const rows = ref((props.document.lines ?? []).filter(line => action !== 'resolve_diff' || line.diffStatus === 'pending').map(line => ({
  ...line, inputQty: action === 'record_counts' ? line.countedQty ?? '' : '0', inputPrice: line.inputUnitCost ?? '',
  disposition: 'returned', inputSelection: (action === 'record_counts' && line.stockSelection?.serials ? { serials: line.stockSelection.serials } : null) as StockSelection | null,
})))
const busy = ref(false), uncertain = ref(false), refreshRequired = ref(false), error = ref(''), retryOriginal = ref(false)
const dependencies = ref<unknown>()
const key = newIdempotencyKey()
let submitted: Record<string, unknown> | undefined
function body(): Record<string, unknown> {
  const common = { version: props.document.version }
  if (action === 'record_counts') return { ...common, lines: rows.value.map(line => ({ lineId: line.id, countedQty: line.inputQty, inputUnitCost: session.can('cost:view') ? line.inputPrice || undefined : undefined, serials: line.inputSelection?.serials })) }
  if (action === 'receive') return { ...common, businessDate: businessDate.value, finalReceipt: finalReceipt.value, lines: rows.value.map(line => ({ lineId: line.id, qty: line.inputQty, stockSelection: line.inputSelection ?? undefined })) }
  if (action === 'resolve_diff') return { ...common, businessDate: businessDate.value, reason: reason.value, lines: rows.value.map(line => ({ lineId: line.id, disposition: line.disposition })) }
  return { ...common, businessDate: action === 'ship' ? businessDate.value : undefined, reason: reason.value || undefined }
}
async function submit(): Promise<void> {
  if (busy.value || uncertain.value || refreshRequired.value) return
  busy.value = true; error.value = ''; dependencies.value = undefined; submitted ??= body()
  try {
    if (action === 'record_counts') await recordStockCounts(props.document.id, submitted)
    else await stockCommand(props.kind, props.document.id, action, submitted, key)
    emit('saved')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      error.value = messageOf(cause)
      dependencies.value = cause instanceof ApiError && cause.code === 'DEPENDENCY_EXISTS' ? cause.details : undefined
      const unknown = cause instanceof ApiError && (cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS')
      uncertain.value = action !== 'record_counts' && unknown
      refreshRequired.value = cause instanceof ApiError && (cause.code === 'VERSION_CONFLICT' || action === 'record_counts' && unknown)
      if (!unknown) { submitted = undefined; retryOriginal.value = false }
    }
  } finally { busy.value = false }
}
async function verify(): Promise<void> {
  if (busy.value) return
  busy.value = true
  dependencies.value = undefined
  try {
    const spec = stockSpecs.find(item => item.path === props.kind)!
    const result = await stockResult(spec.permission + '.' + action, key)
    if (result.status === 'succeeded') emit('saved')
  } catch (cause: unknown) {
    if (!isCancelled(cause)) { error.value = messageOf(cause); if (cause instanceof ApiError && cause.status === 404) { uncertain.value = false; retryOriginal.value = true } }
  } finally { busy.value = false }
}
function confirmLabel(): string { if (action === 'record_counts') return '保存实盘'; return title.value.startsWith('确认') ? title.value : '确认' + title.value }
</script>
<template>
  <el-dialog :model-value="true" :title="title" width="min(840px, 94vw)" :close-on-click-modal="false" :show-close="!busy" @close="emit('close')">
    <p>{{ document.documentNo }}</p><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <DependencyList :details="dependencies" />
    <p v-if="uncertain">结果确认中，请查询原请求结果。</p><p v-if="refreshRequired">修改结果或版本已变化，请关闭并刷新单据后核对。</p><p v-if="retryOriginal">尚未查到已提交结果，可使用原内容重试。</p>
    <fieldset class="form-fieldset" :disabled="busy || uncertain || refreshRequired || retryOriginal">
      <el-form label-position="top">
        <el-form-item v-if="['ship', 'receive', 'resolve_diff'].includes(action)" label="本次业务日期" for="stock-command-date"><el-input id="stock-command-date" v-model="businessDate" type="date" /></el-form-item>
        <template v-if="action === 'record_counts'"><p>按开始盘点时点的实物数量录入，单位均为基础单位，允许填 0。首次盘盈且没有入库价格的商品须填写盘盈单价后审核。</p>
          <div v-for="(line, index) in rows" :key="line.id" class="relation-row">
            <span>{{ line.skuName }} · 账面 {{ line.bookQty }} {{ line.baseUnitName }}</span>
            <el-input v-model="line.inputQty" :aria-label="'实盘数量' + (index + 1)" placeholder="实盘数量" />
            <el-input v-if="session.can('cost:view')" v-model="line.inputPrice" :aria-label="'盘盈单价' + (index + 1)" placeholder="首次盘盈单价（可选）" />
            <StockSelectionFields v-model="line.inputSelection" :sku-id="line.skuId" :warehouse-id="document.warehouseId ?? ''" :mode="Number(line.inputQty) > Number(line.bookQty) ? 'in' : 'out'" serial-only />
          </div>
        </template>
        <template v-if="action === 'receive'"><p>本次实收按基础数量填写，可分批调入；尚未收到的部分保留在途。</p>
          <div v-for="(line, index) in rows" :key="line.id" class="relation-row">
            <span>{{ line.skuName }} · 尚可调入 {{ line.remainingQty }}</span>
            <el-input v-model="line.inputQty" :aria-label="'调入实收' + (index + 1)" />
            <el-button @click="line.inputQty = line.remainingQty ?? '0'">全部调入</el-button>
            <StockSelectionFields v-model="line.inputSelection" :sku-id="line.skuId" :warehouse-id="document.toWarehouseId ?? ''" mode="return" />
          </div>
          <el-checkbox v-model="finalReceipt">本次为最后一批，未收部分转为差异待处理</el-checkbox>
        </template>
        <template v-if="action === 'resolve_diff'"><div v-for="(line, index) in rows" :key="line.id" class="relation-row"><span>{{ line.skuName }} · 差异 {{ line.diffBaseQty }}</span><el-select v-model="line.disposition" :aria-label="'调拨差异处理' + (index + 1)"><el-option value="returned" label="退回调出仓" /><el-option value="lost" label="确认运输损失" /></el-select></div></template>
        <el-form-item v-if="['reject', 'resolve_diff'].includes(action)" label="处理原因" for="stock-command-reason"><el-input id="stock-command-reason" v-model="reason" type="textarea" maxlength="200" /></el-form-item>
      </el-form>
    </fieldset>
    <template #footer><el-button :disabled="busy" @click="emit('close')">关闭</el-button><el-button v-if="uncertain" type="primary" :loading="busy" @click="verify">查询结果</el-button><el-button v-else type="primary" :loading="busy" :disabled="refreshRequired" @click="submit">{{ confirmLabel() }}</el-button></template>
  </el-dialog>
</template>
