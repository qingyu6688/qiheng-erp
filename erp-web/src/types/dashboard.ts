export interface DashboardMetric {
  code: string
  name: string
  detailPath: string
}

export interface DashboardValue {
  label: string
  value: string | null
  unit: string
}

export interface DashboardPlot {
  name: string
  points: DashboardValue[]
}

export interface DashboardResult {
  code: string
  asOf: string
  dataSource: 'replica' | 'primary' | 'mixed'
  values: DashboardValue[]
  plots: DashboardPlot[]
  note: string
}

export interface DashboardData {
  period: string
  dateFrom: string
  dateTo: string
  items: DashboardResult[]
}

export interface DashboardLayout {
  version: number
  preset: 'owner' | 'sales' | 'finance' | 'warehouse'
  metrics: string[]
}
