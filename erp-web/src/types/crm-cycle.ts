import type { DataRow } from './admin'

export type CrmCycleKind =
  'businesses' | 'contracts' | 'receivables' | 'performance-targets' | 'statistics'
export type CrmCycleMode =
  | 'business'
  | 'contract'
  | 'stage'
  | 'stage-action'
  | 'submit'
  | 'order'
  | 'plan'
  | 'attach'
  | 'target'

export interface CrmCycleRow extends DataRow {
  partnerId?: string
  partnerName?: string
  documentNo?: string
  stageGroupId?: string
  stageId?: string
  businessId?: string
  signerContactId?: string
  result?: string
  priceTaxInclusive?: boolean
  wfInstanceId?: string
  salesOrderIds?: string[]
  lines?: CrmCycleRow[]
}

export interface CrmStage {
  id: string
  name: string
  winRate: string
}

export interface CrmStageGroup {
  id: string
  name: string
  stages: CrmStage[]
}

export interface CrmCycleStatistics {
  kind: string
  dateFrom: string
  dateTo: string
  dataSource: 'primary' | 'replica'
  items: DataRow[]
  targets?: CrmCycleRow[]
}

export interface CrmProductLine {
  skuId: string
  unitId: string
  qty: string
  unitPrice: string
  taxRate?: string
  discountRate?: string
}

export interface CrmContractOrderLine {
  contractLineId: string
  warehouseId: string
  qty: string
  label: string
  remainingQty: string
}
