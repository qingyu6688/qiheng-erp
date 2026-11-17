import type { NodeType, WorkflowNode } from '../types/workflow'
import { nodeLabels } from '../types/workflow'
export function selective(type: NodeType): boolean {
  return ['condition_branch', 'route_branch', 'inclusive_branch'].includes(type)
}
export function createNode(type: NodeType, branchField = 'totalAmount'): WorkflowNode {
  const id = 'n' + crypto.randomUUID().replaceAll('-', '').slice(0, 16)
  const node: WorkflowNode = { id, type, name: nodeLabels[type] }
  if (['approver', 'handler', 'copy'].includes(type))
    node.assignee = {
      strategy: 'users',
      userIds: [],
      emptyHandler: type === 'copy' ? 'skip' : 'admin',
    }
  if (type === 'approver' || type === 'handler') node.multi = 'and'
  if (type === 'delay') node.delaySeconds = 60
  if (type === 'trigger') node.triggerEvent = 'checkpoint'
  if (type === 'subprocess') node.subprocess = createNode('approver', branchField)
  if (selective(type) || type === 'parallel_branch')
    node.branches = [
      {
        name: selective(type) ? '满足条件' : '分支一',
        condition: selective(type)
          ? { field: branchField, op: '>', value: branchField === 'totalAmount' ? '50000' : '0' }
          : undefined,
      },
      {
        name: selective(type) ? '其他情况' : '分支二',
        default: selective(type),
      },
    ]
  return node
}
export function visitNodes(root: WorkflowNode, callback: (node: WorkflowNode) => void): void {
  callback(root)
  for (const branch of root.branches ?? []) if (branch.child) visitNodes(branch.child, callback)
  if (root.subprocess) visitNodes(root.subprocess, callback)
  if (root.child) visitNodes(root.child, callback)
}
export function findNode(root: WorkflowNode, id: string): WorkflowNode | undefined {
  let found: WorkflowNode | undefined
  visitNodes(root, (node) => {
    if (node.id === id) found = node
  })
  return found
}
export function updateTree(
  root: WorkflowNode,
  id: string,
  mutate: (node: WorkflowNode) => void,
): WorkflowNode {
  const clone = JSON.parse(JSON.stringify(root)) as WorkflowNode
  const node = findNode(clone, id)
  if (node) mutate(node)
  return clone
}
export function removeNode(root: WorkflowNode, id: string): WorkflowNode {
  const clone = JSON.parse(JSON.stringify(root)) as WorkflowNode
  visitNodes(clone, (node) => {
    if (node.child?.id === id) node.child = node.child.child
    if (node.subprocess?.id === id) node.subprocess = node.subprocess.child
    for (const branch of node.branches ?? [])
      if (branch.child?.id === id) branch.child = branch.child.child
  })
  return clone
}
export function modelProblems(root: WorkflowNode): string[] {
  const problems: string[] = [],
    ids = new Set<string>()
  visitNodes(root, (node) => {
    if (ids.has(node.id)) problems.push('节点编号重复：' + node.name)
    ids.add(node.id)
    if (node.creditApproval && node.type !== 'approver')
      problems.push(node.name + '信用放行必须使用人工审批节点')
    if (!node.name.trim()) problems.push('请填写节点名称')
    if (node.type === 'approver' && node.assignee?.emptyHandler === 'skip')
      problems.push(node.name + '不能跳过人工复核')
    if (['approver', 'handler', 'copy'].includes(node.type)) {
      const a = node.assignee
      if (
        !a ||
        (a.strategy === 'users' && !a.userIds?.length) ||
        (a.strategy === 'role' && !a.roleCodes?.length) ||
        (a.strategy === 'post' && !a.postIds?.length)
      )
        problems.push(node.name + '未选择处理人')
    }
    if (selective(node.type) && node.branches?.filter((branch) => branch.default).length !== 1)
      problems.push(node.name + '须保留一条默认分支')
    if (node.type === 'subprocess' && !node.subprocess) problems.push(node.name + '未配置内部环节')
  })
  const minimum = (node?: WorkflowNode): number =>
    !node
      ? 0
      : Number(node.type === 'approver') +
        (selective(node.type)
          ? Math.min(...(node.branches ?? []).map((b) => minimum(b.child)))
          : node.type === 'parallel_branch'
            ? (node.branches ?? []).reduce((sum, b) => sum + minimum(b.child), 0)
            : 0) +
        minimum(node.child) +
        minimum(node.subprocess)
  if (minimum(root) < 1) problems.push('每条路径至少保留一个人工审批节点')
  return problems
}
