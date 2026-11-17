<script setup lang="ts">
import { computed, onMounted, onScopeDispose, reactive, ref } from 'vue'
import { allRecords, record, records } from '../../api/admin'
import { crmCyclePage, crmCycleRead, crmCycleSave } from '../../api/crm-cycle'
import { isCancelled, messageOf, newIdempotencyKey } from '../../api/http'
import { companyUsers, departments as readDepartments } from '../../api/org'
import WorkflowStarterSelector from '../../components/workflow/WorkflowStarterSelector.vue'
import type { DataRow, SelectOption } from '../../types/admin'
import type {
  CrmContractOrderLine,
  CrmCycleMode,
  CrmCycleRow,
  CrmProductLine,
  CrmStageGroup,
} from '../../types/crm-cycle'
import type { WorkflowBusinessType, WorkflowSelections } from '../../types/workflow'
import { useSessionStore } from '../../stores/session'

const props = defineProps<{
  mode: CrmCycleMode
  source?: CrmCycleRow
  receiptId?: string
  partnerId?: string
  action?: 'advance' | 'win' | 'lose' | 'invalidate'
}>()
const emit = defineEmits<{ close: []; saved: [row: CrmCycleRow] }>()
const session = useSessionStore()
const company = session.companyId
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date())
const text = (value: unknown): string => (value == null ? '' : String(value))
const draft = reactive({
  name: text(props.source?.name),
  partnerId: text(props.source?.partnerId) || props.partnerId || '',
  stageGroupId: text(props.source?.stageGroupId),
  stageId: text(props.source?.stageId),
  businessId: text(props.source?.businessId),
  signerContactId: text(props.source?.signerContactId),
  expectedAmount: text(props.source?.expectedAmount),
  expectedDate: text(props.source?.expectedDate) || today,
  signDate: text(props.source?.signDate) || today,
  startDate: text(props.source?.startDate) || today,
  endDate: text(props.source?.endDate) || today,
  priceTaxInclusive: props.source?.priceTaxInclusive ?? true,
  businessDate: today,
  deliveryDate: '',
  reason: '',
  comment: '',
  contractId: '',
  periodNo: 1,
  planDate: today,
  planAmount: '',
  remindDays: 3,
  receiptId: props.receiptId ?? '',
  targetType: 'user',
  targetId: '',
  year: Number(today.slice(0, 4)),
  month: Number(today.slice(5, 7)),
  kind: 'contract',
  amount: '',
})
const requestKey = newIdempotencyKey()
const blankProduct = (): CrmProductLine => ({
  skuId: '',
  unitId: '',
  qty: '1',
  unitPrice: '',
  taxRate: '',
  discountRate: '',
})
const products = ref<CrmProductLine[]>(
  props.source?.lines?.map((row) => ({
    skuId: text(row.skuId),
    unitId: text(row.unitId),
    qty: text(row.qty),
    unitPrice: text(row.unitPrice),
    taxRate: text(row.taxRate),
    discountRate: text(row.discountRate),
  })) ?? [blankProduct()],
)
const orderLines = ref<CrmContractOrderLine[]>(
  (props.source?.lines ?? [])
    .filter((row) => Number(row.remainingQty) > 0)
    .map((row) => ({
      contractLineId: row.id,
      warehouseId: '',
      qty: '',
      label: text(row.skuName) || text(row.skuId),
      remainingQty: text(row.remainingQty),
    })),
)
const stageRows = ref([
  { name: '初步沟通', winRate: '0.2' },
  { name: '意向确认', winRate: '0.6' },
  { name: '签约', winRate: '0.9' },
])
const customers = ref<SelectOption[]>([])
const skus = ref<SelectOption[]>([])
const units = ref<SelectOption[]>([])
const warehouses = ref<SelectOption[]>([])
const contracts = ref<SelectOption[]>([])
const opportunities = ref<SelectOption[]>([])
const contacts = ref<SelectOption[]>([])
const receipts = ref<SelectOption[]>([])
const users = ref<SelectOption[]>([])
const departments = ref<SelectOption[]>([])
const groups = ref<CrmStageGroup[]>([])
const selections = ref<WorkflowSelections>({})
const ready = ref(false)
const loading = ref(true)
const busy = ref(false)
const error = ref('')
let active = true
const current = (): boolean => active && session.companyId === company

