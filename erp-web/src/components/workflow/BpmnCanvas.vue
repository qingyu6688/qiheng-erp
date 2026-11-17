<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import type Canvas from 'diagram-js/lib/core/Canvas'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn.css'
import type { WorkflowNode } from '../../types/workflow'
import { findNode } from '../../utils/workflow-designer'
import { workflowBpmnDiagram } from '../../utils/workflow-bpmn'

const props = defineProps<{ tree: WorkflowNode }>()
const emit = defineEmits<{ select: [id: string] }>()
const host = ref<HTMLElement>(),
  error = ref(false)
let viewer: NavigatedViewer | undefined,
  drawing = Promise.resolve(),
  current = 0

function redraw(): void {
  const token = ++current,
    xml = workflowBpmnDiagram(props.tree)
  drawing = drawing.then(async () => {
    if (!viewer || token !== current) return
    try {
      await viewer.importXML(xml)
      if (token === current) {
        error.value = false
        viewer?.get<Canvas>('canvas').zoom('fit-viewport')
      }
    } catch {
      if (token === current) error.value = true
    }
  })
}

onMounted(() => {
  viewer = new NavigatedViewer({ container: host.value })
  viewer.on('element.click', (event: { element?: { id: string } }) => {
    const id = event.element?.id
    if (id && findNode(props.tree, id)) emit('select', id)
  })
  redraw()
})
watch(() => props.tree, redraw)
onBeforeUnmount(() => {
  ++current
  viewer?.destroy()
  viewer = undefined
})
</script>

<template>
  <el-alert
    v-if="error"
    type="error"
    title="无法绘制 BPMN 画布，请检查节点配置"
    :closable="false"
  />
  <div ref="host" class="workflow-bpmn-canvas" aria-label="BPMN 画布" />
</template>

<style scoped>
.workflow-bpmn-canvas {
  height: 380px;
  border: 1px solid var(--el-border-color);
  overflow: hidden;
}
</style>
