<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { records } from '../../api/admin'
import { crmEmployees, importCrmClues, readCrmCore, writeCrmCore } from '../../api/crm-core'
import { downloadFile } from '../../api/files'
import { ApiError, isCancelled, messageOf } from '../../api/http'
import { areaTree, dictionaries } from '../../api/platform'
import type { AreaNode, DictionaryItem, Page } from '../../api/platform'
import type { DataRow } from '../../types/admin'
import type {
  CrmClue,
  CrmCoreKind,
  CrmCustomer,
  CrmDealStatus,
  CrmEmployee,
} from '../../types/crm-core'
import { useSessionStore } from '../../stores/session'
import { formatDateTime } from '../../utils/format'
import CrmCoreSettings from './CrmCoreSettings.vue'
import CrmCustomerPeople from './CrmCustomerPeople.vue'
import CrmFollowUpPanel from './CrmFollowUpPanel.vue'
import { crmDictionaryLabel, crmLocalTime, crmTimestamp } from './crm-core-format'
import './crm-core.css'

type Row = CrmClue | CrmCustomer
type OwnerCommand = 'assign' | 'transfer' | 'pool-assign' | 'batch'
interface DuplicatePartner {
  id: string
  code: string
  name: string
}
interface Draft {
  name: string
  contactName: string
  mobile: string
  email: string
  taxNo: string
  partnerId: string
  ownerUserId: string
  source: string
  industry: string
  level: string
  areaId: string
  nextContactAt: string
  dealStatus: CrmDealStatus
  version?: number
}

const props = defineProps<{ kind: CrmCoreKind }>()
const route = useRoute()
const router = useRouter()
const session = useSessionStore()
const title = computed(
  () =>
    ({ clues: '销售线索', customers: '客户档案', pool: '客户公海', settings: '客户关系设置' })[
      props.kind
    ],
)
const rows = ref<Row[]>([])
const cursor = ref<string | null>(null)
const selected = ref<CrmClue[]>([])
const search = ref('')
const clueStatus = ref('')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const formError = ref('')
const employees = ref<CrmEmployee[]>([])
const dictionary = ref<Record<string, DictionaryItem[]>>({})
const areas = ref<AreaNode[]>([])
const partners = ref<DataRow[]>([])
const detail = ref<Row>()
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailTab = ref('profile')
const editOpen = ref(false)
const editId = ref('')
const form = reactive<Draft>(blankDraft())
const actionOpen = ref(false)
const action = ref<OwnerCommand | 'convert'>('assign')
const actionRow = ref<Row>()
const owner = ref('')
const mergeId = ref('')
const partnerCode = ref('')
const duplicates = ref<DuplicatePartner[]>([])
const inputFile = ref<HTMLInputElement>()
let listRevision = 0
let detailRevision = 0
const statusLabels: Record<string, string> = {
  new: '新线索',
  following: '跟进中',
  converted: '已转化',
  invalid: '已作废',
  active: '启用',
  disabled: '已停用',
}
const actionLabels = {
  assign: '分配线索',
  transfer: '转移客户',
  'pool-assign': '分配客户',
  batch: '批量分配线索',
  convert: '转化客户',
}

function blankDraft(): Draft {
  return {
    name: '',
    contactName: '',
    mobile: '',
    email: '',
    taxNo: '',
    partnerId: '',
    ownerUserId: '',
    source: '',
    industry: '',
    level: '',
    areaId: '',
    nextContactAt: '',
    dealStatus: 'unsold',
  }
}

function isCustomer(row: Row): row is CrmCustomer {
  return 'partnerId' in row
}
function rowTitle(row: Row): string {
  return isCustomer(row) ? row.partnerName : row.name
}
function ownerName(id: string): string {
  return id === '0'
    ? '待分配'
    : (employees.value.find((item) => item.id === id)?.displayName ?? '—')
}
function openClue(row: Row): boolean {
  return !isCustomer(row) && (row.status === 'new' || row.status === 'following')
}
function dict(type: string): DictionaryItem[] {
  return dictionary.value[type] ?? []
}

