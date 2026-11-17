<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import {
  alertReceiverOptions,
  listAlertRules,
  listSafetyStock,
  saveAlertRule,
  saveSafetyStock,
} from '../../api/alerts'
import type {
  AlertChannel,
  AlertReceiver,
  AlertReceiverOption,
  AlertRule,
  AlertSafetyRow,
} from '../../api/alerts'
import { isCancelled, messageOf } from '../../api/http'
import { formatDateTime } from '../../utils/format'
import { alertLevels, alertSchedules } from './alert-labels'

const page = ref('rules')
const rows = ref<AlertRule[]>([])
const updating = ref<AlertRule>()
const error = ref('')
const editError = ref('')
const notice = ref('')
const loading = ref(false)
const busy = ref(false)
const channels: Record<AlertChannel, string> = { site: '站内信', email: '邮件', sms: '短信' }
const receivers = {
  owner: '单据负责人',
  'department-leader': '来源部门负责人',
  role: '指定角色',
  user: '指定用户',
  'role-code': '系统角色',
} satisfies Record<AlertReceiver['type'], string>
const audiences = ref<AlertReceiverOption[]>([])
const audiencesBusy = ref(false)
let loadController: AbortController | undefined
let audienceController: AbortController | undefined
let mounted = true

const stock = ref<AlertSafetyRow[]>([])
const stockWord = ref('')
const stockCursor = ref<string>()
const stockEdit = ref<AlertSafetyRow>()

