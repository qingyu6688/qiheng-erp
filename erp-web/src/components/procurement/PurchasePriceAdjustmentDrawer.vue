<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePriceAdjustmentPanel } from '../../hooks/usePriceAdjustmentPanel'
import type { ProcurementDocument } from '../../types/procurement'
import type { PriceCostPart } from '../../types/price-adjustments'
import { priceActionLabels, priceStatusLabels } from '../../types/price-adjustments'
import { formatMoney, formatQuantity } from '../../utils/format'
import StatusTag from '../StatusTag.vue'
import FinancialAmount from '../FinancialAmount.vue'
import WorkflowProgressPanel from '../workflow/WorkflowProgressPanel.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import StockDocumentEditor from '../stock-operations/StockDocumentEditor.vue'
import { useSessionStore } from '../../stores/session'
import type { StockDocument } from '../../types/stock-operations'

const props = defineProps<{ id?: string; initialReceipt?: ProcurementDocument }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const session = useSessionStore(),
  reversing = ref(false),
  reversalNotice = ref('')
const {
  document,
  draft,
  metadata,
  sources,
  sourceCursor,
  sourceId,
  sourceLoading,
  error,
  loading,
  busy,
  editing,
  preview,
  pending,
  submitting,
  selections,
  workflowReady,
  locked,
  actions,
  load,
  edit,
  loadSources,
  addSource,
  save,
  write,
  act,
  submit,
  previewCost,
  close,
} = usePriceAdjustmentPanel(
  props,
  (id) => emit('saved', id),
  () => emit('close'),
)

interface CostRow extends PriceCostPart {
  lineNo: number
  skuName: string
}
const costRows = computed<CostRow[]>(() => {
  const current = document.value
  if (!current) return []
  const parts = preview.value
    ? preview.value.items.flatMap((item) =>
        item.allocations.map((part) => ({ ...part, adjustmentLineId: item.lineId })),
      )
    : current.costAllocations
  return parts.map((part) => {
    const index = current.lines.findIndex((line) => line.id === part.adjustmentLineId)
    return { ...part, lineNo: index + 1, skuName: current.lines[index]?.skuName ?? '商品' }
  })
})
function reversalSaved(value: StockDocument): void {
  reversing.value = false
  reversalNotice.value = `冲销申请 ${value.documentNo} 已创建，请在单据冲销中继续审核和过账。`
}
</script>