const titles: Record<CrmCycleMode, string> = {
  business: '商机',
  contract: '合同',
  stage: '新建阶段组',
  'stage-action': '调整商机',
  submit: '提交合同审批',
  order: '生成销售订单',
  plan: '新建回款计划',
  attach: '关联收款单',
  target: '维护业绩目标',
}
const stageOptions = computed(
  () => groups.value.find((group) => group.id === draft.stageGroupId)?.stages ?? [],
)
const isEditing = computed(() =>
  Boolean(props.source && (props.mode === 'business' || props.mode === 'contract')),
)

function label(row: DataRow): string {
  return [row.code, row.name || row.documentNo || row.partnerName || row.id]
    .filter(Boolean)
    .join(' ')
}
function options(rows: DataRow[]): SelectOption[] {
  return rows.map((row) => ({ value: row.id, label: label(row) }))
}
function departmentOptions(rows: Awaited<ReturnType<typeof readDepartments>>): SelectOption[] {
  return rows.map((row) => ({ value: row.id, label: row.name }))
}

async function related(): Promise<void> {
  const partner = draft.partnerId
  if (!partner) return
  const [deals, people] = await Promise.all([
    session.can('crm_business:view')
      ? crmCyclePage('businesses', { partnerId: partner, result: 'won', limit: 200 })
      : undefined,
    session.can('crm_customer:view')
      ? crmCycleRead<{ items: DataRow[] }>(`customers/${encodeURIComponent(partner)}/contacts`)
      : undefined,
  ])
  if (current() && partner === draft.partnerId) {
    opportunities.value = options(deals?.items ?? [])
    contacts.value = options(people?.items ?? [])
  }
}
function changePartner(): void {
  draft.businessId = ''
  draft.signerContactId = ''
  opportunities.value = []
  contacts.value = []
  void related().catch((cause: unknown) => {
    if (current() && !isCancelled(cause)) error.value = messageOf(cause)
  })
}

async function load(): Promise<void> {
  try {
    const mode = props.mode
    if (mode === 'business' || mode === 'contract') {
      const [parties, articles, measures] = await Promise.all([
        crmCyclePage('customers', { limit: 200 }),
        allRecords('skus'),
        allRecords('units'),
      ])
      if (!current()) return
      customers.value = parties.items.map((row) => ({
        value: row.partnerId ?? row.id,
        label: label(row),
      }))
      skus.value = options(articles)
      units.value = options(measures)
    }
    if (mode === 'business' || mode === 'stage-action') {
      const values = await crmCycleRead<CrmStageGroup[]>('stage-groups')
      if (!current()) return
      groups.value = values
      if (!draft.stageGroupId) draft.stageGroupId = values[0]?.id ?? ''
      if (!draft.stageId) draft.stageId = stageOptions.value[0]?.id ?? ''
    }
    if (mode === 'contract') await related()
    if (mode === 'order')
      warehouses.value = options(await allRecords('warehouses', true, company ?? undefined))
    if (mode === 'plan')
      contracts.value = options(
        (
          await crmCyclePage('contracts', {
            status: 'approved',
            limit: 200,
            partnerId: props.partnerId,
          })
        ).items,
      )
    if (mode === 'attach') {
      const available = props.receiptId
        ? [await record(`receipts/${encodeURIComponent(props.receiptId)}`, true)]
        : (await records('receipts', true, { limit: 200 })).items
      receipts.value = options(
        available.filter((row) => row.receiptType === 'customer' && row.status !== 'reversed'),
      )
      if (props.receiptId && !receipts.value.length)
        throw new Error('只有有效的客户收款单可以关联合同')
    }
    if (mode === 'target') {
      const [members, tree] = await Promise.all([companyUsers(), readDepartments()])
      users.value = members.items.map((user) => ({
        value: user.id,
        label: user.displayName || user.username,
      }))
      departments.value = departmentOptions(tree)
    }
  } catch (cause: unknown) {
    if (current() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current()) loading.value = false
  }
}

function productPayload(): CrmProductLine[] {
  return products.value
    .filter((line) => line.skuId || line.unitId || line.unitPrice)
    .map((line) => ({
      ...line,
      taxRate: line.taxRate || undefined,
      discountRate: line.discountRate || undefined,
    }))
}

