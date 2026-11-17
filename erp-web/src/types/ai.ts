import type { Id } from './api'

export interface AiAssistantOption {
  code: string
  name: string
  toolCodes?: string[]
}

export type AiExplainDocumentType = 'sales_order' | 'shipment' | 'accounting_period'

export interface AiDocumentReference {
  documentType: AiExplainDocumentType
  documentId: Id
}

export interface AiDocumentContext extends AiDocumentReference {
  label: string
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
  status?: 'ok' | 'ambiguous'
  asOf?: string
  summary?: Record<string, unknown>
  detailUrl?: string
  candidates?: { code?: string; name: string }[]
}

export const aiBusinessTools = {
  receivable_balance: { label: '应收余额', example: '查华东商贸的应收余额，截至今天。' },
  payable_balance: { label: '应付余额', example: '截至今天，我们还欠华北供应商多少钱？' },
  available_stock: { label: '可用库存', example: '查 A001 商品在一号仓的可用库存。' },
  order_progress: { label: '销售进度', example: '查销售订单 SO-1001 的发货进度。' },
  purchase_progress: { label: '采购进度', example: '查采购订单 PO-1001 的收货进度。' },
  sales_summary: { label: '销售汇总', example: '本月销售额按业务员汇总。' },
  gross_margin: { label: '销售毛利', example: '查本月按商品汇总的销售毛利。' },
  cash_balance: { label: '资金余额', example: '查基本户截至今天的余额。' },
  document_search: { label: '查找单据', example: '找本月编号包含 SO-1001 的销售订单。' },
} as const

export interface AiMessage {
  id: Id
  role: 'user' | 'assistant' | 'tool'
  content: string
  citations: AiCitation[]
  tools: AiToolResult[]
  createdAt: string
  plainText?: boolean
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
  setCode?: string
  total?: number
  correctRate?: number
  citationRate?: number
  refusalRate?: number
  securityRate?: number
  toolSelectionRate?: number | null
  parameterRate?: number | null
  reportAgreementRate?: number | null
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
  toolSelected?: boolean | null
  parametersCorrect?: boolean | null
  reportAgrees?: boolean | null
  reason: string | null
}

export interface AiEvalCase {
  id: Id
  category: string
  question: string
  scenarioId?: string
  toolCode?: string
  arguments?: Record<string, unknown>
  documentType?: AiExplainDocumentType
  baselineRequired?: boolean
  expectedRefusal: boolean
}
