export interface DataRow {
  id: string
  code?: string
  name?: string
  status?: string
  version?: number
  allowedActions?: string[]
  children?: DataRow[]
  [key: string]: unknown
}

export interface SelectOption {
  value: string
  label: string
}

export interface FormField {
  key: string
  label: string
  kind?: 'text' | 'password' | 'select' | 'multiple' | 'boolean' | 'number' | 'date'
  required?: boolean
  max?: number
  options?: SelectOption[]
  source?: string
  sourcePermission?: string
  createOnly?: boolean
  readonlyOnEdit?: boolean
  permission?: string
  initial?: unknown
  emptyAsNull?: boolean
}

export interface ResourceSpec {
  path: string
  title: string
  singular: string
  permission: string
  company?: boolean
  detail?: boolean
  enable?: boolean
  createOperation?: string
  fields: FormField[]
  columns: { key: string; label: string }[]
}
