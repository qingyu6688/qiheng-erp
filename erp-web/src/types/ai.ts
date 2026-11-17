import type { Id } from './api'

export interface AiAssistantOption {
  code: string
  name: string
  toolCodes?: string[]
}

export interface AiConversation {
  id: Id
  assistantCode: string
  title: string | null
  createdAt: string
}

export interface AiCitation {
  number: number
  segmentId: Id
  title: string
  heading: string | null
  page: number | null
  wikiSlug: string | null
}

export interface AiToolResult {
  code: string
  label: string
  rows: Record<string, unknown>[]
  hasMore?: boolean
}

export interface AiMessage {
  id: Id
  role: 'user' | 'assistant' | 'tool'
  content: string
  citations: AiCitation[]
  tools: AiToolResult[]
  createdAt: string
}

export type AiChatEvent =
  | { type: 'token'; data: { text: string } }
  | { type: 'citation'; data: AiCitation }
  | { type: 'tool_call'; data: { code: string; label?: string } }
  | { type: 'tool_result'; data: AiToolResult }
  | { type: 'done'; data: { messageId: Id; refused: boolean } }
  | { type: 'error'; data: { code: string; message: string } }

export interface AiEvidence {
  segmentId: Id
  title: string
  heading: string | null
  content: string
  page: number | null
  wikiSlug: string | null
  sourceIds: Id[]
}

export type AiVisibility = 'all' | 'roles' | 'departments'

export interface AiLibraryInput {
  name: string
  description: string
  visibility: AiVisibility
  targetIds: Id[]
  status: 'active' | 'disabled'
}

export interface AiLibrary extends AiLibraryInput {
  id: Id
}

export interface AiDocument {
  id: Id
  libraryId: Id
  title: string
  fileName: string
  mime: string
  size: number
  versionNo: number
  status: 'uploaded' | 'parsing' | 'indexed' | 'failed' | 'disabled'
  error: string | null
  uploadedBy: Id
  updatedAt: string
}

export interface AiSegment {
  id: Id
  documentId: Id
  seq: number
  heading: string | null
  pageFrom: number | null
  pageTo: number | null
  charCount: number
  status: string
  content: string
}

export interface AiRecallHit {
  evidence: AiEvidence
  score: number
  selected: boolean
}

export interface AiWikiPage {
  id: Id
  slug: string
  title: string
  summary: string
  content: string
  status: string
  revisionId: Id
  sourceIds: Id[]
}

export interface AiWikiRevision {
  id: Id
  content: string
  sourceIds: Id[]
  authorType: 'ai' | 'human'
  changeNote: string | null
  createdAt: string
}

export interface AiWikiReview {
  id: Id
  slug: string
  title: string
  summary: string
  status: string
  currentRevision: AiWikiRevision | null
  pendingRevision: AiWikiRevision | null
  stale: boolean
}

export interface AiWikiLint {
  id: Id
  pageId: Id
  slug: string
  title: string
  type: string
  detail: string
  status: string
  createdAt: string
}

export interface AiProvider {
  id?: Id
  name: string
  type: 'openai_compatible' | 'anthropic'
  endpointKey: string
  hasKey?: boolean
  apiKey?: string
  enabled: boolean
  dataRegion: 'domestic' | 'overseas'
  outboundConfirmed: boolean
}

export interface AiModel {
  id?: Id
  providerId: Id
  name: string
  modelKey: string
  purpose: 'chat' | 'embedding' | 'rerank' | 'vision'
  contextWindow: number
  priceIn: string
  priceOut: string
  enabled: boolean
}

export interface AiRoute {
  scene: 'kb_chat' | 'wiki_maintain'
  modelId: Id
  fallbackModelId: Id | null
  maxTokens: number
  temperature: number | null
}

export interface AiAssistantConfig extends AiAssistantOption {
  id?: Id
  systemPrompt: string
  libraryIds: Id[]
  toolCodes: string[]
  roleIds: Id[]
  enabled: boolean
}

export interface AiToolDefinition {
  code: string
  label: string
  description: string
}

export interface AiQuota {
  dailyTokenLimit: number
  monthlyCostLimit: string
}

export interface AiUsage {
  date: string
  scene: string
  modelId: Id
  inputTokens: number
  outputTokens: number
  cost: string
  requests: number
}

export interface AiRequestLog {
  id: Id
  scene: string
  modelId: Id | null
  inputTokens: number
  outputTokens: number
  status: string
  errorCode: string | null
  latencyMs: number
  createdAt: string
}

export interface AiEvalSet {
  id: Id
  name: string
  totalCases: number
  latestRun?: AiEvalRun | null
}

export interface AiEvalRun {
  id: Id
  status: string
  total?: number
  correctRate?: number
  citationRate?: number
  refusalRate?: number
  securityRate?: number
  passed?: boolean
  results?: AiEvalResult[]
}

export interface AiEvalResult {
  caseId: Id
  category: string
  correct: boolean
  citationCorrect: boolean
  refused: boolean
  expectedRefusal: boolean
  reason: string | null
}
