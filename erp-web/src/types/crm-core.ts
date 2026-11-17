import type { Id } from './api'

export type CrmCoreKind = 'clues' | 'customers' | 'pool' | 'settings'
export type CrmFollowSubject = 'clue' | 'customer' | 'business' | 'contract'
export type CrmClueStatus = 'new' | 'following' | 'converted' | 'invalid'
export type CrmDealStatus = 'sold' | 'unsold'

export interface CrmProfileFields {
  source?: string
  industry?: string
  areaId?: Id | null
  nextContactAt?: string | null
}

export interface CrmClueInput extends CrmProfileFields {
  name: string
  contactName?: string
  mobile?: string
  email?: string
  taxNo?: string
  version?: number
}

export interface CrmClue extends CrmClueInput {
  id: Id
  status: CrmClueStatus
  ownerUserId: Id
  version: number
  convertedPartnerId?: Id | null
  lastFollowAt?: string | null
}

export interface CrmCustomerInput extends CrmProfileFields {
  partnerId?: Id
  ownerUserId?: Id
  level?: string
  dealStatus?: CrmDealStatus
  version?: number
}

export interface CrmCustomer extends CrmCustomerInput {
  id: Id
  partnerId: Id
  partnerCode: string
  partnerName: string
  ownerUserId: Id
  dealStatus: CrmDealStatus
  status: 'active' | 'disabled'
  canWrite: boolean
  locked: boolean
  version: number
  poolEnteredAt?: string | null
  poolReason?: 'manual' | 'automatic' | null
  lastFollowAt?: string | null
}

export interface CrmEmployee {
  id: Id
  displayName: string
  departmentId: Id
}

export interface CrmFollowUp {
  id: Id
  bizType: CrmFollowSubject
  bizId: Id
  method: string
  content: string
  nextContactAt?: string | null
  mentionedUserIds: Id[]
  createdAt: string
  authorName?: string
}

export interface CrmTeamMember {
  userId: Id
  displayName: string
  permission: 'read' | 'write'
  expiresAt?: string | null
}

export interface CrmContact {
  id: Id
  name: string
  mobile?: string | null
  email?: string | null
  address?: string | null
  position?: string | null
  isDecisionMaker?: boolean
  isDefault?: boolean
  birthday?: string | null
  wechat?: string | null
  remark?: string | null
  version: number
  profileVersion?: number | null
  areaId?: Id | null
}

export interface CrmPoolRule {
  enabled: boolean
  noFollowDays: number
  noDealDays: number
  notifyBeforeDays: number
  applyToLevels: string[]
  version?: number
}

export interface CrmOwnerLimit {
  targetType: 'user' | 'department'
  targetId: Id
  targetName?: string
  maxOwned: number
  maxLocked: number
  includeSold: boolean
  version?: number
}
