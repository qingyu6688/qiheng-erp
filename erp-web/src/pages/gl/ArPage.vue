<script setup lang="ts">
import {
  ElDescriptions,
  ElDescriptionsItem,
} from 'element-plus/es/components/descriptions/index.mjs'
import { ElEmpty } from 'element-plus/es/components/empty/index.mjs'
import { ElTabPane, ElTabs } from 'element-plus/es/components/tabs/index.mjs'
import { computed, ref, watch, onScopeDispose } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { arMetadata, arRows, arRead, arCommand } from '../../api/ar'
import type { ArMetadata, AgingConfig, CollectionDetail } from '../../api/ar'
import { messageOf, isCancelled, newIdempotencyKey } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DataRow, FormField, SelectOption } from '../../types/admin'
import type { WorkflowSelections } from '../../types/workflow'
import DataFields from '../../components/DataFields.vue'
import RecordFrame from '../../components/RecordFrame.vue'
import WorkflowStarterSelector from '../../components/workflow/WorkflowStarterSelector.vue'
import { formatDateTime } from '../../utils/format'
const props = defineProps<{ kind: 'credit' | 'aging' | 'collections' | 'bad-debt' }>()
const route = useRoute(),
  session = useSessionStore(),
  tab = ref(route.params.id ? 'increases' : 'limits'),
  rows = ref<DataRow[]>([]),
  items = ref<DataRow[]>([]),
  metadata = ref<ArMetadata>({ customers: [], users: [] }),
  error = ref(''),
  busy = ref(false),
  open = ref(false),
  selected = ref<DataRow>(),
  values = ref<Record<string, unknown>>({}),
  followDetail = ref<CollectionDetail>(),
  flowRow = ref<DataRow>(),
  selections = ref<WorkflowSelections>({}),
  ready = ref(false)
const config = ref<AgingConfig>({
    version: 0,
    bounds: [30, 60, 90, 180],
    rates: ['0', '0.05', '0.1', '0.2', '0.5'],
  }),
  rateRows = ref<{ days: number; rate: string }[]>([]),
  lastRate = ref('0.5'),
  estimate = ref<{
    target: string
    existing: string
    suggestedIncrease: string
    suggestedRelease: string
    notice: string
  }>(),
  exposure = ref<Record<string, unknown>>()
