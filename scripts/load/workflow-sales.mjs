const identifier = /^[1-9][0-9]{0,18}$/

function requireFact(condition, label) {
  if (!condition) throw new Error('销售审批容量校验失败：' + label)
}

function isId(value) {
  return typeof value === 'string' && identifier.test(value)
}

export function parseWorkflowSalesFixture(text) {
  let fixture
  try {
    fixture = JSON.parse(text)
  } catch {
    throw new Error('销售审批容量夹具不是有效的 JSON')
  }
  requireFact(typeof fixture?.dataset === 'string' && /^[a-z0-9_-]{1,48}$/.test(fixture.dataset), '样本标识')
  requireFact(isId(fixture?.companyId) && isId(fixture?.modelId), '公司及发布模型编号必须为字符串')
  requireFact(Number.isSafeInteger(fixture.modelVersion) && fixture.modelVersion >= 1, '发布版本号')
  requireFact(typeof fixture.nodeKey === 'string' && /^[A-Za-z][A-Za-z0-9_]{0,39}$/.test(fixture.nodeKey), '复核节点编码')
  return fixture
}

export function workflowSalesCount(rate, duration) {
  const count = /^(\d+)(s|m|h)$/.exec(duration)
  const seconds = count ? Number(count[1]) * { s: 1, m: 60, h: 3600 }[count[2]] : 0
  const target = rate * seconds
  requireFact(Number.isSafeInteger(rate) && rate >= 1 && rate <= 200, '每秒订单数必须为 1 至 200 的整数')
  requireFact(seconds > 0 && Number.isSafeInteger(target), '时长须为正整数秒、分钟或小时')
  return target
}

export function parseWorkflowSalesSnapshot(body) {
  // 发布快照把候选人 Long 编号序列化为 JSON 数字，先保留单一候选人的十进制文本，避免解析时取整。
  try {
    return JSON.parse(body.replace(/("userIds"\s*:\s*\[\s*)([1-9][0-9]{0,18})(\s*\])/g, '$1"$2"$3')).data
  } catch {
    throw new Error('销售审批发布快照不是有效的 JSON')
  }
}

export function verifyWorkflowSalesModel(sample, model, published, bindings) {
  const { fixture, makerId, reviewerId } = sample
  requireFact(isId(makerId) && isId(reviewerId) && makerId !== reviewerId, '制单人与复核人必须是不同账号')
  requireFact(model?.id === fixture.modelId && model.companyId === fixture.companyId && model.businessType === 'sales_order' &&
    model.status === 'published' && model.publishedVersion === fixture.modelVersion, '审批模型未在目标公司发布指定版本')
  requireFact(Array.isArray(bindings) && bindings.some((item) => item.companyId === fixture.companyId &&
    item.modelId === fixture.modelId && item.businessType === 'sales_order' && (item.enabled === true || item.enabled === 1) &&
    item.conditionJson == null), '缺少目标公司的无条件销售订单绑定')
  requireFact(published?.versionNo === fixture.modelVersion && /^[a-f0-9]{64}$/.test(published.contentHash ?? ''), '模型发布快照')
  const start = published.simple
  const node = start?.child
  requireFact(start?.type === 'start' && node?.type === 'approver' && node.id === fixture.nodeKey &&
    node.multi === 'and' && !node.child && !node.branches?.length && !node.subprocess && !node.timeout &&
    !node.priceApproval && !node.creditApproval && node.assignee?.strategy === 'users' &&
    node.assignee.userIds?.length === 1 && node.assignee.userIds[0] === reviewerId,
  '发布快照必须只包含一个由复核账号处理的人工审批节点')
}

function verifyInstance(sample, order, instance) {
  const { fixture, makerId } = sample
  requireFact(isId(order?.id) && isId(instance?.id) && order.workflowInstanceId === instance.id &&
    typeof order.documentNo === 'string' && order.documentNo.length > 0 && order.documentNo === instance.businessNo &&
    instance.companyId === fixture.companyId && instance.businessId === order.id &&
    instance.businessType === 'sales_order' && instance.modelId === fixture.modelId &&
    instance.modelVersion === fixture.modelVersion && instance.starterId === makerId, '销售订单与审批实例绑定不一致')
}

export function pendingWorkflowSalesTask(sample, order, instance) {
  verifyInstance(sample, order, instance)
  requireFact(order.status === 'pending' && order.workflowStatus === 'running' && instance.status === 'running', '提交后订单及流程状态')
  const tasks = instance.tasks
  const task = Array.isArray(tasks) && tasks.length === 1 ? tasks[0] : undefined
  requireFact(isId(task?.id) && task.instanceId === instance.id && task.assigneeId === sample.reviewerId &&
    task.nodeKey === sample.fixture.nodeKey && task.nodeKind === 'approver' && task.status === 'pending' &&
    !task.ownerId && Number.isSafeInteger(task.version) && task.version >= 0, '提交后缺少指定复核人的待办')
  return task
}

export function verifyApprovedWorkflowSales(sample, order, instance, task, lines) {
  verifyInstance(sample, order, instance)
  requireFact(order.status === 'approved' && order.workflowStatus === 'approved' && instance.status === 'approved' &&
    order.approvedBy === sample.reviewerId, '订单与流程未由指定复核人同步通过')
  const approvedTask = Array.isArray(instance.tasks) && instance.tasks.length === 1 ? instance.tasks[0] : undefined
  requireFact(approvedTask?.id === task.id && approvedTask.status === 'approved' && approvedTask.assigneeId === sample.reviewerId && approvedTask.version > task.version,
    '审批任务未完成或版本未递增')
  requireFact(Array.isArray(instance.actions), '审批轨迹缺失')
  const approvals = instance.actions.filter((item) => item.action === 'approve')
  const finishes = instance.actions.filter((item) => item.action === 'finish')
  requireFact(approvals.length === 1 && approvals[0].actorId === sample.reviewerId && approvals[0].taskId === task.id &&
    finishes.length === 1 && finishes[0].actorId === sample.reviewerId, '审批意见和来源业务回调必须各执行一次')
  requireFact(Array.isArray(order.lines) && order.lines.length === lines && order.lines.every((line) =>
    line.warehouseId === sample.actor.warehouseId && line.reservationState === 'reserved' && Number(line.baseQty) === 1 && Number(line.reservedQty) === 1),
  '订单行数、仓库或库存预占与审核结果不一致')
}

export function verifyWorkflowSalesIsolation(sample, order, instance, probe, task) {
  const paths = ['/sales-orders/' + order.id, '/workflow/instances/' + instance.id]
  for (const path of paths) {
    requireFact(probe(sample.reviewer, 'GET', path) === 200, '目标公司对照必须可见')
    requireFact(probe(sample.sameTenant, 'GET', path) === 404, '同租户异公司必须拒绝详情读取')
    requireFact(probe(sample.foreignTenant, 'GET', path) === 404, '外租户必须拒绝详情读取')
    requireFact(probe({ ...sample.foreignTenant, companyId: sample.fixture.companyId }, 'GET', path) === 403, '外租户不得进入目标公司')
  }
  if (!task) return
  const taskPath = '/workflow/tasks/' + task.id + '/approve'
  const input = { version: task.version }
  requireFact(probe(sample.actor, 'POST', taskPath, input, 'maker') === 403, '发起人不得同意复核人的待办')
  requireFact(probe(sample.sameTenant, 'POST', taskPath, input, 'company') === 404, '同租户异公司不得审批')
  requireFact(probe(sample.foreignTenant, 'POST', taskPath, input, 'tenant') === 404, '外租户不得审批')
}
