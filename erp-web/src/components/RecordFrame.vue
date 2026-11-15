<script setup lang="ts">
withDefaults(defineProps<{ inline?: boolean; title: string; busy?: boolean; width?: string }>(), {
  width: '560px',
})
const emit = defineEmits<{ close: [] }>()
</script>
<template>
  <section
    v-if="inline"
    class="record-inline panel"
    role="region"
    :aria-label="title"
    :aria-busy="busy"
  >
    <header>
      <h2>{{ title }}</h2>
    </header>
    <div class="record-inline-body"><slot /></div>
    <footer><slot name="footer" /></footer>
  </section>
  <el-dialog
    v-else
    :model-value="true"
    :title="title"
    :width="width"
    :close-on-click-modal="false"
    :show-close="!busy"
    :close-on-press-escape="!busy"
    @close="emit('close')"
    ><slot /><template #footer><slot name="footer" /></template
  ></el-dialog>
</template>
<style scoped>
.record-inline > header {
  padding: 18px 24px;
  border-bottom: 1px solid var(--el-border-color-light);
}
.record-inline h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.record-inline-body {
  padding: 24px;
}
.record-inline > footer {
  padding: 16px 24px;
  border-top: 1px solid var(--el-border-color-light);
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
.record-inline {
  min-width: 0;
}
@media (max-width: 480px) {
  .record-inline > header,
  .record-inline-body,
  .record-inline > footer {
    padding-inline: 16px;
  }
}
</style>
