<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { record, records, allRecords, writeRecord } from '../api/admin'
import { isCancelled, messageOf, newIdempotencyKey } from '../api/http'
import { useSessionStore } from '../stores/session'
import { fieldValues, optionsFrom } from '../utils/admin'
import type { DataRow, FormField, SelectOption } from '../types/admin'
import DataFields from './DataFields.vue'

const props = defineProps<{ resource: string; row: DataRow }>()
const emit = defineEmits<{ close: []; saved: [] }>()
const session = useSessionStore()
const current = ref(props.row)
const busy = ref(false)
const error = ref('')
const notice = ref('')
const items = ref<DataRow[]>([])
const options = reactive<Record<string, SelectOption[]>>({})
const input = reactive<Record<string, unknown>>({})
const roleIds = ref<string[]>([])
const permissionCodes = ref<string[]>([])
const permissions = ref<{ code: string; name: string }[]>([])
const scopes = ref<{ companyId: string; scopeType: string; warehouseId: string }[]>([])
const unitRows = ref<{ unitId: string; rateToBase: string; isBase: boolean }[]>([])
const barcodeRows = ref<{ unitId: string; barcode: string }[]>([])
const password = ref('')
const contact = ref<DataRow>()
const contactKey = ref(newIdempotencyKey())
const requestKeys = new Map<string, string>()
const title = computed(
  () =>
    ({
      companies: '公司业务参数',
      users: '用户权限设置',
      roles: '角色权限',
      partners: '联系人',
      skus: '单位与条码',
    })[props.resource] ?? '设置',
)
const policyFields: FormField[] = [
  {
    key: 'taxpayerType',
    label: '纳税人类型',
    kind: 'select',
    options: [
      { value: 'general', label: '一般纳税人' },
      { value: 'small', label: '小规模纳税人' },
    ],
  },
  { key: 'reservationValidDays', label: '预留有效天数', kind: 'number', max: 365 },
  { key: 'reservationGraceDays', label: '交货日后宽限天数', kind: 'number', max: 90 },
  { key: 'overReceiptPct', label: '采购超收容差（百分比）' },
  { key: 'separateOrderApprover', label: '订单审批经办复核分离', kind: 'boolean' },
  { key: 'separateReceiptConfirmer', label: '收付款确认经办复核分离', kind: 'boolean' },
]
const allContactFields: FormField[] = [
  { key: 'name', label: '姓名', required: true, max: 50 },
  { key: 'mobile', label: '手机号码', max: 32 },
  { key: 'email', label: '邮箱', max: 100 },
  { key: 'address', label: '地址', max: 200 },
  { key: 'isDefault', label: '默认联系人', kind: 'boolean' },
]
const contactFields = computed(() => allContactFields.filter(field =>
  !['mobile', 'address'].includes(field.key) || session.can('partner_contact:view')))

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.map(String) : []
}
function list(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value) ? (value as Record<string, unknown>[]) : []
}
function flattenPermissions(value: DataRow[]): { code: string; name: string }[] {
  return value.flatMap((p) => [
    { code: String(p.code), name: String(p.name) },
    ...flattenPermissions(p.children ?? []),
  ])
}
function keyFor(action: string): string {
  if (!requestKeys.has(action)) requestKeys.set(action, newIdempotencyKey())
  return requestKeys.get(action)!
}
async function refresh(): Promise<void> {
  error.value = ''
  if (props.resource === 'companies')
    Object.assign(input, await record('companies/' + props.row.id + '/policy'))
  if (props.resource === 'users') {
    current.value = await record('users/' + props.row.id)
    roleIds.value = strings(current.value.roleIds)
    scopes.value = list(current.value.dataScopes).map((s) => ({
      companyId: String(s.companyId),
      scopeType: String(s.scopeType),
      warehouseId: s.warehouseId == null ? '' : String(s.warehouseId),
    }))
  }
  if (props.resource === 'partners')
    items.value = (await records('partners/' + props.row.id + '/contacts')).items
  if (props.resource === 'skus') {
    current.value = await record('skus/' + props.row.id)
    unitRows.value = list(current.value.units).map((u) => ({
      unitId: String(u.unitId),
      rateToBase: String(u.rateToBase),
      isBase: Boolean(u.isBase),
    }))
    barcodeRows.value = list(current.value.barcodes).map((b) => ({
      unitId: String(b.unitId),
      barcode: String(b.barcode),
    }))
  }
}
async function load(): Promise<void> {
  busy.value = true
  try {
    await refresh()
    if (props.resource === 'users') {
      if (session.can('role:view')) options.roles = optionsFrom(await allRecords('roles'))
      options.companies = session.user?.companies.map((c) => ({ value: c.id, label: c.name })) ?? []
    }
    if (props.resource === 'roles') {
      current.value = await record('roles/' + props.row.id)
      const result = await records('permissions')
      permissions.value = flattenPermissions(result.items)
      permissionCodes.value = strings(current.value.permissionCodes)
    }
    if (props.resource === 'skus' && session.can('unit:view'))
      options.units = optionsFrom(await allRecords('units'))
  } catch (cause: unknown) {
    if (!isCancelled(cause)) error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function save(action: string): Promise<void> {
  if (busy.value) return
  busy.value = true
  error.value = notice.value = ''
  const version = current.value.version
  const base = props.resource + '/' + props.row.id
  try {
    if (action === 'policy') await writeRecord(base + '/policy', { ...input }, 'PUT')
    if (action === 'roles')
      await writeRecord(base + '/roles', { roleIds: roleIds.value, version }, 'PUT')
    if (action === 'scopes')
      await writeRecord(
        base + '/data-scopes',
        {
          version,
          scopes: scopes.value.map((s) => ({
            companyId: s.companyId,
            scopeType: s.scopeType,
            warehouseId: s.scopeType === 'warehouse' ? s.warehouseId : null,
          })),
        },
        'PUT',
      )
    if (action === 'permissions')
      await writeRecord(
        base + '/permissions',
        { version, permissionCodes: permissionCodes.value },
        'PUT',
      )
    if (action === 'reset-password') {
      if (!password.value) throw new Error('请输入临时密码')
      await writeRecord(
        base + '/reset-password',
        { version, password: password.value },
        'POST',
        false,
        keyFor(action),
      )
      password.value = ''
      requestKeys.delete(action)
    }
    if (action === 'unlock') {
      await writeRecord(base + '/unlock', { version }, 'POST', false, keyFor(action))
      requestKeys.delete(action)
    }
    if (action === 'units')
      await writeRecord(base + '/units', { version, units: unitRows.value }, 'PUT')
    if (action === 'barcodes')
      await writeRecord(base + '/barcodes', { version, barcodes: barcodeRows.value }, 'PUT')
    if (action === 'contact') {
      if (!String(input.name ?? '').trim()) {
        error.value = '请输入联系人姓名'
        return
      }
      await writeRecord(
        base + '/contacts' + (contact.value ? '/' + contact.value.id : ''),
        { ...input, ...(contact.value ? { version: contact.value.version } : {}) },
        contact.value ? 'PUT' : 'POST',
        false,
        contact.value ? undefined : contactKey.value,
      )
      newContact()
    }
    notice.value = '设置已保存'
    emit('saved')
    if (props.resource === 'roles') {
      current.value = await record('roles/' + props.row.id)
    } else await refresh()
  } catch (cause: unknown) {
    error.value = cause instanceof Error ? cause.message : messageOf(cause)
  } finally {
    busy.value = false
  }
}
function newContact(row?: DataRow): void {
  contact.value = row
  contactKey.value = newIdempotencyKey()
  for (const key of Object.keys(input)) delete input[key]
  Object.assign(input, fieldValues(contactFields.value, row))
}
async function deleteContact(row: DataRow): Promise<void> {
  busy.value = true
  try {
    await writeRecord(
      'partners/' + props.row.id + '/contacts/' + row.id + '?version=' + row.version,
      {},
      'DELETE',
    )
    await refresh()
    emit('saved')
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  } finally {
    busy.value = false
  }
}
async function selectScopeCompany(index: number): Promise<void> {
  const scope = scopes.value[index]
  if (!scope) return
  scope.warehouseId = ''
  if (!scope.companyId) {
    options['warehouses-' + index] = []
    return
  }
  try {
    if (session.can('warehouse:view'))
      options['warehouses-' + index] = optionsFrom(
        await allRecords('warehouses', false, scope.companyId),
      )
  } catch (cause: unknown) {
    error.value = messageOf(cause)
  }
}
onMounted(() => {
  newContact()
  void load()
})
</script>
<template>
  <el-dialog
    :model-value="true"
    :title="title + ' · ' + (row.name ?? row.displayName ?? row.code)"
    width="760px"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @close="emit('close')"
  >
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
    <el-alert v-if="notice" type="success" :title="notice" :closable="false" />
    <fieldset class="form-fieldset" :disabled="busy">
      <template v-if="resource === 'companies'">
        <DataFields
          :fields="policyFields"
          :values="input"
          :disabled="busy"
          @change="(k, v) => (input[k] = v)"
        />
        <el-button
          v-if="session.can('company_policy:update')"
          type="primary"
          :loading="busy"
          @click="save('policy')"
          >保存业务参数</el-button
        >
      </template>
      <template v-if="resource === 'users'">
        <section v-if="session.can('user:assign_role')" class="relation-section">
          <h2>角色</h2>
          <el-select v-model="roleIds" multiple filterable aria-label="用户角色"
            ><el-option v-for="o in options.roles" :key="o.value" :value="o.value" :label="o.label"
          /></el-select>
          <el-button type="primary" :loading="busy" @click="save('roles')">保存角色</el-button>
        </section>
        <section v-if="session.can('user:assign_scope')" class="relation-section">
          <h2>数据范围</h2>
          <div v-for="(scope, index) in scopes" :key="index" class="relation-row">
            <el-select
              v-model="scope.companyId"
              aria-label="范围公司"
              @change="selectScopeCompany(index)"
              ><el-option
                v-for="o in options.companies"
                :key="o.value"
                :value="o.value"
                :label="o.label"
            /></el-select>
            <el-select
              v-model="scope.scopeType"
              aria-label="范围类型"
              @change="selectScopeCompany(index)"
              ><el-option value="company" label="整个公司" /><el-option
                value="warehouse"
                label="指定仓库"
            /></el-select>
            <el-select
              v-if="scope.scopeType === 'warehouse'"
              v-model="scope.warehouseId"
              aria-label="范围仓库"
              ><el-option
                v-for="o in options['warehouses-' + index]"
                :key="o.value"
                :value="o.value"
                :label="o.label"
            /></el-select>
            <el-button text @click="scopes.splice(index, 1)">移除</el-button>
          </div>
          <div class="relation-actions">
            <el-button
              @click="scopes.push({ companyId: '', scopeType: 'company', warehouseId: '' })"
              >添加范围</el-button
            ><el-button type="primary" :loading="busy" @click="save('scopes')">保存数据范围</el-button>
          </div>
        </section>
        <section v-if="session.can('user:reset_password')" class="relation-section">
          <h2>重置密码</h2>
          <el-input
            v-model="password"
            type="password"
            show-password
            aria-label="临时密码"
            placeholder="设置临时密码"
            maxlength="72"
          />
          <p>用户下次登录时需要修改密码。</p>
          <el-button :loading="busy" @click="save('reset-password')">重置密码</el-button>
        </section>
        <el-button v-if="session.can('user:update')" :loading="busy" @click="save('unlock')"
          >解除登录锁定</el-button
        >
      </template>
      <template v-if="resource === 'roles'">
        <el-checkbox-group v-model="permissionCodes" class="permission-grid">
          <el-checkbox
            v-for="p in permissions"
            :key="p.code"
            :value="p.code"
            :disabled="!session.can(p.code)"
            >{{ p.name }}<small>{{ p.code }}</small></el-checkbox
          >
        </el-checkbox-group>
        <el-button type="primary" :loading="busy" @click="save('permissions')">保存权限</el-button>
      </template>
      <template v-if="resource === 'partners'">
        <el-table :data="items"
          ><el-table-column prop="name" label="姓名" /><el-table-column
            v-if="session.can('partner_contact:view')"
            prop="mobile"
            label="电话"
          /><el-table-column prop="email" label="邮箱" />
          <el-table-column label="操作"
            ><template #default="{ row: item }"
              ><button
                v-if="session.can('partner_contact:update')"
                class="text-link"
                @click="newContact(item)"
              >
                编辑</button
              ><el-popconfirm
                v-if="session.can('partner_contact:delete')"
                title="确认删除此联系人？"
                @confirm="deleteContact(item)"
                ><template #reference><el-button text>删除</el-button></template></el-popconfirm
              ></template
            ></el-table-column
          >
        </el-table>
        <section
          v-if="session.can('partner_contact:create') || session.can('partner_contact:update')"
          class="relation-section"
        >
          <h2>{{ contact ? '编辑联系人' : '新建联系人' }}</h2>
          <DataFields
            :fields="contactFields"
            :values="input"
            :disabled="busy"
            @change="(k, v) => (input[k] = v)"
          />
          <el-button @click="newContact()">清空</el-button
          ><el-button type="primary" :loading="busy" @click="save('contact')">保存联系人</el-button>
        </section>
      </template>
      <template v-if="resource === 'skus'">
        <section class="relation-section">
          <h2>单位换算</h2>
          <p>换算率表示 1 个该单位相当于多少基础单位。已被业务引用的换算率受服务端保护。</p>
          <div v-for="(unit, index) in unitRows" :key="index" class="relation-row">
            <el-select v-model="unit.unitId" aria-label="换算单位"
              ><el-option
                v-for="o in options.units"
                :key="o.value"
                :value="o.value"
                :label="o.label"
            /></el-select>
            <el-input
              v-model="unit.rateToBase"
              aria-label="换算率"
              placeholder="换算率"
            /><el-checkbox v-model="unit.isBase">基础单位</el-checkbox
            ><el-button text @click="unitRows.splice(index, 1)">移除</el-button>
          </div>
          <div class="relation-actions">
            <el-button @click="unitRows.push({ unitId: '', rateToBase: '1.000000', isBase: false })"
              >添加单位</el-button
            ><el-button type="primary" :loading="busy" @click="save('units')">保存单位换算</el-button>
          </div>
        </section>
        <section class="relation-section">
          <h2>条码</h2>
          <div v-for="(barcode, index) in barcodeRows" :key="index" class="relation-row">
            <el-input
              v-model="barcode.barcode"
              aria-label="条码"
              placeholder="条码"
              maxlength="64"
            /><el-select v-model="barcode.unitId" aria-label="条码单位"
              ><el-option
                v-for="o in options.units"
                :key="o.value"
                :value="o.value"
                :label="o.label" /></el-select
            ><el-button text @click="barcodeRows.splice(index, 1)">移除</el-button>
          </div>
          <div class="relation-actions">
            <el-button @click="barcodeRows.push({ unitId: '', barcode: '' })">添加条码</el-button
            ><el-button type="primary" :loading="busy" @click="save('barcodes')">保存条码</el-button>
          </div>
        </section>
      </template>
    </fieldset>
    <template #footer
      ><el-button :disabled="busy" @click="emit('close')">关闭</el-button
      ><el-button :disabled="busy" @click="load">刷新</el-button></template
    >
  </el-dialog>
</template>