const titles = {
  credit: '客户信用与临时额度',
  aging: '账龄区间与计提比例',
  collections: '催收计划与跟进',
  'bad-debt': '坏账认定与核销',
}
const labels: Record<string, string> = {
  partnerName: '客户',
  customerName: '客户',
  limitAmount: '基础额度',
  overdueDaysLimit: '超期上限（天）',
  policy: '策略',
  amount: '金额',
  businessDate: '业务日期',
  validFrom: '有效起始',
  validTo: '有效截止',
  planDate: '计划日期',
  planAmount: '计划金额',
  ownerName: '负责人',
  status: '状态',
  kind: '类型',
  method: '认定方法',
  reason: '认定依据',
  openAmount: '未清余额',
}
const statuses: Record<string, string> = {
  draft: '草稿',
  pending: '待审批',
  approved: '已批准',
  rejected: '已驳回',
  cancelled: '已取消',
  posted: '已记账',
  open: '进行中',
  completed: '已完成',
  warn: '提示',
  approval: '需要审批',
  block: '禁止',
  provision: '计提准备',
  release: '转回准备',
  write_off: '实际核销',
  aging: '账龄比例',
  individual: '个别认定',
  actual: '实际损失',
}
const endpoint = computed(() =>
  props.kind === 'credit'
    ? tab.value === 'limits'
      ? 'credit-limits'
      : 'credit-increases'
    : props.kind === 'collections'
      ? 'collection-plans'
      : 'bad-debts',
)
const permission = computed(() =>
  props.kind === 'credit'
    ? 'credit_limit'
    : props.kind === 'collections'
      ? 'collection'
      : 'bad_debt',
)
const fields = computed<FormField[]>(() => {
  const customer: FormField = {
    key: props.kind === 'bad-debt' ? 'customerId' : 'partnerId',
    label: '客户',
    kind: 'select',
    required: true,
  }
  const amount: FormField = { key: 'amount', label: '金额', required: true }
  const reason: FormField = {
    key: 'reason',
    label: '认定依据与原因',
    kind: 'textarea',
    required: true,
    max: 500,
  }
  if (props.kind === 'credit' && tab.value === 'limits')
    return [
      customer,
      { key: 'limitAmount', label: '基础信用额度', required: true },
      { key: 'overdueDaysLimit', label: '超期天数上限（0表示不控制）', kind: 'number', initial: 0 },
      {
        key: 'policy',
        label: '超额或超期策略',
        kind: 'select',
        required: true,
        initial: 'block',
        options: [
          { value: 'warn', label: '提示' },
          { value: 'approval', label: '需要人工信用审批' },
          { value: 'block', label: '禁止' },
        ],
      },
      { key: 'validFrom', label: '有效起始日期', kind: 'date', required: true },
      { key: 'validTo', label: '有效截止日期', kind: 'date', emptyAsNull: true },
    ]
  if (props.kind === 'credit')
    return [
      customer,
      amount,
      { key: 'validFrom', label: '有效起始日期', kind: 'date', required: true },
      { key: 'validTo', label: '有效截止日期（最多一年）', kind: 'date', required: true },
      reason,
    ]
  if (props.kind === 'collections')
    return followDetail.value
      ? [
          { key: 'content', label: '跟进内容', kind: 'textarea', required: true, max: 1000 },
          { key: 'promisedDate', label: '承诺回款日期', kind: 'date', emptyAsNull: true },
          { key: 'promisedAmount', label: '承诺回款金额', emptyAsNull: true },
          {
            key: 'status',
            label: '处理后状态',
            kind: 'select',
            initial: 'open',
            options: [
              { value: 'open', label: '继续跟进' },
              { value: 'completed', label: '完成计划' },
              { value: 'cancelled', label: '取消计划' },
            ],
          },
        ]
      : [
          { key: 'openItemId', label: '关联应收未清项', kind: 'select', required: true },
          { key: 'ownerId', label: '负责人', kind: 'select', required: true },
          { key: 'planDate', label: '计划回款日期', kind: 'date', required: true },
          { key: 'planAmount', label: '计划回款金额', required: true },
        ]
  return [
    customer,
    {
      key: 'kind',
      label: '处理类型',
      kind: 'select',
      required: true,
      initial: 'provision',
      options: [
        { value: 'provision', label: '计提坏账准备（企业准则）' },
        { value: 'release', label: '转回多提准备（企业准则）' },
        { value: 'write_off', label: '核销实际坏账' },
      ],
    },
    {
      key: 'method',
      label: '认定方法',
      kind: 'select',
      required: true,
      initial: 'individual',
      options: [
        { value: 'aging', label: '账龄比例估计' },
        { value: 'individual', label: '个别认定' },
        { value: 'actual', label: '实际损失' },
      ],
    },
    { key: 'openItemId', label: '被核销未清项（准备认定不填）', kind: 'select', emptyAsNull: true },
    amount,
    { key: 'businessDate', label: '业务日期', kind: 'date', required: true },
    reason,
  ]
})
const options = computed<Record<string, SelectOption[]>>(() => ({
  partnerId: metadata.value.customers.map((p) => ({ value: p.id, label: p.code + ' ' + p.name })),
  customerId: metadata.value.customers.map((p) => ({ value: p.id, label: p.code + ' ' + p.name })),
  ownerId: metadata.value.users.map((u) => ({ value: u.id, label: u.name })),
  openItemId: items.value.map((i) => ({
    value: i.id,
    label:
      String(i.partnerName || '') + ' / ' + String(i.agingDate) + ' / 余额 ' + String(i.openAmount),
  })),
}))
const columns = computed(() =>
  props.kind === 'credit'
    ? tab.value === 'limits'
      ? ['partnerName', 'limitAmount', 'overdueDaysLimit', 'policy', 'validFrom', 'validTo']
      : ['partnerName', 'amount', 'validFrom', 'validTo', 'status', 'reason']
    : props.kind === 'collections'
      ? ['partnerName', 'planDate', 'planAmount', 'openAmount', 'ownerName', 'status']
      : ['customerName', 'kind', 'method', 'amount', 'businessDate', 'status', 'reason'],
)
let revision = 0,
  formKey = newIdempotencyKey(),
  flowKey = newIdempotencyKey()
