<script setup lang="ts">
import { computed } from 'vue'
import { useSessionStore } from '../../stores/session'
import StatusTag from '../StatusTag.vue'

const props = defineProps<{ mode?: unknown; costSource?: unknown }>()
const session = useSessionStore()
const costLabel = computed((): string => {
  if (!session.can('cost:view') || props.mode !== 'sourceless') return ''
  switch (props.costSource) {
    case 'average':
      return '加权平均成本'
    case 'last_in':
      return '最近入库成本'
    case 'manual':
      return '人工复核成本'
    default:
      return ''
  }
})
</script>

<template>
  <span v-if="mode === 'sourceless' || mode === 'original'" class="return-source">
    <StatusTag
      :label="mode === 'sourceless' ? '无原单退货' : '按原单退货'"
      :tone="mode === 'sourceless' ? 'info' : 'neutral'"
    />
    <span v-if="costLabel" class="return-cost-source">{{ costLabel }}</span>
  </span>
</template>

<style scoped>
.return-source {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px 8px;
  margin-block-start: 4px;
}
.return-cost-source {
  color: var(--erp-text-2);
  font-size: 12px;
}
</style>
