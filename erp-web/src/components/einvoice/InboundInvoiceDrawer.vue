<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useInboundInvoicePanel } from '../../hooks/useInboundInvoicePanel'
import {
  inboundMatchLabels,
  inboundMatchTone,
  inboundSourceLabels,
  inboundStatusLabels,
} from '../../types/inbound-invoices'
import { today } from '../../types/sales'
import { formatMoney } from '../../utils/format'
import DocumentLineTable from '../DocumentLineTable.vue'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'
import InboundMatchTable from './InboundMatchTable.vue'

const props = defineProps<{ id?: string }>()
const mobile = window.matchMedia?.('(max-width: 840px)')
const compact = ref(mobile?.matches ?? false)
function resize(event: MediaQueryListEvent): void {
  compact.value = event.matches
}
mobile?.addEventListener('change', resize)
onScopeDispose(() => mobile?.removeEventListener('change', resize))
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const panel = useInboundInvoicePanel(
  props,
  (id) => emit('saved', id),
  () => emit('close'),
)
const {
  document,
  mode,
  draft,
  lines,
  suppliers,
  supplierCursor,
  supplierLoading,
  sources,
  sourceCursor,
  sourceLoading,
  selectionSupplier,
  history,
  historyCursor,
  historyLoading,
  preview,
  previewCurrent,
  error,
  notice,
  loading,
  busy,
  pending,
  locked,
  refreshNeeded,
  canEdit,
  canMatch,
  canVoid,
  load,
  editManual,
  editMatching,
  cancelEdit,
  loadSuppliers,
  chooseSupplier,
  loadSources,
  addSource,
  removeSource,
  coverSource,
  sourceFor,
  loadHistory,
  previewMatching,
  save,
  clear,
  voidInvoice,
  retryWrite,
  close,
  mayLeave,
} = panel
defineExpose({ mayLeave })
const metrics = computed(() => {
  const value = mode.value === 'match' && previewCurrent.value ? preview.value : document.value
  return value
    ? [
        { label: '已分配未税额', value: value.allocatedUntaxedAmount },
        { label: '已分配税额', value: value.allocatedTaxAmount },
        { label: '尚未分配未税额', value: value.remainingUntaxedAmount },
        { label: '尚未分配税额', value: value.remainingTaxAmount },
        { label: '差异绝对值合计', value: value.absoluteDifference },
        { label: '公司容差', value: value.tolerance ?? document.value?.tolerance },
      ]
    : []
})
const result = computed(() =>
  mode.value === 'match' ? (previewCurrent.value ? preview.value : undefined) : document.value,
)
const fields = [
  { key: 'invoiceUntaxedAmount', label: '票面未税额' },
  { key: 'invoiceTaxAmount', label: '票面税额' },
  { key: 'payableUntaxedAmount', label: '覆盖未税额' },
  { key: 'payableTaxAmount', label: '覆盖税额' },
] as const
const title = computed(() =>
  document.value ? '进项发票 · ' + document.value.invoiceNo : '手工登记进项发票',
)
async function requestVoid(): Promise<void> {
  try {
    const value = await ElMessageBox.prompt(
      '作废会解除当前分配并保留历史，请填写原因。',
      '作废手工发票',
      {
        type: 'warning',
        inputType: 'textarea',
        inputValidator: (value: string) =>
          (Boolean(value?.trim()) && value.trim().length <= 200) || '请填写作废原因，最多200字',
        confirmButtonText: '确认作废',
      },
    )
    await voidInvoice(value.value)
  } catch (cause: unknown) {
    if (cause !== 'cancel' && cause !== 'close') error.value = '作废操作未完成，请重试。'
  }
}
</script>
<template>
  <el-drawer
    :model-value="true"
    :title="title"
    direction="rtl"
    size="min(1180px, 96vw)"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :show-close="!locked"
    :close-on-press-escape="!locked"
    :before-close="close"
    @close="emit('close')"
  >
    <div
      class="inbound-panel"
      :class="{ 'is-matching': mode === 'match' }"
      :aria-busy="loading || busy"
    >
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-alert v-if="notice" :title="notice" type="info" :closable="false" />
      <el-alert
        v-if="pending && !busy"
        title="提交结果尚未确认。录入内容已锁定，请重试本次提交确认结果。"
        type="warning"
        :closable="false"
      />
      <el-alert
        v-if="refreshNeeded && !pending"
        title="请先刷新当前详情，再继续更正或核对。"
        type="warning"
        :closable="false"
      />
      <p v-if="loading" role="status">正在读取进项发票…</p>
      <template v-else>
        <section v-if="document && mode !== 'manual'" class="invoice-face" aria-label="发票票面">
          <div class="invoice-state">
            <StatusTag :label="inboundSourceLabels[document.source]" />
            <StatusTag
              :label="inboundStatusLabels[document.status]"
              :tone="document.status === 'normal' ? 'neutral' : 'danger'"
            />
            <StatusTag
              v-if="document.status === 'normal'"
              :label="inboundMatchLabels[document.matchStatus]"
              :tone="inboundMatchTone(document.matchStatus)"
            />
          </div>
          <dl class="invoice-facts">
            <div>
              <dt>销方</dt>
              <dd>{{ document.sellerName }}</dd>
            </div>
            <div>
              <dt>销方纳税人识别号</dt>
              <dd>{{ document.sellerTaxNo }}</dd>
            </div>
            <div>
              <dt>开票日期 / 接收日期</dt>
              <dd>{{ document.invoiceDate }} / {{ document.receivedDate ?? '未记录' }}</dd>
            </div>
            <div>
              <dt>价税合计</dt>
              <dd class="invoice-total"><FinancialAmount :value="document.amount" /> 元</dd>
            </div>
            <div>
              <dt>未税金额</dt>
              <dd><FinancialAmount :value="document.untaxedAmount" /> 元</dd>
            </div>
            <div>
              <dt>税额</dt>
              <dd><FinancialAmount :value="document.taxAmount" /> 元</dd>
            </div>
          </dl>
          <el-alert
            v-if="document.matchNotice"
            :title="document.matchNotice"
            type="warning"
            :closable="false"
          />
          <p v-if="document.remark" class="muted">备注：{{ document.remark }}</p>
        </section>

        <form
          v-if="mode === 'manual'"
          class="manual-fields"
          aria-label="手工进项发票"
          @submit.prevent="save"
        >
          <div class="form-wide">
            <label for="inbound-supplier">供应商</label>
            <el-select
              id="inbound-supplier"
              v-model="draft.supplierId"
              aria-label="供应商"
              filterable
              remote
              :remote-method="loadSuppliers"
              :loading="supplierLoading"
              :disabled="locked"
              placeholder="按供应商名称、编码或税号搜索"
              @change="chooseSupplier"
            >
              <el-option
                v-if="draft.supplierId && !suppliers.some((row) => row.id === draft.supplierId)"
                :value="draft.supplierId"
                :label="document?.sellerName ?? '当前供应商'"
              />
              <el-option
                v-for="supplier in suppliers"
                :key="supplier.id"
                :value="supplier.id"
                :label="supplier.code + ' · ' + supplier.name"
              />
            </el-select>
            <el-button
              v-if="supplierCursor"
              text
              :disabled="locked || supplierLoading"
              @click="loadSuppliers(undefined, true)"
              >加载更多供应商</el-button
            >
          </div>
          <div>
            <label for="inbound-number">发票号码</label
            ><el-input
              id="inbound-number"
              v-model="draft.invoiceNo"
              :disabled="locked"
              maxlength="64"
            />
          </div>
          <div>
            <label for="inbound-seller-tax">销方纳税人识别号</label
            ><el-input
              id="inbound-seller-tax"
              v-model="draft.sellerTaxNo"
              :disabled="locked"
              maxlength="32"
            />
          </div>
          <div class="form-wide">
            <label for="inbound-seller-name">票面销方名称</label
            ><el-input
              id="inbound-seller-name"
              v-model="draft.sellerName"
              :disabled="locked"
              maxlength="100"
            />
          </div>
          <div>
            <label for="inbound-date">开票日期</label
            ><el-input
              id="inbound-date"
              v-model="draft.invoiceDate"
              type="date"
              :max="today()"
              :disabled="locked"
            />
          </div>
          <div>
            <label for="inbound-received-date">接收日期</label
            ><el-input
              id="inbound-received-date"
              v-model="draft.receivedDate"
              type="date"
              :min="draft.invoiceDate"
              :max="today()"
              :disabled="locked"
            />
          </div>
          <div>
            <label for="inbound-amount">价税合计（元）</label
            ><el-input
              id="inbound-amount"
              v-model="draft.amount"
              inputmode="decimal"
              maxlength="25"
              :disabled="locked"
            />
          </div>
          <div>
            <label for="inbound-tax">税额（元）</label
            ><el-input
              id="inbound-tax"
              v-model="draft.taxAmount"
              inputmode="decimal"
              maxlength="25"
              :disabled="locked"
            />
          </div>
          <div class="form-wide">
            <label for="inbound-remark">备注</label
            ><el-input
              id="inbound-remark"
              v-model="draft.remark"
              type="textarea"
              maxlength="200"
              :disabled="locked"
            />
          </div>
          <p class="form-wide muted">
            手工登记独立于数电网关。核对票面税号；供应商资料未填写税号时，可按原票录入。
          </p>
        </form>

        <section v-else-if="document" class="invoice-work" aria-label="采购核对">
          <template v-if="mode === 'match'">
            <div class="section-heading">
              <h3>核对分配</h3>
              <span>{{ lines.length }} / 200 笔应付</span>
            </div>
            <p class="muted">
              票面分配与应付覆盖分别填写。初值仅便于录入；处理尾差时可“覆盖来源余额”，再预览差异。
            </p>
            <DocumentLineTable
              :rows="lines"
              row-key="payableId"
              role="region"
              aria-label="编辑进项核对分配"
            >
              <el-table-column
                label="来源 / 可用额度"
                min-width="240"
                :fixed="compact ? undefined : 'left'"
                ><template #default="{ row }">
                  <strong>{{
                    sourceFor(row.payableId)?.receiptNo ??
                    document.allocations.find((line) => line.payableId === row.payableId)
                      ?.sourceNo ??
                    '应付 #' + row.payableId
                  }}</strong>
                  <p v-if="sourceFor(row.payableId)" class="muted">
                    可用未税 {{ formatMoney(sourceFor(row.payableId)?.availableUntaxedAmount) }} /
                    税额 {{ formatMoney(sourceFor(row.payableId)?.availableTaxAmount) }}
                  </p>
                  <p v-else class="muted">尚未加载此来源，预览时会重新核验。</p>
                </template></el-table-column
              >
              <el-table-column
                v-for="field in fields"
                :key="field.key"
                :label="field.label"
                min-width="155"
                align="right"
                ><template #default="{ row, $index }"
                  ><el-input
                    v-model="row[field.key]"
                    :aria-label="'第' + ($index + 1) + '行' + field.label"
                    inputmode="decimal"
                    maxlength="25"
                    :disabled="locked" /></template
              ></el-table-column>
              <el-table-column label="操作" min-width="150" :fixed="compact ? undefined : 'right'"
                ><template #default="{ row }"
                  ><el-button
                    text
                    :disabled="locked || !sourceFor(row.payableId)"
                    @click="coverSource(row.payableId)"
                    >覆盖来源余额</el-button
                  ><el-button text :disabled="locked" @click="removeSource(row.payableId)"
                    >移除</el-button
                  ></template
                ></el-table-column
              >
            </DocumentLineTable>
            <div class="section-heading">
              <h3>同供应商候选应付</h3>
              <el-button :disabled="locked || sourceLoading" @click="loadSources()"
                >刷新来源额度</el-button
              >
            </div>
            <DocumentLineTable
              :rows="sources"
              aria-label="同供应商候选应付"
              role="region"
              :aria-busy="sourceLoading"
            >
              <el-table-column prop="supplierName" label="供应商" min-width="160" />
              <el-table-column prop="receiptNo" label="采购来源单号" min-width="230" />
              <el-table-column prop="businessDate" label="业务日期" min-width="120" />
              <el-table-column label="可用未税额" min-width="140" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.availableUntaxedAmount" /></template
              ></el-table-column>
              <el-table-column label="可用税额" min-width="130" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.availableTaxAmount" /></template
              ></el-table-column>
              <el-table-column label="选择" width="100"
                ><template #default="{ row }"
                  ><el-button
                    text
                    :disabled="
                      locked ||
                      lines.some((line) => line.payableId === row.id) ||
                      (!!selectionSupplier && selectionSupplier !== row.supplierId)
                    "
                    @click="addSource(row)"
                    >{{
                      lines.some((line) => line.payableId === row.id) ? '已加入' : '加入分配'
                    }}</el-button
                  ></template
                ></el-table-column
              >
            </DocumentLineTable>
            <el-button
              v-if="sourceCursor"
              :loading="sourceLoading"
              :disabled="locked"
              @click="loadSources(true)"
              >加载更多来源</el-button
            >
            <p v-if="!sources.length && !sourceLoading" class="muted">
              当前页暂无可用来源。{{
                sourceCursor ? '可继续加载下一页。' : '请核对供应商税号、采购来源和到票额度。'
              }}
            </p>
          </template>
          <template v-else>
            <h3>当前核对分配</h3>
            <InboundMatchTable v-if="document.allocations.length" :rows="document.allocations" />
            <p v-else class="muted">
              {{
                document.status === 'normal'
                  ? '尚无有效分配，可在“核对采购应付”中选择同一供应商的来源。'
                  : '该发票已作废或红冲，保留原票面和核对历史。'
              }}
            </p>
          </template>
          <section
            v-if="document.status === 'normal' && (mode === 'view' || previewCurrent)"
            class="result-section"
            aria-label="核对结果"
          >
            <h3>{{ mode === 'match' ? '服务器核对预览' : '核对结果' }}</h3>
            <div v-if="result" class="invoice-state">
              <StatusTag
                :label="inboundMatchLabels[result.matchStatus]"
                :tone="inboundMatchTone(result.matchStatus)"
              /><StatusTag
                :label="result.fullyAllocated ? '票面已分配完' : '票面尚未分配完'"
                :tone="result.fullyAllocated ? 'success' : 'info'"
              />
            </div>
            <dl class="result-metrics">
              <div v-for="metric in metrics" :key="metric.label">
                <dt>{{ metric.label }}</dt>
                <dd><FinancialAmount :value="metric.value" /> 元</dd>
              </div>
            </dl>
            <DocumentLineTable
              v-if="mode === 'match' && previewCurrent && preview"
              :rows="preview.items"
              row-key="payableId"
              aria-label="核对差异预览"
              role="region"
            >
              <el-table-column label="采购来源" min-width="230"
                ><template #default="{ row }">{{
                  sourceFor(row.payableId)?.receiptNo ??
                  document.allocations.find((line) => line.payableId === row.payableId)?.sourceNo ??
                  '应付 #' + row.payableId
                }}</template></el-table-column
              >
              <el-table-column label="未税差异" min-width="140" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.untaxedDifference" signed /></template
              ></el-table-column>
              <el-table-column label="税额差异" min-width="140" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.taxDifference" signed /></template
              ></el-table-column>
            </DocumentLineTable>
            <p class="muted">
              容差按所有分配行的未税差异与税额差异绝对值合计检查。核对不改变原应付、资金或总账。
            </p>
          </section>
          <p v-else-if="mode === 'match'" class="muted" role="status">
            分配修改后，请预览服务器计算的剩余额与差异。
          </p>
          <template v-if="mode === 'view'">
            <h3>已释放历史</h3>
            <InboundMatchTable v-if="history.length" :rows="history" historical />
            <p v-else class="muted">暂无已释放的核对记录。</p>
            <el-button
              v-if="historyCursor"
              :loading="historyLoading"
              :disabled="locked"
              @click="loadHistory"
              >加载更多历史</el-button
            >
          </template>
        </section>
      </template>
    </div>
    <template #footer>
      <div class="drawer-actions">
        <el-button :disabled="locked" @click="close()">关闭</el-button>
        <el-button v-if="pending" type="primary" :loading="busy" @click="retryWrite"
          >重试本次提交</el-button
        >
        <template v-else-if="!loading">
          <el-button v-if="document" :disabled="locked" @click="load">刷新详情</el-button>
          <template v-if="mode === 'view'">
            <el-button v-if="canEdit" :disabled="locked" @click="editManual">更正手工票</el-button>
            <el-button v-if="canVoid" type="danger" plain :disabled="locked" @click="requestVoid"
              >作废手工票</el-button
            >
            <el-button
              v-if="canMatch && document?.allocations.length"
              :disabled="locked"
              @click="clear"
              >解除当前核对</el-button
            >
            <el-button v-if="canMatch" type="primary" :disabled="locked" @click="editMatching">{{
              document?.allocations.length ? '编辑核对分配' : '核对采购应付'
            }}</el-button>
          </template>
          <template v-else>
            <el-button :disabled="locked" @click="cancelEdit">放弃修改</el-button>
            <el-button
              v-if="mode === 'match'"
              :loading="busy"
              :disabled="locked || refreshNeeded"
              @click="previewMatching"
              >预览核对</el-button
            >
            <el-button
              type="primary"
              :loading="busy"
              :disabled="locked || refreshNeeded || (mode === 'match' && !previewCurrent)"
              @click="save"
              >{{ mode === 'manual' ? '保存手工票' : '保存核对' }}</el-button
            >
          </template>
        </template>
      </div>
    </template>
  </el-drawer>
