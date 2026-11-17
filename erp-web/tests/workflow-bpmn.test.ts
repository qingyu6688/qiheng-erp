import { describe, expect, it } from 'vitest'
import type { WorkflowNode } from '../src/types/workflow'
import { workflowBpmnDiagram } from '../src/utils/workflow-bpmn'
import { createNode, findNode, modelProblems, visitNodes } from '../src/utils/workflow-designer'

describe('BPMN 画布和受限节点', () => {
  it('将节点名称按 XML 属性编码并保留包容分支、子流程、延迟和触发器图元', () => {
    const root: WorkflowNode = { id: 'root', type: 'start', name: '部门 <脚本> & "申请"' }
    const inclusive = createNode('inclusive_branch'),
      nested = createNode('subprocess'),
      delay = createNode('delay')
    root.child = inclusive
    inclusive.branches![0].child = nested
    inclusive.branches![1].child = delay
    delay.child = createNode('trigger')
    const xml = workflowBpmnDiagram(root)
    expect(xml).toContain('部门 &lt;脚本&gt; &amp; &quot;申请&quot;')
    for (const kind of ['inclusiveGateway', 'subProcess', 'receiveTask', 'serviceTask'])
      expect(xml).toContain('bpmn:' + kind)
    expect(xml).toContain(`sourceRef="${nested.id}" targetRef="diagram_join_${inclusive.id}"`)
    expect(xml).not.toContain('<脚本>')
  })

  it('遍历和验证子流程中的人工审批，所有择路都须保留人工审核', () => {
    const review = createNode('approver'),
      child = createNode('subprocess'),
      route = createNode('route_branch')
    review.assignee = { strategy: 'users', userIds: ['42'] }
    child.subprocess = review
    route.branches![0].child = child
    const root = { ...createNode('start'), child: route }
    const visited: string[] = []
    visitNodes(root, (node) => visited.push(node.id))
    expect(visited).toContain(review.id)
    expect(findNode(root, review.id)).toBe(review)
    expect(modelProblems(root)).toContain('每条路径至少保留一个人工审批节点')
    route.child = { ...review, id: 'lastReview' }
    expect(modelProblems(root)).toEqual([])
  })
})
