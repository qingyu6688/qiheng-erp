import assert from 'node:assert/strict'
import test from 'node:test'
import {
  parseWorkflowSalesFixture,
  parseWorkflowSalesSnapshot,
  pendingWorkflowSalesTask,
  verifyApprovedWorkflowSales,
  verifyWorkflowSalesIsolation,
  verifyWorkflowSalesModel,
  workflowSalesCount,
} from './workflow-sales.mjs'

const fixture = { dataset: 'baseline-workflow', companyId: '91731000000001401', modelId: '91731000000001402', modelVersion: 1, nodeKey: 'review' }
const sample = {
  fixture,
  makerId: '91731000000001403',
  reviewerId: '91731000000001404',
  actor: { companyId: fixture.companyId, warehouseId: '41', username: 'operator' },
  reviewer: { companyId: fixture.companyId, username: 'reviewer' },
  sameTenant: { companyId: '42', username: 'reviewer' },
  foreignTenant: { companyId: '43', username: 'foreign' },
}
const model = { id: fixture.modelId, companyId: fixture.companyId, businessType: 'sales_order', status: 'published', publishedVersion: 1 }
const published = {
  versionNo: 1, contentHash: 'a'.repeat(64), simple: {
    type: 'start', child: { id: 'review', type: 'approver', multi: 'and', assignee: { strategy: 'users', userIds: [sample.reviewerId] } },
  },
}
const binding = { companyId: fixture.companyId, modelId: fixture.modelId, businessType: 'sales_order', enabled: 1, conditionJson: null }
const order = { id: '101', workflowInstanceId: '201', documentNo: 'CAP-01', status: 'pending', workflowStatus: 'running' }
const task = { id: '301', instanceId: '201', assigneeId: sample.reviewerId, status: 'pending', nodeKey: 'review', nodeKind: 'approver', version: 0 }
const instance = {
  id: '201', companyId: fixture.companyId, businessId: '101', businessNo: order.documentNo, businessType: 'sales_order',
  modelId: fixture.modelId, modelVersion: 1, starterId: sample.makerId, status: 'running', tasks: [task],
}

test('解析私有夹具时保留大整数编号并校验额定迭代数', () => {
  assert.deepEqual(parseWorkflowSalesFixture(JSON.stringify(fixture)), fixture)
  assert.throws(() => parseWorkflowSalesFixture('{'), /JSON/)
  assert.throws(() => parseWorkflowSalesFixture(JSON.stringify({ ...fixture, companyId: Number(fixture.companyId) })), /编号必须为字符串/)
  assert.throws(() => parseWorkflowSalesFixture(JSON.stringify({ ...fixture, dataset: 123 })), /样本标识/)
  assert.equal(workflowSalesCount(20, '30s'), 600)
  assert.equal(workflowSalesCount(2, '1m'), 120)
  assert.throws(() => workflowSalesCount(2, '1.5s'), /时长/)
  assert.throws(() => workflowSalesCount(201, '1s'), /每秒订单数/)
})

test('发布快照、公司绑定和不同处理人须完整对应', () => {
  assert.doesNotThrow(() => verifyWorkflowSalesModel(sample, model, published, [binding]))
  const numeric = JSON.stringify({ data: published }).replace('"' + sample.reviewerId + '"', sample.reviewerId)
  const normalized = parseWorkflowSalesSnapshot(numeric)
  assert.deepEqual(normalized, published)
  assert.doesNotThrow(() => verifyWorkflowSalesModel(sample, model, normalized, [binding]))
  assert.throws(() => verifyWorkflowSalesModel({ ...sample, reviewerId: sample.makerId }, model, published, [binding]), /不同账号/)
  assert.throws(() => verifyWorkflowSalesModel(sample, { ...model, publishedVersion: 2 }, published, [binding]), /指定版本/)
  assert.throws(() => verifyWorkflowSalesModel(sample, model, published, [{ ...binding, enabled: false }]), /无条件/)
  const parallel = structuredClone(published)
  parallel.simple.child.child = { type: 'approver' }
  assert.throws(() => verifyWorkflowSalesModel(sample, model, parallel, [binding]), /只包含一个/)
})

test('订单、流程、原任务、业务回调及库存预占必须同时成立', () => {
  assert.deepEqual(pendingWorkflowSalesTask(sample, order, instance), task)
  assert.throws(() => pendingWorkflowSalesTask(sample, order, { ...instance, companyId: '43' }), /绑定不一致/)
  assert.throws(() => pendingWorkflowSalesTask(sample, order, { ...instance, tasks: [{ ...task, assigneeId: sample.makerId }] }), /复核人的待办/)

  const line = { warehouseId: sample.actor.warehouseId, reservationState: 'reserved', baseQty: '1.000000', reservedQty: '1.000000' }
  const approved = { ...order, approvedBy: sample.reviewerId, status: 'approved', workflowStatus: 'approved', lines: [line] }
  const completed = {
    ...instance,
    status: 'approved',
    tasks: [{ ...task, status: 'approved', version: 1 }],
    actions: [
      { action: 'approve', actorId: sample.reviewerId, taskId: task.id },
      { action: 'finish', actorId: sample.reviewerId },
    ],
  }
  assert.doesNotThrow(() => verifyApprovedWorkflowSales(sample, approved, completed, task, 1))
  assert.throws(() => verifyApprovedWorkflowSales(sample, approved, { ...completed, status: 'running' }, task, 1), /同步通过/)
  assert.throws(() => verifyApprovedWorkflowSales(sample, approved, { ...completed, actions: [...completed.actions, completed.actions[0]] }, task, 1), /各执行一次/)
  assert.throws(() => verifyApprovedWorkflowSales(sample, { ...approved, lines: [{ ...line, reservedQty: 0 }] }, completed, task, 1), /库存预占/)
})

test('应用实际状态码验证异公司、异租户和发起人拒绝', () => {
  const probe = (actor, method, _path, _input, suffix) => {
    if (method === 'POST' && suffix === 'maker') return 403
    if (actor.companyId !== fixture.companyId) return 404
    return actor.username === 'foreign' ? 403 : 200
  }
  assert.doesNotThrow(() => verifyWorkflowSalesIsolation(sample, order, instance, probe, task))
  assert.doesNotThrow(() => verifyWorkflowSalesIsolation(sample, order, instance, probe))
  assert.throws(() => verifyWorkflowSalesIsolation(sample, order, instance, (actor, ...rest) =>
    actor === sample.sameTenant ? 200 : probe(actor, ...rest), task), /异公司必须拒绝/)
  assert.throws(() => verifyWorkflowSalesIsolation(sample, order, instance, (actor, method, path, input, suffix) =>
    suffix === 'tenant' ? 200 : probe(actor, method, path, input, suffix), task), /外租户不得审批/)
})