async function load(): Promise<void> {
  const controller = new AbortController()
  loadController?.abort()
  loadController = controller
  loading.value = true
  error.value = ''
  try {
    const result = await listAlertRules(controller.signal)
    if (!controller.signal.aborted) rows.value = result.items
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

async function loadInventory(append = false): Promise<void> {
  const controller = new AbortController()
  loadController?.abort()
  loadController = controller
  loading.value = true
  error.value = ''
  try {
    const result = await listSafetyStock(
      stockWord.value,
      append ? stockCursor.value : undefined,
      controller.signal,
    )
    if (!controller.signal.aborted) {
      stock.value = append ? [...stock.value, ...result.items] : result.items
      stockCursor.value = result.nextCursor ?? undefined
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
}

onMounted(load)
onUnmounted(() => {
  mounted = false
  loadController?.abort()
  audienceController?.abort()
})

function edit(row: AlertRule): void {
  updating.value = {
    ...row,
    channels: [...row.channels],
    receivers: row.receivers.map((item) => ({ ...item })),
  }
  editError.value = ''
  notice.value = ''
}

function addReceiver(): void {
  updating.value?.receivers.push({ type: 'owner', id: null })
}

function receiverChanged(receiver: AlertReceiver): void {
  receiver.id = null
  void options(receiver.type)
}

function receiverVisibility(receiver: AlertReceiver, shown: boolean): void {
  if (shown) void options(receiver.type)
}

async function options(type: AlertReceiver['type'], keyword = ''): Promise<void> {
  if (type !== 'user' && type !== 'role') return
  const controller = new AbortController()
  audienceController?.abort()
  audienceController = controller
  audiences.value = []
  audiencesBusy.value = true
  try {
    const result = await alertReceiverOptions(type, keyword, controller.signal)
    if (!controller.signal.aborted) audiences.value = result.items
  } catch (cause: unknown) {
    if (!isCancelled(cause)) editError.value = messageOf(cause)
  } finally {
    if (!controller.signal.aborted) audiencesBusy.value = false
  }
}

async function save(): Promise<void> {
  const row = updating.value
  if (!row || busy.value) return
  if (
    !row.channels.length ||
    !row.receivers.length ||
    row.receivers.some((r) => ['role', 'user', 'role-code'].includes(r.type) && !r.id)
  ) {
    editError.value = '请补齐接收人和通知渠道。'
    return
  }
  editError.value = ''
  busy.value = true
  try {
    const saved = await saveAlertRule(row.code, {
      enabled: row.enabled,
      severity: row.severity,
      schedule: row.schedule,
      threshold: row.threshold || null,
      receivers: row.receivers,
      channels: row.channels,
      version: row.version,
    })
    if (!mounted) return
    rows.value = rows.value.map((item) => (item.code === saved.code ? saved : item))
    notice.value = '预警规则已保存。'
    updating.value = undefined
    window.dispatchEvent(new Event('erp:messages-changed'))
  } catch (cause: unknown) {
    if (!isCancelled(cause)) editError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function editStock(row: AlertSafetyRow): void {
  stockEdit.value = { ...row }
  editError.value = ''
  notice.value = ''
}

async function saveStock(): Promise<void> {
  const row = stockEdit.value
  if (!row || busy.value) return
  editError.value = ''
  busy.value = true
  try {
    const saved = await saveSafetyStock(row.id, row.version, row.quantity.trim())
    if (!mounted) return
    stock.value = stock.value.map((item) => (item.id === saved.id ? saved : item))
    notice.value = '当前公司的安全库存已保存。'
    stockEdit.value = undefined
  } catch (cause: unknown) {
    if (!isCancelled(cause)) editError.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="panel alert-settings" aria-label="预警规则设置">
    <el-radio-group v-model="page" @change="page === 'rules' ? load() : loadInventory()">
      <el-radio-button value="rules">业务规则</el-radio-button>
      <el-radio-button value="inventory">安全库存</el-radio-button>
    </el-radio-group>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="notice" role="status">{{ notice }}</p>
    <el-table
      v-if="page === 'rules'"
      v-loading="loading"
      :data="rows"
      row-key="code"
      empty-text="暂无预警规则"
    >
      <el-table-column prop="name" label="规则名称" min-width="145" />
      <el-table-column label="状态" width="80"
        ><template #default="{ row }">{{
          row.enabled ? '启用' : '停用'
        }}</template></el-table-column
      >
      <el-table-column label="阈值" min-width="125"
        ><template #default="{ row }">{{
          row.threshold === null ? '按来源设置' : `${row.threshold} ${row.thresholdUnit ?? ''}`
        }}</template></el-table-column
      >
      <el-table-column label="频率" min-width="125"
        ><template #default="{ row }">{{
          alertSchedules[row.schedule as keyof typeof alertSchedules]
        }}</template></el-table-column
      >
      <el-table-column label="下次执行" min-width="165"
        ><template #default="{ row }">{{
          row.enabled ? formatDateTime(row.nextRunAt) : '—'
        }}</template></el-table-column
      >
      <el-table-column label="操作" width="90"
        ><template #default="{ row }"
          ><el-button type="primary" link @click="edit(row)">配置</el-button></template
        ></el-table-column
      >
    </el-table>
    <template v-else>
      <div class="stock-search">
        <el-input
          v-model="stockWord"
          clearable
          aria-label="安全库存商品搜索"
          placeholder="商品编码或名称"
          @keydown.enter.prevent="loadInventory()"
        />
        <el-button :loading="loading" @click="loadInventory()">查询</el-button>
      </div>
      <el-table v-loading="loading" :data="stock" row-key="id" empty-text="未找到商品">
        <el-table-column prop="code" label="商品编码" min-width="140" />
        <el-table-column prop="name" label="商品名称" min-width="185" />
        <el-table-column prop="quantity" label="公司安全库存" min-width="130" />
        <el-table-column label="操作" width="90"
          ><template #default="{ row }"
            ><el-button type="primary" link @click="editStock(row)">调整</el-button></template
          ></el-table-column
        >
      </el-table>
      <el-button v-if="stockCursor" :loading="loading" @click="loadInventory(true)"
        >加载更多商品</el-button
      >
    </template>
  </section>
  <el-dialog
    v-if="updating"
    :model-value="true"
    :title="updating.name"
    width="min(690px, 94vw)"
    :show-close="!busy"
    :close-on-click-modal="false"
    @close="updating = undefined"
  >
    <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="save">
      <div class="alert-edit-fields">
        <el-form-item label="启用规则"
          ><el-switch v-model="updating.enabled" aria-label="启用规则"
        /></el-form-item>
        <el-form-item label="预警级别">
          <el-select v-model="updating.severity" aria-label="预警级别"
            ><el-option
              v-for="(label, value) in alertLevels"
              :key="value"
              :value="value"
              :label="label"
          /></el-select>
        </el-form-item>
        <el-form-item label="触发频率">
          <el-select v-model="updating.schedule" aria-label="触发频率"
            ><el-option
              v-for="(label, value) in alertSchedules"
              :key="value"
              :value="value"
              :label="label"
          /></el-select>
        </el-form-item>
        <el-form-item v-if="updating.thresholdUnit" :label="`阈值（${updating.thresholdUnit}）`">
          <el-input
            :model-value="updating.threshold ?? ''"
            aria-label="规则阈值"
            inputmode="decimal"
            @update:model-value="updating.threshold = $event"
          />
        </el-form-item>
      </div>
      <el-form-item label="通知渠道">
        <el-checkbox-group v-model="updating.channels"
          ><el-checkbox v-for="(label, value) in channels" :key="value" :value="value">{{
            label
          }}</el-checkbox></el-checkbox-group
        >
      </el-form-item>
      <el-form-item label="接收人">
        <div class="receiver-list">
          <div v-for="(receiver, index) in updating.receivers" :key="index" class="receiver-row">
            <el-select
              v-model="receiver.type"
              aria-label="接收人类型"
              @change="receiverChanged(receiver)"
            >
              <el-option
                v-for="(label, value) in receivers"
                :key="value"
                :value="value"
                :label="label"
              />
            </el-select>
            <el-select
              v-if="receiver.type === 'role' || receiver.type === 'user'"
              v-model="receiver.id"
              aria-label="选择接收人"
              filterable
              remote
              :remote-method="(word: string) => options(receiver.type, word)"
              :loading="audiencesBusy"
              @visible-change="receiverVisibility(receiver, $event)"
            >
              <el-option
                v-for="entry in audiences"
                :key="entry.id"
                :value="entry.id"
                :label="entry.name"
              />
              <el-option
                v-if="receiver.id && !audiences.some((entry) => entry.id === receiver.id)"
                :value="receiver.id"
                :label="receiver.id"
              />
            </el-select>
            <el-select
              v-else-if="receiver.type === 'role-code'"
              v-model="receiver.id"
              aria-label="选择系统角色"
            >
              <el-option value="SYSTEM_ADMIN" label="系统管理员" /><el-option
                value="WAREHOUSE_ADMIN"
                label="仓库管理员"
              />
            </el-select>
            <el-button type="danger" link @click="updating.receivers.splice(index, 1)"
              >移除</el-button
            >
          </div>
        </div>
        <el-button v-if="updating.receivers.length < 30" link type="primary" @click="addReceiver"
          >添加接收人</el-button
        >
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">保存规则</el-button>
      <el-button :disabled="busy" @click="updating = undefined">取消</el-button>
    </el-form>
  </el-dialog>
  <el-dialog
    v-if="stockEdit"
    :model-value="true"
    :title="'安全库存 · ' + stockEdit.code"
    width="min(480px,94vw)"
    :show-close="!busy"
    :close-on-click-modal="false"
    @close="stockEdit = undefined"
  >
    <el-alert v-if="editError" :title="editError" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="saveStock">
      <el-form-item label="当前公司安全库存"
        ><el-input v-model="stockEdit.quantity" aria-label="当前公司安全库存" inputmode="decimal"
      /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">保存数量</el-button>
      <el-button :disabled="busy" @click="stockEdit = undefined">取消</el-button>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.alert-settings {
  padding: 16px;
}
.alert-settings > * + * {
  margin-top: 12px;
}
.alert-edit-fields {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(160px, 100%), 1fr));
  gap: 8px 14px;
}
.receiver-list {
  width: 100%;
  display: grid;
  gap: 8px;
}
.receiver-row,
.stock-search {
  display: flex;
  gap: 8px;
  align-items: center;
}
.receiver-row .el-select,
.stock-search .el-input {
  width: min(230px, 100%);
}
</style>
