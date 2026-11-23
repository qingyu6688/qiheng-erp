<script setup lang="ts">
import { computed, ref } from 'vue'
import { usePurchaseFeePanel } from '../../hooks/usePurchaseFeePanel'
import { useSessionStore } from '../../stores/session'
import type { ProcurementDocument } from '../../types/procurement'
import type { StockDocument } from '../../types/stock-operations'
import {
  allocationMethodLabels,
  feeActionLabels,
  feeStatusLabels,
  feeTypeLabels,
} from '../../types/purchase-fees'
import { formatMoney, formatQuantity } from '../../utils/format'
import StatusTag from '../StatusTag.vue'
import WorkflowProgressPanel from '../workflow/WorkflowProgressPanel.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import StockDocumentEditor from '../stock-operations/StockDocumentEditor.vue'

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
} = usePurchaseFeePanel(
  props,
  (id) => emit('saved', id),
  () => emit('close'),
)
const costRows = computed(() => {
  const current = document.value
  if (!current) return []
  const parts = preview.value
    ? preview.value.items.flatMap((item) =>
        item.allocations.map((part) => ({ ...part, allocationId: item.allocationId })),
      )
    : current.costAllocations
  return parts.map((part) => ({
    ...part,
    skuName: current.allocations.find((line) => line.id === part.allocationId)?.skuName ?? '商品',
  }))
})
function reversalSaved(value: StockDocument): void {
  reversing.value = false
  reversalNotice.value = `冲销申请 ${value.documentNo} 已创建，请在单据冲销中继续审核和过账。`
}
</script>

