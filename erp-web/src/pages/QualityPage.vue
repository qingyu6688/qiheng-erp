<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { changeQualityInspection, qualityInspection, qualityInspections } from '../api/quality'
import type { QualityDefect, QualityInspection, QualityInspectionDetail, QualityValue } from '../api/quality'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { dictionaries } from '../api/platform'
import type { DictionaryItem } from '../api/platform'
import QualitySettingsPanel from '../components/QualitySettingsPanel.vue'
import { useSessionStore } from '../stores/session'
import { formatQuantity } from '../utils/format'

const session = useSessionStore(), route = useRoute()
const tab = ref('inspections'), status = ref('draft')
const items = ref<QualityInspection[]>([]), cursors = ref<(string | undefined)[]>([undefined])
const next = ref<string | null>(), detail = ref<QualityInspectionDetail>()
const loading = ref(false), busy = ref(false), error = ref('')
const action = ref<'submit' | 'approve' | 'reject' | 'dispose'>()
const statuses = { draft: '待检', submitted: '待审核', approved: '已审核', cancelled: '已取消' }
const results = { pending: '待判定', passed: '合格', failed: '不合格', partial: '部分合格' }
const dispositions = { return: '采购退货', scrap: '报废', reinspect: '复检' }
const defectTypes = ref<DictionaryItem[]>([])
const form = reactive({
  qualifiedQty: '', unqualifiedQty: '', concessionQty: '', concessionReason: '',
  values: [] as QualityValue[], defects: [] as QualityDefect[],
  action: 'return', qty: '', reason: '',
})
const detailOpen = computed({ get: () => Boolean(detail.value), set: open => { if (!open) detail.value = undefined } })
const commandOpen = computed({ get: () => Boolean(action.value), set: open => { if (!open) action.value = undefined } })
let revision = 0, detailRevision = 0
let commandKey = ''

async function load(reset = true): Promise<void> {
  if (tab.value === 'settings' || !session.can('qc_inspection:view')) return
  if (reset) cursors.value = [undefined]
  const sequence = ++revision
  loading.value = true
  error.value = ''
  try {
    const page = await qualityInspections(tab.value === 'nonconforming', status.value || undefined, cursors.value.at(-1))
    if (sequence === revision) {
      items.value = page.items
      next.value = page.nextCursor
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause) && sequence === revision) error.value = messageOf(cause)
  } finally {
    if (sequence === revision) loading.value = false
  }
}

function changePage(forward: boolean): void {
  if (forward && next.value) cursors.value.push(next.value)
  if (!forward && cursors.value.length > 1) cursors.value.pop()
  void load(false)
}

async function open(id: string): Promise<void> {
  const sequence = ++detailRevision
  error.value = ''
  try {
    const result = await qualityInspection(id)
    if (sequence === detailRevision) detail.value = result
  } catch (cause: unknown) {
    if (!isCancelled(cause) && sequence === detailRevision) error.value = messageOf(cause)
  }
}

