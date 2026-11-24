<script setup lang="ts">
import { ref } from 'vue'
import type { SourcelessKind } from '../../hooks/useSourcelessMutation'
import SourcelessDocumentPanel from './SourcelessDocumentPanel.vue'
defineProps<{ kind: SourcelessKind; id?: string }>()
const emit = defineEmits<{ close: []; saved: [id: string] }>()
const panel = ref<InstanceType<typeof SourcelessDocumentPanel>>()
function beforeClose(done: () => void): void {
  if (panel.value?.mayLeave()) done()
}
</script>
<template>
  <el-drawer
    :model-value="true"
    :title="kind === 'sales-returns' ? '无原单销售退货' : '退货信用额退款'"
    direction="rtl"
    size="min(1180px,96vw)"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    :before-close="beforeClose"
    @close="emit('close')"
    ><SourcelessDocumentPanel
      ref="panel"
      :key="kind + (id ?? 'new')"
      :kind="kind"
      :id="id"
      @close="emit('close')"
      @saved="emit('saved', $event)"
  /></el-drawer>
</template>
