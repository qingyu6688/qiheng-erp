<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDatePicker } from 'element-plus/es/components/date-picker/index.mjs'
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs'
import { ElSkeleton } from 'element-plus/es/components/skeleton/index.mjs'
import 'element-plus/theme-chalk/el-date-picker.css'
import 'element-plus/theme-chalk/el-empty.css'
import 'element-plus/theme-chalk/el-skeleton.css'
import { useSalesQuotePanel } from '../../hooks/useSalesQuotePanel'
import { quoteActionLabels, quoteStatusLabels } from '../../types/sales-quotes'
import type { SalesQuoteAction } from '../../types/sales-quotes'
import { formatMoney, formatQuantity } from '../../utils/format'
import { useSessionStore } from '../../stores/session'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'
import WorkflowProgressPanel from '../workflow/WorkflowProgressPanel.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'
import SalesOrderSourcePreview from './SalesOrderSourcePreview.vue'

const props = defineProps<{ id?: string; readOnly?: boolean }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const session = useSessionStore()
const panel = useSalesQuotePanel(() => props.id, (id) => emit('saved', id), () => emit('close'), () => Boolean(props.readOnly))
const { document, draft, pricing, metadata, units, editing, loading, busy, calculating, error, pending, locked, submitting, selections, workflowReady } = panel
const showingOrder = ref(false)
const needsPriceApproval = computed(() => Boolean(document.value?.belowMinPrice && document.value.belowMinPricePolicy === 'approval'))
const priceBlocked = computed(() => Boolean(document.value?.belowMinPrice && document.value.belowMinPricePolicy === 'block'))
const actions = computed<SalesQuoteAction[]>(() => document.value?.allowedActions.filter((action): action is SalesQuoteAction => action !== 'update' && panel.can(action) && (!priceBlocked.value || action === 'void')) ?? [])
function addLine(): void {
  if (draft.value.order.lines.length >= 500 || locked.value) return
  draft.value.order.lines.push({ skuId: '', unitId: '', warehouseId: metadata.value.warehouses[0]?.id, qty: '1' })
}
function addFee(): void {
  if (locked.value || (draft.value.order.fees?.length ?? 0) >= 50) return
  draft.value.order.fees ??= []
  draft.value.order.fees.push({ feeType: 'freight', bearer: 'buyer', amount: '' })
}
function lineName(index: number): string {
  const line = document.value?.lines.find((line) => line.lineNo === index + 1)
  return line?.skuName ?? metadata.value.skus.find((sku) => sku.id === draft.value.order.lines[index]?.skuId)?.name ?? '商品'
}
</script>