async function start(selected: NonNullable<typeof action.value>): Promise<void> {
  const row = detail.value
  if (!row) return
  error.value = ''
  commandKey = newIdempotencyKey()
  action.value = selected
  Object.assign(form, {
    qualifiedQty: row.roundQty, unqualifiedQty: '0', concessionQty: '0', concessionReason: '',
    values: row.snapshot.checks.map(check => {
      const current = row.values.find(value => value.roundNo === row.roundNo && value.itemId === check.itemId)
      return { itemId: check.itemId, measuredValue: current?.measuredValue ?? '', judgement: current?.judgement ?? 'ok' }
    }),
    defects: row.defects.filter(value => value.roundNo === row.roundNo).map(value => ({ defectCode: value.defectCode, qty: value.qty, remark: value.remark })),
    action: 'return', qty: row.remainingQty, reason: '',
  })
  if (selected !== 'submit') return
  try {
    const result = await dictionaries('quality_defect')
    if (detail.value?.id === row.id) defectTypes.value = (result.items?.quality_defect ?? []).filter(item => item.enabled)
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}

async function execute(): Promise<void> {
  const row = detail.value, selected = action.value
  if (!row || !selected || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const data = selected === 'submit' ? {
      version: row.version, qualifiedQty: form.qualifiedQty, unqualifiedQty: form.unqualifiedQty,
      concessionQty: form.concessionQty, concessionReason: form.concessionReason,
      values: form.values.filter(item => item.measuredValue.trim()), defects: form.defects,
    } : selected === 'dispose' ? {
      version: row.version, action: form.action, qty: form.qty, reason: form.reason,
    } : { version: row.version, reason: form.reason }
    const result = await changeQualityInspection(row.id, selected, data, commandKey)
    if (detail.value?.id === row.id) detail.value = result
    action.value = undefined
    await load()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function dispositionLink(item: QualityInspectionDetail['dispositions'][number]): string {
  if (!item.targetId) return ''
  if (item.targetType === 'purchase_return' && session.can('purchase_return:view')) return '/purchase-returns/' + item.targetId
  if (item.targetType === 'stock_adjustment' && session.can('stock_adjustment:view')) return '/stock-adjustments/' + item.targetId
  return ''
}

watch(() => session.companyId, () => {
  detailRevision++
  revision++
  items.value = []
  detail.value = undefined
  action.value = undefined
  defectTypes.value = []
  tab.value = session.can('qc_inspection:view') ? 'inspections' : 'settings'
  void load()
}, { immediate: true })
watch([tab, status], () => { void load() })
watch(() => [route.query.inspectionId, session.companyId], () => {
  const id = route.query.inspectionId
  if (typeof id === 'string' && /^\d+$/.test(id) && session.can('qc_inspection:view')) void open(id)
}, { immediate: true })
watch(() => route.query.tab, value => {
  if (value === 'nonconforming' && session.can('qc_inspection:view')) tab.value = 'nonconforming'
}, { immediate: true })
</script>

<template>
  <div class="page-heading"><h1>来料质量</h1></div>
  <el-tabs v-model="tab">
    <el-tab-pane v-if="session.can('qc_inspection:view')" name="inspections" label="来料检单" />
    <el-tab-pane v-if="session.can('qc_inspection:view')" name="nonconforming" label="不合格品处理" />
    <el-tab-pane v-if="session.can('qc_setting:view')" name="settings" label="检验设置" />
  </el-tabs>
  <QualitySettingsPanel v-if="tab === 'settings'" />
  <div v-else class="content-panel">
    <div v-if="tab === 'inspections'" class="filters">
      <el-select v-model="status" aria-label="检验状态">
        <el-option value="" label="全部状态" />
        <el-option v-for="(label, value) in statuses" :key="value" :value="value" :label="label" />
      </el-select>
    </div>
    <el-alert v-if="error && !detail" :title="error" type="error" :closable="false" />
    <el-table v-loading="loading" :data="items" empty-text="暂无符合条件的检验单">
      <el-table-column label="检验单号" min-width="200"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">{{ row.inspectionNo }}</el-button></template></el-table-column>
      <el-table-column prop="skuName" label="商品" min-width="140" />
      <el-table-column prop="supplierName" label="供应商" min-width="130" />
      <el-table-column prop="warehouseName" label="仓库" min-width="130" />
      <el-table-column prop="businessDate" label="收货日期" width="120" />
      <el-table-column label="待检数量" width="105"><template #default="{ row }">{{ formatQuantity(row.roundQty) }}</template></el-table-column>
      <el-table-column label="结果" width="110"><template #default="{ row }">{{ results[row.result as QualityInspection['result']] }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ statuses[row.status as QualityInspection['status']] }}</template></el-table-column>
    </el-table>
    <div class="table-footer">
      <span>第 {{ cursors.length }} 页</span>
      <div><el-button :disabled="cursors.length === 1 || loading" @click="changePage(false)">上一页</el-button><el-button :disabled="!next || loading" @click="changePage(true)">下一页</el-button></div>
    </div>
  </div>
  <el-dialog v-model="detailOpen" :title="detail?.inspectionNo ?? '来料检单'" width="min(1020px, 94vw)" :close-on-click-modal="false">
    <template v-if="detail">
      <el-alert v-if="error && !action" :title="error" type="error" :closable="false" />
      <dl class="quality-summary">
        <div><dt>原收货单</dt><dd><router-link v-if="session.can('goods_receipt:view')" :to="'/goods-receipts/' + detail.sourceId">查看采购收货</router-link><span v-else>{{ detail.sourceId }}</span></dd></div>
        <div><dt>检验模板</dt><dd>{{ detail.snapshot.name }}</dd></div>
        <div><dt>批次 / 库位</dt><dd>{{ detail.lotKey || '—' }} / {{ detail.locationKey || '—' }}</dd></div>
        <div><dt>状态</dt><dd>{{ statuses[detail.status] }} · {{ results[detail.result] }}</dd></div>
        <div><dt>本轮 / 抽样数量</dt><dd>{{ formatQuantity(detail.roundQty) }} / {{ formatQuantity(detail.sampleQty) }}</dd></div>
        <div><dt>合格 / 让步 / 不合格</dt><dd>{{ formatQuantity(detail.qualifiedQty) }} / {{ formatQuantity(detail.concessionQty) }} / {{ formatQuantity(detail.unqualifiedQty) }}</dd></div>
      </dl>
      <p v-if="detail.concessionReason">让步接收原因 {{ detail.concessionReason }}</p>
      <el-table :data="detail.snapshot.checks" empty-text="暂无检验项目">
        <el-table-column prop="name" label="检验项目" />
        <el-table-column prop="standardValue" label="标准" />
        <el-table-column prop="method" label="方法" />
        <el-table-column label="本轮结果"><template #default="{ row }"><span v-for="value in detail.values.filter(item => item.roundNo === detail?.roundNo && item.itemId === row.itemId)" :key="value.itemId">{{ value.measuredValue }} · {{ value.judgement === 'ok' ? '通过' : '未通过' }}</span></template></el-table-column>
      </el-table>
      <template v-if="detail.rounds.length">
        <h3>审核记录</h3>
        <el-table :data="detail.rounds">
          <el-table-column prop="roundNo" label="轮次" />
          <el-table-column prop="qualifiedQty" label="合格" />
          <el-table-column prop="concessionQty" label="让步接收" />
          <el-table-column prop="unqualifiedQty" label="不合格" />
          <el-table-column prop="approverId" label="审核人" min-width="160" />
        </el-table>
      </template>
      <template v-if="detail.dispositions.length">
        <h3>处置记录</h3>
        <el-table :data="detail.dispositions">
          <el-table-column label="处置"><template #default="{ row }">{{ dispositions[row.action as keyof typeof dispositions] }}</template></el-table-column>
          <el-table-column prop="qty" label="数量" />
          <el-table-column prop="reason" label="原因" />
          <el-table-column label="下游单据"><template #default="{ row }"><router-link v-if="dispositionLink(row)" :to="dispositionLink(row)">查看单据</router-link></template></el-table-column>
        </el-table>
      </template>
    </template>
    <template #footer>
      <el-button v-if="detail?.status === 'draft' && session.can('qc_inspection:submit')" type="primary" @click="start('submit')">录入检验结果</el-button>
      <template v-if="detail?.status === 'submitted' && detail.inspectorId !== session.user?.id && session.can('qc_inspection:approve')">
        <el-button @click="start('reject')">驳回</el-button><el-button type="primary" @click="start('approve')">审核放行</el-button>
      </template>
      <el-button v-if="detail?.status === 'approved' && Number(detail.remainingQty) > 0 && session.can('qc_inspection:dispose')" type="primary" @click="start('dispose')">处置不合格品</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="commandOpen" :title="action === 'submit' ? '提交检验结果' : action === 'approve' ? '审核放行' : action === 'reject' ? '驳回检验结果' : '不合格品处置'" width="min(820px, 94vw)" :close-on-click-modal="false">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="busy">
      <template v-if="action === 'submit'">
        <div class="sales-form-grid">
          <el-form-item label="合格数量"><el-input v-model="form.qualifiedQty" /></el-form-item>
          <el-form-item label="不合格数量"><el-input v-model="form.unqualifiedQty" /></el-form-item>
          <el-form-item label="让步接收数量"><el-input v-model="form.concessionQty" /></el-form-item>
          <el-form-item v-if="Number(form.concessionQty) > 0" label="让步接收原因"><el-input v-model="form.concessionReason" maxlength="500" /></el-form-item>
        </div>
        <div v-for="(value, index) in form.values" :key="value.itemId" class="quality-line">
          <el-form-item :label="detail?.snapshot.checks[index]?.name ?? '检验项目'" :required="detail?.snapshot.checks[index]?.required">
            <el-input v-model="value.measuredValue" :placeholder="detail?.snapshot.checks[index]?.standardValue" maxlength="160" />
          </el-form-item>
          <el-form-item v-if="detail?.snapshot.checks[index]?.type === 'qualitative'" label="判定">
            <el-select v-model="value.judgement"><el-option value="ok" label="通过" /><el-option value="ng" label="未通过" /></el-select>
          </el-form-item>
        </div>
        <div v-for="(defect, index) in form.defects" :key="index" class="quality-line">
          <el-form-item label="缺陷"><el-select v-model="defect.defectCode"><el-option v-for="option in defectTypes" :key="option.value" :value="option.value" :label="option.label" /></el-select></el-form-item>
          <el-form-item label="缺陷数量"><el-input v-model="defect.qty" /></el-form-item>
          <el-form-item label="说明"><el-input v-model="defect.remark" maxlength="200" /></el-form-item>
          <el-button link type="danger" @click="form.defects.splice(index, 1)">移除</el-button>
        </div>
        <el-button v-if="defectTypes.length" :disabled="form.defects.length >= 100" @click="form.defects.push({ defectCode: '', qty: '' })">添加缺陷</el-button>
      </template>
      <template v-else-if="action === 'dispose'">
        <div class="sales-form-grid">
          <el-form-item label="处置方式"><el-select v-model="form.action"><el-option v-for="(label, key) in dispositions" :key="key" :value="key" :label="label" /></el-select></el-form-item>
          <el-form-item label="处置数量"><el-input v-model="form.qty" /></el-form-item>
        </div>
        <el-form-item label="处置原因"><el-input v-model="form.reason" type="textarea" maxlength="200" /></el-form-item>
      </template>
      <el-form-item v-else-if="action === 'reject'" label="驳回原因"><el-input v-model="form.reason" type="textarea" maxlength="200" /></el-form-item>
      <p v-else>审核后放行合格和让步接收的数量，不合格数量继续冻结。</p>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="action = undefined">取消</el-button><el-button type="primary" :loading="busy" @click="execute">确认</el-button></template>
  </el-dialog>
</template>

<style scoped>
.quality-summary, .quality-line {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
  gap: 12px;
  align-items: center;
}
.quality-summary dt { color: var(--el-text-color-secondary); }
.quality-summary dd { margin: 4px 0 0; }
</style>