const detailWrite = computed(() => {
  const item = detail.value
  if (!item) return false
  return isCustomer(item)
    ? item.canWrite && session.can('crm_customer:update')
    : openClue(item) && session.can('crm_clue:update')
})
const sensitiveEdit = computed(() => !editId.value || session.can('partner_contact:view'))

async function loadList(append = false): Promise<void> {
  if (props.kind === 'settings') return
  const revision = ++listRevision
  loading.value = true
  error.value = ''
  try {
    const page = await readCrmCore<Page<Row>>(`/${props.kind}`, {
      keyword: search.value.trim(),
      status: props.kind === 'clues' ? clueStatus.value || undefined : undefined,
      cursor: append ? cursor.value : undefined,
      limit: 30,
    })
    if (revision !== listRevision) return
    rows.value = append ? [...rows.value, ...page.items] : page.items
    cursor.value = page.nextCursor
    if (!append) selected.value = []
  } catch (cause: unknown) {
    if (!isCancelled(cause) && revision === listRevision) error.value = messageOf(cause)
  } finally {
    if (revision === listRevision) loading.value = false
  }
}

async function loadReferences(): Promise<void> {
  try {
    const [dicts, staff, area] = await Promise.all([
      dictionaries('customer_source,crm_industry,crm_level,crm_follow_method'),
      props.kind === 'pool' && !session.can('crm_pool:assign') ? Promise.resolve([]) : crmEmployees(),
      areaTree(),
    ])
    dictionary.value = dicts.items ?? {}
    employees.value = staff
    areas.value = area.items
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  }
}

async function loadEmployees(keyword: string): Promise<void> {
  try {
    const employeesById = new Map(employees.value.map((item) => [item.id, item]))
    for (const user of await crmEmployees(keyword)) employeesById.set(user.id, user)
    employees.value = [...employeesById.values()]
  } catch (cause: unknown) {
    if (!isCancelled(cause)) formError.value = messageOf(cause)
  }
}

async function loadPartners(keyword = ''): Promise<void> {
  if (!session.can('partner:view')) return
  try {
    partners.value = (
      await records('partners', true, { keyword, role: 'customer', limit: 60 })
    ).items
  } catch (cause: unknown) {
    if (!isCancelled(cause)) formError.value = messageOf(cause)
  }
}

async function loadDetail(id: string, keepTab = false): Promise<void> {
  const revision = ++detailRevision
  if (!keepTab) {
    detail.value = undefined
    detailTab.value = 'profile'
  }
  detailLoading.value = true
  detailOpen.value = true
  error.value = ''
  try {
    const found = await readCrmCore<Row>(`/${props.kind === 'clues' ? 'clues' : 'customers'}/${id}`)
    if (revision === detailRevision) detail.value = found
  } catch (cause: unknown) {
    if (!isCancelled(cause) && revision === detailRevision) error.value = messageOf(cause)
  } finally {
    if (revision === detailRevision) detailLoading.value = false
  }
}

function show(row: Row): void {
  if (props.kind === 'customers') {
    if (route.query.partnerId === row.id) void loadDetail(row.id)
    else void router.replace({ query: { ...route.query, partnerId: row.id } })
  } else void loadDetail(row.id)
}

function hideDetail(): void {
  ++detailRevision
  detailOpen.value = false
  detail.value = undefined
  if (props.kind === 'customers' && route.query.partnerId)
    void router.replace({ query: { ...route.query, partnerId: undefined } })
}

function openEditor(current?: Row): void {
  Object.assign(form, blankDraft(), { version: undefined })
  editId.value = current?.id ?? ''
  formError.value = ''
  if (current) {
    form.version = current.version
    form.source = current.source ?? ''
    form.industry = current.industry ?? ''
    form.areaId = current.areaId ?? ''
    form.nextContactAt = crmLocalTime(current.nextContactAt)
    if (isCustomer(current)) {
      form.partnerId = current.partnerId
      form.level = current.level ?? ''
      form.dealStatus = current.dealStatus
    } else {
      form.name = current.name
      form.contactName = current.contactName ?? ''
      if (sensitiveEdit.value) {
        form.mobile = current.mobile ?? ''
        form.email = current.email ?? ''
        form.taxNo = current.taxNo ?? ''
      }
    }
  } else if (props.kind === 'customers') void loadPartners()
  editOpen.value = true
}