async function load(): Promise<void> {
  const current = ++revision
  rows.value = []
  items.value = []
  metadata.value = { customers: [], users: [] }
  error.value = ''
  busy.value = true
  try {
    const m = await arMetadata()
    if (current !== revision) return
    metadata.value = m
    if (props.kind === 'aging') {
      const c = await arRead<AgingConfig>('aging-config')
      if (current !== revision) return
      config.value = c
      rateRows.value = c.bounds.map((days, i) => ({ days, rate: c.rates[i]! }))
      lastRate.value = c.rates.at(-1)!
    } else {
      const data = await arRows(endpoint.value)
      if (current !== revision) return
      rows.value = data
      if (props.kind === 'collections' || props.kind === 'bad-debt') {
        const source = await arRows(
          props.kind === 'collections' ? 'collection-items' : 'bad-debts/items',
        )
        if (current === revision) items.value = source
      }
    }
  } catch (e: unknown) {
    if (current === revision && !isCancelled(e)) error.value = messageOf(e)
  } finally {
    if (current === revision) busy.value = false
  }
}
async function edit(row?: DataRow, follow = false): Promise<void> {
  followDetail.value = undefined
  estimate.value = undefined
  selected.value = row
  formKey = newIdempotencyKey()
  if (follow && row)
    try {
      followDetail.value = await arRead<CollectionDetail>('collection-plans/' + row.id)
    } catch (e: unknown) {
      error.value = messageOf(e)
      return
    }
  values.value = Object.fromEntries(
    fields.value.map((field) => [field.key, row?.[field.key] ?? field.initial ?? '']),
  )
  for (const field of fields.value)
    if (field.kind === 'date' && !values.value[field.key] && !field.emptyAsNull)
      values.value[field.key] = new Date().toLocaleDateString('en-CA')
  open.value = true
}
async function save(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const body: Record<string, unknown> = { ...values.value, version: selected.value?.version ?? 0 }
    for (const field of fields.value)
      if (field.emptyAsNull && !body[field.key]) body[field.key] = null
    const path = followDetail.value
      ? 'collection-plans/' + followDetail.value.id + '/followups'
      : endpoint.value + (selected.value ? '/' + selected.value.id : '')
    await arCommand(path, body, formKey, selected.value && !followDetail.value ? 'PUT' : 'POST')
    open.value = false
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
async function action(row: DataRow, action: string): Promise<void> {
  if (props.kind === 'credit' && action === 'submit') {
    flowRow.value = row
    selections.value = {}
    flowKey = newIdempotencyKey()
    return
  }
  try {
    const { value } = await ElMessageBox.prompt('请填写处理说明', '确认处理', {
      inputValidator: (v: string) => (v.trim() ? true : '请填写说明'),
    })
    await arCommand(
      endpoint.value + '/' + row.id + '/' + action,
      { version: row.version, reason: value },
      newIdempotencyKey(),
    )
    await load()
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close' && !isCancelled(e)) error.value = messageOf(e)
  }
}
async function submitFlow(): Promise<void> {
  if (!flowRow.value) return
  busy.value = true
  try {
    await arCommand(
      'credit-increases/' + flowRow.value.id + '/submit',
      {
        version: flowRow.value.version,
        reason: '提交临时调增审批',
        workflowAssignees: selections.value,
      },
      flowKey,
    )
    flowRow.value = undefined
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
async function saveAging(): Promise<void> {
  busy.value = true
  try {
    await arCommand(
      'aging-config',
      {
        version: config.value.version,
        bounds: rateRows.value.map((r) => r.days),
        rates: [...rateRows.value.map((r) => r.rate), lastRate.value],
      },
      formKey,
      'PUT',
    )
    formKey = newIdempotencyKey()
    await load()
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  } finally {
    busy.value = false
  }
}
async function estimateDebt(): Promise<void> {
  try {
    estimate.value = await arRead('bad-debts/estimate', {
      customerId: values.value.customerId,
      asOf: values.value.businessDate,
    })
    if (estimate.value)
      values.value.amount =
        values.value.kind === 'release'
          ? estimate.value.suggestedRelease
          : estimate.value.suggestedIncrease
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  }
}
async function inspect(row: DataRow): Promise<void> {
  try {
    exposure.value = await arRead('credit-limits/exposure/' + String(row.partnerId))
  } catch (e: unknown) {
    if (!isCancelled(e)) error.value = messageOf(e)
  }
}
watch(
  [() => session.companyId, () => props.kind, tab],
  () => {
    open.value = false
    flowRow.value = undefined
    formKey = newIdempotencyKey()
    void load()
  },
  { immediate: true },
)
onScopeDispose(() => {
  revision++
})
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>{{ titles[kind] }}</h1>
      <p v-if="kind === 'credit'">
        额度占用包含待审、已审批及已出库未转应收的订单承诺；发货时重新检查。
      </p>
      <p v-else-if="kind === 'collections'">
        按来源应收的当前部门范围查看；到期前3天、当天及逾期每7天提醒负责人。
      </p>
      <p v-else-if="kind === 'bad-debt'">
        坏账须独立复核，核销通过原未清项分配机制办理。小企业准则按实际损失处理。
      </p>
      <p v-else>应收、应付账龄共用当前公司区间；计提比例仅用于财务估计。</p>
    </div>
    <el-button
      v-if="kind !== 'aging' && session.can(permission + ':edit')"
      type="primary"
      @click="edit()"
      >{{
        kind === 'credit' && tab === 'limits'
          ? '配置客户额度'
          : kind === 'credit'
            ? '申请临时调增'
            : kind === 'collections'
              ? '新建催收计划'
              : '新建坏账认定'
      }}</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <section v-if="kind === 'aging'" class="panel ar-panel">
    <el-table :data="rateRows"
      ><el-table-column label="账龄上限（含当天）"
        ><template #default="{ row }"
          ><el-input-number
            v-model="row.days"
            :min="0"
            :max="36500"
            :disabled="!session.can('aging_config:edit')" /></template></el-table-column
      ><el-table-column label="该区间计提比例（0至1）"
        ><template #default="{ row }"
          ><el-input
            v-model="row.rate"
            :disabled="!session.can('aging_config:edit')" /></template></el-table-column
      ><el-table-column width="90"
        ><template #default="{ $index }"
          ><el-button
            v-if="session.can('aging_config:edit')"
            link
            @click="rateRows.splice($index, 1)"
            >移除</el-button
          ></template
        ></el-table-column
      ></el-table
    ><el-form-item label="超过最后一个上限的比例"
      ><el-input
        v-model="lastRate"
        :disabled="!session.can('aging_config:edit')"
        style="width: 200px" /></el-form-item
    ><el-button
      v-if="session.can('aging_config:edit')"
      @click="rateRows.push({ days: (rateRows.at(-1)?.days || 0) + 30, rate: '0' })"
      >添加区间</el-button
    ><el-button
      v-if="session.can('aging_config:edit')"
      type="primary"
      :loading="busy"
      @click="saveAging"
      >保存区间与比例</el-button
    >
  </section>
  <section v-else class="panel ar-panel">
    <el-tabs v-if="kind === 'credit'" v-model="tab"
      ><el-tab-pane label="客户信用额度" name="limits" /><el-tab-pane
        label="临时调增申请"
        name="increases" /></el-tabs
    ><el-table :data="rows"
      ><el-table-column
        v-for="column in columns"
        :key="column"
        :label="labels[column]"
        min-width="130"
        ><template #default="{ row }">{{
          statuses[String(row[column])] || row[column] || '—'
        }}</template></el-table-column
      ><el-table-column label="操作" min-width="230"
        ><template #default="{ row }"
          ><template v-if="kind === 'credit' && tab === 'limits'"
            ><el-button v-if="session.can('credit_limit:edit')" link @click="edit(row)"
              >编辑</el-button
            ><el-button link @click="inspect(row)">占用明细</el-button></template
          ><template v-else-if="kind === 'collections'"
            ><el-button link @click="edit(row, true)">跟进记录</el-button></template
          ><template v-else
            ><el-button
              v-if="
                ['draft', 'rejected'].includes(String(row.status)) &&
                session.can(permission + ':edit')
              "
              link
              @click="action(row, 'submit')"
              >提交</el-button
            ><el-button
              v-if="row.status === 'pending' && session.can(permission + ':approve')"
              link
              @click="action(row, 'approve')"
              >审批</el-button
            ><el-button
              v-if="row.status === 'pending' && session.can(permission + ':approve')"
              link
              @click="action(row, 'reject')"
              >驳回</el-button
            ><router-link
              v-if="row.wfInstanceId && row.wfInstanceId !== '0'"
              class="text-link"
              :to="'/workflow/instances/' + row.wfInstanceId"
              >审批进度</router-link
            ></template
          ></template
        ></el-table-column
      ><template #empty>{{ busy ? '正在读取往来资料…' : '当前范围内暂无记录' }}</template></el-table
    >
  </section>
  <RecordFrame
    v-if="open"
    :title="followDetail ? '催收跟进' : selected ? '编辑额度' : '新建记录'"
    width="720px"
    :busy="busy"
    @close="open = false"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div v-if="followDetail">
      <article v-for="f in followDetail.followups" :key="f.id" class="followup">
        <small>{{ formatDateTime(f.createdAt) }}</small>
        <p>{{ f.content }}</p>
        <p v-if="f.promisedDate">
          承诺 {{ f.promisedDate }} 回款 {{ f.promisedAmount || '待确认' }}
        </p>
      </article>
      <el-empty v-if="!followDetail.followups.length" description="尚无跟进记录" />
    </div>
    <DataFields
      :fields="fields"
      :values="values"
      :options="options"
      :disabled="busy || (kind === 'collections' && !session.can('collection:edit'))"
      @change="(key, value) => (values[key] = value)"
    /><template
      v-if="kind === 'bad-debt' && values.kind !== 'write_off' && values.method === 'aging'"
      ><el-button :disabled="!values.customerId || !values.businessDate" @click="estimateDebt"
        >计算账龄建议</el-button
      >
      <p v-if="estimate">
        目标准备 {{ estimate.target }} · 已有准备 {{ estimate.existing }} · 建议补提
        {{ estimate.suggestedIncrease }}。{{ estimate.notice }}
      </p></template
    ><template #footer
      ><el-button :disabled="busy" @click="open = false">关闭</el-button
      ><el-button
        v-if="session.can(permission + ':edit')"
        type="primary"
        :loading="busy"
        @click="save"
        >保存</el-button
      ></template
    ></RecordFrame
  >
  <RecordFrame v-if="flowRow" title="提交临时额度调增" :busy="busy" @close="flowRow = undefined"
    ><el-alert v-if="error" :title="error" type="error" :closable="false" /><WorkflowStarterSelector
      v-model="selections"
      business-type="credit_increase"
      :business-id="flowRow.id"
      :disabled="busy"
      @ready="ready = $event"
    /><template #footer
      ><el-button :disabled="busy" @click="flowRow = undefined">取消</el-button
      ><el-button type="primary" :loading="busy" :disabled="!ready" @click="submitFlow"
        >提交审批</el-button
      ></template
    ></RecordFrame
  >
  <el-dialog :model-value="!!exposure" title="当前信用占用" @close="exposure = undefined"
    ><template v-if="exposure"
      ><el-descriptions :column="1" border
        ><el-descriptions-item label="有效额度">{{
          exposure.creditLimit || '未配置有效额度'
        }}</el-descriptions-item
        ><el-descriptions-item label="当前占用">{{
          exposure.creditExposure || '—'
        }}</el-descriptions-item
        ><el-descriptions-item label="最长未清账龄"
          >{{ exposure.creditOverdueDays || '0' }} 天</el-descriptions-item
        ><el-descriptions-item label="检查结果">{{
          exposure.overCreditLimit ? '超额或超期' : '在有效范围内'
        }}</el-descriptions-item></el-descriptions
      ></template
    ></el-dialog
  >
</template>
<style scoped>
.ar-panel {
  padding: 22px;
}
.ar-panel > .el-form-item {
  margin-top: 20px;
}
.followup {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-light);
}
.followup small {
  color: var(--el-text-color-secondary);
}
</style>
