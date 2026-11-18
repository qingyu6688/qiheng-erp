<script setup lang="ts">
withDefaults(defineProps<{ calculating?: boolean; errors?: number }>(), {
  calculating: false,
  errors: 0,
})
const emit = defineEmits<{ locateError: [] }>()
</script>

<template>
  <div class="fixed-footer">
    <div class="footer-summary" :aria-busy="calculating">
      <span v-if="calculating" role="status" class="amount-skeleton">正在试算…</span>
      <slot v-else name="summary" />
      <button v-if="errors" type="button" class="footer-errors" @click="emit('locateError')">
        {{ errors }} 处需要修改
      </button>
    </div>
    <div class="footer-actions"><slot /></div>
  </div>
</template>

<style scoped>
.fixed-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  width: 100%;
}
.footer-summary,
.footer-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
.footer-summary {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.footer-summary :deep(strong) {
  font-size: 18px;
  font-weight: 600;
}
.footer-errors {
  padding: 0;
  color: var(--erp-danger-text);
  background: transparent;
  border: 0;
  cursor: pointer;
}
.footer-actions {
  margin-left: auto;
  gap: 8px;
}
.amount-skeleton {
  min-width: 160px;
  color: var(--erp-text-3);
  border-radius: var(--erp-radius);
  background: var(--erp-fill-2);
}
</style>
