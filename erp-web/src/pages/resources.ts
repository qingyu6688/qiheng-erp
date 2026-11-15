import type { FormField, ResourceSpec, SelectOption } from '../types/admin'
import { salesSpecs } from '../types/sales'

const choices = (pairs: string[][]): SelectOption[] =>
  pairs.map(([value, label]) => ({ value: value!, label: label! }))
const code: FormField = { key: 'code', label: '编码', required: true, max: 32 }
const name: FormField = { key: 'name', label: '名称', required: true, max: 100 }
const status: FormField = {
  key: 'status',
  label: '状态',
  kind: 'select',
  initial: 'active',
  options: choices([
    ['active', '启用'],
    ['disabled', '停用'],
  ]),
}
const baseColumns = [
  { key: 'code', label: '编码' },
  { key: 'name', label: '名称' },
]

export const resourceSpecs: Record<string, ResourceSpec> = {
  companies: {
    path: 'companies',
    title: '公司管理',
    singular: '公司',
    permission: 'company',
    detail: true,
    enable: true,
    fields: [
      { ...code, max: 16 },
      name,
      { key: 'taxNo', label: '统一社会信用代码', max: 32 },
      { key: 'address', label: '地址', max: 200 },
      { key: 'phone', label: '电话', max: 32 },
    ],
    columns: [...baseColumns, { key: 'phone', label: '电话' }],
  },
  users: {
    path: 'users',
    title: '用户管理',
    singular: '用户',
    permission: 'user',
    detail: true,
    enable: true,
    fields: [
      { key: 'username', label: '用户名', required: true, max: 64, readonlyOnEdit: true },
      { key: 'displayName', label: '姓名', required: true, max: 64 },
      { key: 'password', label: '初始密码', kind: 'password', required: true, createOnly: true },
      { key: 'mobile', label: '手机号码', max: 32, permission: 'user:view_mobile' },
    ],
    columns: [
      { key: 'username', label: '用户名' },
      { key: 'displayName', label: '姓名' },
      { key: 'mustChangePassword', label: '需要改密' },
    ],
  },
  roles: {
    path: 'roles',
    title: '角色权限',
    singular: '角色',
    permission: 'role',
    detail: true,
    fields: [code, { ...name, max: 64 }, status],
    columns: baseColumns,
  },
  partners: {
    path: 'partners',
    title: '往来单位',
    singular: '往来单位',
    permission: 'partner',
    detail: true,
    enable: true,
    fields: [
      code,
      name,
      { key: 'shortName', label: '简称', max: 50 },
      { key: 'taxNo', label: '税号', max: 32 },
      {
        key: 'roles',
        label: '业务角色',
        kind: 'multiple',
        required: true,
        options: choices([
          ['customer', '客户'],
          ['supplier', '供应商'],
        ]),
      },
    ],
    columns: [...baseColumns, { key: 'roles', label: '业务角色' }],
  },
  units: {
    path: 'units',
    title: '计量单位',
    singular: '计量单位',
    permission: 'unit',
    fields: [{ ...name, max: 16 }, status],
    columns: [{ key: 'name', label: '单位名称' }],
  },
  'product-categories': {
    path: 'product-categories',
    title: '商品分类',
    singular: '商品分类',
    permission: 'product_category',
    fields: [
      code,
      { ...name, max: 64 },
      {
        key: 'parentId',
        label: '上级分类',
        kind: 'select',
        source: 'product-categories',
        sourcePermission: 'product_category:view',
      },
      { key: 'sortNo', label: '排序', kind: 'number', initial: 0 },
      status,
    ],
    columns: [
      { key: 'code', label: '分类编码' },
      { key: 'treeLabel', label: '分类名称' },
    ],
  },
  products: {
    path: 'products',
    title: '商品资料',
    singular: '商品',
    permission: 'product',
    detail: true,
    fields: [
      code,
      name,
      {
        key: 'categoryId',
        label: '商品分类',
        kind: 'select',
        source: 'product-categories',
        sourcePermission: 'product_category:view',
        required: true,
      },
      {
        key: 'baseUnitId',
        label: '基础单位',
        kind: 'select',
        source: 'units',
        sourcePermission: 'unit:view',
        required: true,
      },
      {
        key: 'itemType',
        label: '商品类型',
        kind: 'select',
        initial: 'goods',
        options: choices([
          ['goods', '商品'],
          ['material', '物料'],
          ['finished', '成品'],
        ]),
      },
      status,
    ],
    columns: baseColumns,
  },
  skus: {
    path: 'skus',
    title: 'SKU 与条码',
    singular: 'SKU',
    permission: 'sku',
    detail: true,
    enable: true,
    fields: [
      {
        key: 'productId',
        label: '所属商品',
        kind: 'select',
        source: 'products',
        sourcePermission: 'product:view',
        required: true,
        readonlyOnEdit: true,
      },
      code,
      name,
      { key: 'spec', label: '规格', max: 200 },
      { key: 'defaultTaxRate', label: '默认税率', initial: '0.130000' },
      { key: 'purchasePrice', label: '采购价', initial: '', permission: 'cost:view' },
      { key: 'salePrice', label: '销售价', initial: '' },
    ],
    columns: [
      ...baseColumns,
      { key: 'spec', label: '规格' },
      { key: 'salePrice', label: '销售价' },
    ],
  },
  'cash-accounts': {
    path: 'cash-accounts',
    title: '结算账户',
    singular: '结算账户',
    permission: 'cash_account',
    company: true,
    detail: true,
    enable: true,
    fields: [
      code,
      name,
      {
        key: 'accountType',
        label: '账户类型',
        kind: 'select',
        required: true,
        initial: 'bank',
        options: choices([
          ['cash', '现金'],
          ['bank', '银行'],
          ['other', '其他'],
        ]),
      },
      { key: 'bankName', label: '开户银行', max: 100 },
      { key: 'accountNo', label: '银行账号', max: 64, permission: 'cash_account_no:view', emptyAsNull: false },
    ],
    columns: [
      ...baseColumns,
      { key: 'bankName', label: '开户银行' },
      { key: 'accountNo', label: '银行账号' },
      { key: 'balance', label: '当前余额' },
    ],
  },
  'income-expense-items': {
    path: 'income-expense-items',
    title: '收支项目',
    singular: '收支项目',
    permission: 'income_expense_item',
    fields: [
      code,
      { ...name, max: 64 },
      {
        key: 'itemType',
        label: '类型',
        kind: 'select',
        required: true,
        initial: 'expense',
        options: choices([
          ['income', '收入'],
          ['expense', '支出'],
        ]),
      },
      status,
    ],
    columns: [...baseColumns, { key: 'itemType', label: '类型' }],
  },
  'accounting-periods': {
    path: 'accounting-periods',
    title: '会计期间',
    singular: '期间',
    permission: 'period',
    company: true,
    createOperation: 'period.open',
    fields: [{ key: 'period', label: '期间（如 202611）', required: true, max: 6 }],
    columns: [
      { key: 'period', label: '期间' },
      { key: 'startDate', label: '开始日期' },
      { key: 'endDate', label: '结束日期' },
    ],
  },
  'audit-logs': {
    path: 'audit-logs',
    title: '操作审计',
    singular: '审计记录',
    permission: 'audit',
    fields: [],
    columns: [
      { key: 'action', label: '操作' },
      { key: 'entityType', label: '业务对象' },
      { key: 'entityId', label: '对象编号' },
      { key: 'operatorId', label: '操作人' },
      { key: 'createdAt', label: '时间' },
      { key: 'requestId', label: '请求编号' },
    ],
  },
}

export const resourceGroups = [
  { title: '组织与权限', paths: ['companies', 'users', 'roles', 'audit-logs'] },
  {
    title: '基础资料',
    paths: ['partners', 'cash-accounts', 'income-expense-items', 'accounting-periods'],
  },
  { title: '商品', paths: ['units', 'product-categories', 'products', 'skus'] },
]

export function defaultWorkspace(can: (permission: string) => boolean, hasCompany: boolean): string {
  if (hasCompany && can('warehouse:view')) return '/warehouses'
  if (hasCompany) {
    const sales = salesSpecs.find(spec => can(spec.permission + ':view'))
    if (sales) return '/' + sales.path
    if (can('settlement:view')) return '/settlements'
    if (can('inventory:view')) return '/inventory-balances'
  }
  const first = Object.values(resourceSpecs).find(spec => can(spec.permission + ':view') && (!spec.company || hasCompany))
  return first ? '/' + first.path : '/account/password'
}
