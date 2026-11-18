<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'

defineProps<{ title: string; backLabel: string; backTo?: string }>()
defineEmits<{ back: [] }>()
</script>

<template>
  <header class="page-heading document-page-heading">
    <div class="document-page-heading__main">
      <router-link v-if="backTo" class="text-link back-link" :to="backTo">
        <ArrowLeft class="icon" aria-hidden="true" />{{ backLabel }}
      </router-link>
      <button v-else type="button" class="text-link back-link" @click="$emit('back')">
        <ArrowLeft class="icon" aria-hidden="true" />{{ backLabel }}
      </button>
      <div class="document-page-heading__title">
        <h1>{{ title }}</h1>
        <slot name="status" />
      </div>
      <slot name="meta" />
    </div>
    <div v-if="$slots.actions" class="sales-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.document-page-heading {
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px 16px;
}
.document-page-heading__main {
  min-width: 0;
}
.document-page-heading__title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.document-page-heading__title h1 {
  margin: 0;
  overflow-wrap: anywhere;
}
.back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 4px;
}
.back-link .icon {
  width: 13px;
  height: 13px;
}
</style>