<template>
  <el-drawer
    :model-value="true"
    :title="document ? '采购调价 · ' + document.documentNo : '新建采购调价单'"
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
    <div class="price-panel" :aria-busy="loading || busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-alert v-if="reversalNotice" :title="reversalNotice" type="success" :closable="false" />
      <el-alert
        v-if="pending && !busy"
        title="提交结果尚未确认，请重试本次请求。"
        type="warning"
        :closable="false"
      />
      <p v-if="loading" role="status">正在读取调价单…</p>
      <template v-else>
        <template v-if="editing">
          <p class="price-notice">补付为正，折让为负；仅调整原收货尚未退货的数量。</p>
          <el-form label-position="top" :disabled="locked" @submit.prevent="save">
            <div class="price-fields">
              <el-form-item label="供应商" required>
                <el-select
                  v-model="draft.partnerId"
                  aria-label="供应商"
                  filterable
                  :disabled="draft.lines.length > 0"
                >
                  <el-option
                    v-for="partner in metadata.partners"
                    :key="partner.id"
                    :value="partner.id"
                    :label="partner.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="收货仓库" required>
                <el-select
                  v-model="draft.warehouseId"
                  aria-label="收货仓库"
                  :disabled="draft.lines.length > 0"
                >
                  <el-option
                    v-for="warehouse in metadata.warehouses"
                    :key="warehouse.id"
                    :value="warehouse.id"
                    :label="warehouse.name"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="调价日期" required>
                <el-input v-model="draft.businessDate" aria-label="调价日期" type="date" />
              </el-form-item>
              <el-form-item label="调价原因" required>
                <el-input
                  v-model="draft.reason"
                  aria-label="调价原因"
                  maxlength="500"
                  placeholder="填写本次结算调价的依据"
                />
              </el-form-item>
            </div>
            <p v-if="draft.lines.length" class="price-help">
              移除明细后可切换供应商和仓库。金额和税额由保存后的单据显示。
            </p>
            <div class="price-source-picker">
              <el-select
                v-model="sourceId"
                aria-label="选择原收货行"
                filterable
                :loading="sourceLoading"
                placeholder="选择已过账的原收货行"
              >
                <el-option
                  v-for="source in sources"
                  :key="source.id"
                  :value="source.id"
                  :label="
                    source.receiptNo +
                    ' · ' +
                    source.skuName +
                    ' · 可调 ' +
                    formatQuantity(source.returnableQty) +
                    ' 基本单位'
                  "
                />
              </el-select>
              <el-button :disabled="!sourceId" @click="addSource">添加明细</el-button>
              <el-button v-if="sourceCursor" :loading="sourceLoading" @click="loadSources(true)"
                >更多来源</el-button
              >
            </div>
            <el-empty
              v-if="!draft.lines.length && !sourceLoading"
              description="选择供应商和仓库后，添加需要调价的原收货行"
            />
            <div v-else class="price-table-scroll">
              <table class="price-table">
                <thead>
                  <tr>
                    <th>原收货行</th>
                    <th>调整方式</th>
                    <th>新价格或调整金额</th>
                    <th class="price-operation">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(line, index) in draft.lines" :key="line.receiptLineId">
                    <td>{{ line.label ?? '原收货行' }}</td>
                    <td>
                      <el-select
                        v-model="line.mode"
                        :aria-label="'第' + (index + 1) + '行调整方式'"
                      >
                        <el-option value="unit" label="新含税单价" /><el-option
                          value="amount"
                          label="含税调整金额"
                        />
                      </el-select>
                    </td>
                    <td>
                      <el-input
                        v-model="line.value"
                        inputmode="decimal"
                        maxlength="32"
                        :aria-label="
                          '第' +
                          (index + 1) +
                          '行' +
                          (line.mode === 'unit' ? '新含税单价' : '含税调整金额')
                        "
                        :placeholder="
                          line.mode === 'unit' ? '按原交易单位填写' : '补付为正，折让为负'
                        "
                      />
                    </td>
                    <td>
                      <el-button link type="danger" @click="draft.lines.splice(index, 1)"
                        >移除</el-button
                      >
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </el-form>
        </template>

        <template v-else-if="document">
          <div class="price-document-heading">
            <StatusTag
              :label="priceStatusLabels[document.status]"
              :tone="
                document.status === 'posted'
                  ? 'success'
                  : document.status === 'pending'
                    ? 'warning'
                    : 'neutral'
              "
            />
            <el-button :disabled="locked" @click="load">刷新单据</el-button>
          </div>
          <dl class="price-description">
            <div>
              <dt>供应商</dt>
              <dd>{{ document.partnerName }}</dd>
            </div>
            <div>
              <dt>原收货仓库</dt>
              <dd>{{ document.warehouseName }}</dd>
            </div>
            <div>
              <dt>调价日期</dt>
              <dd>{{ document.businessDate }}</dd>
            </div>
            <div class="price-total">
              <dt>含税调整</dt>
              <dd>
                <FinancialAmount :value="document.amount" signed /><span class="price-unit"
                  >元</span
                >
              </dd>
            </div>
            <div>
              <dt>未税调整</dt>
              <dd>{{ formatMoney(document.untaxedAmount) }} 元</dd>
            </div>
            <div>
              <dt>税额调整</dt>
              <dd>{{ formatMoney(document.taxAmount) }} 元</dd>
            </div>
          </dl>
          <p class="price-reason"><strong>调价依据：</strong>{{ document.reason }}</p>
          <div class="price-table-scroll">
            <table class="price-table" aria-label="采购调价明细">
              <thead>
                <tr>
                  <th scope="col">原收货 / 商品</th>
                  <th scope="col" class="price-number">剩余基本数量</th>
                  <th scope="col" class="price-number">原含税单价</th>
                  <th scope="col" class="price-number price-impact-cell">新含税单价</th>
                  <th scope="col" class="price-number price-impact-cell">含税调整（元）</th>
                  <th scope="col" class="price-number">税额调整（元）</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="line in document.lines" :key="line.id">
                  <td>
                    <strong class="price-item-name">{{ line.skuName }}</strong>
                    <small>{{ line.receiptNo }} · {{ line.unitName }}</small>
                  </td>
                  <td class="price-number">{{ formatQuantity(line.sourceBaseQty) }}</td>
                  <td class="price-number price-original">
                    {{ formatQuantity(line.oldUnitPrice) }}
                  </td>
                  <td class="price-number price-impact-cell">
                    <span class="price-change-arrow" aria-hidden="true">→</span>
                    <strong class="price-new-value">{{ formatQuantity(line.newUnitPrice) }}</strong>
                  </td>
                  <td class="price-number price-impact-cell">
                    <FinancialAmount :value="line.amount" signed />
                  </td>
                  <td class="price-number"><FinancialAmount :value="line.taxAmount" signed /></td>
                </tr>
              </tbody>
            </table>
          </div>
          <WorkflowProgressPanel
            business-type="price_adjustment"
            :business-id="document.id"
            :instance-id="document.wfInstanceId"
          />
          <section v-if="costRows.length" class="price-costs">
            <h3>{{ preview ? '成本分配预览' : '成本处理结果' }}</h3>
            <p v-if="preview" class="price-help">{{ preview.notice }}</p>
            <div class="price-table-scroll">
              <table class="price-table" aria-label="调价成本处理明细">
                <thead>
                  <tr>
                    <th scope="col">明细</th>
                    <th scope="col">成本归属仓</th>
                    <th scope="col" class="price-number">在库份额（基本数量）</th>
                    <th scope="col" class="price-number price-impact-cell">调整库存成本（元）</th>
                    <th scope="col" class="price-number price-impact-cell">已出库差异（元）</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="part in costRows" :key="part.lineNo + '-' + part.warehouseId">
                    <td>
                      <span class="price-line-no">{{ part.lineNo }}</span><strong
                        class="price-item-name"
                        >{{ part.skuName }}</strong
                      >
                    </td>
                    <td>{{ part.warehouseName }}</td>
                    <td class="price-number">{{ formatQuantity(part.inStockQty) }}</td>
                    <td class="price-number price-impact-cell">
                      <FinancialAmount :value="part.stockAmount" signed />
                    </td>
                    <td class="price-number price-impact-cell">
                      <FinancialAmount :value="part.issuedAmount" signed />
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </section>
          <section v-if="submitting" class="price-submit">
            <h3>提交采购调价审批</h3>
            <WorkflowStarterSelector
              v-model="selections"
              business-type="price_adjustment"
              :business-id="document.id"
              :disabled="locked"
              @ready="workflowReady = $event"
            />
            <el-button type="primary" :disabled="!workflowReady || locked" @click="submit"
              >确认提交审批</el-button
            >
            <el-button :disabled="locked" @click="submitting = false">返回详情</el-button>
          </section>
        </template>
      </template>
    </div>
    <template #footer>
      <div class="price-footer">
        <el-button v-if="pending && !busy" type="primary" @click="write">重试本次请求</el-button>
        <el-button
          v-if="editing"
          type="primary"
          :loading="busy"
          :disabled="locked || loading"
          @click="save"
          >保存草稿</el-button
        >
        <template v-else-if="document && !submitting">
          <el-button
            v-if="document.allowedActions.includes('update')"
            :disabled="locked"
            @click="edit"
            >编辑草稿</el-button
          >
          <el-button v-if="document.canPreviewCost" :disabled="locked" @click="previewCost"
            >预览成本分配</el-button
          >
          <el-button
            v-if="document.status === 'posted' && session.can('reversal:create')"
            :disabled="locked"
            @click="reversing = true"
            >申请冲销</el-button
          >
          <el-button
            v-for="action in actions"
            :key="action"
            :type="
              action === 'post' || action === 'approve' || action === 'submit'
                ? 'primary'
                : undefined
            "
            :disabled="locked"
            @click="act(action)"
            >{{ priceActionLabels[action] }}</el-button
          >
        </template>
        <el-button :disabled="locked" @click="close()">关闭</el-button>
      </div>
    </template>
  </el-drawer>
  <StockDocumentEditor
    v-if="reversing && document"
    kind="document-reversals"
    :preset-source="{
      id: document.id,
      documentNo: document.documentNo,
      sourceType: 'price_adjustment',
    }"
    @close="reversing = false"
    @saved="reversalSaved"
  />