async function saveEditor(): Promise<void> {
  await perform(async () => {
    const common = {
      source: form.source,
      industry: form.industry,
      areaId: form.areaId || null,
      nextContactAt: crmTimestamp(form.nextContactAt),
      version: form.version,
    }
    const body =
      props.kind === 'clues'
        ? {
            ...common,
            name: form.name.trim(),
            contactName: form.contactName,
            ...(sensitiveEdit.value
              ? { mobile: form.mobile, email: form.email, taxNo: form.taxNo }
              : {}),
          }
        : {
            ...common,
            partnerId: form.partnerId,
            ownerUserId: form.ownerUserId || undefined,
            level: form.level,
            dealStatus: form.dealStatus,
          }
    const path = `/${props.kind}${editId.value ? '/' + editId.value : ''}`
    const saved = await writeCrmCore<Row>(path, body, editId.value ? 'PUT' : 'POST')
    editOpen.value = false
    await loadList()
    if (detailOpen.value || props.kind === 'clues') await loadDetail(saved.id, true)
    ElMessage.success('已保存')
  })
}

function openAction(kind: OwnerCommand | 'convert', item?: Row): void {
  action.value = kind
  actionRow.value = item
  owner.value = ''
  mergeId.value = ''
  partnerCode.value = ''
  duplicates.value = []
  formError.value = ''
  actionOpen.value = true
}

async function saveAction(): Promise<void> {
  await perform(async () => {
    const item = actionRow.value
    try {
      if (action.value === 'batch') {
        await writeCrmCore('/clues/assign', {
          ownerUserId: owner.value,
          items: selected.value.map((row) => ({ id: row.id, version: row.version })),
        })
      } else if (action.value === 'convert' && item) {
        await writeCrmCore(`/clues/${item.id}/convert`, {
          version: item.version,
          partnerId: mergeId.value || undefined,
          code: partnerCode.value || undefined,
        })
      } else if (item) {
        const prefix = action.value === 'assign' ? 'clues' : action.value === 'transfer' ? 'customers' : 'pool'
        const name = action.value === 'pool-assign' ? 'assign' : action.value
        await writeCrmCore(`/${prefix}/${item.id}/${name}`, {
          version: item.version,
          ownerUserId: owner.value,
        })
      }
    } catch (cause: unknown) {
      if (cause instanceof ApiError && cause.code === 'CRM_DUPLICATE_PARTNER') {
        const details = cause.details as { candidates?: DuplicatePartner[] } | undefined
        duplicates.value = details?.candidates ?? []
      }
      throw cause
    }
    actionOpen.value = false
    await loadList()
    if (action.value === 'convert' && item) await loadDetail(item.id, true)
    else if (detailOpen.value) hideDetail()
    ElMessage.success('操作已完成')
  })
}

async function change(
  item: Row,
  command: 'lock' | 'unlock' | 'release' | 'claim' | 'invalidate',
): Promise<void> {
  await perform(async () => {
    const prefix = command === 'claim' ? 'pool' : isCustomer(item) ? 'customers' : 'clues'
    await writeCrmCore(`/${prefix}/${item.id}/${command}`, { version: item.version })
    await loadList()
    if (command === 'release') hideDetail()
    else if (detailOpen.value && detail.value?.id === item.id) await loadDetail(item.id, true)
    ElMessage.success('操作已完成')
  })
}

