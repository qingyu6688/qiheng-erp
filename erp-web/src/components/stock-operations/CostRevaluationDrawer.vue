<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElDatePicker } from 'element-plus/es/components/date-picker/index.mjs'
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs'
import { ElPagination } from 'element-plus/es/components/pagination/index.mjs'
import { ElSkeleton } from 'element-plus/es/components/skeleton/index.mjs'
import 'element-plus/theme-chalk/el-date-picker.css'
import 'element-plus/theme-chalk/el-empty.css'
import 'element-plus/theme-chalk/el-pagination.css'
import 'element-plus/theme-chalk/el-skeleton.css'
import { useCostRevaluationPanel } from '../../hooks/useCostRevaluationPanel'
import { revaluationActionLabels, revaluationStatusLabels } from '../../types/cost-revaluations'
import { formatDateTime, formatMoney, formatQuantity } from '../../utils/format'
import FinancialAmount from '../FinancialAmount.vue'
import StatusTag from '../StatusTag.vue'
import WorkflowProgressPanel from '../workflow/WorkflowProgressPanel.vue'
import WorkflowStarterSelector from '../workflow/WorkflowStarterSelector.vue'

const props = defineProps<{ id?: string; readOnly?: boolean }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const panel = useCostRevaluationPanel(() => props.id, (id) => emit('saved', id), () => emit('close'), () => Boolean(props.readOnly))
const { document, metadata, draft, loading, busy, error, pending, locked, submitting, workflowReady, selections } = panel
const linePage = ref(1)
const lines = computed(() => document.value?.lines.slice((linePage.value - 1) * 50, linePage.value * 50) ?? [])
const actions = computed(() => document.value?.allowedActions.filter((action) => panel.can(action)) ?? [])
</script>

<template>
  <el-drawer :model-value="true" :title="document ? '成本重估 · ' + document.documentNo : '新建成本重估'"
    direction="rtl" size="min(1180px, 96vw)" append-to-body destroy-on-close :close-on-click-modal="false"
    :close-on-press-escape="!locked" :show-close="!locked" :before-close="panel.close" @close="emit('close')">
    <div class="revaluation-panel" :aria-busy="loading || busy">
      <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <el-alert v-if="pending && !busy" title="提交结果尚未确认，请核对结果或重试原请求。" type="warning" :closable="false">
        <el-button @click="panel.verify">核对结果</el-button><el-button @click="panel.retry">重试原请求</el-button>
      </el-alert>
      <el-skeleton v-if="loading" :rows="8" animated />
      <template v-else-if="document">
        <div class="revaluation-heading">
          <StatusTag :label="revaluationStatusLabels[document.status]" :tone="document.status === 'posted' ? 'success' : 'info'" />
          <el-button :disabled="locked" @click="panel.load">刷新任务</el-button>
        </div>
        <dl class="cost-summary" aria-label="成本重估差异摘要">
          <div><dt>重估前库存成本</dt><dd>{{ formatMoney(document.beforeCost) }}<small>元</small></dd></div>
          <div><dt>重估后库存成本</dt><dd>{{ formatMoney(document.afterCost) }}<small>元</small></dd></div>
          <div class="cost-focus"><dt>库存成本调整</dt><dd><FinancialAmount :value="document.stockDelta" signed /><small>元</small></dd></div>
          <div><dt>出库成本差异</dt><dd><FinancialAmount :value="document.issuedDelta" signed /><small>元</small></dd></div>
        </dl>
        <dl class="revaluation-facts" aria-label="成本重估任务资料">
          <div><dt>仓库</dt><dd>{{ document.warehouseName }}</dd></div>
          <div><dt>商品</dt><dd>{{ document.skuName }}</dd></div>
          <div><dt>起算日</dt><dd>{{ document.startDate }}</dd></div>
          <div><dt>调整入账日</dt><dd>{{ document.businessDate }}</dd></div>
          <div><dt>当前基础数量</dt><dd>{{ formatQuantity(document.quantity) }}</dd></div>
          <div><dt>重放范围</dt><dd>{{ document.eventCount }} 条成本事件 · {{ document.lines.length }} 条差异</dd></div>
          <div class="revaluation-reason"><dt>重估原因</dt><dd>{{ document.reason }}</dd></div>
        </dl>
        <p class="cost-note">保留原出入库成本快照。库存变化后需重新预览和审批，差额计入调整日的库存及成本差异。</p>
        <h3>成本差异明细</h3>
        <el-table :data="lines" row-key="id" empty-text="当前没有需要调整的成本差异" max-height="440">
          <el-table-column label="原流水" min-width="200"><template #default="{ row }">
            <strong>{{ row.movementId }}</strong><div class="cost-note">{{ formatDateTime(row.postedAt) }}</div>
          </template></el-table-column>
          <el-table-column prop="businessDate" label="业务日期" min-width="115" />
          <el-table-column label="基础数量变化" min-width="125" align="right"><template #default="{ row }">{{ formatQuantity(row.onHandDelta) }}</template></el-table-column>
          <el-table-column label="原有效成本" min-width="120" align="right"><template #default="{ row }">{{ formatMoney(row.beforeCost) }}</template></el-table-column>
          <el-table-column label="重估成本" min-width="120" align="right"><template #default="{ row }">{{ formatMoney(row.afterCost) }}</template></el-table-column>
          <el-table-column label="库存成本差额" min-width="140" align="right"><template #default="{ row }"><FinancialAmount :value="row.costDelta" signed /></template></el-table-column>
        </el-table>
        <el-pagination v-if="document.lines.length > 50" v-model:current-page="linePage" :page-size="50" :total="document.lines.length" layout="prev, pager, next" />
        <p class="cost-note">明细金额按库存方向显示：增加为正，减少为负。出库成本差异与库存调整方向相反。</p>
        <WorkflowProgressPanel business-type="cost_revaluation" :business-id="document.id" :instance-id="document.wfInstanceId" />
        <section v-if="submitting && !readOnly" class="revaluation-submit">
          <h3>提交重估审批</h3>
          <WorkflowStarterSelector v-model="selections" business-type="cost_revaluation" :business-id="document.id" :disabled="locked" @ready="workflowReady = $event" />
          <el-button type="primary" :disabled="!workflowReady || locked" @click="panel.submit">确认提交</el-button>
          <el-button :disabled="locked" @click="submitting = false">取消</el-button>
        </section>
      </template>
      <el-form v-else-if="!id" label-position="top" :disabled="locked || readOnly" class="revaluation-form">
        <p class="cost-note">按实际过账顺序重放当前仓库商品成本，提交前先保存差异预览。</p>
        <el-form-item label="仓库" required><el-select v-model="draft.warehouseId" filterable aria-label="重估仓库"><el-option v-for="item in metadata.warehouses" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item label="商品" required><el-select v-model="draft.skuId" filterable remote :remote-method="panel.searchSkus" placeholder="输入商品名称搜索，最多显示200项" aria-label="重估商品"><el-option v-for="item in metadata.skus" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <div class="revaluation-dates">
          <el-form-item label="起算日" required><el-date-picker v-model="draft.startDate" type="date" value-format="YYYY-MM-DD" aria-label="重估起算日" /></el-form-item>
          <el-form-item label="调整入账日" required><el-date-picker v-model="draft.businessDate" type="date" value-format="YYYY-MM-DD" aria-label="重估入账日" /></el-form-item>
        </div>
        <el-form-item label="重估原因" required><el-input v-model="draft.reason" type="textarea" :rows="3" maxlength="500" show-word-limit /></el-form-item>
      </el-form>
      <el-empty v-else description="暂时无法读取成本重估任务"><el-button @click="panel.load">重新读取</el-button></el-empty>
    </div>
    <template #footer><div class="revaluation-footer">
      <el-button v-if="!id && !document && !loading && panel.can('create')" type="primary" :loading="busy" :disabled="locked" @click="panel.save">生成差异预览</el-button>
      <el-button v-for="action in actions" :key="action" :type="action === 'post' || action === 'submit' ? 'primary' : 'default'" :disabled="locked || submitting" @click="panel.act(action)">{{ revaluationActionLabels[action] }}</el-button>
      <el-button :disabled="locked" @click="panel.close()">关闭</el-button>
    </div></template>
  </el-drawer>
