export const crmCoreNavigation = [
  { kind: 'clues', title: '线索', permission: 'crm_clue:view' },
  { kind: 'customers', title: '客户', permission: 'crm_customer:view' },
  { kind: 'pool', title: '客户公海', permission: 'crm_pool:view' },
  { kind: 'settings', title: '公海规则与限制', permission: 'crm_pool:manage' },
] as const

export const crmCycleNavigation = [
  { kind: 'businesses', title: '商机', permission: 'crm_business:view' },
  { kind: 'contracts', title: '合同', permission: 'crm_contract:view' },
  { kind: 'receivables', title: '合同回款', permission: 'crm_receivable:view' },
  { kind: 'performance-targets', title: '业绩目标', permission: 'crm_target:view' },
  { kind: 'statistics', title: 'CRM 统计', permission: 'crm_statistics:view' },
] as const
