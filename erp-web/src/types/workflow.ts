export type WorkflowBusinessType =
  'sales_order' | 'purchase_order' | 'refund_request' | 'document_reversal' | 'stock_adjustment'
export type NodeType =
  'start' | 'approver' | 'handler' | 'copy' | 'condition_branch' | 'parallel_branch'
export type AssigneeStrategy =
  | 'users'
  | 'role'
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
  priceApproval?: boolean
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
  fieldsByType?: Record<WorkflowBusinessType, string[]>
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
  nodeName?: string
  taskStatus?: string
  taskVersion?: number
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
  starterId: string
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
}
export const nodeLabels: Record<NodeType, string> = {
  start: '发起人',
  approver: '人工审批',
  handler: '办理',
  copy: '抄送',
  condition_branch: '条件分支',
  parallel_branch: '并行分支',
}
export const fieldLabels: Record<string, string> = {
  totalAmount: '单据金额',
  discountRate: '折后比例（%，100为原价）',
  belowMinPrice: '是否低于最低售价',
  ownerDeptId: '冻结归属部门',
  customerId: '客户编号',
  supplierId: '供应商编号',
  requisitionApproved: '关联请购是否均已审核',
  refundMethod: '退款账户类型',
  sourceType: '冲销来源类型',
  crossPeriod: '是否跨月',
  adjustType: '调整类型',
  salesUserId: '销售员',
  buyerUserId: '采购员',
  createdBy: '创建人',
}
export const businessRoutes: Record<WorkflowBusinessType, string> = {
  sales_order: 'sales-orders',
  purchase_order: 'purchase-orders',
  refund_request: 'refund-requests',
  document_reversal: 'document-reversals',
  stock_adjustment: 'stock-adjustments',
}