</template>

<style scoped>
.revaluation-panel { display: grid; grid-template-columns: minmax(0, 1fr); gap: 20px; }
.revaluation-panel > * { min-width: 0; }
.revaluation-heading, .revaluation-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.revaluation-footer { justify-content: flex-end; flex-wrap: wrap; }
.cost-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin: 0; border-block: 1px solid var(--erp-border-1); }
.cost-summary > div { padding: 20px 16px; }
.cost-summary dt { color: var(--erp-text-2); font-size: 13px; margin-bottom: 12px; }
.cost-summary dd { margin: 0; font-size: 21px; font-weight: 650; font-variant-numeric: tabular-nums; }
.cost-summary small { margin-left: 6px; font-size: 12px; font-weight: 400; color: var(--erp-text-2); }
.cost-focus { background: var(--erp-primary-1); }
.cost-summary :deep(.financial-amount) { font-size: 24px; }
.cost-note { margin: 4px 0; color: var(--erp-text-2); font-size: 13px; line-height: 1.7; }
h3 { margin: 0; font-size: 15px; }
.revaluation-form { max-width: 720px; }
.revaluation-dates { display: flex; gap: 24px; flex-wrap: wrap; }
.revaluation-facts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px 28px; margin: 0; }
.revaluation-facts dt { color: var(--erp-text-2); font-size: 12px; margin-bottom: 6px; }
.revaluation-facts dd { margin: 0; overflow-wrap: anywhere; line-height: 1.6; }
.revaluation-reason { grid-column: 1 / -1; }
.revaluation-submit { display: grid; gap: 12px; }
@media (max-width: 760px) { .cost-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 420px) { .cost-summary > div { padding: 14px 8px; } .cost-summary dd { font-size: 17px; } .cost-summary :deep(.financial-amount) { font-size: 20px; } }
</style>
