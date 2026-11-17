import type { WorkflowNode } from '../types/workflow'

interface Shape {
  id: string
  type: string
  name: string
  x: number
  y: number
  size: number
}
interface Edge {
  id: string
  from: Shape
  to: Shape
  name?: string
}
const namespaces = {
  bpmn: 'http://www.omg.org/spec/BPMN/20100524/MODEL',
  bpmndi: 'http://www.omg.org/spec/BPMN/20100524/DI',
  dc: 'http://www.omg.org/spec/DD/20100524/DC',
  di: 'http://www.omg.org/spec/DD/20100524/DI',
}
const kind: Record<WorkflowNode['type'], string> = {
  start: 'startEvent',
  approver: 'userTask',
  handler: 'userTask',
  copy: 'serviceTask',
  condition_branch: 'exclusiveGateway',
  parallel_branch: 'parallelGateway',
  inclusive_branch: 'inclusiveGateway',
  route_branch: 'exclusiveGateway',
  subprocess: 'subProcess',
  delay: 'receiveTask',
  trigger: 'serviceTask',
}

export function workflowBpmnDiagram(root: WorkflowNode): string {
  const shapes: Shape[] = [],
    edges: Edge[] = []
  function shape(id: string, type: string, name: string, column: number, lane: number): Shape {
    const size = type.endsWith('Event') ? 38 : type.endsWith('Gateway') ? 54 : 114
    const node = { id, type, name, x: 100 + column * 170, y: 100 + lane * 125, size }
    shapes.push(node)
    return node
  }
  function connect(from: Shape, to: Shape, name?: string): void {
    edges.push({ id: 'diagram_flow_' + (edges.length + 1), from, to, name })
  }
  function draw(
    node: WorkflowNode,
    column: number,
    lane: number,
  ): { first: Shape; last: Shape; column: number; lane: number } {
    const first = shape(node.id, kind[node.type], node.name, column, lane)
    let last = first,
      endColumn = column,
      lastLane = lane
    if (node.branches?.length) {
      const branchEnds: { last: Shape; name?: string }[] = []
      for (const branch of node.branches) {
        const name = branch.name + (branch.default ? '（默认）' : '')
        if (!branch.child) {
          branchEnds.push({ last: first, name })
          continue
        }
        const result = draw(branch.child, column + 1, lastLane + 1)
        connect(first, result.first, name)
        branchEnds.push({ last: result.last })
        endColumn = Math.max(endColumn, result.column)
        lastLane = result.lane
      }
      endColumn++
      last = shape('diagram_join_' + node.id, kind[node.type], '', endColumn, lane)
      for (const edge of branchEnds) connect(edge.last, last, edge.name)
    }
    if (node.child) {
      const result = draw(node.child, endColumn + 1, lane)
      connect(last, result.first)
      return {
        first,
        last: result.last,
        column: result.column,
        lane: Math.max(lastLane, result.lane),
      }
    }
    return { first, last, column: endColumn, lane: lastLane }
  }
  const drawn = draw(root, 0, 0)
  connect(drawn.last, shape('diagram_end', 'endEvent', '完成审批', drawn.column + 1, 0))
  const definitions = Object.entries(namespaces)
    .map(([prefix, url]) => `xmlns:${prefix}="${url}"`)
    .join(' ')
  const nodes = shapes
    .map((node) => `<bpmn:${node.type} id="${escape(node.id)}" name="${escape(node.name)}"/>`)
    .join('')
  const links = edges
    .map(
      ({ id, from, to, name }) =>
        `<bpmn:sequenceFlow id="${id}" sourceRef="${escape(from.id)}" targetRef="${escape(to.id)}"${name ? ` name="${escape(name)}"` : ''}/>`,
    )
    .join('')
  const graphicNodes = shapes
    .map(
      ({ id, type, size, x, y }) =>
        `<bpmndi:BPMNShape id="${escape(id)}_di" bpmnElement="${escape(id)}"${type === 'subProcess' ? ' isExpanded="false"' : ''}><dc:Bounds x="${x - size / 2}" y="${y - height(size) / 2}" width="${size}" height="${height(size)}"/></bpmndi:BPMNShape>`,
    )
    .join('')
  const graphicEdges = edges
    .map(({ id, from, to }) => {
      const begin = from.x + from.size / 2,
        end = to.x - to.size / 2,
        middle = (begin + end) / 2
      const points =
        from.y === to.y
          ? [
              [begin, from.y],
              [end, to.y],
            ]
          : [
              [begin, from.y],
              [middle, from.y],
              [middle, to.y],
              [end, to.y],
            ]
      return `<bpmndi:BPMNEdge id="${id}_di" bpmnElement="${id}">${points.map(([x, y]) => `<di:waypoint x="${x}" y="${y}"/>`).join('')}</bpmndi:BPMNEdge>`
    })
    .join('')
  return `<bpmn:definitions ${definitions} targetNamespace="urn:erp:workflow-design"><bpmn:process id="erp_workflow_diagram" isExecutable="false">${nodes}${links}</bpmn:process><bpmndi:BPMNDiagram id="erp_diagram"><bpmndi:BPMNPlane id="erp_plane" bpmnElement="erp_workflow_diagram">${graphicNodes}${graphicEdges}</bpmndi:BPMNPlane></bpmndi:BPMNDiagram></bpmn:definitions>`
}

function height(size: number): number {
  return size === 114 ? 76 : size
}
function escape(value: string): string {
  return value.replace(
    /[&<>"']/g,
    (item) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[item] ?? item,
  )
}