</template>

<style scoped>
.price-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 20px;
  min-width: 0;
}
.price-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 20px;
}
.price-fields .el-select,
.price-source-picker .el-select {
  width: 100%;
}
.price-source-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-block: 20px;
}
.price-source-picker > .el-select {
  flex: 1;
  min-width: 180px;
}
.price-table-scroll {
  min-width: 0;
  max-width: 100%;
  overflow-x: auto;
}
.price-table {
  border-collapse: collapse;
  width: 100%;
  min-width: 700px;
}
.price-table th {
  font-size: 12px;
  font-weight: 500;
  color: var(--erp-text-2);
  background: var(--erp-fill-2);
}
.price-table th,
.price-table td {
  text-align: left;
  padding: 12px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.price-table td:first-child {
  min-width: 190px;
}
.price-table td > .el-select {
  min-width: 150px;
}
.price-table td > .el-input {
  min-width: 180px;
}
.price-table small {
  display: block;
  margin-top: 5px;
  color: var(--erp-text-2);
}
.price-item-name {
  font-weight: 600;
  color: var(--erp-text-1);
}
.price-line-no {
  margin-right: 8px;
  color: var(--erp-text-2);
  font-size: 12px;
}
.price-table .price-impact-cell {
  background: var(--erp-primary-1);
}
.price-table th.price-impact-cell,
.price-new-value {
  color: var(--erp-primary-7);
}
.price-new-value {
  font-size: 17px;
  font-weight: 650;
}
.price-original,
.price-change-arrow {
  color: var(--erp-text-2);
}
.price-change-arrow {
  margin-right: 10px;
}
.price-table tbody tr:hover td:not(.price-impact-cell) {
  background: var(--erp-fill-1);
}
.price-table .price-number {
  text-align: right;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.price-notice,
.price-help {
  color: var(--erp-text-2);
  margin: 0;
  line-height: 1.7;
}
.price-help {
  font-size: 12px;
}
.price-document-heading,
.price-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.price-footer {
  justify-content: flex-end;
}
.price-description {
  margin: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}
.price-description dt {
  color: var(--erp-text-2);
  font-size: 12px;
}
.price-description dd {
  margin: 7px 0 0;
  font-weight: 500;
  overflow-wrap: anywhere;
}
.price-total :deep(.financial-amount) {
  font-size: 24px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.price-unit {
  margin-left: 6px;
  color: var(--erp-text-2);
  font-size: 12px;
  font-weight: 400;
}
.price-reason {
  margin: 0;
  line-height: 1.7;
  white-space: pre-wrap;
}
.price-costs h3,
.price-submit h3 {
  font-size: 15px;
  margin: 0 0 12px;
}
.price-submit {
  border-top: 1px solid var(--el-border-color-light);
  padding-top: 20px;
}
@media (max-width: 680px) {
  .price-fields {
    grid-template-columns: 1fr;
  }
  .price-description {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .price-source-picker {
    flex-wrap: wrap;
  }
}
</style>