async function perform(callback: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  formError.value = ''
  try {
    await callback()
  } catch (cause: unknown) {
    if (isCancelled(cause)) return
    if (editOpen.value || actionOpen.value) formError.value = messageOf(cause)
    else error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function chooseFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await perform(async () => {
    const result = await importCrmClues(file)
    ElMessage.success(`已导入 ${result.imported} 条线索`)
    await loadList()
  })
}

async function refreshDetails(): Promise<void> {
  const current = detail.value
  if (current) await loadDetail(current.id, true)
  await loadList()
}

function setSelected(items: Row[]): void {
  selected.value = items.filter((item): item is CrmClue => openClue(item))
}

onMounted(() => {
  if (props.kind !== 'settings') {
    void loadList()
    void loadReferences()
  }
})
watch(
  () => route.query.partnerId,
  (id) => {
    if (props.kind === 'customers' && typeof id === 'string' && /^[1-9][0-9]{0,18}$/.test(id))
      void loadDetail(id)
  },
  { immediate: true },
)
</script>

<template>
  <div class="page-heading">
    <h1>{{ title }}</h1>
    <div class="crm-actions">
      <template v-if="kind === 'clues' && session.can('crm_clue:import')">
        <input ref="inputFile" hidden type="file" accept=".csv,text/csv" @change="chooseFile" />
        <el-button
          :disabled="busy"
          @click="perform(() => downloadFile('/crm/clues/import-template', '线索导入模板.csv'))"
          >导入模板</el-button
        >
        <el-button :disabled="busy" @click="inputFile?.click()">导入线索</el-button>
      </template>
      <el-button
        v-if="kind === 'clues' && session.can('crm_clue:create')"
        type="primary"
        @click="openEditor()"
        >新建线索</el-button
      >
      <el-button
        v-if="
          kind === 'customers' && session.can('crm_customer:create') && session.can('partner:view')
        "
        type="primary"
        @click="openEditor()"
        >建立客户档案</el-button
      >
    </div>
  </div>
  <CrmCoreSettings v-if="kind === 'settings'" />
  <template v-else>
    <el-alert
      v-if="error && !detailOpen"
      class="crm-error"
      :title="error"
      type="error"
      :closable="false"
    />
    <section class="panel table-panel crm-table">
      <form class="crm-actions crm-list-toolbar" @submit.prevent="loadList()">
        <el-input
          v-model="search"
          maxlength="100"
          clearable
          aria-label="搜索客户与线索"
          placeholder="名称或编码"
        />
        <el-select
          v-if="kind === 'clues'"
          v-model="clueStatus"
          clearable
          aria-label="线索状态"
          placeholder="全部状态"
          @change="loadList()"
        >
          <el-option
            v-for="status in ['new', 'following', 'converted', 'invalid']"
            :key="status"
            :label="statusLabels[status]"
            :value="status"
          />
        </el-select>
        <el-button native-type="submit" :loading="loading">搜索</el-button>
        <el-button
          v-if="kind === 'clues' && session.can('crm_clue:assign')"
          :disabled="!selected.length"
          @click="openAction('batch')"
          >批量分配<span v-if="selected.length">（{{ selected.length }}）</span></el-button
        >
      </form>
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        :empty-text="loading ? '正在加载…' : '暂无匹配记录'"
        @selection-change="setSelected"
      >
        <el-table-column
          v-if="kind === 'clues' && session.can('crm_clue:assign')"
          type="selection"
          :selectable="openClue"
          width="44"
        />
        <el-table-column :label="kind === 'clues' ? '线索名称' : '客户'" min-width="165">
          <template #default="{ row }"
            ><el-button v-if="kind !== 'pool'" link type="primary" @click="show(row)">{{
              rowTitle(row)
            }}</el-button
            ><span v-else>{{ rowTitle(row) }}</span></template
          >
        </el-table-column>
        <el-table-column
          v-if="kind === 'clues'"
          prop="contactName"
          label="联系人"
          min-width="105"
        />
        <el-table-column v-if="kind === 'clues'" prop="mobile" label="手机尾号" min-width="112" />
        <el-table-column v-else prop="partnerCode" label="客户编码" min-width="110" />
        <el-table-column v-if="kind !== 'pool'" label="负责人" min-width="116"
          ><template #default="{ row }">{{ ownerName(row.ownerUserId) }}</template></el-table-column
        >
        <el-table-column label="状态" width="100"
          ><template #default="{ row }"
            ><el-tag
              effect="plain"
              :type="row.status === 'invalid' || row.status === 'disabled' ? 'info' : 'primary'"
              >{{ statusLabels[row.status] }}</el-tag
            ></template
          ></el-table-column
        >
        <el-table-column v-if="kind === 'customers'" label="客户级别" min-width="110"
          ><template #default="{ row }">{{
            crmDictionaryLabel(dict('crm_level'), row.level)
          }}</template></el-table-column
        >
        <el-table-column v-if="kind !== 'pool'" label="下次联系" min-width="151"
          ><template #default="{ row }">{{
            row.nextContactAt ? formatDateTime(row.nextContactAt) : '—'
          }}</template></el-table-column
        >
        <el-table-column v-else label="回收原因" min-width="108"
          ><template #default="{ row }">{{
            row.poolReason === 'automatic' ? '规则回收' : '手动放回'
          }}</template></el-table-column
        >
        <el-table-column v-if="kind === 'pool'" label="操作" width="138"
          ><template #default="{ row }">
            <el-button
              v-if="session.can('crm_pool:claim')"
              link
              type="primary"
              :disabled="busy || row.status !== 'active'"
              @click="change(row, 'claim')"
              >领取</el-button
            >
            <el-button
              v-if="session.can('crm_pool:assign')"
              link
              type="primary"
              :disabled="busy || row.status !== 'active'"
              @click="openAction('pool-assign', row)"
              >分配</el-button
            >
          </template></el-table-column
        >
      </el-table>
      <div v-if="cursor" class="crm-list-more">
        <el-button :loading="loading" @click="loadList(true)">加载更多</el-button>
      </div>
    </section>
  </template>

  <el-drawer
    v-model="detailOpen"
    :title="detail ? rowTitle(detail) : '查看资料'"
    size="min(760px, 100vw)"
    @closed="hideDetail"
  >
    <el-alert v-if="error" class="crm-error" :title="error" type="error" :closable="false" />
    <div v-loading="detailLoading" class="crm-detail">
      <template v-if="detail">
        <div class="crm-actions">
          <el-button v-if="detailWrite" @click="openEditor(detail)">编辑</el-button>
          <template v-if="isCustomer(detail) && detail.canWrite">
            <el-button
              v-if="session.can('crm_customer:transfer')"
              @click="openAction('transfer', detail)"
              >转移客户</el-button
            >
            <el-button
              v-if="session.can('crm_customer:lock')"
              :disabled="busy"
              @click="change(detail, detail.locked ? 'unlock' : 'lock')"
              >{{ detail.locked ? '解锁' : '锁定' }}</el-button
            >
            <el-button
              v-if="session.can('crm_customer:release')"
              :disabled="busy || detail.locked"
              @click="change(detail, 'release')"
              >放回公海</el-button
            >
          </template>
          <template v-else-if="openClue(detail)">
            <el-button v-if="session.can('crm_clue:assign')" @click="openAction('assign', detail)"
              >分配线索</el-button
            >
            <el-button
              v-if="session.can('crm_clue:convert')"
              type="primary"
              @click="openAction('convert', detail)"
              >转化客户</el-button
            >
            <el-button
              v-if="session.can('crm_clue:invalidate')"
              :disabled="busy"
              @click="change(detail, 'invalidate')"
              >作废</el-button
            >
          </template>
        </div>
        <el-tabs v-model="detailTab">
          <el-tab-pane label="基本信息" name="profile">
            <el-descriptions :column="2" border>
              <el-descriptions-item label="负责人">{{
                ownerName(detail.ownerUserId)
              }}</el-descriptions-item>
              <el-descriptions-item label="状态">{{
                statusLabels[detail.status]
              }}</el-descriptions-item>
              <el-descriptions-item label="来源">{{
                crmDictionaryLabel(dict('customer_source'), detail.source)
              }}</el-descriptions-item>
              <el-descriptions-item label="行业">{{
                crmDictionaryLabel(dict('crm_industry'), detail.industry)
              }}</el-descriptions-item>
              <el-descriptions-item label="最近跟进">{{
                detail.lastFollowAt ? formatDateTime(detail.lastFollowAt) : '—'
              }}</el-descriptions-item>
              <el-descriptions-item label="下次联系">{{
                detail.nextContactAt ? formatDateTime(detail.nextContactAt) : '—'
              }}</el-descriptions-item>
              <template v-if="isCustomer(detail)">
                <el-descriptions-item label="客户编码">{{
                  detail.partnerCode
                }}</el-descriptions-item>
                <el-descriptions-item label="客户级别">{{
                  crmDictionaryLabel(dict('crm_level'), detail.level)
                }}</el-descriptions-item>
                <el-descriptions-item label="成交状态">{{
                  detail.dealStatus === 'sold' ? '已成交' : '未成交'
                }}</el-descriptions-item>
                <el-descriptions-item label="客户锁定">{{
                  detail.locked ? '已锁定' : '未锁定'
                }}</el-descriptions-item>
              </template>
              <template v-else>
                <el-descriptions-item label="联系人">{{
                  detail.contactName || '—'
                }}</el-descriptions-item>
                <el-descriptions-item label="手机号">{{
                  detail.mobile || '—'
                }}</el-descriptions-item>
                <el-descriptions-item
                  v-if="detail.convertedPartnerId && session.can('crm_customer:view')"
                  label="已转化客户"
                  ><router-link
                    class="text-link"
                    :to="{
                      path: '/crm/customers',
                      query: { partnerId: detail.convertedPartnerId },
                    }"
                    >查看客户档案</router-link
                  ></el-descriptions-item
                >
              </template>
            </el-descriptions>
            <nav v-if="isCustomer(detail)" class="crm-actions crm-related" aria-label="客户关联业务">
              <router-link v-if="session.can('crm_business:view')" class="el-button" :to="{ path: '/crm/businesses', query: { partnerId: detail.partnerId } }">客户商机</router-link>
              <router-link v-if="session.can('crm_contract:view')" class="el-button" :to="{ path: '/crm/contracts', query: { partnerId: detail.partnerId } }">客户合同</router-link>
              <router-link v-if="session.can('crm_receivable:view')" class="el-button" :to="{ path: '/crm/receivables', query: { partnerId: detail.partnerId } }">客户回款</router-link>
            </nav>
          </el-tab-pane>
          <el-tab-pane v-if="isCustomer(detail)" name="people" label="联系人与团队" lazy
            ><CrmCustomerPeople
              :key="detail.id"
              :partner-id="detail.id"
              :can-write="detail.canWrite"
              :employees="employees"
          /></el-tab-pane>
          <el-tab-pane name="follow" label="跟进记录" lazy
            ><CrmFollowUpPanel
              :key="detail.id"
              :id="detail.id"
              :type="isCustomer(detail) ? 'customer' : 'clue'"
              :can-write="detailWrite"
              :methods="dict('crm_follow_method')"
              :employees="employees"
              @changed="refreshDetails"
          /></el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>

  <el-dialog
    v-model="editOpen"
    :title="
      kind === 'clues'
        ? editId
          ? '编辑线索'
          : '新建线索'
        : editId
          ? '编辑客户档案'
          : '建立客户档案'
    "
    width="min(640px, 95vw)"
  >
    <el-alert
      v-if="formError"
      class="crm-error"
      :title="formError"
      type="error"
      :closable="false"
    />
    <el-form label-position="top" @submit.prevent="saveEditor">
      <template v-if="kind === 'clues'">
        <el-form-item label="线索名称" required
          ><el-input v-model="form.name" :maxlength="100" aria-label="线索名称"
        /></el-form-item>
        <div class="crm-form-grid">
          <el-form-item label="联系人"
            ><el-input v-model="form.contactName" :maxlength="50" aria-label="联系人"
          /></el-form-item>
          <template v-if="sensitiveEdit">
            <el-form-item label="手机号"
              ><el-input v-model="form.mobile" :maxlength="32" aria-label="手机号"
            /></el-form-item>
            <el-form-item label="邮箱"
              ><el-input v-model="form.email" type="email" :maxlength="100" aria-label="邮箱"
            /></el-form-item>
            <el-form-item label="税号"
              ><el-input v-model="form.taxNo" :maxlength="32" aria-label="税号"
            /></el-form-item>
          </template>
        </div>
      </template>
      <template v-else>
        <el-form-item v-if="!editId" label="ERP 客户" required
          ><el-select
            v-model="form.partnerId"
            filterable
            remote
            :remote-method="loadPartners"
            aria-label="ERP 客户"
            placeholder="按名称或编码搜索"
            ><el-option
              v-for="partner in partners"
              :key="partner.id"
              :label="`${partner.code || ''} ${partner.name || ''}`"
              :value="partner.id" /></el-select
        ></el-form-item>
        <el-form-item v-if="!editId && session.can('crm_customer:transfer')" label="负责人"
          ><el-select
            v-model="form.ownerUserId"
            filterable
            remote
            clearable
            :remote-method="loadEmployees"
            aria-label="负责人"
            placeholder="未选时由本人负责"
            ><el-option
              v-for="user in employees"
              :key="user.id"
              :label="user.displayName"
              :value="user.id" /></el-select
        ></el-form-item>
        <div class="crm-form-grid">
          <el-form-item label="客户级别"
            ><el-select v-model="form.level" clearable aria-label="客户级别"
              ><el-option
                v-for="item in dict('crm_level')"
                :key="item.value"
                :value="item.value"
                :label="item.label"
                :disabled="!item.enabled" /></el-select
          ></el-form-item>
          <el-form-item label="成交状态"
            ><el-select v-model="form.dealStatus" aria-label="成交状态"
              ><el-option label="未成交" value="unsold" /><el-option
                label="已成交"
                value="sold" /></el-select
          ></el-form-item>
        </div>
      </template>
      <div class="crm-form-grid">
        <el-form-item label="来源"
          ><el-select v-model="form.source" clearable aria-label="客户来源"
            ><el-option
              v-for="item in dict('customer_source')"
              :key="item.value"
              :value="item.value"
              :label="item.label"
              :disabled="!item.enabled" /></el-select
        ></el-form-item>
        <el-form-item label="行业"
          ><el-select v-model="form.industry" clearable aria-label="行业"
            ><el-option
              v-for="item in dict('crm_industry')"
              :key="item.value"
              :value="item.value"
              :label="item.label"
              :disabled="!item.enabled" /></el-select
        ></el-form-item>
        <el-form-item label="所属地区"
          ><el-cascader
            v-model="form.areaId"
            :options="areas"
            :props="{ value: 'id', label: 'name', emitPath: false, checkStrictly: true }"
            clearable
            filterable
            aria-label="所属地区"
        /></el-form-item>
        <el-form-item label="下次联系"
          ><el-input v-model="form.nextContactAt" type="datetime-local" aria-label="下次联系时间"
        /></el-form-item>
      </div>
      <el-button
        type="primary"
        native-type="submit"
        :disabled="kind === 'clues' ? !form.name.trim() : !form.partnerId"
        :loading="busy"
        >保存</el-button
      >
    </el-form>
  </el-dialog>

  <el-dialog v-model="actionOpen" :title="actionLabels[action]" width="min(530px, 95vw)">
    <el-alert
      v-if="formError"
      class="crm-error"
      :title="formError"
      :type="duplicates.length ? 'warning' : 'error'"
      :closable="false"
    />
    <el-form label-position="top" @submit.prevent="saveAction">
      <template v-if="action === 'convert'">
        <el-form-item v-if="duplicates.length" label="合并至现有往来单位" required
          ><el-select v-model="mergeId" aria-label="合并至现有往来单位"
            ><el-option
              v-for="item in duplicates"
              :key="item.id"
              :label="`${item.code} · ${item.name}`"
              :value="item.id" /></el-select
        ></el-form-item>
        <el-form-item v-else label="客户编码"
          ><el-input
            v-model="partnerCode"
            :maxlength="32"
            placeholder="留空将自动生成客户编码"
            aria-label="客户编码"
        /></el-form-item>
      </template>
      <el-form-item v-else label="选择负责人" required
        ><el-select
          v-model="owner"
          filterable
          remote
          :remote-method="loadEmployees"
          aria-label="选择负责人"
          placeholder="按姓名搜索员工"
          ><el-option
            v-for="user in employees"
            :key="user.id"
            :value="user.id"
            :label="user.displayName" /></el-select
      ></el-form-item>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        :disabled="action === 'convert' ? duplicates.length > 0 && !mergeId : !owner"
        >{{ action === 'convert' && mergeId ? '确认合并' : '确认' }}</el-button
      >
    </el-form>
  </el-dialog>
</template>