<template>
  <el-drawer :model-value="true" :title="document ? '销售报价 · ' + document.documentNo : '新建销售报价'" direction="rtl"
    size="min(1180px, 96vw)" append-to-body destroy-on-close :close-on-click-modal="false" :close-on-press-escape="!locked"
    :show-close="!locked" :before-close="panel.close" @close="emit('close')">
    <div class="quote-panel" :aria-busy="loading || busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-alert v-if="pending && !busy" title="提交结果尚未确认，请核对结果或重试原请求。" type="warning" :closable="false">
        <el-button @click="panel.verify">核对结果</el-button><el-button @click="panel.retry">重试原请求</el-button>
      </el-alert>
      <el-skeleton v-if="loading" :rows="9" animated />
      <template v-else-if="document || !id">
        <div v-if="document && !editing" class="quote-heading"><StatusTag :label="quoteStatusLabels[document.status]" :tone="document.status === 'issued' ? 'success' : 'neutral'" /><el-button :disabled="locked" @click="panel.load">刷新报价</el-button></div>
        <dl class="quote-summary" aria-label="报价金额摘要">
          <div class="quote-total"><dt>价税合计</dt><dd><FinancialAmount :value="pricing?.totalAmount" /><small>元</small></dd></div>
          <div><dt>未税金额</dt><dd>{{ formatMoney(pricing?.untaxedAmount) }}<small>元</small></dd></div>
          <div><dt>税额</dt><dd>{{ formatMoney(pricing?.taxAmount) }}<small>元</small></dd></div>
          <div><dt>报价有效期至</dt><dd class="quote-deadline">{{ editing ? draft.validTo : document?.validTo }}</dd></div>
        </dl>
        <el-alert v-if="document?.belowMinPrice && !editing" :type="priceBlocked ? 'error' : needsPriceApproval ? 'warning' : 'info'" :closable="false"
          :title="priceBlocked ? '当前公司禁止低于最低售价的报价，请修改价格后重新确认。' : needsPriceApproval ? '本报价低于最低售价，生效前需人工价格复核。' : '本报价低于最低售价，当前公司按提示规则处理。'" />
        <el-form v-if="editing && !readOnly" label-position="top" :disabled="locked" class="quote-form">
          <div class="quote-fields">
            <el-form-item label="客户" required><el-select v-model="draft.order.customerId" filterable remote :remote-method="panel.loadMetadata" aria-label="报价客户"><el-option v-for="item in metadata.customers" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
            <el-form-item label="报价日期" required><el-date-picker v-model="draft.order.businessDate" type="date" value-format="YYYY-MM-DD" aria-label="报价日期" /></el-form-item>
            <el-form-item label="有效期至" required><el-date-picker v-model="draft.validTo" type="date" value-format="YYYY-MM-DD" aria-label="报价有效期" /></el-form-item>
            <el-form-item label="交货日期"><el-date-picker v-model="draft.order.deliveryDate" type="date" value-format="YYYY-MM-DD" aria-label="报价交货日期" /></el-form-item>
            <el-form-item v-if="session.can('crm_business:view')" label="关联商机"><el-select v-model="draft.opportunityId" clearable filterable placeholder="可选，先选择客户" aria-label="关联商机"><el-option v-for="item in metadata.opportunities" :key="item.id" :value="item.id" :label="item.name" /></el-select></el-form-item>
            <el-form-item label="报价口径"><el-switch v-model="draft.order.priceTaxInclusive" active-text="含税" inactive-text="未税" /></el-form-item>
          </div>
          <h3>报价明细</h3>
          <div class="quote-lines-wrap"><table class="quote-lines" aria-label="报价商品明细"><thead><tr><th>商品</th><th>仓库</th><th>单位</th><th>数量</th><th>单价</th><th>行优惠</th><th>操作</th></tr></thead><tbody>
            <tr v-for="(line, index) in draft.order.lines" :key="index">
              <td><el-select v-model="line.skuId" filterable remote :remote-method="panel.loadMetadata" :aria-label="'报价商品' + (index + 1)" @change="panel.selectSku(index)"><el-option v-for="sku in metadata.skus" :key="sku.id" :label="sku.name" :value="sku.id" /></el-select></td>
              <td><el-select v-model="line.warehouseId" :aria-label="'报价仓库' + (index + 1)"><el-option v-for="warehouse in metadata.warehouses" :key="warehouse.id" :label="warehouse.name" :value="warehouse.id" /></el-select></td>
              <td><el-select v-model="line.unitId" :aria-label="'报价单位' + (index + 1)"><el-option v-for="unit in units[line.skuId] ?? []" :key="unit.unitId" :label="unit.unitName" :value="unit.unitId" /></el-select></td>
              <td><el-input v-model="line.qty" :aria-label="'报价数量' + (index + 1)" /></td>
              <td><el-input v-model="line.unitPrice" placeholder="留空取默认价" :aria-label="'报价单价' + (index + 1)" /><small v-if="pricing?.lines[index]">计算单价 {{ formatMoney(pricing.lines[index]?.unitPrice) }}</small></td>
              <td><el-input v-model="line.lineDiscountAmount" placeholder="0" :aria-label="'报价行优惠' + (index + 1)" /></td>
              <td><el-button link type="danger" :disabled="draft.order.lines.length <= 1" @click="draft.order.lines.splice(index, 1)">移除</el-button></td>
            </tr>
          </tbody></table></div>
          <el-button :disabled="draft.order.lines.length >= 500" @click="addLine">增加商品</el-button>
          <div class="quote-fields quote-discount"><el-form-item label="整单优惠"><el-input v-model="draft.order.orderDiscountAmount" aria-label="报价整单优惠" /></el-form-item><el-form-item label="备注"><el-input v-model="draft.order.remark" maxlength="500" /></el-form-item></div>
          <h3>费用</h3>
          <div v-for="(fee, index) in draft.order.fees ?? []" :key="index" class="quote-fee">
            <el-select v-model="fee.feeType" aria-label="费用类型"><el-option label="运费" value="freight" /><el-option label="其他费用" value="other" /></el-select>
            <el-select v-model="fee.bearer" aria-label="费用承担方"><el-option label="买方承担" value="buyer" /><el-option label="卖方承担" value="seller" /></el-select>
            <el-input v-model="fee.amount" aria-label="费用金额" placeholder="金额" /><el-button link type="danger" @click="draft.order.fees?.splice(index, 1)">移除</el-button>
          </div>
          <el-button :disabled="(draft.order.fees?.length ?? 0) >= 50" @click="addFee">增加费用</el-button>
          <p class="quote-note" :aria-busy="calculating">{{ calculating ? '正在计算报价…' : '报价按当前价格规则试算；确认报价不预留库存，转单后继续销售订单审批。' }}</p>
        </el-form>
        <template v-else-if="document">
          <dl class="quote-facts" aria-label="销售报价资料">
            <div><dt>客户</dt><dd>{{ document.customerName }}</dd></div>
            <div><dt>报价日期</dt><dd>{{ document.businessDate }}</dd></div>
            <div><dt>报价口径</dt><dd>{{ document.input.priceTaxInclusive ? '含税' : '未税' }}</dd></div>
            <div><dt>商机</dt><dd>{{ document.opportunityName ?? '未关联' }}</dd></div>
            <div class="quote-remark"><dt>备注</dt><dd>{{ document.remark || '—' }}</dd></div>
          </dl>
          <el-table :data="document.pricing.lines" row-key="lineNo" max-height="440" empty-text="暂无报价明细">
            <el-table-column label="商品" min-width="180"><template #default="{ $index }"><strong>{{ lineName($index) }}</strong></template></el-table-column>
            <el-table-column label="数量" min-width="110" align="right"><template #default="{ row, $index }">{{ formatQuantity(row.qty, document.lines[$index]?.unitName) }}</template></el-table-column>
            <el-table-column label="单价" min-width="120" align="right"><template #default="{ row }">{{ formatMoney(row.unitPrice) }}</template></el-table-column>
            <el-table-column label="优惠" min-width="110" align="right"><template #default="{ row }">{{ formatMoney(row.lineDiscountAmount) }}</template></el-table-column>
            <el-table-column label="含税金额" min-width="140" align="right"><template #default="{ row }"><FinancialAmount :value="row.totalAmount" /></template></el-table-column>
          </el-table>
          <p v-if="document.convertedOrderId" class="quote-converted">已生成销售订单 {{ document.convertedOrderNo ?? '' }}<el-button v-if="session.can('sales_order:view')" link type="primary" @click="showingOrder = true">右侧查看订单</el-button></p>
          <WorkflowProgressPanel business-type="sales_quote" :business-id="document.id" :instance-id="document.wfInstanceId" />
          <section v-if="submitting && !readOnly && !priceBlocked" class="quote-confirm"><h3>{{ needsPriceApproval ? '提交价格审批' : '确认报价生效' }}</h3>
            <WorkflowStarterSelector v-if="needsPriceApproval" v-model="selections" business-type="sales_quote" :business-id="document.id" :disabled="locked" @ready="workflowReady = $event" />
            <p v-else class="quote-note">确认后可在有效期内转为销售订单，报价不会占用库存。</p>
            <div><el-button type="primary" :disabled="locked || (needsPriceApproval && !workflowReady)" @click="panel.submit">确认</el-button><el-button :disabled="locked" @click="submitting = false">取消</el-button></div>
          </section>
        </template>
        <el-alert v-for="(warning, index) in pricing?.warnings ?? []" :key="index" :title="warning.message" type="warning" :closable="false" />
      </template>
      <el-empty v-else description="暂时无法读取报价"><el-button @click="panel.load">重新读取</el-button></el-empty>
    </div>
    <template #footer><div class="quote-footer">
      <template v-if="editing && !readOnly"><el-button type="primary" :loading="busy" :disabled="locked" @click="panel.save">保存报价</el-button><el-button v-if="document" :disabled="locked" @click="panel.cancelEdit">取消修改</el-button></template>
      <template v-else><el-button v-if="document?.allowedActions.includes('update') && panel.can('update')" :disabled="locked" @click="panel.edit">修改报价</el-button><el-button v-for="action in actions" :key="action" :type="action === 'void' ? 'default' : 'primary'" :disabled="locked || submitting" @click="panel.act(action)">{{ quoteActionLabels[action] }}</el-button></template>
      <el-button :disabled="locked" @click="panel.close()">关闭</el-button>
    </div></template>
  </el-drawer>
  <SalesOrderSourcePreview v-if="showingOrder && document?.convertedOrderId" :id="document.convertedOrderId" @close="showingOrder = false" />
