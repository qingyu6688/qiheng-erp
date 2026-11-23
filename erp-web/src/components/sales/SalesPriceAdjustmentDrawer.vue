<script setup lang="ts">
import { computed, inject, ref } from 'vue'
import { useSalesPriceAdjustmentPanel } from '../../hooks/useSalesPriceAdjustmentPanel'
import type { SalesDocument } from '../../types/sales'
import type { SalesRedInvoice } from '../../types/sales-price-adjustments'
import SalesInvoiceDrawer from '../sales/SalesInvoiceDrawer.vue'
import { priceActionLabels, priceStatusLabels } from '../../types/price-adjustments'
import { formatMoney, formatQuantity } from '../../utils/format'
import StatusTag from '../StatusTag.vue'
import FinancialAmount from '../FinancialAmount.vue'
import WorkflowProgressPanel from '../workflow/WorkflowProgressPanel.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import StockDocumentEditor from '../stock-operations/StockDocumentEditor.vue'
import { useSessionStore } from '../../stores/session'
import type { StockDocument } from '../../types/stock-operations'

const props = defineProps<{ id?: string; initialShipment?: SalesDocument }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const readOnlyPreview = inject('erp.readOnlyPreview', false)
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
  previewPosting,
  close,
} = useSalesPriceAdjustmentPanel(
  props,
  (id) => emit('saved', id),
  () => emit('close'),
)

const invoiceId = ref<string>()
const redInvoices = computed<SalesRedInvoice[]>(() => [
  ...new Map(
    (document.value?.lines ?? [])
      .flatMap((line) => line.redInvoices ?? [])
      .map((invoice) => [invoice.invoiceId, invoice]),
  ).values(),
])
const needsRed = computed(() => document.value?.lines.some((line) => line.needsRed) ?? false)
function invoiceChanged(): void {
  void load()
}
function reversalSaved(value: StockDocument): void {
  reversing.value = false
  reversalNotice.value = `冲销申请 ${value.documentNo} 已创建，请在单据冲销中继续审核和过账。`
}
</script>

