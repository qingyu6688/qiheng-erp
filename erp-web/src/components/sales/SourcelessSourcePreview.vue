<script setup lang="ts">
import { provide } from 'vue'
import SourcelessDocumentDrawer from './SourcelessDocumentDrawer.vue'
defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
provide('erp.readOnlyPreview', true)
</script>
<template>
  <SourcelessDocumentDrawer kind="sales-returns" :id="id" @close="emit('close')" />
</template>
