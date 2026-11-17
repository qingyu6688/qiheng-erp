<script setup lang="ts">
import type { Id } from '../types/api'

defineProps<{
  rows: { id: Id; title: string; subtitle?: string; status?: string }[]
  more: boolean
  busy: boolean
}>()
const emit = defineEmits<{ select: [id: Id]; more: [] }>()
</script>

<template>
  <view>
    <text v-if="!rows.length" class="muted">{{ busy ? '正在加载…' : '暂无记录' }}</text>
    <button
      v-for="row in rows" :key="row.id" class="work-list-row secondary"
      :disabled="busy" @click="emit('select', row.id)"
    >
      <view class="work-row-title">
        <text>{{ row.title }}</text><text v-if="row.status" class="status">{{ row.status }}</text>
      </view>
      <text v-if="row.subtitle" class="muted">{{ row.subtitle }}</text>
    </button>
    <button v-if="more" class="secondary" :disabled="busy" @click="emit('more')">加载更多</button>
  </view>
</template>