<template>
  <el-drawer
    class="sales-price-adjustment-drawer"
    :model-value="true"
    :title="document ? '销售调价 · ' + document.documentNo : '新建销售调价单'"
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
          <p class="price-notice">
            补收为正，折让为负；仅调整已签收且尚未退货的数量，原库存和销售成本保持不变。
          </p>
          <el-form label-position="top" :disabled="locked" @submit.prevent="save">
            <div class="price-fields">
              <el-form-item label="客户" required>
                <el-select
                  v-model="draft.partnerId"
                  aria-label="客户"
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
              <el-form-item label="发货仓库" required>
                <el-select
                  v-model="draft.warehouseId"
                  aria-label="发货仓库"
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
              移除明细后可切换客户和仓库。金额和税额由保存后的单据显示。
            </p>
            <div class="price-source-picker">
              <el-select
                v-model="sourceId"
                aria-label="选择原发货行"
                filterable
                :loading="sourceLoading"
                placeholder="选择已签收的原发货行"
              >
                <el-option
                  v-for="source in sources"
                  :key="source.id"
                  :value="source.id"
                  :label="
                    source.shipmentNo +
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
              description="选择客户和仓库后，添加需要调价的原发货行"
            />
            <div v-else class="price-table-scroll">
              <table class="price-table">
                <thead>
                  <tr>
                    <th>原发货行</th>
                    <th>调整方式</th>
                    <th>新价格或调整金额</th>
                    <th class="price-operation">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="(line, index) in draft.lines" :key="line.shipmentLineId">
                    <td>{{ line.label ?? '原发货行' }}</td>
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
                          line.mode === 'unit' ? '按原交易单位填写' : '补收为正，折让为负'
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
              <dt>客户</dt>
              <dd>{{ document.partnerName }}</dd>
            </div>
            <div>
              <dt>原发货仓库</dt>
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
            <table class="price-table" aria-label="销售调价明细">
              <thead>
                <tr>
                  <th scope="col">原发货 / 商品</th>
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
                    <small>{{ line.shipmentNo }} · {{ line.unitName }}</small>
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
            business-type="sales_price_adjustment"
            :business-id="document.id"
            :instance-id="document.wfInstanceId"
          />
          <section v-if="needsRed || redInvoices.length" class="price-reds">
            <h3>原票红字处理</h3>
            <el-alert
              v-if="needsRed"
              title="请先完成原票整票红字，再过账销售折让。待确认、处理中和普通作废均不能代替红字完成。"
              type="warning"
              :closable="false"
            />
            <p v-if="needsRed && !redInvoices.length" class="price-help">
              当前没有原票完整来源的查看权限，请由有权的财务人员处理。
            </p>
            <ul v-if="redInvoices.length" class="price-invoice-list">
              <li v-for="invoice in redInvoices" :key="invoice.invoiceId">
                <strong>{{ invoice.invoiceNo }}</strong>
                <StatusTag
                  :label="invoice.redComplete ? '红字已完成' : '待完成红字'"
                  :tone="invoice.redComplete ? 'success' : 'warning'"
                />
                <el-button
                  link
                  type="primary"
                  :disabled="locked"
                  @click="invoiceId = invoice.invoiceId"
                  >查看原票及红字</el-button
                >
              </li>
            </ul>
          </section>
          <section v-if="preview" class="price-preview">
            <h3>过账预览</h3>
            <p class="price-help">{{ preview.notice }}</p>
            <el-table :data="preview.items" row-key="lineId" empty-text="暂无预览明细">
              <el-table-column prop="shipmentLineId" label="原发货行" min-width="190" />
              <el-table-column label="可调基本数量" min-width="140" align="right"
                ><template #default="{ row }">{{
                  formatQuantity(row.remainingQuantity)
                }}</template></el-table-column
              >
              <el-table-column label="含税调整（元）" min-width="150" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.amount" signed /></template
              ></el-table-column>
              <el-table-column label="税额调整（元）" min-width="140" align="right"
                ><template #default="{ row }"
                  ><FinancialAmount :value="row.taxAmount" signed /></template
              ></el-table-column>
              <el-table-column label="红字要求" min-width="130"
                ><template #default="{ row }"
                  ><StatusTag
                    :label="row.needsRed ? '须先完成红字' : '可继续核对'"
                    :tone="row.needsRed ? 'warning' : 'neutral'" /></template
              ></el-table-column>
            </el-table>
          </section>
          <section v-if="submitting" class="price-submit">
            <h3>提交销售调价审批</h3>
            <WorkflowStarterSelector
              v-model="selections"
              business-type="sales_price_adjustment"
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
          v-if="editing && !readOnlyPreview"
          type="primary"
          :loading="busy"
          :disabled="locked || loading"
          @click="save"
          >保存草稿</el-button
        >
        <template v-else-if="document && !submitting">
          <el-button
            v-if="
              !readOnlyPreview &&
              session.can('shipment:view') &&
              document.allowedActions.includes('update')
            "
            :disabled="locked"
            @click="edit"
            >编辑草稿</el-button
          >
          <el-button
            v-if="
              !readOnlyPreview &&
              ['draft', 'rejected', 'pending', 'approved'].includes(document.status)
            "
            :disabled="locked"
            @click="previewPosting"
            >预览过账金额</el-button
          >
          <el-button
            v-if="
              !readOnlyPreview && document.status === 'posted' && session.can('reversal:create')
            "
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
  <SalesInvoiceDrawer
    v-if="invoiceId"
    :key="invoiceId"
    :id="invoiceId"
    @close="invoiceId = undefined"
    @changed="invoiceChanged"
  />
  <StockDocumentEditor
    v-if="reversing && document"
    kind="document-reversals"
    :preset-source="{
      id: document.id,
      documentNo: document.documentNo,
      sourceType: 'sales_price_adjustment',
    }"
    @close="reversing = false"
    @saved="reversalSaved"
  />
</template>

<style scoped>
:global(.sales-price-adjustment-drawer .el-drawer__header) {
  align-items: flex-start;
}
:global(.sales-price-adjustment-drawer .el-drawer__title) {
  min-width: 0;
  overflow-wrap: anywhere;
}
.price-invoice-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
}
.price-invoice-list strong {
  min-width: 0;
  overflow-wrap: anywhere;
}
.price-invoice-list li {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-light);
}

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
.price-reds h3,
.price-preview h3,
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
