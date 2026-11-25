<script setup lang="ts">
import WorkflowProgressPanel from '../components/workflow/WorkflowProgressPanel.vue'
import { computed, defineAsyncComponent, inject, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  document,
  candidates,
  financeWrite,
  commandResult,
  saveShipmentLogistics,
} from '../api/sales'
import { ApiError, isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import type { SalesKind, SalesDocument } from '../types/sales'
import type { WorkOrder } from '../types/manufacturing'
import { salesSpecs, actionLabels, statusLabels, salesStatusLabel } from '../types/sales'
import OrderEditor from '../components/sales/OrderEditor.vue'
import DocumentEditor from '../components/sales/DocumentEditor.vue'
import CommandDialog from '../components/sales/CommandDialog.vue'
import { useSessionStore } from '../stores/session'
import DocumentFilesPanel from '../components/DocumentFilesPanel.vue'
import AiDocumentButton from '../components/AiDocumentButton.vue'
import StatusTag from '../components/StatusTag.vue'
import PageHeader from '../components/PageHeader.vue'
import DocumentMoreActions from '../components/DocumentMoreActions.vue'
import DocumentDetailLayout from '../components/DocumentDetailLayout.vue'
import DocumentLineTable from '../components/DocumentLineTable.vue'
import OrderDetailOverview from '../components/OrderDetailOverview.vue'
import { documentStateTag } from '../types/document-ui'
import { formatDateTime, formatMoney, formatQuantity } from '../utils/format'
const SalesPriceAdjustmentDrawer = defineAsyncComponent(
  () => import('../components/sales/SalesPriceAdjustmentDrawer.vue'),
)
const SalesInvoiceDrawer = defineAsyncComponent(
  () => import('../components/sales/SalesInvoiceDrawer.vue'),
)
const SourcelessDocumentPanel = defineAsyncComponent(
  () => import('../components/sales/SourcelessDocumentPanel.vue'),
)
const salesPriceSource = ref<SalesDocument>(),
  invoiceDetailId = ref<string>()
const readOnlyPreview = inject('erp.readOnlyPreview', false)
function closeSalesPrice(): void {
  salesPriceSource.value = undefined
  void load()
}
const props = defineProps<{ kind: SalesKind }>()
const route = useRoute(),
  router = useRouter(),
  session = useSessionStore()
const spec = computed(() => salesSpecs.find((s) => s.path === props.kind)!)
const value = ref<SalesDocument>(),
  error = ref(''),
  loading = ref(false),
  editing = ref(false),
  changing = ref(false),
  action = ref('')
const history = ref<Record<string, unknown>[]>([])
const closePreview = inject<() => void>('erp.closeDocumentPreview')
const sourcelessPage = computed(
  () =>
    (props.kind === 'sales-returns' && value.value?.returnMode === 'sourceless') ||
    (props.kind === 'refund-requests' && value.value?.refundMode === 'sourceless_credit'),
)
function closeSourceless(): void {
  if (closePreview) closePreview()
  else void router.push('/' + props.kind)
}
const orderPage = computed(() => props.kind === 'sales-orders')
const orderRelations = computed(() =>
  orderPage.value && value.value
    ? {
        kind: 'sales-orders' as const,
        id: value.value.id,
        version: value.value.version,
      }
    : undefined,
)
const moreActions = computed(() =>
  orderPage.value
    ? (value.value?.allowedActions ?? []).filter((action) =>
        ['cancel', 'delete', 'revoke_approval'].includes(action),
      )
    : [],
)
const mainActions = computed(() =>
  (value.value?.allowedActions ?? []).filter((action) => !moreActions.value.includes(action)),
)
function actionLabel(action: string): string {
  return actionLabels[action] ?? action
}
const WorkOrderEditor = defineAsyncComponent(() => import('./mfg/WorkOrderEditor.vue'))
const generatingWork = ref(false)
const generatedWork = ref<WorkOrder>()
function workOrderSaved(order: WorkOrder): void {
  generatedWork.value = order
  generatingWork.value = false
}
const commandWarnings = ref<{ message: string }[]>([])
const logisticsOpen = ref(false),
  logisticsNumber = ref(''),
  carrierCode = ref(''),
  logisticsBusy = ref(false),
  logisticsError = ref('')
function editLogistics(): void {
  logisticsNumber.value = value.value?.logisticsNo ?? ''
  carrierCode.value = value.value?.carrierCode ?? ''
  logisticsError.value = ''
  logisticsOpen.value = true
}
async function saveLogistics(): Promise<void> {
  const shipment = value.value
  if (!shipment || logisticsBusy.value || !session.can('shipment:update')) return
  logisticsBusy.value = true
  logisticsError.value = ''
  try {
    value.value = await saveShipmentLogistics(
      shipment.id,
      shipment.version,
      logisticsNumber.value.trim(),
      carrierCode.value.trim(),
    )
    logisticsOpen.value = false
  } catch (cause: unknown) {
    if (!isCancelled(cause)) logisticsError.value = messageOf(cause)
  } finally {
    logisticsBusy.value = false
  }
}
const decision = ref<{
  action: 'approve' | 'reject'
  changeId: string
  version: number
  key: string
}>()
const decisionReason = ref(''),
  decisionBusy = ref(false),
  decisionError = ref(''),
  decisionUncertain = ref(false),
  decisionConflict = ref(false)
async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const data = await document(props.kind, String(route.params.id))
    const special =
      (props.kind === 'sales-returns' && data.returnMode === 'sourceless') ||
      (props.kind === 'refund-requests' && data.refundMode === 'sourceless_credit')
    value.value = special
      ? {
          id: data.id,
          status: data.status,
          version: data.version,
          allowedActions: [],
          returnMode: data.returnMode,
          refundMode: data.refundMode,
        }
      : data
    if (props.kind === 'sales-orders')
      history.value = (await candidates('sales-orders/' + route.params.id + '/reservations')).items
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    loading.value = false
  }
}
function choose(name: string): void {
  if (name === 'update') {
    if (props.kind === 'sales-orders')
      void router.push('/sales-orders/' + route.params.id + '/edit')
    else editing.value = true
    return
  }
  if (name === 'change' || name === 'changes') {
    changing.value = true
    return
  }
  action.value = name
}
async function saved(): Promise<void> {
  editing.value = false
  changing.value = false
  action.value = ''
  await load()
  if (!value.value && error.value) await router.replace('/' + props.kind)
}
async function commandSaved(warnings: { message: string }[] = []): Promise<void> {
  commandWarnings.value = warnings
  const deleted = action.value === 'delete'
  action.value = ''
  if (deleted) {
    await router.replace('/' + props.kind)
    return
  }
  await saved()
}
function changeDecision(name: 'approve' | 'reject'): void {
  if (!value.value) return
  const change = value.value.change
  if (!change || change.status !== 'pending') {
    error.value = '请刷新后重试，当前没有待处理的变更信息'
    return
  }
  decision.value = {
    action: name,
    changeId: change.id,
    version: change.version,
    key: newIdempotencyKey(),
  }
  decisionReason.value = ''
  decisionError.value = ''
  decisionUncertain.value = false
  decisionConflict.value = false
}
async function submitDecision(): Promise<void> {
  if (!decision.value || decisionBusy.value) return
  const current = decision.value
  if (current.action === 'reject' && !decisionReason.value.trim()) {
    decisionError.value = '请填写驳回意见'
    return
  }
  decisionBusy.value = true
  decisionError.value = ''
  try {
    await financeWrite(
      'sales-orders/changes/' + current.changeId + '/' + current.action,
      { version: current.version, comment: decisionReason.value || undefined },
      current.key,
    )
    decision.value = undefined
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      decisionError.value = messageOf(cause)
      if (cause instanceof ApiError) {
        decisionUncertain.value = cause.status === 0 || cause.code === 'REQUEST_IN_PROGRESS'
        decisionConflict.value = cause.code === 'VERSION_CONFLICT'
      }
    }
  } finally {
    decisionBusy.value = false
  }
}
async function verifyDecision(): Promise<void> {
  if (!decision.value || decisionBusy.value) return
  decisionBusy.value = true
  try {
    const result = await commandResult(
      'sales_order.change_' + decision.value.action,
      decision.value.key,
    )
    if (result.status === 'succeeded') {
      decision.value = undefined
      await load()
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) {
      decisionError.value = messageOf(cause)
      if (cause instanceof ApiError && cause.status === 404) {
        decisionUncertain.value = false
        decisionError.value = '尚无已提交结果，可使用原请求重试'
      }
    }
  } finally {
    decisionBusy.value = false
  }
}
onMounted(async () => {
  const path = route.path
  await load()
  if (route.path !== path || !orderPage.value || route.query.next !== 'submit' || !value.value)
    return
  const query = { ...route.query }
  delete query.next
  if (value.value.allowedActions.includes('submit')) choose('submit')
  else error.value = '订单已保存，当前状态或权限不允许提交审核。'
  await router.replace({ path, query })
})
function closeEditor(): void {
  editing.value = false
  changing.value = false
}
</script>
<template>
  <SourcelessDocumentPanel
    v-if="sourcelessPage"
    :kind="kind === 'sales-returns' ? 'sales-returns' : 'refund-requests'"
    :id="value?.id"
    @saved="load"
    @close="closeSourceless"
  />
  <template v-else>
    <PageHeader
      :title="value?.documentNo ?? value?.invoiceNo ?? spec.singular"
      :back-to="'/' + kind"
      :back-label="'返回' + spec.title"
    >
      <template v-if="value && orderPage" #status>
        <StatusTag
          data-order-status
          :label="salesStatusLabel(kind, value.status)"
          :tone="documentStateTag(kind, value.status)"
        />
      </template>
      <template v-if="value" #actions>
        <el-button
          v-if="
            !readOnlyPreview &&
            kind === 'shipments' &&
            ['signed', 'signed_with_diff'].includes(value.status) &&
            session.can('sales_price_adjustment:view') &&
            session.can('sales_price_adjustment:create') &&
            session.can('shipment:view')
          "
          @click="salesPriceSource = value"
          >销售调价</el-button
        >
        <el-button
          v-if="kind === 'invoice-registrations' && session.can('invoice_registration:view')"
          @click="invoiceDetailId = value.id"
          >原票及红字</el-button
        >

        <el-button
          v-if="
            kind === 'sales-orders' &&
            value.status === 'approved' &&
            session.can('mfg_work_order:create')
          "
          @click="generatingWork = true"
          >生成工单</el-button
        >
        <router-link
          v-if="
            kind === 'receipts' &&
            value.status !== 'reversed' &&
            session.can('crm_receivable:view') &&
            session.can('crm_receivable:manage')
          "
          class="text-link"
          :to="{ path: '/crm/receivables', query: { receiptId: value.id } }"
          >关联 CRM 合同</router-link
        >
        <AiDocumentButton
          v-if="kind === 'sales-orders' || kind === 'shipments'"
          :document="{
            documentType: kind === 'sales-orders' ? 'sales_order' : 'shipment',
            documentId: value.id,
            label: value.documentNo ?? value.id,
          }"
        />
        <router-link
          v-if="orderPage && session.can(spec.permission + ':print')"
          class="text-link"
          :to="'/print/' + spec.permission + '/' + value.id"
          >打印</router-link
        >
        <template v-for="name in mainActions" :key="name"
          ><el-button
            v-if="name !== 'change_approve' && name !== 'change_reject'"
            :type="
              ['approve', 'post', 'sign', 'confirm', 'register_paid'].includes(name)
                ? 'primary'
                : undefined
            "
            @click="choose(name)"
            >{{ actionLabels[name] ?? name }}</el-button
          ><el-button
            v-else
            @click="changeDecision(name === 'change_approve' ? 'approve' : 'reject')"
            >{{ actionLabels[name] }}</el-button
          ></template
        >
        <DocumentMoreActions :actions="moreActions" :label="actionLabel" @choose="choose" />
      </template>
    </PageHeader>
    <el-alert v-if="error" type="error" :title="error" :closable="false" />
    <p v-if="generatedWork" role="status">
      已生成生产工单 {{ generatedWork.documentNo }}。
      <router-link
        v-if="session.can('mfg_work_order:view')"
        class="text-link"
        :to="'/mfg/work-orders/' + generatedWork.id"
        >查看生产工单</router-link
      >
    </p>
    <p v-if="loading" role="status">正在加载单据…</p>
    <template v-if="value">
      <OrderDetailOverview
        v-if="orderPage"
        kind="sales-orders"
        :order="value"
        :party="
          value.customer?.name ??
          (typeof value.customerName === 'string' ? value.customerName : value.partnerName)
        "
        :representative="value.salesUserName"
      />
      <section v-else class="panel sales-detail-header">
        <dl>
          <dt>状态</dt>
          <dd>
            <StatusTag
              :label="salesStatusLabel(kind, value.status)"
              :tone="documentStateTag(kind, value.status)"
            />
          </dd>
          <template v-if="value.salesUserName"
            ><dt>销售员</dt>
            <dd>{{ value.salesUserName }}</dd></template
          >
          <dt>业务日期</dt>
          <dd>{{ value.businessDate ?? value.invoiceDate }}</dd>
          <dt>客户 / 往来</dt>
          <dd>{{ value.customer?.name ?? value.customerName ?? value.partnerName ?? '—' }}</dd>
          <dt>金额</dt>
          <dd>
            {{
              formatMoney(
                String(
                  value.totalAmount ??
                    value.receivableAmount ??
                    value.creditAmount ??
                    value.amount ??
                    '',
                ),
              )
            }}
          </dd>
          <dt>税额</dt>
          <dd>{{ formatMoney(value.taxAmount) }}</dd>
          <dt>版本</dt>
          <dd>{{ value.version }}</dd>
          <template v-if="kind === 'shipments' && value.deliveryMethod === 'delivery'">
            <dt>物流单号</dt>
            <dd>
              {{ value.logisticsNo || '—' }}
              <el-button
                v-if="
                  session.can('shipment:update') && !['voided', 'reversed'].includes(value.status)
                "
                link
                type="primary"
                @click="editLogistics"
                >编辑运单号</el-button
              >
            </dd>
            <dt>承运商编码</dt>
            <dd>{{ value.carrierCode || '—' }}</dd>
          </template>
        </dl>
        <p v-if="value.createdAt" class="document-meta-time">
          制单时间 {{ formatDateTime(String(value.createdAt))
          }}<template v-if="value.approvedAt">
            · 审核时间 {{ formatDateTime(String(value.approvedAt)) }}</template
          ><template v-if="value.confirmedAt">
            · 确认时间 {{ formatDateTime(String(value.confirmedAt)) }}</template
          >
        </p>
        <p v-if="value.reason">原因 {{ value.reason }}</p>
        <p v-if="value.remark">备注 {{ value.remark }}</p>
      </section>
      <el-alert
        v-for="(warning, index) in [...commandWarnings, ...(value.warnings ?? [])]"
        :key="index"
        type="warning"
        :title="warning.message"
        :closable="false"
      />
      <DocumentDetailLayout :split="orderPage" :order="orderRelations">
        <section v-if="value.lines?.length" class="panel" aria-labelledby="document-lines-title">
          <div class="document-section-title">
            <h2 id="document-lines-title">明细与进度</h2>
            <span>数量按单据单位显示</span>
          </div>
          <DocumentLineTable :rows="value.lines">
            <el-table-column label="商品 / 来源" min-width="160"
              ><template #default="{ row }">{{
                row.sku?.name ??
                row.skuName ??
                row.sourceNo ??
                row.orderLineId ??
                row.receivableId ??
                row.id
              }}</template></el-table-column
            >
            <el-table-column label="数量" min-width="100"
              ><template #default="{ row }">{{
                formatQuantity(row.qty ?? row.baseQty)
              }}</template></el-table-column
            >
            <el-table-column label="单位" min-width="80"
              ><template #default="{ row }">{{
                row.unit?.name ?? row.unitName ?? '—'
              }}</template></el-table-column
            >
            <el-table-column label="金额" min-width="120"
              ><template #default="{ row }">{{
                formatMoney(
                  row.receivableNet ?? row.receivableAmount ?? row.creditAmount ?? row.amount,
                )
              }}</template></el-table-column
            >
            <el-table-column v-if="kind === 'sales-orders'" label="可发 / 预留" min-width="140"
              ><template #default="{ row }"
                >{{ row.progress?.shippableQty }} /
                {{ statusLabels[row.reservationState ?? 'none'] ?? row.reservationState }}</template
              ></el-table-column
            >
            <el-table-column v-if="kind === 'shipments'" label="签收 / 差异" min-width="140"
              ><template #default="{ row }"
                >{{ row.signedQty ?? '—' }} / {{ row.diffQty ?? '—' }}
                {{ row.diffDisposition === 'pending' ? '待处理' : '' }}</template
              ></el-table-column
            >
            <el-table-column
              v-if="session.can('cost:view') && kind !== 'sales-orders'"
              label="成本"
              min-width="100"
              ><template #default="{ row }">{{
                formatMoney(row.costAmount)
              }}</template></el-table-column
            >
          </DocumentLineTable>
        </section>
        <section v-if="value.fees?.length" class="panel sales-detail-header">
          <h2>费用</h2>
          <p v-for="(fee, index) in value.fees" :key="index">
            {{ fee.feeType === 'freight' ? '运费' : '其他费用' }} ·
            {{ fee.bearer === 'buyer' ? '买方承担' : '我方承担' }} · ¥{{ fee.amount }}
          </p>
        </section>
        <section v-if="kind === 'sales-orders' && history.length" class="panel sales-detail-header">
          <h2>库存预留记录</h2>
          <el-table :data="history"
            ><el-table-column prop="remainingQty" label="剩余预留" /><el-table-column label="状态"
              ><template #default="{ row }">{{
                statusLabels[row.status] ?? row.status
              }}</template></el-table-column
            ><el-table-column label="到期时间"
              ><template #default="{ row }">{{
                formatDateTime(String(row.expireAt ?? ''))
              }}</template></el-table-column
            ></el-table
          >
        </section>
        <el-alert
          v-if="kind === 'sales-orders' && value?.lines?.some((line) => line.belowMinPrice)"
          title="此单含低于最低售价的商品，请核对折扣及价格审批要求。"
          type="warning"
          :closable="false"
        />
        <template #progress>
          <WorkflowProgressPanel
            v-if="
              value &&
              (kind === 'sales-orders'
                ? 'sales_order'
                : kind === 'refund-requests'
                  ? 'refund_request'
                  : undefined)
            "
            :business-type="
              (kind === 'sales-orders'
                ? 'sales_order'
                : kind === 'refund-requests'
                  ? 'refund_request'
                  : undefined)!
            "
            :business-id="value.id"
            :instance-id="value.workflowInstanceId"
          />
        </template>
        <template #attachments>
          <DocumentFilesPanel
            :key="value.version"
            :owner-type="spec.permission"
            :owner-id="value.id"
            :hide-print="orderPage"
          />
        </template>
      </DocumentDetailLayout>
    </template>
    <el-dialog v-model="logisticsOpen" title="编辑运单号" width="min(440px, 95vw)">
      <el-alert v-if="logisticsError" :title="logisticsError" type="error" :closable="false" />
      <el-form label-position="top" @submit.prevent="saveLogistics">
        <el-form-item label="物流单号"
          ><el-input v-model.trim="logisticsNumber" maxlength="100"
        /></el-form-item>
        <el-form-item label="承运商编码"
          ><el-input v-model.trim="carrierCode" maxlength="48"
        /></el-form-item>
        <el-button type="primary" native-type="submit" :loading="logisticsBusy">保存</el-button>
      </el-form>
    </el-dialog>
    <OrderEditor
      v-if="(editing || changing) && value && kind === 'sales-orders'"
      :original="value"
      :change="changing"
      @close="closeEditor"
      @saved="saved"
    />
    <WorkOrderEditor
      v-if="generatingWork && value"
      :sales="value"
      @close="generatingWork = false"
      @saved="workOrderSaved"
    />
    <DocumentEditor
      v-if="editing && value && kind !== 'sales-orders'"
      :kind="kind"
      :original="value"
      @close="editing = false"
      @saved="saved"
    />
    <CommandDialog
      v-if="action && value"
      :kind="kind"
      :document="value"
      :action="action"
      @close="action = ''"
      @saved="commandSaved"
    />
    <el-dialog
      v-if="decision"
      :model-value="true"
      :title="decision.action === 'approve' ? '批准订单变更' : '驳回订单变更'"
      width="520px"
      :close-on-click-modal="false"
      :show-close="!decisionBusy"
      @close="decision = undefined"
    >
      <fieldset
        class="form-fieldset"
        :disabled="decisionBusy || decisionUncertain || decisionConflict"
      >
        <p>变更版本 {{ decision.version }}</p>
        <el-form-item label="审批意见"
          ><el-input
            v-model="decisionReason"
            aria-label="变更审批意见"
            type="textarea"
            maxlength="500"
        /></el-form-item>
      </fieldset>
      <el-alert v-if="decisionError" type="error" :title="decisionError" :closable="false" />
      <p v-if="decisionUncertain">结果确认中，请查询原请求结果。</p>
      <p v-if="decisionConflict">变更已更新，请关闭并刷新后重试。</p>
      <template #footer
        ><el-button :disabled="decisionBusy" @click="decision = undefined">关闭</el-button
        ><el-button
          v-if="decisionUncertain"
          type="primary"
          :loading="decisionBusy"
          @click="verifyDecision"
          >查询结果</el-button
        ><el-button
          v-else
          type="primary"
          :disabled="decisionConflict"
          :loading="decisionBusy"
          @click="submitDecision"
          >确认</el-button
        ></template
      >
    </el-dialog>
    <SalesPriceAdjustmentDrawer
      v-if="salesPriceSource"
      :key="salesPriceSource.id"
      :initial-shipment="salesPriceSource"
      @close="closeSalesPrice"
    />
    <SalesInvoiceDrawer
      v-if="invoiceDetailId"
      :key="invoiceDetailId"
      :id="invoiceDetailId"
      @close="invoiceDetailId = undefined"
      @changed="load"
    />
  </template>
</template>

<style scoped>
.document-section-title {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.document-section-title h2 {
  font-size: 16px;
  margin: 0;
}
.document-section-title span,
.document-meta-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.panel + .panel {
  margin-top: 20px;
}
</style>
