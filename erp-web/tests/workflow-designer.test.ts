import { expect, it } from 'vitest'
import {
  createNode,
  findNode,
  modelProblems,
  removeNode,
  updateTree,
} from '../src/utils/workflow-designer'
import type { WorkflowNode } from '../src/types/workflow'

function review(id = 'review'): WorkflowNode {
  return {
    id,
    type: 'approver',
    name: '经理复核',
    assignee: { strategy: 'users', userIds: ['2'], emptyHandler: 'reject' },
    multi: 'and',
  }
}
function root(child?: WorkflowNode): WorkflowNode {
  return { id: 'root', type: 'start', name: '发起人', child }
}
it('条件分支每条路径必须有人工复核', () => {
  const branch = createNode('condition_branch')
  branch.branches![0]!.child = review()
  expect(modelProblems(root(branch))).toContain('每条路径至少保留一个人工审批节点')
  branch.child = review('common')
  expect(modelProblems(root(branch))).toEqual([])
})
it('并行分支汇合前只需保留至少一项人工复核', () => {
  const parallel = createNode('parallel_branch')
  parallel.branches![0]!.child = review()
  expect(modelProblems(root(parallel))).toEqual([])
})
it('删除分支节点后保留汇合后的环节', () => {
  const branch = createNode('condition_branch')
  branch.child = review()
  const original = root(branch),
    next = removeNode(original, branch.id)
  expect(next.child?.id).toBe('review')
  expect(original.child?.id).toBe(branch.id)
})
it('编辑嵌套节点不修改原草稿', () => {
  const branch = createNode('parallel_branch')
  branch.branches![1]!.child = review()
  const original = root(branch),
    next = updateTree(original, 'review', (node) => {
      node.name = '财务复核'
    })
  expect(findNode(next, 'review')?.name).toBe('财务复核')
  expect(findNode(original, 'review')?.name).toBe('经理复核')
})
it('不能跳过人工审批且不能保存重复节点', () => {
  const node = review()
  node.assignee!.emptyHandler = 'skip'
  node.child = review()
  expect(modelProblems(root(node))).toEqual(
    expect.arrayContaining(['经理复核不能跳过人工复核', '节点编号重复：经理复核']),
  )
})
it('新节点使用安全编号，未配置用户不可保存', () => {
  const node = createNode('approver')
  expect(node.id).toMatch(/^[A-Za-z][A-Za-z0-9_]{0,39}$/)
  expect(modelProblems(root(node))).toContain('人工审批未选择处理人')
})
it('条件必须有且只有一条默认分支', () => {
  const node = createNode('condition_branch')
  node.child = review()
  node.branches![0]!.default = true
  expect(modelProblems(root(node))).toContain('条件分支须保留一条默认分支')
})
