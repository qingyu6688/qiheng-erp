<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { readCrmCore, writeCrmCore } from '../../api/crm-core'
import { isCancelled, messageOf } from '../../api/http'
import type { CrmContact, CrmEmployee, CrmTeamMember } from '../../types/crm-core'
import { useSessionStore } from '../../stores/session'
import { formatDateTime } from '../../utils/format'
import { crmTimestamp } from './crm-core-format'

const props = defineProps<{ partnerId: string; canWrite: boolean; employees: CrmEmployee[] }>()
const session = useSessionStore()
const contacts = ref<CrmContact[]>([])
const team = ref<CrmTeamMember[]>([])
const busy = ref(false)
const error = ref('')
const editing = ref(false)
const addMember = ref(false)
const privateAccess = computed(() => session.can('partner_contact:view'))
const canManageContacts = computed(() => props.canWrite && session.can('crm_customer:contact'))
const canManageTeam = computed(() => props.canWrite && session.can('crm_customer:team'))
const teamInput = reactive<{ userId: string; permission: 'read' | 'write'; expiresAt: string }>({
  userId: '',
  permission: 'read',
  expiresAt: '',
})
const contact = reactive<CrmContact>(blankContact())

function blankContact(): CrmContact {
  return {
    id: '',
    name: '',
    mobile: '',
    email: '',
    address: '',
    position: '',
    birthday: '',
    wechat: '',
    remark: '',
    isDefault: false,
    isDecisionMaker: false,
    version: 0,
    profileVersion: null,
    areaId: null,
  }
}

async function load(): Promise<void> {
  const id = props.partnerId
  try {
    const [contactPage, teamPage] = await Promise.all([
      readCrmCore<{ items: CrmContact[] }>(`/customers/${id}/contacts`),
      readCrmCore<{ items: CrmTeamMember[] }>(`/customers/${id}/team`),
    ])
    if (id === props.partnerId) {
      contacts.value = contactPage.items
      team.value = teamPage.items
    }
  } catch (cause: unknown) {
    if (!isCancelled(cause) && id === props.partnerId) error.value = messageOf(cause)
  }
}

async function perform(action: () => Promise<void>): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}

function openEditor(current?: CrmContact): void {
  error.value = ''
  Object.assign(contact, blankContact(), current)
  editing.value = true
}

async function saveContact(): Promise<void> {
  if (!contact.name.trim()) return
  await perform(async () => {
    const details = privateAccess.value
      ? {
          mobile: contact.mobile || undefined,
          email: contact.email || undefined,
          address: contact.address || undefined,
          areaId: contact.areaId,
          birthday: contact.birthday || null,
          wechat: contact.wechat || '',
        }
      : {}
    await writeCrmCore(`/customers/${props.partnerId}/contacts`, {
      contactId: contact.id || undefined,
      name: contact.name,
      position: contact.position,
      remark: contact.remark,
      isDefault: Boolean(contact.isDefault),
      decisionMaker: Boolean(contact.isDecisionMaker),
      version: contact.id ? contact.version : undefined,
      profileVersion: contact.profileVersion,
      ...details,
    })
    editing.value = false
    await load()
    ElMessage.success('联系人已保存')
  })
}

function openMember(): void {
  error.value = ''
  Object.assign(teamInput, { userId: '', permission: 'read', expiresAt: '' })
  addMember.value = true
}

async function saveMember(): Promise<void> {
  if (!teamInput.userId) return
  await perform(async () => {
    const result = await writeCrmCore<{ items: CrmTeamMember[] }>(
      `/customers/${props.partnerId}/team`,
      {
        ...teamInput,
        expiresAt: crmTimestamp(teamInput.expiresAt),
      },
    )
    team.value = result.items
    addMember.value = false
    ElMessage.success('团队权限已保存')
  })
}

async function removeMember(user: CrmTeamMember): Promise<void> {
  await perform(async () => {
    await writeCrmCore(`/customers/${props.partnerId}/team/${user.userId}`, undefined, 'DELETE')
    team.value = team.value.filter((person) => person.userId !== user.userId)
    ElMessage.success('成员已移除')
  })
}

watch(
  () => props.partnerId,
  () => {
    contacts.value = []
    team.value = []
    error.value = ''
    void load()
  },
  { immediate: true },
)
</script>

