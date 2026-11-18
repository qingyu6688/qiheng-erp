<script setup lang="ts">
defineProps<{ inline?: boolean; title: string; busy: boolean }>()
const emit = defineEmits<{ close: [] }>()
</script>
<template>
  <section v-if="inline" class="panel order-editor-page" :aria-label="title" :aria-busy="busy">
    <header>
      <h2>基础信息</h2>
    </header>
    <div class="order-editor-body" role="region" :aria-label="title + '表单内容'" tabindex="0">
      <slot />
    </div>
    <footer class="order-editor-footer"><slot name="footer" /></footer>
  </section>
  <el-dialog
    v-else
    :model-value="true"
    :title="title"
    class="sales-editor-dialog"
    top="5vh"
    width="min(1100px, 96vw)"
    :close-on-click-modal="false"
    :show-close="!busy"
    @close="emit('close')"
    ><slot /><template #footer><slot name="footer" /></template
  ></el-dialog>
</template>
<style scoped>
.order-editor-page > header {
  padding: 20px 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.order-editor-page {
  display: flex;
  flex-direction: column;
  min-height: 360px;
  height: calc(100dvh - var(--erp-top, 56px) - var(--erp-tabs, 36px) - 140px);
}
h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.order-editor-body {
  min-height: 0;
  overflow: auto;
  flex: 1;
  padding: 16px;
}
.order-editor-body:focus-visible {
  outline: 2px solid var(--erp-primary-6);
  outline-offset: -2px;
}
.order-editor-footer {
  flex: none;
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 16px;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-light);
  border-radius: 0 0 var(--erp-radius-card) var(--erp-radius-card);
}
@media (max-width: 480px) {
  .order-editor-page > header,
  .order-editor-body,
  .order-editor-footer {
    padding-inline: 16px;
  }
}
</style>
