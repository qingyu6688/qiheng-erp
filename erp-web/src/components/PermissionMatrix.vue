<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSessionStore } from '../stores/session'

interface Permission {
  code: string
  name: string
}
const props = defineProps<{ permissions: Permission[]; modelValue: string[]; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
const session = useSessionStore()
const keyword = ref('')
const actions = [
  { key: 'view', label: '查看' },
  { key: 'create', label: '新建' },
  { key: 'update', label: '修改' },
  { key: 'submit', label: '提交' },
  { key: 'post', label: '过账' },
]
const resourceNames: Record<string, string> = {
  company: '公司',
  company_policy: '公司业务参数',
  user: '用户',
  role: '角色',
  audit: '审计',
  warehouse: '仓库',
  unit: '计量单位',
  product_category: '商品分类',
  product: '商品',
  sku: 'SKU',
  partner: '往来单位',
  partner_contact: '联系人',
  cash_account: '结算账户',
  cash_account_no: '银行账号',
  income_expense_item: '收支项目',
  period: '会计期间',
  inventory: '库存',
  inventory_cost: '库存成本',
  cost: '成本',
  pricing: '价格试算',
  sales_order: '销售订单',
  sales_order_change: '销售变更',
  shipment: '发货与签收',
  invoice: '销售发票',
  sales_invoice: '销售发票',
  sales_return: '销售退货',
  receipt: '收款',
  sales_refund: '销售退款',
  purchase_order: '采购订单',
  purchase_order_change: '采购变更',
  receiving: '采购收货',
  purchase_invoice: '采购发票',
  purchase_return: '采购退货',
  payment: '付款',
  purchase_refund: '采购退款',
  opening_entry: '期初',
  stock_transfer: '库存调拨',
  stock_count: '库存盘点',
  stock_adjustment: '库存调整',
  settlement: '往来核销',
  account_transfer: '账户转账',
  reconciliation: '对账',
  report: '报表',
  import: '导入',
  attachment: '附件',
  admin: '系统运维',
  tenant: '系统管理',
}
const groups = computed(() => {
  const grouped = new Map<string, { resource: string; name: string; permissions: Permission[] }>()
  for (const permission of props.permissions) {
    const resource = permission.code.split(':')[0]!
    if (!grouped.has(resource))
      grouped.set(resource, {
        resource,
        name: resourceNames[resource] ?? permission.name.replace(/^(查看|新增|修改|删除)/, ''),
        permissions: [],
      })
    grouped.get(resource)!.permissions.push(permission)
  }
  const query = keyword.value.trim().toLowerCase()
  return [...grouped.values()].filter(
    (group) =>
      !query ||
      [
        group.name,
        group.resource,
        ...group.permissions.flatMap((permission) => [permission.name, permission.code]),
      ].some((value) => value.toLowerCase().includes(query)),
  )
})
function otherPermissions(permissions: Permission[]): Permission[] {
  return permissions.filter(
    (permission) => !actions.some((action) => permission.code.endsWith(':' + action.key)),
  )
}
</script>
<template>
  <div class="permission-toolbar">
    <label for="permission-search">搜索权限</label>
    <el-input
      id="permission-search"
      v-model="keyword"
      placeholder="资源名称或权限名称"
      clearable
      maxlength="100"
    />
  </div>
  <p class="permission-hint">
    只能授予当前账号已有的权限。不可授予的项目保持原有选择，并由服务端复核。
  </p>
  <el-checkbox-group
    :model-value="modelValue"
    :disabled="disabled"
    @update:model-value="emit('update:modelValue', $event.map(String))"
  >
    <div class="permission-table-wrap">
      <table class="permission-matrix" aria-label="角色权限矩阵">
        <thead>
          <tr>
            <th scope="col">资源</th>
            <th v-for="action in actions" :key="action.key" scope="col">{{ action.label }}</th>
            <th scope="col">其他</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="group in groups" :key="group.resource">
            <th scope="row">{{ group.name }}</th>
            <td v-for="action in actions" :key="action.key">
              <template
                v-for="permission in group.permissions.filter(
                  (p) => p.code === group.resource + ':' + action.key,
                )"
                :key="permission.code"
              >
                <el-checkbox
                  :value="permission.code"
                  :aria-label="permission.name + ' ' + permission.code"
                  :disabled="!session.can(permission.code)"
                  :title="permission.code"
                  ><span class="visually-hidden"
                    >{{ permission.name }} {{ permission.code }}</span
                  ></el-checkbox
                >
              </template>
              <span
                v-if="!group.permissions.some((p) => p.code === group.resource + ':' + action.key)"
                class="permission-empty"
                >—</span
              >
            </td>
            <td class="permission-other">
              <el-checkbox
                v-for="permission in otherPermissions(group.permissions)"
                :key="permission.code"
                :value="permission.code"
                :aria-label="permission.name + ' ' + permission.code"
                :disabled="!session.can(permission.code)"
                :title="permission.code"
                >{{ permission.name
                }}<span class="visually-hidden"> {{ permission.code }}</span></el-checkbox
              >
            </td>
          </tr>
          <tr v-if="!groups.length">
            <td colspan="7" class="permission-empty">没有匹配的权限</td>
          </tr>
        </tbody>
      </table>
    </div>
  </el-checkbox-group>
  <p class="permission-hint" aria-live="polite">
    共 {{ permissions.length }} 项权限，已选 {{ modelValue.length }} 项
  </p>
</template>
<style scoped>
.permission-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.permission-toolbar label {
  white-space: nowrap;
  font-size: 13px;
}
.permission-hint {
  font-size: 12px;
  line-height: 20px;
  color: #6b7380;
}
.permission-table-wrap {
  overflow: auto;
}
.permission-matrix {
  border-collapse: collapse;
  min-width: 520px;
  width: 100%;
  font-size: 12px;
}
.permission-matrix th,
.permission-matrix td {
  padding: 10px 8px;
  border-bottom: 1px solid #e3e6eb;
  text-align: center;
}
.permission-matrix thead th {
  background: #f6f7f9;
  font-weight: 500;
}
.permission-matrix th:first-child {
  text-align: left;
  min-width: 90px;
}
.permission-matrix :deep(.el-checkbox) {
  margin: 0;
  height: auto;
  min-height: 24px;
}
.permission-matrix :deep(.el-checkbox__label) {
  padding-left: 0;
}
.permission-other {
  text-align: left !important;
  min-width: 150px;
}
.permission-other :deep(.el-checkbox) {
  display: flex;
  margin: 5px 0;
}
.permission-other :deep(.el-checkbox__label) {
  padding-left: 7px;
  white-space: normal;
  font-size: 12px;
}
.permission-empty {
  color: #9ba2ac;
}
.visually-hidden {
  position: absolute;
  clip: rect(0, 0, 0, 0);
  width: 1px;
  height: 1px;
  overflow: hidden;
  white-space: nowrap;
}
</style>
