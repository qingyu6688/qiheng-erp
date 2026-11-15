<script setup lang="ts">
defineProps<{ inline?: boolean; title: string; busy: boolean }>()
const emit = defineEmits<{ close: [] }>()
</script>
<template>
  <section v-if="inline" class="panel order-editor-page" :aria-label="title">
    <header>
      <h2>{{ title }}</h2>
      <p>金额由服务器试算；保存草稿后再提交审核。</p>
    </header>
    <div class="order-editor-body"><slot /></div>
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
h2 {
  margin: 0;
  font-size: 16px;
}
.order-editor-page > header p {
  margin: 6px 0 0;
  font-size: 12px;
}
.order-editor-body {
  padding: 24px;
}
.order-editor-footer {
  position: sticky;
  bottom: 0;
  z-index: 3;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 24px;
  background: white;
  border-top: 1px solid var(--el-border-color-light);
}
</style>