<template>
  <el-drawer
    :model-value="true"
    :title="document ? '采购费用 · ' + document.documentNo : '新建采购费用单'"
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
    <div class="fee-panel" :aria-busy="loading || busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-alert v-if="reversalNotice" :title="reversalNotice" type="success" :closable="false" />
      <el-alert
        v-if="pending && !busy"
        title="提交结果尚未确认，请重试本次请求。"
        type="warning"
        :closable="false"
      />
      <p v-if="loading" role="status">正在读取费用单…</p>
      <template v-else-if="editing">
        <el-alert
          v-if="!metadata.costEnabled"
          title="公司已关闭采购费用资本化，请使用费用付款登记。"
          type="warning"
          :closable="false"
        />
        <p class="fee-help">费用可由其他承运商收取；选择收货来源后，按金额、基本数量或重量分摊。</p>
        <el-form label-position="top" :disabled="locked" @submit.prevent="save">
          <div class="fee-fields">
            <el-form-item label="承运商 / 费用供应商" required>
              <el-select v-model="draft.partnerId" aria-label="费用供应商" filterable>
                <el-option
                  v-for="partner in metadata.partners"
                  :key="partner.id"
                  :value="partner.id"
                  :label="partner.name"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="费用日期" required
              ><el-input v-model="draft.businessDate" aria-label="费用日期" type="date"
            /></el-form-item>
            <el-form-item label="分摊方式" required>
              <el-select v-model="draft.allocationMethod" aria-label="分摊方式">
                <el-option
                  v-for="(label, method) in allocationMethodLabels"
                  :key="method"
                  :value="method"
                  :label="label"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="费用原因" required
              ><el-input v-model="draft.reason" aria-label="费用原因" maxlength="500"
            /></el-form-item>
          </div>
          <section class="fee-section">
            <div class="fee-section-heading">
              <h3>费用明细</h3>
              <el-button
                :disabled="draft.fees.length >= 50"
                @click="
                  draft.fees.push({ feeType: 'freight', amount: '', taxRate: '0', remark: '' })
                "
                >添加费用</el-button
              >
            </div>
            <div v-for="(fee, index) in draft.fees" :key="index" class="fee-line">
              <el-select v-model="fee.feeType" :aria-label="'第' + (index + 1) + '行费用类型'"
                ><el-option
                  v-for="(label, type) in feeTypeLabels"
                  :key="type"
                  :value="type"
                  :label="label"
              /></el-select>
              <el-input
                v-model="fee.amount"
                inputmode="decimal"
                :aria-label="'第' + (index + 1) + '行含税费用'"
                placeholder="含税费用（元）"
                maxlength="25"
              />
              <el-input
                v-model="fee.taxRate"
                inputmode="decimal"
                :aria-label="'第' + (index + 1) + '行税率'"
                placeholder="税率，如 0.09"
                maxlength="8"
              />
              <el-input
                v-model="fee.remark"
                :aria-label="'第' + (index + 1) + '行费用备注'"
                placeholder="费用备注"
                maxlength="200"
              />
              <el-button link type="danger" @click="draft.fees.splice(index, 1)"
                >移除费用</el-button
              >
            </div>
          </section>
          <section class="fee-section">
            <h3>收货来源</h3>
            <div class="fee-source-picker">
              <el-select
                v-model="sourceId"
                aria-label="收货来源"
                filterable
                :loading="sourceLoading"
                placeholder="选择收货单与商品"
              >
                <el-option
                  v-for="source in sources"
                  :key="source.id"
                  :value="source.id"
                  :label="
                    source.receiptNo +
                    ' · ' +
                    source.skuName +
                    ' · 可分摊数量 ' +
                    formatQuantity(source.returnableQty)
                  "
                />
              </el-select>
              <el-button :disabled="!sourceId" @click="addSource">添加来源</el-button>
              <el-button v-if="sourceCursor" :loading="sourceLoading" @click="loadSources(true)"
                >更多来源</el-button
              >
            </div>
            <ul class="fee-sources">
              <li v-for="(source, index) in draft.sources" :key="source.receiptLineId">
                <span>{{ source.label }}</span
                ><el-button link type="danger" @click="draft.sources.splice(index, 1)"
                  >移除来源</el-button
                >
              </li>
            </ul>
            <el-empty
              v-if="!draft.sources.length"
              description="请选择至少一个已过账收货行"
              :image-size="54"
            />
          </section>
        </el-form>
      </template>
      <template v-else-if="document">
        <div class="fee-section-heading">
          <StatusTag :label="feeStatusLabels[document.status]" /><el-button
            :disabled="locked"
            @click="load"
            >刷新单据</el-button
          >
        </div>
        <el-alert
          v-if="document.sourceKind === 'receipt'"
          title="随采购收货自动确认，审批依据为采购订单；通过原收货单整体冲销。"
          type="info"
          :closable="false"
        />
        <dl class="fee-description">
          <div>
            <dt>费用供应商</dt>
            <dd>{{ document.partnerName }}</dd>
          </div>
          <div>
            <dt>费用日期</dt>
            <dd>{{ document.businessDate }}</dd>
          </div>
          <div>
            <dt>分摊方式</dt>
            <dd>{{ allocationMethodLabels[document.allocationMethod] }}</dd>
          </div>
          <div>
            <dt>含税费用</dt>
            <dd>{{ formatMoney(document.amount) }} 元</dd>
          </div>
          <div>
            <dt>未税费用</dt>
            <dd>{{ formatMoney(document.untaxedAmount) }} 元</dd>
          </div>
          <div>
            <dt>税额</dt>
            <dd>{{ formatMoney(document.taxAmount) }} 元</dd>
          </div>
          <div v-if="document.sourceKind === 'manual'">
            <dt>应付到期日</dt>
            <dd>{{ document.dueDate ?? '不设账期' }}</dd>
          </div>
        </dl>
        <p><strong>费用原因：</strong>{{ document.reason }}</p>
        <section class="fee-section">
          <h3>费用明细</h3>
          <el-table :data="document.lines" border>
            <el-table-column label="费用类型" min-width="110"
              ><template #default="{ row }">{{
                feeTypeLabels[row.feeType as keyof typeof feeTypeLabels]
              }}</template></el-table-column
            >
            <el-table-column label="含税费用" align="right" min-width="110"
              ><template #default="{ row }">{{
                formatMoney(row.amount)
              }}</template></el-table-column
            >
            <el-table-column label="税额" align="right" min-width="100"
              ><template #default="{ row }">{{
                formatMoney(row.taxAmount)
              }}</template></el-table-column
            >
            <el-table-column prop="dueDate" label="应付到期日" min-width="120" /><el-table-column
              prop="remark"
              label="备注"
              min-width="140"
            />
          </el-table>
        </section>
        <section class="fee-section">
          <h3>收货分摊</h3>
          <el-table :data="document.allocations" border>
            <el-table-column prop="receiptNo" label="原收货" min-width="175" /><el-table-column
              prop="skuName"
              label="商品"
              min-width="130"
            />
            <el-table-column prop="sourceWarehouseName" label="原收货仓" min-width="120" />
            <el-table-column label="剩余基本数量" align="right" min-width="120"
              ><template #default="{ row }">{{
                formatQuantity(row.sourceBaseQty)
              }}</template></el-table-column
            >
            <el-table-column label="含税分摊" align="right" min-width="105"
              ><template #default="{ row }">{{
                formatMoney(row.amount)
              }}</template></el-table-column
            >
            <el-table-column label="税额" align="right" min-width="90"
              ><template #default="{ row }">{{
                formatMoney(row.taxAmount)
              }}</template></el-table-column
            >
            <el-table-column label="费用成本" align="right" min-width="105"
              ><template #default="{ row }">{{
                formatMoney(row.costAmount)
              }}</template></el-table-column
            >
          </el-table>
        </section>
        <WorkflowProgressPanel
          v-if="document.sourceKind === 'manual'"
          business-type="purchase_fee_doc"
          :business-id="document.id"
          :instance-id="document.wfInstanceId"
        />
        <section v-if="costRows.length" class="fee-section">
          <h3>{{ preview ? '成本分配预览' : '成本处理结果' }}</h3>
          <p v-if="preview" class="fee-help">{{ preview.notice }}</p>
          <el-table :data="costRows" border>
            <el-table-column prop="skuName" label="商品" min-width="130" /><el-table-column
              prop="warehouseName"
              label="成本归属仓"
              min-width="140"
            />
            <el-table-column label="在库基本数量" align="right" min-width="125"
              ><template #default="{ row }">{{
                formatQuantity(row.inStockQty)
              }}</template></el-table-column
            >
            <el-table-column label="计入库存成本" align="right" min-width="125"
              ><template #default="{ row }">{{
                formatMoney(row.stockAmount)
              }}</template></el-table-column
            >
            <el-table-column label="已出库差异" align="right" min-width="120"
              ><template #default="{ row }">{{
                formatMoney(row.issuedAmount)
              }}</template></el-table-column
            >
          </el-table>
        </section>
        <section v-if="submitting" class="fee-section">
          <h3>提交采购费用审批</h3>
          <WorkflowStarterSelector
            v-model="selections"
            business-type="purchase_fee_doc"
            :business-id="document.id"
            :disabled="locked"
            @ready="workflowReady = $event"
          />
          <el-button type="primary" :disabled="!workflowReady || locked" @click="submit"
            >确认提交审批</el-button
          ><el-button :disabled="locked" @click="submitting = false">返回详情</el-button>
        </section>
      </template>
    </div>
    <template #footer
      ><div class="fee-footer">
        <el-button v-if="pending && !busy" type="primary" @click="write">重试本次请求</el-button>
        <el-button
          v-if="editing"
          type="primary"
          :loading="busy"
          :disabled="locked || loading || !metadata.costEnabled"
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
            v-if="
              document.sourceKind === 'manual' &&
              document.status === 'posted' &&
              session.can('reversal:create')
            "
            :disabled="locked"
            @click="reversing = true"
            >申请冲销</el-button
          >
          <el-button
            v-for="action in actions"
            :key="action"
            :type="['post', 'approve', 'submit'].includes(action) ? 'primary' : undefined"
            :disabled="locked"
            @click="act(action)"
            >{{ feeActionLabels[action] }}</el-button
          >
        </template>
        <el-button :disabled="locked" @click="close()">关闭</el-button>
      </div></template
    >
  </el-drawer>
  <StockDocumentEditor
    v-if="reversing && document"
    kind="document-reversals"
    :preset-source="{
      id: document.id,
      documentNo: document.documentNo,
      sourceType: 'purchase_fee_doc',
    }"
    @close="reversing = false"
    @saved="reversalSaved"
  />
</template>

<style scoped>
.fee-panel {
  display: grid;
  gap: 18px;
}
.fee-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 20px;
}
.fee-fields .el-select {
  width: 100%;
}
.fee-help {
  color: var(--text-secondary);
  line-height: 1.7;
}
.fee-section {
  margin-top: 20px;
}
.fee-section h3 {
  margin: 0 0 14px;
  font-size: 15px;
}
.fee-section-heading,
.fee-source-picker,
.fee-footer {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.fee-section-heading {
  justify-content: space-between;
}
.fee-line {
  display: grid;
  grid-template-columns: 130px 1fr 130px 1.3fr auto;
  gap: 12px;
  margin: 12px 0;
}
.fee-source-picker .el-select {
  flex: 1;
  min-width: 260px;
}
.fee-sources {
  list-style: none;
  padding: 0;
}
.fee-sources li {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--border-color);
}
.fee-description {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin: 0;
}
.fee-description dt {
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 7px;
}
.fee-description dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.fee-footer {
  justify-content: flex-end;
}
@media (max-width: 720px) {
  .fee-fields,
  .fee-description {
    grid-template-columns: 1fr;
  }
  .fee-line {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