</template>
<style scoped>
.inbound-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
}
.invoice-face,
.invoice-work {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-content: start;
  gap: 18px;
  min-width: 0;
}
.inbound-panel > :deep(.el-alert),
.inbound-panel > [role='status'] {
  grid-column: 1 / -1;
}
.inbound-panel.is-matching {
  grid-template-columns: 220px minmax(0, 1fr);
  gap: 24px;
}
.is-matching .invoice-face {
  position: sticky;
  top: 0;
}
.is-matching .invoice-face .invoice-facts {
  grid-template-columns: minmax(0, 1fr);
}
.invoice-state,
.section-heading,
.drawer-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.section-heading {
  justify-content: space-between;
}
h3,
p {
  margin: 0;
}
.muted,
dt {
  color: var(--erp-text-2);
  font-size: 12px;
  line-height: 1.7;
}
.invoice-facts,
.result-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
  margin: 0;
}
dd {
  margin: 8px 0 0;
  overflow-wrap: anywhere;
}
.invoice-total :deep(.financial-amount) {
  font-size: 24px;
}
.result-section {
  border-top: 1px solid var(--erp-border-1);
  padding-top: 18px;
  display: grid;
  gap: 18px;
}
.manual-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
}
.manual-fields label {
  display: block;
  margin-bottom: 8px;
  color: var(--erp-text-2);
}
.form-wide {
  grid-column: 1 / -1;
}
.drawer-actions {
  justify-content: flex-end;
}
.drawer-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}
@media (max-width: 640px) {
  .invoice-facts,
  .result-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .manual-fields {
    grid-template-columns: minmax(0, 1fr);
  }
  .drawer-actions {
    gap: 6px;
  }
}
@media (max-width: 840px) {
  .inbound-panel.is-matching {
    grid-template-columns: minmax(0, 1fr);
  }
  .is-matching .invoice-face {
    position: static;
  }
  .is-matching .invoice-face .invoice-facts {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