async function saveDocument(): Promise<CrmCycleRow> {
  const id = props.source?.id
  const method = id ? 'PUT' : 'POST'
  const base = {
    name: draft.name,
    partnerId: draft.partnerId,
    version: props.source?.version,
    lines: productPayload(),
  }
  if (props.mode === 'business')
    return crmCycleSave(
      id ? `businesses/${id}` : 'businesses',
      {
        ...base,
        stageGroupId: draft.stageGroupId,
        stageId: draft.stageId,
        expectedDate: draft.expectedDate,
        expectedAmount: draft.expectedAmount || undefined,
      },
      method,
    )
  return crmCycleSave(
    id ? `contracts/${id}` : 'contracts',
    {
      ...base,
      businessId: draft.businessId || undefined,
      signerContactId: draft.signerContactId || undefined,
      signDate: draft.signDate,
      startDate: draft.startDate,
      endDate: draft.endDate,
      priceTaxInclusive: draft.priceTaxInclusive,
    },
    method,
  )
}

function save(): Promise<CrmCycleRow> {
  const id = props.source?.id
  switch (props.mode) {
    case 'business':
    case 'contract':
      return saveDocument()
    case 'stage':
      return crmCycleSave('stage-groups', { name: draft.name, stages: stageRows.value })
    case 'stage-action':
      return crmCycleSave(`businesses/${id}/${props.action}`, {
        version: props.source?.version,
        stageId: draft.stageId,
        reason: draft.reason,
      })
    case 'submit':
      return crmCycleSave(`contracts/${id}/submit`, {
        version: props.source?.version,
        comment: draft.comment,
        workflowAssignees: selections.value,
      })
    case 'order':
      return crmCycleSave(
        `contracts/${id}/create-sales-order`,
        {
          businessDate: draft.businessDate,
          deliveryDate: draft.deliveryDate || undefined,
          lines: orderLines.value
            .filter((line) => line.qty.trim())
            .map(({ contractLineId, warehouseId, qty }) => ({ contractLineId, warehouseId, qty })),
        },
        'POST',
        requestKey,
      )
    case 'plan':
      return crmCycleSave('receivable-plans', {
        contractId: draft.contractId,
        periodNo: draft.periodNo,
        planDate: draft.planDate,
        planAmount: draft.planAmount,
        remindDays: draft.remindDays,
      })
    case 'attach':
      return crmCycleSave('receivables', { planId: id, receiptId: draft.receiptId })
    case 'target':
      return crmCycleSave('performance-targets', {
        targetType: draft.targetType,
        targetId: draft.targetId,
        year: draft.year,
        month: draft.month,
        kind: draft.kind,
        amount: draft.amount,
      })
  }
}

