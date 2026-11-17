import { request } from './http'

export type FieldEntity =
  'sales_order' | 'sales_order_line' | 'purchase_order' | 'sku' | 'business_partner'
export type FieldValue = string | boolean | string[] | null
export type FieldValues = Record<string, FieldValue>
export interface CustomField {
  id: string
  fieldKey: string
  label: string
  type: string
  choices: string[]
  dictionaryCode: string | null
  defaultValue: FieldValue
  required: boolean
  indexSlot: number | null
  listVisible: boolean
  printVisible: boolean
  importExport: boolean
  sortNo: number
  enabled: boolean
  version: number
}
export interface FieldInput extends Omit<CustomField, 'id' | 'indexSlot' | 'version'> {
  searchable: boolean
  version?: number
}
export interface CodeSegment {
  type: string
  value?: string
  length?: number
}
export interface CodeRule {
  id?: string
  companyId: string
  target: string
  segments: CodeSegment[]
  resetCycle: string
  enabled: boolean
  version?: number
}
export const customFields = (entity: FieldEntity): Promise<CustomField[]> =>
  request({ url: `/custom-fields/${entity}` }, !['sku', 'business_partner'].includes(entity))
export const allCustomFields = (entity: FieldEntity): Promise<CustomField[]> =>
  request({ url: `/sys/custom-fields/${entity}` }, true)
export const saveCustomField = (entity: FieldEntity, data: FieldInput): Promise<CustomField> =>
  request({ method: 'PUT', url: `/sys/custom-fields/${entity}`, data }, true)
export const codeRules = (): Promise<CodeRule[]> => request({ url: '/sys/code-rules' }, true)
function ruleInput(rule: CodeRule): Omit<CodeRule, 'id'> {
  const { companyId, target, segments, resetCycle, enabled, version } = rule
  return { companyId, target, segments, resetCycle, enabled, version }
}
export const saveCodeRule = (data: CodeRule): Promise<CodeRule> =>
  request({ method: 'PUT', url: '/sys/code-rules', data: ruleInput(data) }, true)
export const previewCodeRule = (
  rule: CodeRule,
  date?: string,
  categoryId?: string,
): Promise<{ code: string }> =>
  request(
    {
      method: 'POST',
      url: '/sys/code-rules/preview',
      data: { rule: ruleInput(rule), date, categoryId },
    },
    true,
  )
export const exportOrderFields = (kind: string, params: Record<string, unknown>): Promise<Blob> =>
  request(
    { url: `/document-browser/${kind}/custom-fields.csv`, responseType: 'blob', params },
    true,
  )

export const fieldLabels: Record<string, string> = {
  text: '单行文本',
  textarea: '多行文本',
  number: '整数',
  decimal: '小数',
  date: '日期',
  datetime: '日期和时间',
  select: '单选',
  multi_select: '多选',
  user: '用户编号',
  department: '部门编号',
  bool: '是或否',
}
export const entityLabels: Record<FieldEntity, string> = {
  sales_order: '销售订单',
  sales_order_line: '销售订单行',
  purchase_order: '采购订单',
  sku: '商品 SKU',
  business_partner: '往来单位',
}

export function formatField(value: unknown): string {
  if (Array.isArray(value)) return value.join('、')
  if (typeof value === 'boolean') return value ? '是' : '否'
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '—'
}
