<script setup lang="ts">
import { provide } from 'vue'
import SourcelessDocumentDrawer from './SourcelessDocumentDrawer.vue'
import type { SourcelessKind } from '../../hooks/useSourcelessMutation'
withDefaults(defineProps<{ id: string; kind?: SourcelessKind }>(), { kind: 'sales-returns' })
const emit = defineEmits<{ close: [] }>()
provide('erp.readOnlyPreview', true)
</script>
<template>
  <SourcelessDocumentDrawer :kind="kind" :id="id" @close="emit('close')" />
</template>