</template>

<style scoped>
.quote-panel { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; }
.quote-panel > * { min-width: 0; }
.quote-heading, .quote-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.quote-footer { justify-content: flex-end; flex-wrap: wrap; }
.quote-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0; border-block: 1px solid var(--erp-border-1); }
.quote-summary > div { padding: 18px 16px; }
.quote-summary dt { color: var(--erp-text-2); font-size: 13px; margin-bottom: 12px; }
.quote-summary dd { margin: 0; font-size: 20px; font-weight: 650; font-variant-numeric: tabular-nums; }
.quote-summary .quote-deadline { font-size: 17px; }
.quote-summary small { margin-left: 6px; font-size: 12px; font-weight: 400; color: var(--erp-text-2); }
.quote-total { background: var(--erp-primary-1); }
.quote-total :deep(.financial-amount) { font-size: 26px; }
.quote-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 20px; }
.quote-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 28px; margin: 0; }
.quote-facts dt { color: var(--erp-text-2); font-size: 12px; margin-bottom: 6px; }
.quote-facts dd { margin: 0; line-height: 1.6; overflow-wrap: anywhere; }
.quote-remark { grid-column: 1 / -1; }
.quote-lines-wrap { overflow-x: auto; margin: 14px 0; }
.quote-lines { width: 100%; min-width: 920px; border-collapse: collapse; }
.quote-lines th { text-align: left; padding: 10px 8px; background: var(--erp-fill-2); color: var(--erp-text-2); font-size: 12px; font-weight: 500; }
.quote-lines td { padding: 10px 6px; border-bottom: 1px solid var(--erp-border-1); vertical-align: top; }
.quote-lines th:nth-child(1) { width: 23%; }.quote-lines th:nth-child(2) { width: 17%; }.quote-lines th:nth-child(3) { width: 13%; }
.quote-lines small, .quote-note { color: var(--erp-text-2); font-size: 12px; line-height: 1.7; }
.quote-discount { margin-top: 20px; grid-template-columns: 1fr 2fr; }
.quote-fee { display: grid; grid-template-columns: 1fr 1fr 1fr auto; gap: 12px; margin: 12px 0; }
.quote-confirm { display: grid; gap: 12px; }
.quote-converted { padding: 12px 16px; background: var(--erp-fill-2); display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
h3 { font-size: 15px; margin: 0 0 12px; }
@media (max-width: 760px) { .quote-summary, .quote-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); } .quote-fee { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 420px) { .quote-fields, .quote-discount { grid-template-columns: 1fr; } .quote-summary > div { padding: 12px 8px; } .quote-summary dd { font-size: 17px; } .quote-total :deep(.financial-amount) { font-size: 21px; } }
</style>
