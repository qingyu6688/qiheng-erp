<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'

// 下拉里找不到资料时给出去处：在新标签页添加，回来点刷新，不丢正在编辑的单据
const props = defineProps<{ text: string; to: string; label: string }>()
const emit = defineEmits<{ reload: [] }>()
const router = useRouter()
const href = computed(() => router.resolve(props.to).href)
</script>

<template>
  <div class="empty-option-hint">
    <span>{{ text }}</span>
    <a :href="href" target="_blank" rel="noopener">{{ label }}</a>
    <button type="button" class="text-link" @click="emit('reload')">已添加，刷新列表</button>
  </div>
</template>

<style scoped>
.empty-option-hint {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  color: var(--erp-text-2);
  font-size: 13px;
}
.empty-option-hint a {
  color: var(--el-color-primary);
}
.empty-option-hint .text-link {
  justify-self: start;
  padding: 0;
  border: 0;
  background: none;
  color: var(--el-color-primary);
  font: inherit;
  cursor: pointer;
}
</style>
