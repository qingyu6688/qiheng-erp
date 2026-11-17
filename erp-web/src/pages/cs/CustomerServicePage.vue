<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { allRecords } from '../../api/admin'
import {
  addPortalAccount,
  customerTicket,
  customerTickets,
  portalAccounts,
  portalContactChoices,
  ticketAction,
  ticketAssist,
  updatePortalAccount,
  type TicketAssist,
  type TicketDetail,
} from '../../api/customer-service'
import { isCancelled, messageOf } from '../../api/http'
import { useSessionStore } from '../../stores/session'
import type { DataRow } from '../../types/admin'
import { formatDateTime } from '../../utils/format'

const props = defineProps<{ kind: 'tickets' | 'accounts' }>()
const route = useRoute()
const session = useSessionStore()
const rows = ref<DataRow[]>([])
const cursor = ref<string | null>(null)
const active = ref<TicketDetail>()
const support = ref<TicketAssist>()
const selected = ref<DataRow>()
const partners = ref<DataRow[]>([])
const contacts = ref<DataRow[]>([])
const reply = ref('')
const privateReply = ref(false)
const assignee = ref('')
const editingAccount = ref(false)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const form = reactive({
  partnerId: '',
  contactId: '',
  username: '',
  password: '',
  status: 'active',
})
const tickets = computed(() => props.kind === 'tickets')
const title = computed(() => (tickets.value ? '客服工单' : '门户账号'))
const canManage = computed(() => session.can('portal_user:manage'))
const ticketStatus: Record<string, string> = {
  open: '待分配',
  assigned: '已分配',
  processing: '处理中',
  waiting_customer: '待客户回复',
  resolved: '已解决',
  closed: '已关闭',
  active: '启用',
  disabled: '停用',
}

function status(value: unknown): string {
  return typeof value === 'string' ? (ticketStatus[value] ?? value) : '—'
}