async function submit(): Promise<void> {
  if (busy.value || loading.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await save()
    if (current()) emit('saved', result)
  } catch (cause: unknown) {
    if (current() && !isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (current()) busy.value = false
  }
}

onMounted(load)
onScopeDispose(() => {
  active = false
})
</script>

<template>
  <el-dialog
    :model-value="true"
    :close-on-click-modal="false"
    :title="titles[mode]"
    width="min(880px, calc(100vw - 32px))"
    @close="emit('close')"
  >
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="busy || loading" @submit.prevent="submit">
      <template v-if="mode === 'business' || mode === 'contract' || mode === 'stage'">
        <el-form-item label="名称"><el-input v-model="draft.name" maxlength="120" /></el-form-item>
      </template>
      <template v-if="mode === 'business' || mode === 'contract'">
        <el-form-item label="客户">
          <el-select
            v-model="draft.partnerId"
            filterable
            :disabled="isEditing"
            @change="changePartner"
          >
            <el-option v-for="item in customers" :key="item.value" v-bind="item" />
          </el-select>
        </el-form-item>
      </template>
      <template v-if="mode === 'business'">
        <div class="crm-fields">
          <el-form-item label="阶段组"
            ><el-select
              v-model="draft.stageGroupId"
              :disabled="isEditing"
              @change="draft.stageId = stageOptions[0]?.id ?? ''"
            >
              <el-option
                v-for="group in groups"
                :key="group.id"
                :label="group.name"
                :value="group.id"
              /> </el-select
          ></el-form-item>
          <el-form-item label="阶段"
            ><el-select v-model="draft.stageId" :disabled="isEditing">
              <el-option
                v-for="stage in stageOptions"
                :key="stage.id"
                :label="stage.name"
                :value="stage.id"
              /> </el-select
          ></el-form-item>
          <el-form-item label="预计成交日"
            ><el-input v-model="draft.expectedDate" type="date"
          /></el-form-item>
          <el-form-item label="预计金额"
            ><el-input v-model="draft.expectedAmount" placeholder="留空按明细汇总"
          /></el-form-item>
        </div>
      </template>
      <template v-if="mode === 'contract'">
        <div class="crm-fields">
          <el-form-item label="已赢单商机"
            ><el-select v-model="draft.businessId" clearable filterable>
              <el-option
                v-for="item in opportunities"
                :key="item.value"
                v-bind="item"
              /> </el-select
          ></el-form-item>
          <el-form-item label="签约联系人"
            ><el-select v-model="draft.signerContactId" clearable filterable>
              <el-option v-for="item in contacts" :key="item.value" v-bind="item" /> </el-select
          ></el-form-item>
          <el-form-item label="签约日"
            ><el-input v-model="draft.signDate" type="date"
          /></el-form-item>
          <el-form-item label="开始日"
            ><el-input v-model="draft.startDate" type="date"
          /></el-form-item>
          <el-form-item label="结束日"
            ><el-input v-model="draft.endDate" type="date"
          /></el-form-item>
          <el-form-item label="价格口径"
            ><el-checkbox v-model="draft.priceTaxInclusive">单价含税</el-checkbox></el-form-item
          >
        </div>
      </template>
      <template v-if="mode === 'business' || mode === 'contract'">
        <div class="crm-lines" v-for="(line, index) in products" :key="index">
          <el-form-item label="商品"
            ><el-select v-model="line.skuId" filterable
              ><el-option v-for="item in skus" :key="item.value" v-bind="item" /></el-select
          ></el-form-item>
          <el-form-item label="单位"
            ><el-select v-model="line.unitId" filterable
              ><el-option v-for="item in units" :key="item.value" v-bind="item" /></el-select
          ></el-form-item>
          <el-form-item label="数量"><el-input v-model="line.qty" /></el-form-item>
          <el-form-item label="单价"><el-input v-model="line.unitPrice" /></el-form-item>
          <el-form-item label="折扣比例"
            ><el-input v-model="line.discountRate" placeholder="0 到 1"
          /></el-form-item>
          <el-form-item v-if="mode === 'contract'" label="税率"
            ><el-input v-model="line.taxRate" placeholder="留空取默认"
          /></el-form-item>
          <el-button :aria-label="`移除第 ${index + 1} 行商品`" @click="products.splice(index, 1)"
            >移除</el-button
          >
        </div>
        <el-button :disabled="products.length >= 200" @click="products.push(blankProduct())"
          >添加商品</el-button
        >
      </template>
      <template v-if="mode === 'stage'">
        <div v-for="(stage, index) in stageRows" :key="index" class="crm-fields">
          <el-form-item label="阶段名称"><el-input v-model="stage.name" /></el-form-item>
          <el-form-item label="赢率"
            ><el-input v-model="stage.winRate" placeholder="0 到 1"
          /></el-form-item>
          <el-button @click="stageRows.splice(index, 1)">移除阶段</el-button>
        </div>
        <el-button
          :disabled="stageRows.length >= 100"
          @click="stageRows.push({ name: '', winRate: '0' })"
          >添加阶段</el-button
        >
      </template>
      <template v-if="mode === 'stage-action'">
        <el-form-item v-if="action === 'advance'" label="目标阶段">
          <el-select v-model="draft.stageId"
            ><el-option
              v-for="stage in stageOptions"
              :key="stage.id"
              :label="stage.name"
              :value="stage.id"
          /></el-select>
        </el-form-item>
        <el-form-item v-else-if="action !== 'win'" label="结束原因"
          ><el-input v-model="draft.reason" type="textarea" maxlength="300"
        /></el-form-item>
        <p v-else>确认将 {{ source?.name }} 标为赢单？</p>
      </template>
      <template v-if="mode === 'submit' && source">
        <WorkflowStarterSelector
          v-model="selections"
          :business-type="'crm_contract' as WorkflowBusinessType"
          :business-id="source.id"
          @ready="ready = $event"
        />
        <el-form-item label="提交说明"
          ><el-input v-model="draft.comment" maxlength="500"
        /></el-form-item>
      </template>
      <template v-if="mode === 'order'">
        <div class="crm-fields">
          <el-form-item label="业务日期"
            ><el-input v-model="draft.businessDate" type="date"
          /></el-form-item>
          <el-form-item label="交货日期"
            ><el-input v-model="draft.deliveryDate" type="date"
          /></el-form-item>
        </div>
        <p v-if="!orderLines.length">合同已无可生成订单的剩余数量。</p>
        <div v-for="line in orderLines" :key="line.contractLineId" class="crm-fields">
          <span>{{ line.label }} · 剩余 {{ line.remainingQty }}</span>
          <el-form-item label="本次数量"
            ><el-input v-model="line.qty" placeholder="留空不生成"
          /></el-form-item>
          <el-form-item label="仓库"
            ><el-select v-model="line.warehouseId" filterable
              ><el-option v-for="item in warehouses" :key="item.value" v-bind="item" /></el-select
          ></el-form-item>
        </div>
      </template>
      <div v-if="mode === 'plan'" class="crm-fields">
        <el-form-item label="已审批合同"
          ><el-select v-model="draft.contractId" filterable
            ><el-option v-for="item in contracts" :key="item.value" v-bind="item" /></el-select
        ></el-form-item>
        <el-form-item label="计划期次"
          ><el-input-number v-model="draft.periodNo" :min="1" :max="999"
        /></el-form-item>
        <el-form-item label="计划回款日"
          ><el-input v-model="draft.planDate" type="date"
        /></el-form-item>
        <el-form-item label="计划金额"><el-input v-model="draft.planAmount" /></el-form-item>
        <el-form-item label="提前提醒天数"
          ><el-input-number v-model="draft.remindDays" :min="0" :max="90"
        /></el-form-item>
      </div>
      <el-form-item v-if="mode === 'attach'" label="收款单">
        <el-select v-model="draft.receiptId" filterable
          ><el-option v-for="item in receipts" :key="item.value" v-bind="item"
        /></el-select>
      </el-form-item>
      <div v-if="mode === 'target'" class="crm-fields">
        <el-form-item label="目标范围"
          ><el-select v-model="draft.targetType" @change="draft.targetId = ''"
            ><el-option label="员工" value="user" /><el-option
              label="部门"
              value="department" /></el-select
        ></el-form-item>
        <el-form-item label="指定员工或部门"
          ><el-select v-model="draft.targetId" filterable
            ><el-option
              v-for="item in draft.targetType === 'user' ? users : departments"
              :key="item.value"
              v-bind="item" /></el-select
        ></el-form-item>
        <el-form-item label="年份"
          ><el-input-number v-model="draft.year" :min="1900" :max="9999"
        /></el-form-item>
        <el-form-item label="月份"
          ><el-input-number v-model="draft.month" :min="1" :max="12"
        /></el-form-item>
        <el-form-item label="目标口径"
          ><el-select v-model="draft.kind"
            ><el-option label="合同额" value="contract" /><el-option
              label="回款额"
              value="receivable" /></el-select
        ></el-form-item>
        <el-form-item label="目标金额"><el-input v-model="draft.amount" /></el-form-item>
      </div>
      <div class="crm-submit">
        <el-button @click="emit('close')">取消</el-button>
        <el-button
          native-type="submit"
          type="primary"
          :loading="busy"
          :disabled="
            loading || (mode === 'submit' && !ready) || (mode === 'order' && !orderLines.length)
          "
        >
          {{ mode === 'submit' ? '提交审批' : mode === 'order' ? '生成草稿' : '确认' }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.crm-fields {
  display: grid;
  gap: 12px;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  align-items: center;
}
.crm-lines {
  display: grid;
  gap: 8px;
  grid-template-columns: repeat(auto-fit, minmax(95px, 1fr));
  align-items: center;
  border-top: 1px solid var(--el-border-color-light);
  padding: 10px 0;
}
.crm-submit {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 16px;
}
</style>
