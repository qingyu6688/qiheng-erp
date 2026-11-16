import type { NodeType, WorkflowNode } from '../types/workflow'
import { nodeLabels } from '../types/workflow'
export function createNode(type: NodeType): WorkflowNode {
  const id = 'n' + crypto.randomUUID().replaceAll('-', '').slice(0, 16)
  const node: WorkflowNode = { id, type, name: nodeLabels[type] }
  if (['approver', 'handler', 'copy'].includes(type))
    node.assignee = {
      strategy: 'users',
      userIds: [],
      emptyHandler: type === 'copy' ? 'skip' : 'admin',
    }
  if (type === 'approver' || type === 'handler') node.multi = 'and'
  if (type === 'condition_branch' || type === 'parallel_branch')
    node.branches = [
      {
        name: type === 'condition_branch' ? '满足条件' : '分支一',
        condition:
          type === 'condition_branch'
            ? { field: 'totalAmount', op: '>', value: '50000' }
            : undefined,
      },
      {
        name: type === 'condition_branch' ? '其他情况' : '分支二',
        default: type === 'condition_branch',
      },
    ]
  return node
}
export function visitNodes(root: WorkflowNode, callback: (node: WorkflowNode) => void): void {
  callback(root)
  for (const branch of root.branches ?? []) if (branch.child) visitNodes(branch.child, callback)
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
    if (
      node.type === 'condition_branch' &&
      node.branches?.filter((branch) => branch.default).length !== 1
    )
      problems.push(node.name + '须保留一条默认分支')
  })
  const minimum = (node?: WorkflowNode): number =>
    !node
      ? 0
      : Number(node.type === 'approver') +
        (node.type === 'condition_branch'
          ? Math.min(...(node.branches ?? []).map((b) => minimum(b.child)))
          : node.type === 'parallel_branch'
            ? (node.branches ?? []).reduce((sum, b) => sum + minimum(b.child), 0)
            : 0) +
        minimum(node.child)
  if (minimum(root) < 1) problems.push('每条路径至少保留一个人工审批节点')
  return problems
}