async function operate(command: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    await command()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

async function fetchRows(more = false): Promise<void> {
  const page = await (tickets.value ? customerTickets : portalAccounts)(
    more ? (cursor.value ?? undefined) : undefined,
  )
  rows.value = more ? [...rows.value, ...page.items] : page.items
  cursor.value = page.nextCursor
}

async function openTicket(id: string): Promise<void> {
  const value = await customerTicket(id)
  active.value = value
  support.value = undefined
  reply.value = ''
  privateReply.value = false
  assignee.value = String(value.ticket.assigneeId ?? '')
}

function command(action: 'assign' | 'reply' | 'resolve' | 'close'): void {
  const row = active.value?.ticket
  if (!row || !session.can('cs_ticket:' + action)) return
  if (action === 'close' && !window.confirm('确认关闭此工单？')) return
  const version = row.version
  const data =
    action === 'assign'
      ? { version, assigneeId: assignee.value }
      : action === 'reply'
        ? { version, content: reply.value.trim(), internal: privateReply.value }
        : { version }
  void operate(async () => {
    await ticketAction(row.id, action, data)
    await openTicket(row.id)
    await fetchRows()
    notice.value = '工单已更新。'
  })
}

function inspectAccount(row?: DataRow): void {
  selected.value = row
  Object.assign(form, {
    partnerId: '',
    contactId: '',
    username: '',
    password: '',
    status: row?.status ?? 'active',
  })
  contacts.value = []
  editingAccount.value = true
  if (!row && session.can('partner:view')) {
    void operate(async () => {
      partners.value = (await allRecords('partners')).filter(
        (item) =>
          item.status === 'active' && Array.isArray(item.roles) && item.roles.includes('customer'),
      )
    })
  }
}

async function loadContacts(): Promise<void> {
  contacts.value = []
  form.contactId = ''
  if (!form.partnerId || !session.can('crm_customer:contact')) return
  const target = form.partnerId
  await operate(async () => {
    const result = await portalContactChoices(target)
    if (target === form.partnerId) contacts.value = result
  })
}

function copySuggestion(): void {
  const text = support.value?.replySuggestion
  if (text)
    void operate(async () => {
      await navigator.clipboard.writeText(text)
      notice.value = '建议已复制。'
    })
}

function saveAccount(): void {
  if (!canManage.value) return
  const current = selected.value
  void operate(async () => {
    if (current)
      await updatePortalAccount(current.id, {
        status: form.status,
        password: form.password || undefined,
        version: current.version ?? 0,
      })
    else
      await addPortalAccount({
        partnerId: form.partnerId,
        contactId: form.contactId,
        username: form.username,
        password: form.password,
      })
    form.password = ''
    editingAccount.value = false
    await fetchRows()
    notice.value = '门户账号已保存。'
  })
}

function deepTicket(): string | undefined {
  const id = route.query.ticketId
  return tickets.value && typeof id === 'string' && /^[1-9][0-9]*$/.test(id) ? id : undefined
}

onMounted(() =>
  operate(async () => {
    await fetchRows()
    const id = deepTicket()
    if (id) await openTicket(id)
  }),
)
watch(
  () => route.query.ticketId,
  () => {
    const id = deepTicket()
    if (id) void operate(() => openTicket(id))
  },
)
</script>

<template>
  <div class="page-heading">
    <div>
      <h1>{{ title }}</h1>
    </div>
    <el-button v-if="!tickets && canManage" type="primary" @click="inspectAccount()"
      >开通账号</el-button
    >
  </div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" />
  <p v-if="notice" role="status">{{ notice }}</p>
  <section class="panel table-panel">
    <el-table :data="rows" :empty-text="busy ? '正在加载…' : '暂无记录'" row-key="id">
      <el-table-column v-if="tickets" label="工单号" min-width="145"
        ><template #default="{ row }">{{ row.documentNo ?? row.no }}</template></el-table-column
      >
      <el-table-column v-if="tickets" label="标题" prop="title" min-width="200" />
      <el-table-column v-else label="账号" prop="username" min-width="160" />
      <el-table-column label="客户" prop="customerName" min-width="130" />
      <el-table-column v-if="!tickets" label="联系人" prop="contactName" min-width="130" />
      <el-table-column label="状态" width="110"
        ><template #default="{ row }">{{ status(row.status) }}</template></el-table-column
      >
      <el-table-column v-if="tickets" label="处理期限" min-width="140"
        ><template #default="{ row }"
          ><span :class="{ 'ticket-overdue': row.slaOverdue }">{{
            row.slaDueAt ? formatDateTime(String(row.slaDueAt)) : '—'
          }}</span></template
        ></el-table-column
      >
      <el-table-column label="操作" width="100"
        ><template #default="{ row }">
          <el-button
            v-if="tickets"
            link
            type="primary"
            :disabled="busy"
            @click="operate(() => openTicket(row.id))"
            >查看</el-button
          >
          <el-button
            v-else-if="canManage"
            link
            type="primary"
            :disabled="busy"
            @click="inspectAccount(row)"
            >维护</el-button
          >
        </template></el-table-column
      >
    </el-table>
    <el-button v-if="cursor" :disabled="busy" @click="operate(() => fetchRows(true))"
      >加载更多</el-button
    >
  </section>
  <section v-if="active" class="panel ticket-detail">
    <div class="page-heading">
      <div>
        <h2>{{ active.ticket.title }}</h2>
        <p>{{ status(active.ticket.status) }}</p>
      </div>
      <el-button @click="active = undefined">关闭详情</el-button>
    </div>
    <ol v-if="active.replies.length" class="ticket-replies">
      <li v-for="item in active.replies" :key="item.id">
        <strong>{{
          item.internal ? '内部备注' : item.authorType === 'customer' ? '客户' : '客服'
        }}</strong>
        <p>{{ item.content }}</p>
      </li>
    </ol>
    <div v-if="active.ticket.status !== 'closed'" class="ticket-actions">
      <el-input
        v-if="session.can('cs_ticket:assign')"
        v-model.trim="assignee"
        aria-label="坐席编号"
        placeholder="坐席编号"
        style="max-width: 180px"
      />
      <el-button
        v-if="session.can('cs_ticket:assign')"
        :disabled="busy || !assignee"
        @click="command('assign')"
        >分配坐席</el-button
      >
      <el-button
        v-if="session.can('cs_ticket:resolve')"
        :disabled="busy || active.ticket.status === 'resolved'"
        @click="command('resolve')"
        >标记已解决</el-button
      >
      <el-button
        v-if="session.can('cs_ticket:close')"
        :disabled="busy || active.ticket.status !== 'resolved'"
        @click="command('close')"
        >关闭工单</el-button
      >
    </div>
    <el-form
      v-if="session.can('cs_ticket:reply') && active.ticket.status !== 'closed'"
      label-position="top"
      @submit.prevent="command('reply')"
    >
      <el-form-item label="回复"
        ><el-input v-model.trim="reply" type="textarea" :rows="3" maxlength="2000"
      /></el-form-item>
      <el-checkbox v-model="privateReply">设为内部备注</el-checkbox>
      <el-button type="primary" native-type="submit" :disabled="busy || !reply">发送回复</el-button>
    </el-form>
    <div v-if="session.can('cs_ticket:reply')" class="ticket-assist">
      <el-button
        :disabled="busy"
        @click="
          operate(async () => {
            support = await ticketAssist(active!.ticket.id)
          })
        "
        >获取坐席辅助</el-button
      >
      <div v-if="support">
        <p v-if="support.customerName">客户 {{ support.customerName }}</p>
        <p v-if="support.openReceivable !== undefined">待回款 {{ support.openReceivable }}</p>
        <ul v-if="support.recentOrders?.length">
          <li v-for="order in support.recentOrders" :key="order.id">
            <router-link :to="'/sales-orders/' + order.id">{{ order.documentNo }}</router-link>
          </li>
        </ul>
        <ul v-if="support.recentShipments?.length">
          <li v-for="shipment in support.recentShipments" :key="shipment.id">
            <router-link :to="'/shipments/' + shipment.id">{{ shipment.documentNo }}</router-link>
          </li>
        </ul>
        <p v-if="support.replySuggestion" class="ticket-note">{{ support.replySuggestion }}</p>
        <el-button v-if="support.replySuggestion" @click="copySuggestion">复制建议</el-button>
      </div>
    </div>
  </section>
  <el-dialog
    v-model="editingAccount"
    :title="selected ? '维护门户账号' : '开通门户账号'"
    width="min(500px, 95vw)"
    @closed="form.password = ''"
  >
    <el-form label-position="top" :disabled="busy" @submit.prevent="saveAccount">
      <template v-if="!selected">
        <el-form-item label="客户"
          ><el-select
            v-if="partners.length"
            v-model="form.partnerId"
            filterable
            @change="loadContacts"
            ><el-option
              v-for="item in partners"
              :key="item.id"
              :value="item.id"
              :label="String(item.name)" /></el-select
          ><el-input
            v-else
            v-model.trim="form.partnerId"
            placeholder="客户编号"
            @change="loadContacts"
        /></el-form-item>
        <el-form-item label="联系人"
          ><el-select v-if="contacts.length" v-model="form.contactId" filterable
            ><el-option
              v-for="item in contacts"
              :key="item.id"
              :value="item.id"
              :label="String(item.name ?? item.contactName ?? item.id)" /></el-select
          ><el-input v-else v-model.trim="form.contactId" placeholder="联系人编号"
        /></el-form-item>
        <el-form-item label="手机号或邮箱"
          ><el-input v-model.trim="form.username" autocomplete="off"
        /></el-form-item>
      </template>
      <el-form-item v-else label="状态"
        ><el-select v-model="form.status"
          ><el-option label="启用" value="active" /><el-option
            label="停用"
            value="disabled" /></el-select
      ></el-form-item>
      <el-form-item :label="selected ? '新密码（选填）' : '初始密码'"
        ><el-input v-model="form.password" type="password" autocomplete="new-password"
      /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy">保存</el-button>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.ticket-detail {
  margin-top: 16px;
  padding: 18px;
}
.ticket-replies p,
.ticket-note {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.ticket-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.ticket-assist {
  margin-top: 16px;
}
.ticket-overdue {
  color: var(--el-color-danger);
}
</style>