<template>
  <el-alert
    v-if="error && !editing && !addMember"
    class="crm-error"
    :title="error"
    type="error"
    :closable="false"
  />
  <div class="page-heading">
    <h3>联系人</h3>
    <el-button v-if="canManageContacts" @click="openEditor()">新增联系人</el-button>
  </div>
  <el-table :data="contacts" empty-text="暂无联系人">
    <el-table-column prop="name" label="姓名" min-width="110" />
    <el-table-column prop="position" label="职位" min-width="95" />
    <el-table-column prop="mobile" label="手机号" min-width="128" />
    <el-table-column label="类型" width="95"
      ><template #default="{ row }">{{
        row.isDecisionMaker ? '决策人' : '联系人'
      }}</template></el-table-column
    >
    <el-table-column v-if="canManageContacts" label="操作" width="75"
      ><template #default="{ row }"
        ><el-button link type="primary" @click="openEditor(row)">编辑</el-button></template
      ></el-table-column
    >
  </el-table>
  <div class="page-heading crm-team-heading">
    <h3>客户团队</h3>
    <el-button v-if="canManageTeam" @click="openMember">维护成员</el-button>
  </div>
  <el-table :data="team" empty-text="暂无团队成员">
    <el-table-column prop="displayName" label="员工" />
    <el-table-column label="权限"
      ><template #default="{ row }">{{
        row.permission === 'write' ? '可维护' : '只读'
      }}</template></el-table-column
    >
    <el-table-column label="到期时间"
      ><template #default="{ row }">{{
        row.expiresAt ? formatDateTime(row.expiresAt) : '长期有效'
      }}</template></el-table-column
    >
    <el-table-column v-if="canManageTeam" label="操作" width="85"
      ><template #default="{ row }"
        ><el-button link type="danger" :disabled="busy" @click="removeMember(row)"
          >移除</el-button
        ></template
      ></el-table-column
    >
  </el-table>

  <el-dialog
    v-model="editing"
    :title="contact.id ? '编辑联系人' : '新增联系人'"
    width="min(640px, 95vw)"
    append-to-body
  >
    <el-alert v-if="error" class="crm-error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="saveContact">
      <div class="crm-form-grid">
        <el-form-item label="姓名" required
          ><el-input v-model="contact.name" :maxlength="50" aria-label="联系人姓名"
        /></el-form-item>
        <el-form-item label="职位"
          ><el-input v-model="contact.position" :maxlength="80" aria-label="职位"
        /></el-form-item>
        <template v-if="privateAccess">
          <el-form-item label="手机"
            ><el-input v-model="contact.mobile" :maxlength="32" aria-label="联系人手机"
          /></el-form-item>
          <el-form-item label="邮箱"
            ><el-input v-model="contact.email" :maxlength="100" aria-label="联系人邮箱"
          /></el-form-item>
          <el-form-item label="微信"
            ><el-input v-model="contact.wechat" :maxlength="80" aria-label="微信号"
          /></el-form-item>
          <el-form-item label="生日"
            ><el-input v-model="contact.birthday" type="date" aria-label="生日"
          /></el-form-item>
        </template>
      </div>
      <el-form-item v-if="privateAccess" label="地址"
        ><el-input v-model="contact.address" :maxlength="200" aria-label="地址"
      /></el-form-item>
      <el-form-item label="备注"
        ><el-input
          v-model="contact.remark"
          type="textarea"
          :maxlength="500"
          aria-label="联系人备注"
      /></el-form-item>
      <div class="crm-inline">
        <el-checkbox v-model="contact.isDecisionMaker">决策人</el-checkbox
        ><el-checkbox v-model="contact.isDefault">默认联系人</el-checkbox>
      </div>
      <el-button
        type="primary"
        native-type="submit"
        :loading="busy"
        :disabled="!contact.name.trim()"
        >保存联系人</el-button
      >
    </el-form>
  </el-dialog>
  <el-dialog v-model="addMember" title="维护客户团队" width="min(500px, 95vw)" append-to-body>
    <el-alert v-if="error" class="crm-error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" @submit.prevent="saveMember">
      <el-form-item label="员工" required
        ><el-select v-model="teamInput.userId" filterable aria-label="团队员工"
          ><el-option
            v-for="user in employees"
            :key="user.id"
            :value="user.id"
            :label="user.displayName" /></el-select
      ></el-form-item>
      <el-form-item label="权限"
        ><el-radio-group v-model="teamInput.permission"
          ><el-radio value="read">只读</el-radio
          ><el-radio value="write">可维护</el-radio></el-radio-group
        ></el-form-item
      >
      <el-form-item label="到期时间"
        ><el-input
          v-model="teamInput.expiresAt"
          type="datetime-local"
          aria-label="团队权限到期时间"
      /></el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!teamInput.userId"
        >保存团队权限</el-button
      >
    </el-form>
  </el-dialog>
</template>

<style scoped>
.crm-team-heading {
  margin-top: 18px;
}
.crm-inline {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}
</style>
