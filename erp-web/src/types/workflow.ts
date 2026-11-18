export type WorkflowBusinessType =
  | 'sales_order'
  | 'purchase_order'
  | 'refund_request'
  | 'document_reversal'
  | 'stock_adjustment'
  | 'credit_increase'
  | 'crm_contract'
  | 'mfg_issue_doc'
  | 'mfg_receipt_doc'
  | 'mfg_bom'
  | 'purchase_requisition'
  | 'stock_count'
  | 'stock_misc_doc'
  | 'gl_voucher'
  | 'qc_inspection'
  | 'ar_bad_debt'
  | 'wf_generic_request'
export type NodeType =
  | 'start'
  | 'approver'
  | 'handler'
  | 'copy'
  | 'condition_branch'
  | 'parallel_branch'
  | 'inclusive_branch'
  | 'route_branch'
  | 'subprocess'
  | 'delay'
  | 'trigger'
export type AssigneeStrategy =
  | 'users'
  | 'role'
  | 'approval_permission'
  | 'dept_leader'
  | 'multi_level_leader'
  | 'post'
  | 'starter_select'
  | 'starter'
  | 'field_user'
  | 'starter_dept_members'
export interface Condition {
  field?: string
  op?: string
  value?: string
  all?: Condition[]
  any?: Condition[]
}
export interface Assignee {
  strategy: AssigneeStrategy
  userIds?: string[]
  roleCodes?: string[]
  postIds?: string[]
  level?: number
  starterDepartment?: boolean
  field?: string
  emptyHandler?: 'skip' | 'admin' | 'reject'
}
export interface WorkflowNode {
  id: string
  type: NodeType
  name: string
  assignee?: Assignee
  multi?: 'and' | 'or' | 'sequential'
  timeout?: {
    hours: number
    action: 'remind' | 'approve' | 'reject' | 'terminate'
    repeat?: number
  }
  branches?: { name: string; condition?: Condition; default?: boolean; child?: WorkflowNode }[]
  child?: WorkflowNode
  creditApproval?: boolean
  priceApproval?: boolean
  subprocess?: WorkflowNode
  delaySeconds?: number
  triggerEvent?: 'checkpoint' | 'review_required'
  fieldAccess?: Record<string, 'read' | 'write' | 'hidden'>
}
export interface Choice {
  id: string
  name: string
  code?: string
  parentId?: string
  leaderUserId?: string
}
export interface WorkflowMetadata {
  businessTypes: WorkflowBusinessType[]
  fieldsByType?: Partial<Record<WorkflowBusinessType, string[]>>
  fields: Record<string, string>
  users: Choice[]
  roles: Choice[]
  departments: Choice[]
  posts: Choice[]
}
export interface ModelVersion {
  versionNo: number
  publishedBy: string
  publishedAt: string
  contentHash: string
}
export interface WorkflowModel {
  id: string
  companyId: string
  code: string
  name: string
  businessType: WorkflowBusinessType
  categoryId?: string
  status: 'draft' | 'published' | 'disabled'
  publishedVersion: number
  version: number
  updatedAt?: string
  simple?: WorkflowNode
  versions?: ModelVersion[]
}
export interface ModelInput {
  companyId: string
  code: string
  name: string
  businessType: WorkflowBusinessType
  categoryId?: string
  simple: WorkflowNode
  version: number
}
export interface WorkflowBinding {
  id: string
  companyId: string
  businessType: WorkflowBusinessType
  modelId: string
  modelName?: string
  priority: number
  conditionJson?: string | Condition
  enabled: boolean | number
  version: number
}
export interface BindingInput {
  companyId: string
  businessType: WorkflowBusinessType
  modelId: string
  priority: number
  condition?: Condition
  enabled: boolean
  version: number
}
export interface WorkflowPage<T> {
  items: T[]
  nextCursor: string | null
}
export interface WorkflowListRow {
  id: string
  instanceId: string
  title: string
  businessType: WorkflowBusinessType
  businessNo: string
  status: string
  automaticProcessingFailed?: boolean
  nodeName?: string
  taskStatus?: string
  taskVersion?: number
  starterName?: string | null
  totalAmount?: string | null
  batchGroup?: string | null
  ownerId?: string
  readAt?: string
  action?: string
  comment?: string
  itemCreatedAt: string
}
export interface WorkflowTask {
  id: string
  instanceId: string
  nodeKey: string
  nodeName: string
  nodeKind: 'approver' | 'handler'
  assigneeId: string
  assigneeName?: string
  ownerId?: string
  parentTaskId?: string
  addedBy?: string
  signPosition?: 'before' | 'after'
  status: string
  priceApproval?: boolean | number
  canHandle?: boolean
  approvalEpoch?: number
  comment?: string
  version: number
  createdAt: string
  endedAt?: string
  dueAt?: string
}
export interface WorkflowAction {
  id: string
  actorId: string
  actorName: string
  taskId?: string
  action: string
  comment?: string
  createdAt: string
}
export interface WorkflowInstance {
  id: string
  businessType: WorkflowBusinessType
  businessId: string
  businessNo: string
  title: string
  status: string
  automaticProcessingFailed?: boolean
  starterId: string
  starterName?: string | null
  modelVersion: number
  version: number
  startedAt: string
  endedAt?: string
  summary: Record<string, unknown>
  tasks: WorkflowTask[]
  actions: WorkflowAction[]
  canCancel: boolean
  canTerminate: boolean
}
export interface WorkflowProgress {
  instances: {
    id: string
    status: string
    modelVersion: number
    startedAt: string
    endedAt?: string
  }[]
  tasks: Pick<
    WorkflowTask,
    | 'id'
    | 'nodeKey'
    | 'nodeName'
    | 'nodeKind'
    | 'assigneeId'
    | 'assigneeName'
    | 'status'
    | 'comment'
    | 'createdAt'
    | 'endedAt'
    | 'dueAt'
  >[]
  canOpen?: boolean
}
export interface TaskDecision {
  version: number
  comment?: string
  userId?: string
  userIds?: string[]
  taskIds?: string[]
  position?: 'before' | 'after'
  targetNodeKey?: string
  fields?: WorkflowFormValues
}
export interface WorkflowBatchInput {
  group: string
  items: { id: string; version: number }[]
}
export interface WorkflowBatchResult {
  id: string
  approved: boolean
  message?: string | null
}
export type WorkflowFormValues = Record<string, string | boolean | null>
export interface WorkflowFormField {
  key: string
  label: string
  type: 'text' | 'number' | 'date' | 'select' | 'boolean'
  required: boolean
  options?: string[]
}
export interface WorkflowForm {
  id: string
  code: string
  name: string
  modelId: string
  modelName: string
  enabled: boolean | number
  version: number
  fields: WorkflowFormField[]
}
export interface GenericWorkflowRequest {
  id: string
  status: string
  requestNo: string
  canUpload: boolean
  fields: { field: WorkflowFormField; value: string | boolean | null; access: 'read' | 'write' }[]
}
export interface StartOptions {
  nodes: { id: string; name: string; candidates: Choice[] }[]
}
export type WorkflowSelections = Record<string, string[]>
export const businessLabels: Record<WorkflowBusinessType, string> = {
  sales_order: '销售订单',
  purchase_order: '采购订单',
  refund_request: '退款申请',
  document_reversal: '冲销申请',
  stock_adjustment: '库存调整',
  credit_increase: '临时信用额度调增',
  crm_contract: 'CRM 合同',
  mfg_issue_doc: '生产领退料',
  mfg_receipt_doc: '生产完工入库',
  mfg_bom: '物料清单',
  purchase_requisition: '请购单',
  stock_count: '库存盘点',
  stock_misc_doc: '其他出入库',
  gl_voucher: '总账凭证',
  qc_inspection: '来料检验',
  ar_bad_debt: '坏账处理',
  wf_generic_request: '通用申请',
}
export const workflowStatus: Record<string, string> = {
  running: '审批中',
  approved: '已通过',
  rejected: '已驳回',
  cancelled: '已撤销',
  terminated: '已终止',
  pending: '待处理',
  waiting_sign: '等待后加签',
  removed: '已减签',
  auto_approved: '自动办理',
  draft: '草稿',
  published: '已发布',
  disabled: '已停用',
}
export const actionLabels: Record<string, string> = {
  start: '发起申请',
  approve: '同意',
  handle: '完成办理',
  reject: '驳回',
  transfer: '转办',
  delegate: '委派',
  resolve: '反馈委派意见',
  add_sign: '加签',
  remove_sign: '减签',
  cancel: '撤销',
  terminate: '终止',
  finish: '业务审批完成',
  timeout_remind: '超时提醒',
  timeout_approve: '自动办理',
  timeout_reject: '超时驳回',
  timeout_terminate: '超时终止',
  empty_reject: '无有效审批人自动驳回',
  delay_scheduled: '等待延迟节点到期',
  delay_resumed: '延迟节点恢复',
  trigger_enqueued: '流程事件已入队',
  trigger_delivered: '流程事件已处理',
}
export const nodeLabels: Record<NodeType, string> = {
  start: '发起人',
  approver: '人工审批',
  handler: '办理',
  copy: '抄送',
  condition_branch: '条件分支',
  parallel_branch: '并行分支',
  inclusive_branch: '包容分支',
  route_branch: '优先路由',
  subprocess: '子流程',
  delay: '延迟等待',
  trigger: '触发事件',
}
export const fieldLabels: Record<string, string> = {
  totalAmount: '单据金额',
  discountRate: '折后比例（%，100为原价）',
  belowMinPrice: '是否低于最低售价',
  overCreditLimit: '是否超额或超期',
  creditExposure: '当前信用占用',
  creditLimit: '有效信用额度',
  ownerDeptId: '冻结归属部门',
  customerId: '客户编号',
  customerLevel: '客户级别',
  supplierId: '供应商编号',
  requisitionApproved: '关联请购是否均已审核',
  refundMethod: '退款账户类型',
  sourceType: '冲销来源类型',
  crossPeriod: '是否跨月',
  adjustType: '调整类型',
  overIssue: '是否超领',
  salesUserId: '销售员',
  buyerUserId: '采购员',
  createdBy: '创建人',
}
export const businessRoutes: Partial<Record<WorkflowBusinessType, string>> = {
  sales_order: 'sales-orders',
  purchase_order: 'purchase-orders',
  refund_request: 'refund-requests',
  document_reversal: 'document-reversals',
  stock_adjustment: 'stock-adjustments',
  credit_increase: 'ar/credit',
  crm_contract: 'crm/contracts',
  mfg_issue_doc: 'mfg/issues',
  mfg_receipt_doc: 'mfg/receipts',
  purchase_requisition: 'purchase-requisitions',
  stock_count: 'stock-counts',
  gl_voucher: 'gl/vouchers',
}
